package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FFMpegDownloadManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-ffmpeg-download");
    static final String MANAGED_VERSION = "8.1.2-20260816";
    private static final String PROXY_PREFIX = "https://gh-proxy.cn/";
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_NS = Duration.ofMillis(200L).toNanos();
    private static final Duration REQUEST_TIMEOUT = Duration.ofHours(2L);
    private static final int FILE_OPERATION_ATTEMPTS = 5;
    private static final long FILE_OPERATION_RETRY_MS = 200L;

    private static final List<Asset> ASSETS = List.of(
        new Asset(OS.WINDOWS, "x86_64", "windows-x86_64", PackageType.ZIP,
            "ffmpeg-n8.1.2-44-g7c533d0f86-win64-gpl-8.1.zip", "ffmpeg.exe", 168_274_018L,
            "d2425b12dc746a2b044148c6100440d4065876ac4ed6e3eb13a68437b7719796",
            URI.create("https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-08-16-13-00/ffmpeg-n8.1.2-44-g7c533d0f86-win64-gpl-8.1.zip")),
        new Asset(OS.WINDOWS, "aarch64", "windows-aarch64", PackageType.ZIP,
            "ffmpeg-n8.1.2-44-g7c533d0f86-winarm64-gpl-8.1.zip", "ffmpeg.exe", 113_416_408L,
            "41697f5bb87e5a92f8e6b317b5b2d307431f8a4fb64f4f30d4cdf4d63d4b791a",
            URI.create("https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-08-16-13-00/ffmpeg-n8.1.2-44-g7c533d0f86-winarm64-gpl-8.1.zip")),
        new Asset(OS.LINUX, "x86_64", "linux-x86_64", PackageType.EXECUTABLE,
            "ffmpeg-linux-x64", "ffmpeg", 48_299_480L,
            "9eac5b2b5076db5ff853a6fa0dcd6b8de7d0cac8481eadda6c47cd935825f1ee",
            URI.create("https://github.com/shaka-project/static-ffmpeg-binaries/releases/download/n8.1.2-1/ffmpeg-linux-x64")),
        new Asset(OS.LINUX, "aarch64", "linux-aarch64", PackageType.EXECUTABLE,
            "ffmpeg-linux-arm64", "ffmpeg", 36_523_320L,
            "6e7b1d7d1aa8c35e3fedd78a140aa0968717aeb7386ecfb0ee00773d9f0a4503",
            URI.create("https://github.com/shaka-project/static-ffmpeg-binaries/releases/download/n8.1.2-1/ffmpeg-linux-arm64")),
        new Asset(OS.MACOS, "x86_64", "macos-x86_64", PackageType.EXECUTABLE,
            "ffmpeg-osx-x64", "ffmpeg", 42_745_472L,
            "62c87854d851f202fc4a29bdda0fe7b6ebcddd37b863482ce1bdc81151b03fe4",
            URI.create("https://github.com/shaka-project/static-ffmpeg-binaries/releases/download/n8.1.2-1/ffmpeg-osx-x64")),
        new Asset(OS.MACOS, "aarch64", "macos-aarch64", PackageType.EXECUTABLE,
            "ffmpeg-osx-arm64", "ffmpeg", 34_074_040L,
            "e7b9fcd97f95f333512d6e8b8ac24d9dbc08f189f36047695499bd7b57214b22",
            URI.create("https://github.com/shaka-project/static-ffmpeg-binaries/releases/download/n8.1.2-1/ffmpeg-osx-arm64"))
    );

    private final Path root;
    private final HttpClient client;
    private final Asset asset;
    private final CandidateValidator validator;
    private final ExecutorService executor;

    private volatile Status status;
    private volatile CompletableFuture<Result> active;

    FFMpegDownloadManager(Path root, HttpClient client, Asset asset, CandidateValidator validator)
    {
        this.root = root.toAbsolutePath().normalize();
        this.client = client;
        this.asset = asset;
        this.validator = validator;
        this.executor = Executors.newSingleThreadExecutor((runnable) ->
        {
            Thread thread = new Thread(runnable, "bbs-ffmpeg-download");

            thread.setDaemon(true);

            return thread;
        });
        this.status = asset == null
            ? new Status(Phase.UNSUPPORTED, Failure.UNSUPPORTED_PLATFORM, 0L, 0L, 0D, -1L, "", null)
            : Status.idle(asset.size());
    }

    public static FFMpegDownloadManager getInstance()
    {
        return Holder.INSTANCE;
    }

    public Status getStatus()
    {
        return this.status;
    }

    public boolean isSupported()
    {
        return this.asset != null;
    }

    public boolean isBusy()
    {
        CompletableFuture<Result> future = this.active;

        return future != null && !future.isDone();
    }

    public synchronized CompletableFuture<Result> start()
    {
        if (this.active != null && !this.active.isDone())
        {
            return this.active;
        }

        if (this.asset == null)
        {
            InstallException failure = new InstallException(Failure.UNSUPPORTED_PLATFORM, "Unsupported platform");

            this.status = new Status(Phase.UNSUPPORTED, failure.failure, 0L, 0L, 0D, -1L, "", null);

            return CompletableFuture.failedFuture(failure);
        }

        this.status = new Status(Phase.CHECKING, Failure.NONE, 0L, this.asset.size(), 0D, -1L, "", null);
        CompletableFuture<Result> future = CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return this.installAsset(this.asset);
            }
            catch (Exception e)
            {
                throw new CompletionException(e);
            }
        }, this.executor);

        this.active = future;
        future.whenComplete((result, error) ->
        {
            if (error == null)
            {
                this.status = new Status(Phase.READY, Failure.NONE, this.asset.size(), this.asset.size(),
                    0D, 0L, "", result.executable());
            }
            else
            {
                Throwable cause = unwrap(error);
                Failure failure = cause instanceof InstallException install ? install.failure : Failure.FILESYSTEM;

                this.status = new Status(Phase.FAILED, failure, this.status.downloadedBytes(), this.asset.size(),
                    this.status.bytesPerSecond(), this.status.etaSeconds(), this.status.source(), null);
                LOGGER.warn("[BBS-SEM] topic=ffmpeg.download phase=install result=failed failure={} error_class={} error={}",
                    failure, cause.getClass().getName(), cause.getMessage());
            }
        });

        return future;
    }

    public CompletableFuture<FFMpegUtils.Validation> validateCandidate(Path candidate)
    {
        return CompletableFuture.supplyAsync(() -> FFMpegUtils.validateFFMPEG(candidate), this.executor);
    }

    Result installAsset(Asset selected) throws Exception
    {
        Path versionRoot = this.root.resolve(MANAGED_VERSION);
        Path finalRoot = versionRoot.resolve(selected.platformId());
        Path executable = finalRoot.resolve(selected.executableName());
        Path ready = finalRoot.resolve(".ready");

        Files.createDirectories(versionRoot);

        if (Files.isRegularFile(ready) && Files.isRegularFile(executable))
        {
            this.status = new Status(Phase.CHECKING, Failure.NONE, selected.size(), selected.size(), 0D, 0L, "", executable);

            if (this.validator.isUsable(executable))
            {
                return new Result(executable, true);
            }
        }

        Path partial = versionRoot.resolve(selected.assetName() + ".part");

        this.downloadAsset(selected, partial);
        this.status = new Status(Phase.VERIFYING, Failure.NONE, selected.size(), selected.size(), 0D, 0L, "", null);

        if (!verifyChecksum(partial, selected))
        {
            Files.deleteIfExists(partial);
            this.transferFrom(selected.directUrl(), selected, partial);
            this.status = new Status(Phase.VERIFYING, Failure.NONE, selected.size(), selected.size(), 0D, 0L, "", null);

            if (!verifyChecksum(partial, selected))
            {
                Files.deleteIfExists(partial);

                throw new InstallException(Failure.CHECKSUM, "FFmpeg checksum mismatch");
            }
        }

        Path staging = versionRoot.resolve(selected.platformId() + ".installing");

        deleteTree(versionRoot, staging);
        Files.createDirectories(staging);
        this.status = new Status(Phase.INSTALLING, Failure.NONE, selected.size(), selected.size(), 0D, 0L, "", null);

        boolean published = false;

        try
        {
            Path candidate = this.publishCandidate(partial, staging, selected);

            if (!this.validator.isUsable(candidate))
            {
                throw new InstallException(Failure.VALIDATION, "FFmpeg is missing libx264 or AAC support");
            }

            Files.writeString(staging.resolve(".ready"), selected.sha256() + System.lineSeparator(),
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            deleteTree(versionRoot, finalRoot);
            moveDirectory(staging, finalRoot);
            published = true;

            try
            {
                Files.deleteIfExists(partial);
            }
            catch (IOException e)
            {
                LOGGER.debug("Failed to delete completed FFmpeg download {}", partial, e);
            }

            return new Result(finalRoot.resolve(selected.executableName()), false);
        }
        finally
        {
            if (!published)
            {
                try
                {
                    deleteTree(versionRoot, staging);
                }
                catch (IOException cleanup)
                {
                    LOGGER.warn("[BBS-SEM] topic=ffmpeg.download phase=cleanup result=failed error_class={} error={}",
                        cleanup.getClass().getName(), cleanup.getMessage());
                }
            }
        }
    }

    void transferFrom(URI uri, Asset selected, Path partial) throws InstallException
    {
        try
        {
            Files.createDirectories(partial.getParent());
            long existing = Files.exists(partial) ? Files.size(partial) : 0L;

            if (existing > selected.size())
            {
                Files.delete(partial);
                existing = 0L;
            }

            if (existing == selected.size())
            {
                return;
            }

            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "BBS-FSR-FFmpeg/" + MANAGED_VERSION)
                .GET();

            if (existing > 0L)
            {
                request.header("Range", "bytes=" + existing + "-");
            }

            this.status = new Status(Phase.DOWNLOADING, Failure.NONE, existing, selected.size(), 0D, -1L,
                uri.getHost() == null ? "" : uri.getHost(), null);

            HttpResponse<InputStream> response;

            try
            {
                response = this.client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();

                throw new IOException("Interrupted while downloading FFmpeg", e);
            }

            try (InputStream input = response.body())
            {
                int statusCode = response.statusCode();
                long writeOffset;
                boolean append;

                if (statusCode == 206)
                {
                    ContentRange range = parseContentRange(response.headers().firstValue("Content-Range").orElse(null));

                    if (range == null || range.start() != existing || range.end() != selected.size() - 1L
                        || range.total() != selected.size())
                    {
                        throw new IOException("Invalid Content-Range response");
                    }

                    writeOffset = existing;
                    append = existing > 0L;
                }
                else if (statusCode == 200)
                {
                    writeOffset = 0L;
                    append = false;
                }
                else if (statusCode == 416 && existing == selected.size())
                {
                    return;
                }
                else
                {
                    throw new IOException("Unexpected HTTP status " + statusCode);
                }

                StandardOpenOption[] options = append
                    ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
                long started = System.nanoTime();
                long lastProgress = started;
                long written = writeOffset;
                byte[] buffer = new byte[BUFFER_SIZE];

                try (OutputStream output = Files.newOutputStream(partial, options))
                {
                    int count;

                    while ((count = input.read(buffer)) >= 0)
                    {
                        if (count == 0)
                        {
                            continue;
                        }

                        output.write(buffer, 0, count);
                        written += count;

                        if (written > selected.size())
                        {
                            throw new IOException("FFmpeg response exceeded the expected size");
                        }

                        long now = System.nanoTime();

                        if (now - lastProgress >= PROGRESS_INTERVAL_NS || written == selected.size())
                        {
                            this.updateDownloadProgress(selected, written, writeOffset, started, now, uri.getHost());
                            lastProgress = now;
                        }
                    }
                }

                if (written != selected.size())
                {
                    throw new IOException("FFmpeg response ended before the expected size");
                }

                this.updateDownloadProgress(selected, written, writeOffset, started, System.nanoTime(), uri.getHost());
            }
        }
        catch (IOException e)
        {
            throw new InstallException(Failure.NETWORK, "Failed to download FFmpeg", e);
        }
    }

    private void downloadAsset(Asset selected, Path partial) throws InstallException
    {
        List<URI> sources = List.of(URI.create(PROXY_PREFIX + selected.directUrl()), selected.directUrl());
        InstallException failure = null;

        for (URI source : sources)
        {
            for (int attempt = 0; attempt < 2; attempt += 1)
            {
                try
                {
                    this.transferFrom(source, selected, partial);

                    return;
                }
                catch (InstallException e)
                {
                    failure = e;
                }
            }
        }

        throw failure == null
            ? new InstallException(Failure.NETWORK, "No FFmpeg download source is available")
            : failure;
    }

    private Path publishCandidate(Path partial, Path staging, Asset selected) throws InstallException
    {
        Path executable = staging.resolve(selected.executableName());

        try
        {
            if (selected.packageType() == PackageType.ZIP)
            {
                extractZip(partial, staging, selected.executableName());
            }
            else
            {
                Files.copy(partial, executable, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!Files.isRegularFile(executable))
            {
                throw new IOException("FFmpeg executable was not found in the downloaded asset");
            }

            if (selected.os() != OS.WINDOWS)
            {
                makeExecutable(executable);
            }

            return executable;
        }
        catch (InstallException e)
        {
            throw e;
        }
        catch (IOException | RuntimeException e)
        {
            throw new InstallException(Failure.ARCHIVE, "Failed to install FFmpeg", e);
        }
    }

    private void updateDownloadProgress(Asset selected, long downloaded, long initial, long started, long now, String source)
    {
        double seconds = Math.max((now - started) / 1_000_000_000D, 0.001D);
        double speed = Math.max(downloaded - initial, 0L) / seconds;
        long eta = speed <= 0D ? -1L : Math.max(0L, (long) Math.ceil((selected.size() - downloaded) / speed));

        this.status = new Status(Phase.DOWNLOADING, Failure.NONE, downloaded, selected.size(), speed, eta,
            source == null ? "" : source, null);
    }

    static void extractZip(Path archive, Path staging, String executableName) throws InstallException
    {
        Path normalizedStaging = staging.toAbsolutePath().normalize();
        boolean found = false;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive)))
        {
            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null)
            {
                Path relative;

                try
                {
                    relative = Path.of(entry.getName().replace('\\', '/')).normalize();
                }
                catch (InvalidPathException e)
                {
                    throw new InstallException(Failure.ARCHIVE, "Invalid FFmpeg ZIP entry", e);
                }

                Path resolved = normalizedStaging.resolve(relative).normalize();

                if (relative.isAbsolute() || !resolved.startsWith(normalizedStaging))
                {
                    throw new InstallException(Failure.ARCHIVE, "FFmpeg ZIP entry escapes the installation directory");
                }

                if (!entry.isDirectory() && relative.getFileName() != null
                    && relative.getFileName().toString().equalsIgnoreCase(executableName))
                {
                    if (found)
                    {
                        throw new InstallException(Failure.ARCHIVE, "FFmpeg ZIP contains multiple executables");
                    }

                    Files.copy(zip, staging.resolve(executableName), StandardCopyOption.REPLACE_EXISTING);
                    found = true;
                }

                zip.closeEntry();
            }
        }
        catch (InstallException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new InstallException(Failure.ARCHIVE, "Failed to extract FFmpeg ZIP", e);
        }

        if (!found)
        {
            throw new InstallException(Failure.ARCHIVE, "FFmpeg ZIP does not contain the executable");
        }
    }

    static boolean verifyChecksum(Path file, Asset selected) throws InstallException
    {
        try
        {
            if (!Files.isRegularFile(file) || Files.size(file) != selected.size())
            {
                return false;
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = Files.newInputStream(file))
            {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;

                while ((count = input.read(buffer)) >= 0)
                {
                    if (count > 0)
                    {
                        digest.update(buffer, 0, count);
                    }
                }
            }

            return HexFormat.of().formatHex(digest.digest()).equals(selected.sha256());
        }
        catch (IOException | NoSuchAlgorithmException e)
        {
            throw new InstallException(Failure.CHECKSUM, "Failed to verify FFmpeg", e);
        }
    }

    static Asset selectAsset(OS os, String architecture)
    {
        String normalized = normalizeArchitecture(architecture);

        for (Asset asset : ASSETS)
        {
            if (asset.os() == os && asset.architecture().equals(normalized))
            {
                return asset;
            }
        }

        return null;
    }

    static String normalizeArchitecture(String architecture)
    {
        String value = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT).replace('-', '_');

        return switch (value)
        {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> value;
        };
    }

    static void deleteTree(Path managedRoot, Path target) throws IOException
    {
        retryAccessDenied(() -> deleteTreeOnce(managedRoot, target));
    }

    private static void deleteTreeOnce(Path managedRoot, Path target) throws IOException
    {
        Path normalizedRoot = managedRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();

        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot))
        {
            throw new IOException("Refusing to delete outside the FFmpeg managed directory");
        }

        if (!Files.exists(normalizedTarget))
        {
            return;
        }

        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                Files.delete(file);

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException
            {
                if (error != null)
                {
                    throw error;
                }

                Files.delete(directory);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeExecutable(Path executable) throws IOException
    {
        try
        {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(executable);

            permissions = new java.util.HashSet<>(permissions);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        }
        catch (UnsupportedOperationException e)
        {
            executable.toFile().setExecutable(true, false);
        }

        if (!Files.isExecutable(executable))
        {
            throw new IOException("FFmpeg executable permission could not be set");
        }
    }

    private static ContentRange parseContentRange(String header)
    {
        if (header == null)
        {
            return null;
        }

        Matcher matcher = CONTENT_RANGE.matcher(header.trim());

        if (!matcher.matches() || matcher.group(3).equals("*"))
        {
            return null;
        }

        try
        {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));

            return start <= end && end < total ? new ContentRange(start, end, total) : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException
    {
        try
        {
            moveDirectoryWithRetry(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            moveDirectoryWithRetry(source, target);
        }
    }

    private static void moveDirectoryWithRetry(Path source, Path target, StandardCopyOption... options) throws IOException
    {
        retryAccessDenied(() -> Files.move(source, target, options));
    }

    private static void retryAccessDenied(FileOperation operation) throws IOException
    {
        AccessDeniedException denied = null;

        for (int attempt = 0; attempt < FILE_OPERATION_ATTEMPTS; attempt += 1)
        {
            try
            {
                operation.run();

                return;
            }
            catch (AccessDeniedException e)
            {
                denied = e;

                if (attempt + 1 < FILE_OPERATION_ATTEMPTS)
                {
                    pauseBeforeRetry(attempt, e);
                }
            }
        }

        throw denied;
    }

    private static void pauseBeforeRetry(int attempt, AccessDeniedException failure) throws IOException
    {
        try
        {
            Thread.sleep(FILE_OPERATION_RETRY_MS * (attempt + 1L));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            failure.addSuppressed(e);
            throw failure;
        }
    }

    @FunctionalInterface
    private interface FileOperation
    {
        void run() throws IOException;
    }

    private static Throwable unwrap(Throwable error)
    {
        Throwable current = error;

        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }

        return current;
    }

    private static final class Holder
    {
        private static final FFMpegDownloadManager INSTANCE = new FFMpegDownloadManager(
            BBSMod.getSettingsPath("tools/ffmpeg").toPath(),
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(),
            selectAsset(OS.CURRENT, System.getProperty("os.arch")),
            (candidate) -> FFMpegUtils.validateFFMPEG(candidate).usable()
        );
    }

    enum PackageType
    {
        ZIP,
        EXECUTABLE
    }

    record Asset(OS os, String architecture, String platformId, PackageType packageType,
                 String assetName, String executableName, long size, String sha256, URI directUrl)
    {}

    record ContentRange(long start, long end, long total)
    {}

    @FunctionalInterface
    interface CandidateValidator
    {
        boolean isUsable(Path executable) throws Exception;
    }

    public enum Phase
    {
        IDLE,
        CHECKING,
        DOWNLOADING,
        VERIFYING,
        INSTALLING,
        READY,
        FAILED,
        UNSUPPORTED
    }

    public enum Failure
    {
        NONE,
        UNSUPPORTED_PLATFORM,
        NETWORK,
        CHECKSUM,
        ARCHIVE,
        VALIDATION,
        FILESYSTEM
    }

    public record Status(Phase phase, Failure failure, long downloadedBytes, long totalBytes,
                         double bytesPerSecond, long etaSeconds, String source, Path executable)
    {
        static Status idle(long totalBytes)
        {
            return new Status(Phase.IDLE, Failure.NONE, 0L, totalBytes, 0D, -1L, "", null);
        }
    }

    public record Result(Path executable, boolean reused)
    {}

    static final class InstallException extends Exception
    {
        private final Failure failure;

        InstallException(Failure failure, String message)
        {
            super(message);
            this.failure = failure;
        }

        InstallException(Failure failure, String message, Throwable cause)
        {
            super(message, cause);
            this.failure = failure;
        }

        Failure failure()
        {
            return this.failure;
        }
    }
}
