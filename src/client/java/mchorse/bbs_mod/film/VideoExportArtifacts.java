package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Allocates and cleans only the files owned by one export session. */
public final class VideoExportArtifacts
{
    @FunctionalInterface
    interface FileIdentityStrategy
    {
        Object identity(Path path, BasicFileAttributes attributes) throws IOException;
    }

    private static final FileIdentityStrategy FILE_KEY_IDENTITY = (path, attributes) -> attributes.fileKey();

    private final UUID sessionId;
    private final Path root;
    private final Path workDirectory;
    private final Path rawVideo;
    private final Path filmAudio;
    private final Path mixedAudio;
    private final Path normalizedAudio;
    private final Path muxPartial;
    private final Path recordingLog;
    private final Path muxLog;
    private final Path finalVideo;
    private final Path recoveryAudio;
    private final FileIdentityStrategy identityStrategy;
    private final boolean hardLinkIdentityFallback;
    private volatile boolean finalPublished;
    private volatile boolean recoveryPublished;
    /** Paths claimed while absent by this session's producers. */
    private final Set<Path> claimed = Collections.synchronizedSet(new HashSet<>());
    /** Stable identities captured when each producer first proved ownership. */
    private final Map<Path, ArtifactIdentity> produced = Collections.synchronizedMap(new HashMap<>());

    private VideoExportArtifacts(UUID sessionId, Path root, Path workDirectory, String baseName,
                                 FileIdentityStrategy identityStrategy, boolean hardLinkIdentityFallback)
    {
        this.sessionId = sessionId;
        this.root = root;
        this.workDirectory = workDirectory;
        this.rawVideo = workDirectory.resolve("video.mp4");
        this.filmAudio = workDirectory.resolve("film.wav");
        this.mixedAudio = workDirectory.resolve("mixed.wav");
        this.normalizedAudio = workDirectory.resolve("normalized.wav");
        this.muxPartial = workDirectory.resolve("mux-partial.mp4");
        this.recordingLog = workDirectory.resolve("recording.log");
        this.muxLog = workDirectory.resolve("mux.log");
        this.finalVideo = root.resolve(baseName + "-" + sessionId + ".mp4");
        this.recoveryAudio = root.resolve(baseName + "-" + sessionId + ".recovery.wav");
        this.identityStrategy = Objects.requireNonNull(identityStrategy, "identityStrategy");
        this.hardLinkIdentityFallback = hardLinkIdentityFallback;
    }

    public static VideoExportArtifacts allocate(Path outputRoot, String requestedBaseName) throws IOException
    {
        return allocate(outputRoot, requestedBaseName, FILE_KEY_IDENTITY, true);
    }

    static VideoExportArtifacts allocate(Path outputRoot, String requestedBaseName,
                                         FileIdentityStrategy identityStrategy) throws IOException
    {
        return allocate(outputRoot, requestedBaseName, identityStrategy, false);
    }

    private static VideoExportArtifacts allocate(Path outputRoot, String requestedBaseName,
                                                  FileIdentityStrategy identityStrategy,
                                                  boolean hardLinkIdentityFallback) throws IOException
    {
        if (outputRoot == null)
        {
            throw new IOException("Video export output root is missing");
        }
        if (identityStrategy == null)
        {
            throw new IOException("Video export file identity strategy is missing");
        }

        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        String base = sanitizeBaseName(requestedBaseName);

        for (int attempt = 0; attempt < 8; attempt++)
        {
            UUID id = UUID.randomUUID();
            Path work = root.resolve(".bbs-export-" + id);

            try
            {
                Files.createDirectory(work);
                return new VideoExportArtifacts(id, root, work, base, identityStrategy,
                    hardLinkIdentityFallback);
            }
            catch (FileAlreadyExistsException ignored)
            {}
        }

        throw new IOException("Could not allocate a unique video export work directory");
    }

    private static String sanitizeBaseName(String requested)
    {
        String value = requested == null ? "video" : requested.trim();
        if (value.isEmpty()) value = "video";
        value = Path.of(value).getFileName().toString();
        value = value.replaceAll("[^A-Za-z0-9._ -]", "_");
        return value.isEmpty() ? "video" : value;
    }

    public UUID sessionId() { return this.sessionId; }
    public Path root() { return this.root; }
    public Path workDirectory() { return this.workDirectory; }
    public Path rawVideo() { return this.rawVideo; }
    public Path filmAudio() { return this.filmAudio; }
    public Path mixedAudio() { return this.mixedAudio; }
    public Path normalizedAudio() { return this.normalizedAudio; }
    public Path muxPartial() { return this.muxPartial; }
    public Path recordingLog() { return this.recordingLog; }
    public Path muxLog() { return this.muxLog; }
    /** Compatibility alias for the recorder log. */
    public Path log() { return this.recordingLog; }
    public Path finalVideo() { return this.finalVideo; }
    public Path recoveryAudio() { return this.recoveryAudio; }

