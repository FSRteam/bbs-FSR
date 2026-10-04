package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.utils.net.ArchiveExtractor;
import mchorse.bbs_mod.utils.net.AtomicFiles;
import mchorse.bbs_mod.utils.net.Hashes;
import mchorse.bbs_mod.utils.net.HttpTransfer;
import mchorse.bbs_mod.utils.net.SharedHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FFmpeg asset installer on top of the shared download kernel
 * ({@link mchorse.bbs_mod.utils.net.HttpTransfer} and siblings):
 * manifest-driven platform selection, proxy-first multi-source transfer,
 * SHA-256 pinning and a validated staging publication. Transfer, hashing,
 * archive and file primitives live in the kernel — this class only owns
 * the FFmpeg policy.
 */
public final class FFMpegDownloadManager
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-ffmpeg-download");
    static final String MANAGED_VERSION = "8.1.2-20260816";
    private static final String PROXY_PREFIX = "https://gh-proxy.cn/";

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

        AtomicFiles.deleteTree(versionRoot, staging);
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

            AtomicFiles.deleteTree(versionRoot, finalRoot);
            AtomicFiles.moveDirectory(staging, finalRoot);
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
                    AtomicFiles.deleteTree(versionRoot, staging);
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
            HttpTransfer.download(this.client, uri, partial, selected.size(), selected.size(),
                "BBS-FSR-FFmpeg/" + MANAGED_VERSION, null, (downloaded, total, speed, eta) ->
                {
                    this.status = new Status(Phase.DOWNLOADING, Failure.NONE, downloaded, selected.size(),
                        speed, eta, uri.getHost() == null ? "" : uri.getHost(), null);
                });
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
                AtomicFiles.makeExecutable(executable);
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

    static void extractZip(Path archive, Path staging, String executableName) throws InstallException
    {
        try
        {
            ArchiveExtractor.extractEntry(archive, staging, executableName);
        }
        catch (IOException e)
        {
            throw new InstallException(Failure.ARCHIVE, e.getMessage(), e);
        }
    }

    static boolean verifyChecksum(Path file, Asset selected) throws InstallException
    {
        try
        {
            return Hashes.matches(file, "SHA-256", selected.sha256(), selected.size());
        }
        catch (IOException e)
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
            SharedHttp.get(),
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
