package mchorse.bbs_mod.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Executable ownership checks for same-path artifact replacement. */
public final class VideoExportArtifactIdentityTest
{
    public static void main(String[] args) throws Exception
    {
        runAll();
    }

    public static void runAll() throws Exception
    {
        assertUnchangedArtifactCompletes();
        assertRawPublicationPreservesReplacement();
        assertMuxPublicationPreservesReplacement();
        assertCleanupPreservesReplacement();
        assertRecoveryPreservesReplacement();
        assertRepeatedMarkCannotAdoptReplacement();
        assertUnavailableIdentityFailsClosed();
    }

    private static void assertUnchangedArtifactCompletes() throws Exception
    {
        withArtifacts("unchanged", (artifacts) ->
        {
            Path workDirectory = artifacts.workDirectory();
            produce(artifacts, artifacts.rawVideo(), "session-video");

            Path published = artifacts.publishRawVideo();
            check(Files.readString(published).equals("session-video"), "unchanged artifact was not published");
            check(artifacts.cleanup().isEmpty(), "unchanged artifact cleanup reported a failure");
            check(!Files.exists(workDirectory), "successful publication retained an identity anchor");
            check(Files.readString(published).equals("session-video"), "cleanup removed the published artifact");
        });
    }

    private static void assertRawPublicationPreservesReplacement() throws Exception
    {
        withArtifacts("raw-publish", (artifacts) ->
        {
            Path raw = produce(artifacts, artifacts.rawVideo(), "session-video");
            replaceInPlace(raw, "user-video");

            IOException failure = expect(IOException.class, artifacts::publishRawVideo);
            check(hasMessage(failure, "identity"), "raw publication identity failure was not diagnosed");
            check(!Files.exists(artifacts.finalVideo()), "replacement was published as the final raw video");
            check(Files.readString(raw).equals("user-video"), "raw publication changed the user replacement");

            assertCleanupPreserves(artifacts, raw, "user-video");
        });
    }

    private static void assertMuxPublicationPreservesReplacement() throws Exception
    {
        withArtifacts("mux-publish", (artifacts) ->
        {
            Path muxed = produce(artifacts, artifacts.muxPartial(), "session-mux");
            replaceInPlace(muxed, "user-mux");

            IOException failure = expect(IOException.class, artifacts::publishMuxedVideo);
            check(hasMessage(failure, "identity"), "mux publication identity failure was not diagnosed");
            check(!Files.exists(artifacts.finalVideo()), "replacement was published as the final muxed video");
            check(Files.readString(muxed).equals("user-mux"), "mux publication changed the user replacement");

            assertCleanupPreserves(artifacts, muxed, "user-mux");
        });
    }

    private static void assertCleanupPreservesReplacement() throws Exception
    {
        withArtifacts("cleanup", (artifacts) ->
        {
            Path audio = produce(artifacts, artifacts.normalizedAudio(), "session-audio");
            replaceInPlace(audio, "user-audio");
            assertCleanupPreserves(artifacts, audio, "user-audio");
        });
    }

    private static void assertRecoveryPreservesReplacement() throws Exception
    {
        withArtifacts("recovery", (artifacts) ->
        {
            Path audio = produce(artifacts, artifacts.mixedAudio(), "session-recovery");
            replaceInPlace(audio, "user-recovery");

            IOException failure = expect(IOException.class, () -> artifacts.promoteRecovery(audio));
            check(hasMessage(failure, "identity"), "recovery identity failure was not diagnosed");
            check(!Files.exists(artifacts.recoveryAudio()), "replacement was promoted as recovery audio");
            check(Files.readString(audio).equals("user-recovery"), "recovery promotion changed the user replacement");

            assertCleanupPreserves(artifacts, audio, "user-recovery");
        });
    }