    public boolean owns(Path path)
    {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.equals(this.rawVideo) || normalized.equals(this.filmAudio)
            || normalized.equals(this.mixedAudio) || normalized.equals(this.normalizedAudio)
            || normalized.equals(this.muxPartial)
            || normalized.equals(this.recordingLog) || normalized.equals(this.muxLog)
            || normalized.startsWith(this.workDirectory);
    }

    /**
     * Claim one exact intermediate before a producer starts.  A pre-existing
     * path is never claimed, so later cleanup cannot remove it.
     */
    public synchronized Path claim(Path path) throws IOException
    {
        Path normalized = normalizeKnown(path);

        if (Files.exists(normalized))
        {
            throw new FileAlreadyExistsException(normalized.toString());
        }

        if (this.claimed.contains(normalized))
        {
            throw new FileAlreadyExistsException("Artifact is already claimed: " + normalized);
        }

        this.claimed.add(normalized);

        return normalized;
    }

    public boolean isClaimed(Path path)
    {
        return path != null && this.claimed.contains(path.toAbsolutePath().normalize());
    }

    /** Mark a claimed path only after its producer has taken filesystem ownership. */
    public synchronized void markProduced(Path path) throws IOException
    {
        Path normalized = normalizeKnown(path);
        if (!this.claimed.contains(normalized))
        {
            throw new IOException("Artifact was not claimed by this session: " + normalized);
        }

        ArtifactIdentity existing = this.produced.get(normalized);

        if (existing != null)
        {
            requireCurrentIdentity(normalized, existing);
            return;
        }

        this.produced.put(normalized, captureIdentity(normalized));
    }

    private Path normalizeKnown(Path path) throws IOException
    {
        if (path == null)
        {
            throw new IOException("Owned export artifact path is missing");
        }

        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.equals(this.rawVideo) && !normalized.equals(this.filmAudio)
            && !normalized.equals(this.mixedAudio) && !normalized.equals(this.normalizedAudio)
            && !normalized.equals(this.muxPartial) && !normalized.equals(this.recordingLog)
            && !normalized.equals(this.muxLog))
        {
            throw new IOException("Path is not a known intermediate artifact: " + normalized);
        }