    private static void assertRepeatedMarkCannotAdoptReplacement() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-artifact-repeat-mark-");
        AtomicReference<Object> identity = new AtomicReference<>(new Object());
        VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "repeat-mark",
            (path, attributes) -> identity.get());

        try
        {
            Path raw = produce(artifacts, artifacts.rawVideo(), "session-video");
            replaceInPlace(raw, "user-video");
            identity.set(new Object());

            IOException failure = expect(IOException.class, () -> artifacts.markProduced(raw));
            check(hasMessage(failure, "identity"), "repeated mark rebound a changed artifact identity");
            assertCleanupPreserves(artifacts, raw, "user-video");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertUnavailableIdentityFailsClosed() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-artifact-no-key-");
        AtomicBoolean available = new AtomicBoolean(true);
        Object stableIdentity = new Object();
        VideoExportArtifacts artifacts = VideoExportArtifacts.allocate(root, "no-key",
            (path, attributes) -> available.get() ? stableIdentity : null);

        try
        {
            Path raw = produce(artifacts, artifacts.rawVideo(), "session-video");
            available.set(false);

            IOException publishFailure = expect(IOException.class, artifacts::publishRawVideo);
            check(hasMessage(publishFailure, "identity"), "unavailable publish identity was not diagnosed");
            check(!Files.exists(artifacts.finalVideo()), "artifact was published without a provable identity");

            List<Throwable> cleanupFailures = artifacts.cleanup();
            check(hasMessage(cleanupFailures, "identity"), "unavailable cleanup identity was not diagnosed");
            check(Files.readString(raw).equals("session-video"), "cleanup deleted a file with unprovable identity");

            VideoExportArtifacts unmarked = VideoExportArtifacts.allocate(root, "no-key-at-mark",
                (path, attributes) -> null);
            Path unmarkedRaw = unmarked.rawVideo();
            unmarked.claim(unmarkedRaw);
            Files.writeString(unmarkedRaw, "unproven-video");

            IOException markFailure = expect(IOException.class, () -> unmarked.markProduced(unmarkedRaw));
            check(hasMessage(markFailure, "identity"), "unavailable mark identity was not diagnosed");
            unmarked.cleanup();
            check(Files.readString(unmarkedRaw).equals("unproven-video"),
                "cleanup deleted an artifact whose identity was never recorded");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static Path produce(VideoExportArtifacts artifacts, Path path, String content) throws Exception
    {
        artifacts.claim(path);
        Files.writeString(path, content);
        artifacts.markProduced(path);

        return path;
    }

    private static void replaceInPlace(Path path, String content) throws IOException
    {
        Path replacement = Files.createTempFile(path.getParent(), "user-replacement-", ".tmp");
        Files.writeString(replacement, content);
        Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertCleanupPreserves(VideoExportArtifacts artifacts, Path path, String content)
        throws Exception
    {
        List<Throwable> failures = artifacts.cleanup();
        check(hasMessage(failures, "identity"), "cleanup identity failure was not reported");
        check(Files.exists(artifacts.workDirectory()), "cleanup removed the replacement's work directory");
        check(Files.readString(path).equals(content), "cleanup deleted or changed the user replacement");

        IOException remarkFailure = expect(IOException.class, () -> artifacts.markProduced(path));
        check(hasMessage(remarkFailure, "identity"), "cleanup allowed a replacement to be marked as produced");
        check(Files.readString(path).equals(content), "repeated mark changed the user replacement");
    }

    private static void withArtifacts(String name, ArtifactCheck check) throws Exception
    {
        Path root = Files.createTempDirectory("bbs-artifact-identity-");

        try
        {
            check.run(VideoExportArtifacts.allocate(root, name));
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static boolean hasMessage(List<? extends Throwable> failures, String fragment)
    {
        for (Throwable failure : failures)
        {
            if (hasMessage(failure, fragment)) return true;
        }

        return false;
    }

    private static boolean hasMessage(Throwable failure, String fragment)
    {
        for (Throwable current = failure; current != null; current = current.getCause())
        {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(fragment.toLowerCase())) return true;
        }

        return false;
    }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingAction action) throws Exception
    {
        try
        {
            action.run();
        }
        catch (Throwable failure)
        {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + failure, failure);
        }

        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root)) return;

        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ArtifactCheck
    {
        void run(VideoExportArtifacts artifacts) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }
}