        return normalized;
    }

    public Path publishRawVideo() throws IOException
    {
        ArtifactIdentity identity = requireProducedIdentity(this.rawVideo);
        requireProduced(this.rawVideo);
        moveWithoutReplace(this.rawVideo, this.finalVideo);
        this.claimed.remove(this.rawVideo);
        this.produced.remove(this.rawVideo);
        this.finalPublished = true;
        releasePublishedIdentity(this.rawVideo, this.finalVideo, identity);
        return this.finalVideo;
    }

    public Path publishMuxedVideo() throws IOException
    {
        ArtifactIdentity identity = requireProducedIdentity(this.muxPartial);
        requireProduced(this.muxPartial);
        moveWithoutReplace(this.muxPartial, this.finalVideo);
        this.claimed.remove(this.muxPartial);
        this.produced.remove(this.muxPartial);
        this.finalPublished = true;
        releasePublishedIdentity(this.muxPartial, this.finalVideo, identity);
        return this.finalVideo;
    }

    public Path promoteRecovery(Path source) throws IOException
    {
        if (!this.owns(source) || !this.isClaimed(source)
            || !this.produced.containsKey(source.toAbsolutePath().normalize()))
        {
            throw new IOException("Recovery audio is not owned by this session");
        }

        Path normalized = source.toAbsolutePath().normalize();
        ArtifactIdentity identity = requireProducedIdentity(normalized);
        requireProduced(normalized);

        moveWithoutReplace(normalized, this.recoveryAudio);
        this.claimed.remove(normalized);
        this.produced.remove(normalized);
        this.recoveryPublished = true;
        releasePublishedIdentity(normalized, this.recoveryAudio, identity);
        return this.recoveryAudio;
    }

    /** Delete only known session files and the session directory itself. */
    public List<Throwable> cleanup()
    {
        return this.cleanup(false);
    }

    /** Preserve the original raw video when publication itself failed. */
    public List<Throwable> cleanup(boolean preserveRawVideo)
    {
        List<Throwable> failures = new ArrayList<>();

        Map<Path, ArtifactIdentity> owned;
        synchronized (this.produced)
        {
            owned = new HashMap<>(this.produced);
        }

        if (preserveRawVideo)
        {
            owned.remove(this.rawVideo);
        }

        for (Map.Entry<Path, ArtifactIdentity> entry : owned.entrySet())
        {
            Path path = entry.getKey();

            try
            {
                requireCurrentIdentity(path, entry.getValue());
                Files.deleteIfExists(path);
                releaseIdentity(entry.getValue());
                this.claimed.remove(path);
                this.produced.remove(path);
            }
            catch (Exception e)
            {
                retainAnchorForCleanup(path, entry.getValue());
                failures.add(e);
            }
        }

        /* A stale preserve flag must not strand an empty session directory
         * after the raw file has already been moved to the final artifact. */
        boolean keepWorkDirectory = preserveRawVideo && validFile(this.rawVideo);

        if (!keepWorkDirectory)
        {
            try
            {
                Files.deleteIfExists(this.workDirectory);
            }
            catch (java.nio.file.DirectoryNotEmptyException ignored)
            {
                /* An unclaimed pre-existing file remains protected. */
            }
            catch (Exception e)
            {
                failures.add(e);
            }
        }

        return List.copyOf(failures);
    }

    public VideoExportArtifact describe(ChannelLayout requested, ChannelLayout delivered,
                                        boolean audioPresent, long videoFrames, long audioFrames)
    {
        Path published = Files.exists(this.finalVideo) ? this.finalVideo : null;
        return new VideoExportArtifact(
            published,
            published == null && Files.exists(this.rawVideo) ? this.rawVideo : null,
            this.workDirectory,
            Files.exists(this.recoveryAudio) ? this.recoveryAudio : null,
            requested,
            delivered,
            audioPresent,
            videoFrames,
            audioFrames,
            VideoExportAudioProfile.SAMPLE_RATE,
            audioPresent ? VideoExportAudioProfile.CODEC : null,
            audioPresent ? VideoExportAudioProfile.PROFILE : null,
            audioPresent ? VideoExportAudioProfile.BITRATE : 0
        );
    }

    public VideoExportArtifact describe(VideoExportRequest request, boolean audioPresent,
                                        long capturedFrames, long audioFrames, boolean includeRaw)
    {
        if (request == null)
        {
            return VideoExportArtifact.empty(ChannelLayout.MONO);
        }

        Path published = this.finalPublished && validFile(this.finalVideo) ? this.finalVideo : null;
        Path raw = includeRaw && validFile(this.rawVideo) ? this.rawVideo : null;
        Path recovery = this.recoveryPublished && validFile(this.recoveryAudio) ? this.recoveryAudio : null;
        long outputFrames = request.outputFramesFor(capturedFrames);

        return new VideoExportArtifact(
            published,
            raw,
            this.workDirectory,
            recovery,
            request.layout(),
            audioPresent ? request.layout() : null,
            audioPresent,
            outputFrames,
            audioFrames,
            audioPresent ? request.sampleRate() : 0,
            audioPresent ? VideoExportAudioProfile.CODEC : null,
            audioPresent ? VideoExportAudioProfile.PROFILE : null,
            audioPresent ? VideoExportAudioProfile.BITRATE : 0,
            request.sessionId(), request.generation(), request.sourceId(),
            request.sourceStart(), request.sourceEnd(), request.openEnd(),
            request.width(), request.height(), request.captureFrameRate(),
            request.outputFrameRate(), request.motionBlurPasses(), capturedFrames,
            request.durationSecondsFor(capturedFrames)
        );
    }

    private static boolean validFile(Path path)
    {
        try
        {
            return path != null && Files.isRegularFile(path) && Files.size(path) > 0L;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static void requireProduced(Path path) throws IOException
    {
        if (!validFile(path))
        {
            throw new IOException("Owned export artifact is missing or empty: " + path);
        }
    }

    private ArtifactIdentity requireProducedIdentity(Path path) throws IOException
    {
        Path normalized = path.toAbsolutePath().normalize();
        ArtifactIdentity identity = this.produced.get(normalized);

        if (identity == null)
        {
            throw new IOException("Artifact producer did not own the path: " + path);
        }

        requireCurrentIdentity(normalized, identity);
        return identity;
    }

    private ArtifactIdentity captureIdentity(Path path) throws IOException
    {
        BasicFileAttributes attributes = readRegularFileAttributes(path);

        Object token = this.identityStrategy.identity(path, attributes);

        if (token != null)
        {
            return new ArtifactIdentity(token, null);
        }

        if (this.hardLinkIdentityFallback)
        {
            return createHardLinkIdentity(path);
        }

        throw new IOException("Stable file identity is unavailable; preserving export artifact: " + path);
    }

    private void requireCurrentIdentity(Path path, ArtifactIdentity expected) throws IOException
    {
        BasicFileAttributes attributes;

        try
        {
            attributes = readRegularFileAttributes(path);
        }
        catch (IOException e)
        {
            throw new IOException("Could not prove owned export artifact identity; preserving path: " + path, e);
        }

        if (expected.anchor() != null)
        {
            try
            {
                readRegularFileAttributes(expected.anchor());

                if (Files.isSameFile(path, expected.anchor())) return;
            }
            catch (IOException | SecurityException e)
            {
                throw new IOException("Could not prove owned export artifact identity; preserving path: " + path
                    + "; original identity anchor: " + expected.anchor(), e);
            }

            throw new IOException("Owned export artifact identity changed; preserving replacement: " + path
                + "; original session artifact: " + expected.anchor());
        }

        Object current = this.identityStrategy.identity(path, attributes);

        if (current == null)
        {
            throw new IOException("Stable file identity is unavailable; preserving export artifact: " + path);
        }

        if (!Objects.equals(expected.token(), current))
        {
            throw new IOException("Owned export artifact identity changed; preserving replacement: " + path);
        }
    }

    private static BasicFileAttributes readRegularFileAttributes(Path path) throws IOException
    {
        BasicFileAttributes attributes;

        try
        {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch (SecurityException e)
        {
            throw new IOException("Could not read owned export artifact attributes: " + path, e);
        }

        if (!attributes.isRegularFile())
        {
            throw new IOException("Owned export artifact is not a regular file: " + path);
        }

        return attributes;
    }

    private ArtifactIdentity createHardLinkIdentity(Path path) throws IOException
    {
        IOException collision = null;

        /* A private hard link pins the actual file when a provider exposes no
         * fileKey.  isSameFile can then distinguish a replacement by name. */
        for (int attempt = 0; attempt < 8; attempt++)
        {
            Path anchor = this.workDirectory.resolve(".bbs-identity-" + UUID.randomUUID());

            try
            {
                Files.createLink(anchor, path);

                if (!Files.isSameFile(path, anchor))
                {
                    throw new IOException("Identity anchor does not reference its export artifact: " + path);
                }

                return new ArtifactIdentity(null, anchor);
            }
            catch (FileAlreadyExistsException e)
            {
                collision = e;
            }
            catch (UnsupportedOperationException | SecurityException e)
            {
                throw new IOException("Stable file identity is unavailable and hard-link identity is unsupported; "
                    + "preserving export artifact: " + path, e);
            }
            catch (IOException e)
            {
                try
                {
                    Files.deleteIfExists(anchor);
                }
                catch (IOException cleanupFailure)
                {
                    e.addSuppressed(cleanupFailure);
                }

                throw new IOException("Could not establish a hard-link identity; preserving export artifact: " + path, e);
            }
        }

        throw new IOException("Could not allocate a unique export artifact identity anchor: " + path, collision);
    }

    private void releasePublishedIdentity(Path source, Path published, ArtifactIdentity identity) throws IOException
    {
        try
        {
            releaseIdentity(identity);
        }
        catch (IOException e)
        {
            retainAnchorForCleanup(source, identity);
            throw new IOException("Export artifact was published at " + published
                + " but its identity anchor could not be removed: " + identity.anchor(), e);
        }
    }

    private static void releaseIdentity(ArtifactIdentity identity) throws IOException
    {
        if (identity.anchor() != null)
        {
            try
            {
                Files.deleteIfExists(identity.anchor());
            }
            catch (SecurityException e)
            {
                throw new IOException("Could not remove export artifact identity anchor: " + identity.anchor(), e);
            }
        }
    }

    private void retainAnchorForCleanup(Path previousPath, ArtifactIdentity identity)
    {
        Path anchor = identity.anchor();

        if (anchor == null || anchor.equals(previousPath) || !Files.exists(anchor, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }

        boolean previousExists = Files.exists(previousPath, LinkOption.NOFOLLOW_LINKS);
        boolean previousChanged = false;

        if (previousExists)
        {
            try
            {
                if (Files.isSameFile(previousPath, anchor)) return;
                previousChanged = true;
            }
            catch (IOException ignored)
            {
                return;
            }
        }

        if (previousChanged)
        {
            /* Keep the old path binding so a later markProduced call cannot
             * adopt the foreign replacement after this failed cleanup. */
            this.produced.put(anchor, identity);
        }
        else if (!previousExists && Files.notExists(previousPath, LinkOption.NOFOLLOW_LINKS))
        {
            this.claimed.remove(previousPath);
            this.produced.remove(previousPath);
            this.produced.put(anchor, identity);
        }
    }

    private static void moveWithoutReplace(Path source, Path target) throws IOException
    {
        if (source == null || target == null || !validFile(source))
        {
            throw new IOException("Owned export artifact is missing: " + source);
        }

        if (Files.exists(target))
        {
            throw new FileAlreadyExistsException(target.toString());
        }

        /* A no-option move is atomic on the same filesystem and, unlike
         * ATOMIC_MOVE, is required to fail when a target appears concurrently. */
        Files.move(source, target);
    }

    private record ArtifactIdentity(Object token, Path anchor)
    {}
}
