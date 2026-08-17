package mchorse.bbs_mod.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Hermetic regressions for FFmpeg manifest selection, transfer, and publication. */
public final class FFMpegDownloadManagerTest
{
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-");

    private FFMpegDownloadManagerTest()
    {}

    public static void main(String[] args) throws Exception
    {
        assertManifestSelection();
        assertProcessTermination();
        assertRangeResume();
        assertFullResponseReplacesPartial();
        assertProxyFallbackAndPublication();
        assertTransientExecutableLockIsRetried();
        assertZipPublicationAndCrashRecovery();
        assertCapabilityFailureLeavesNoInstall();
        assertHashFailureLeavesNoInstall();
        assertZipTraversalIsRejected();

        System.out.println("FFMpegDownloadManagerTest: all tests passed");
    }

    private static void assertManifestSelection()
    {
        check(FFMpegDownloadManager.selectAsset(OS.WINDOWS, "amd64") != null, "Windows x64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.WINDOWS, "ARM64") != null, "Windows ARM64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.LINUX, "x86_64") != null, "Linux x64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.LINUX, "aarch64") != null, "Linux ARM64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.MACOS, "x64") != null, "macOS x64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.MACOS, "arm64") != null, "macOS ARM64 asset missing");
        check(FFMpegDownloadManager.selectAsset(OS.WINDOWS, "mips64") == null, "Unknown architecture was accepted");
    }

    private static void assertProcessTermination() throws Exception
    {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
            HangingProcess.class.getName()).start();

        try
        {
            check(!process.waitFor(100L, TimeUnit.MILLISECONDS), "Termination fixture exited too early");
            check(!FFMpegUtils.waitForProcess(process, 10L), "A timed-out process was reported as healthy");
            check(!process.isAlive(), "Timed-out FFmpeg validation process remained alive");
        }
        finally
        {
            process.destroyForcibly();
            process.waitFor(2L, TimeUnit.SECONDS);
        }
    }

    private static void assertTransientExecutableLockIsRetried() throws Exception
    {
        if (OS.CURRENT != OS.WINDOWS)
        {
            return;
        }

        byte[] content = bytes("temporarily-locked-ffmpeg-binary");
        AssetFixture fixture = asset(content, "locked-publication-test");
        Path root = Files.createTempDirectory("bbs-ffmpeg-locked-publish-");
        AtomicReference<Process> locker = new AtomicReference<>();

        try
        {
            FFMpegDownloadManager manager = fixture.manager(
                root, new RecordingHttpClient(content, false, false), (candidate) ->
                {
                    Process process = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        LockedFileProcess.class.getName(), candidate.toString()).redirectErrorStream(true).start();

                    locker.set(process);

                    try (var output = process.inputReader())
                    {
                        check("locked".equals(output.readLine()), "File-lock fixture failed to start");
                    }

                    return true;
                });
            FFMpegDownloadManager.Result result = manager.installAsset(fixture.asset);

            check(Files.isRegularFile(result.executable()), "Transient file lock prevented publication");
            check(Files.isRegularFile(result.executable().getParent().resolve(".ready")),
                "Transient file lock prevented the ready marker from being published");
        }
        finally
        {
            Process process = locker.get();

            if (process != null)
            {
                process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
            }

            deleteTree(root);
        }
    }

    private static void assertRangeResume() throws Exception
    {
        byte[] content = bytes("0123456789abcdef");
        AssetFixture fixture = asset(content, "range-test");
        Path root = Files.createTempDirectory("bbs-ffmpeg-range-");

        try
        {
            Path partial = root.resolve("partial.part");
            Files.write(partial, Arrays.copyOf(content, 5));
            RecordingHttpClient client = new RecordingHttpClient(content, false, false);
            FFMpegDownloadManager manager = fixture.manager(root, client, (candidate) -> true);

            manager.transferFrom(fixture.asset.directUrl(), fixture.asset, partial);

            check(Arrays.equals(content, Files.readAllBytes(partial)), "Range resume produced the wrong bytes");
            check(client.requests.size() == 1, "Range resume made an unexpected number of requests");
            check("bytes=5-".equals(client.requests.get(0).headers().firstValue("Range").orElse(null)),
                "Range resume sent the wrong offset");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertFullResponseReplacesPartial() throws Exception
    {
        byte[] content = bytes("complete-response");
        AssetFixture fixture = asset(content, "full-response-test");
        Path root = Files.createTempDirectory("bbs-ffmpeg-full-");

        try
        {
            Path partial = root.resolve("partial.part");
            Files.write(partial, bytes("stale"));
            RecordingHttpClient client = new RecordingHttpClient(content, true, false);
            FFMpegDownloadManager manager = fixture.manager(root, client, (candidate) -> true);

            manager.transferFrom(fixture.asset.directUrl(), fixture.asset, partial);

            check(Arrays.equals(content, Files.readAllBytes(partial)),
                "Server response without Range was appended to the partial file");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertProxyFallbackAndPublication() throws Exception
    {
        byte[] content = bytes("fake-ffmpeg-binary");
        AssetFixture fixture = asset(content, "publication-test");
        Path root = Files.createTempDirectory("bbs-ffmpeg-publish-");
        RecordingHttpClient client = new RecordingHttpClient(content, false, true);

        try
        {
            FFMpegDownloadManager manager = fixture.manager(root, client, (candidate) -> Files.isRegularFile(candidate));
            FFMpegDownloadManager.Result result = manager.installAsset(fixture.asset);
            Path finalRoot = result.executable().getParent();

            check(!result.reused(), "First install was incorrectly reported as reused");
            check(Files.isRegularFile(result.executable()), "Published executable is missing");
            check(Files.isRegularFile(finalRoot.resolve(".ready")), "Ready marker was not published");
            check(Files.readString(finalRoot.resolve(".ready")).startsWith(fixture.asset.sha256()),
                "Ready marker does not contain the verified digest");
            check(client.requests.size() >= 3, "Proxy fallback did not retry before using GitHub");
            check("gh-proxy.cn".equals(client.requests.get(0).uri().getHost()), "Proxy was not tried first");
            check("github.com".equals(client.requests.get(client.requests.size() - 1).uri().getHost()),
                "Direct GitHub fallback was not used");
            check(!treeHasFileEndingWith(root, ".part"),
                "Completed download left a partial file behind");

            RecordingHttpClient unused = new RecordingHttpClient(content, false, true);
            FFMpegDownloadManager reusedManager = fixture.manager(root, unused, (candidate) -> Files.isRegularFile(candidate));
            FFMpegDownloadManager.Result reused = reusedManager.installAsset(fixture.asset);

            check(reused.reused(), "A ready installation was not reused");
            check(unused.requests.isEmpty(), "Ready installation unexpectedly downloaded again");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertHashFailureLeavesNoInstall() throws Exception
    {
        byte[] content = bytes("wrong-content");
        AssetFixture fixture = new AssetFixture(new FFMpegDownloadManager.Asset(
            OS.WINDOWS, "x86_64", "hash-test", FFMpegDownloadManager.PackageType.EXECUTABLE,
            "hash-test", "ffmpeg.exe", content.length, "0000000000000000000000000000000000000000000000000000000000000000",
            URI.create("https://github.com/example/hash-test")));
        Path root = Files.createTempDirectory("bbs-ffmpeg-hash-");

        try
        {
            FFMpegDownloadManager manager = fixture.manager(root, new RecordingHttpClient(content, false, false), (candidate) -> true);

            try
            {
                manager.installAsset(fixture.asset);
                throw new AssertionError("Hash mismatch unexpectedly installed FFmpeg");
            }
            catch (FFMpegDownloadManager.InstallException expected)
            {
                check(expected.failure() == FFMpegDownloadManager.Failure.CHECKSUM,
                    "Hash mismatch returned the wrong failure type");
            }

            check(treeHasNoFileNamed(root, ".ready"), "Hash failure wrote a ready marker");
            check(treeHasNoFileNamed(root, "ffmpeg.exe"), "Hash failure published an executable");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertZipPublicationAndCrashRecovery() throws Exception
    {
        byte[] executable = bytes("zipped-ffmpeg-binary");
        byte[] archive = zip("bundle/bin/ffmpeg.exe", executable);
        FFMpegDownloadManager.Asset asset = new FFMpegDownloadManager.Asset(
            OS.WINDOWS, "x86_64", "zip-test", FFMpegDownloadManager.PackageType.ZIP,
            "zip-test.zip", "ffmpeg.exe", archive.length, sha256(archive),
            URI.create("https://github.com/example/zip-test"));
        AssetFixture fixture = new AssetFixture(asset);
        Path root = Files.createTempDirectory("bbs-ffmpeg-zip-publish-");
        Path staging = root.resolve(FFMpegDownloadManager.MANAGED_VERSION).resolve("zip-test.installing");

        try
        {
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("crash-residue"), "stale");

            FFMpegDownloadManager manager = fixture.manager(
                root, new RecordingHttpClient(archive, false, false), Files::isRegularFile);
            FFMpegDownloadManager.Result result = manager.installAsset(asset);

            check(Arrays.equals(executable, Files.readAllBytes(result.executable())),
                "ZIP installation published the wrong executable bytes");
            check(Files.isRegularFile(result.executable().getParent().resolve(".ready")),
                "ZIP installation did not publish a ready marker");
            check(!Files.exists(result.executable().getParent().resolve("crash-residue")),
                "Crash residue survived publication");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertCapabilityFailureLeavesNoInstall() throws Exception
    {
        byte[] content = bytes("valid-but-incapable-binary");
        AssetFixture fixture = asset(content, "capability-test");
        Path root = Files.createTempDirectory("bbs-ffmpeg-capability-");

        try
        {
            FFMpegDownloadManager manager = fixture.manager(
                root, new RecordingHttpClient(content, false, false), (candidate) -> false);

            try
            {
                manager.installAsset(fixture.asset);
                throw new AssertionError("Incapable FFmpeg was installed");
            }
            catch (FFMpegDownloadManager.InstallException expected)
            {
                check(expected.failure() == FFMpegDownloadManager.Failure.VALIDATION,
                    "Capability failure returned the wrong failure type");
            }

            check(treeHasNoFileNamed(root, ".ready"), "Capability failure wrote a ready marker");
            check(treeHasNoFileNamed(root, "ffmpeg.exe"), "Capability failure published an executable");
            check(treeHasFileEndingWith(root, ".part"), "Capability failure discarded a verified resumable asset");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static void assertZipTraversalIsRejected() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-ffmpeg-zip-");
        Path archive = root.resolve("asset.zip");
        Path staging = root.resolve("staging");

        try
        {
            Files.createDirectories(staging);

            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
            {
                zip.putNextEntry(new ZipEntry("../outside.txt"));
                zip.write(bytes("escape"));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("bin/ffmpeg.exe"));
                zip.write(bytes("binary"));
                zip.closeEntry();
            }

            try
            {
                FFMpegDownloadManager.extractZip(archive, staging, "ffmpeg.exe");
                throw new AssertionError("ZIP traversal entry was accepted");
            }
            catch (FFMpegDownloadManager.InstallException expected)
            {
                check(expected.failure() == FFMpegDownloadManager.Failure.ARCHIVE,
                    "ZIP traversal returned the wrong failure type");
            }

            check(!Files.exists(root.resolve("outside.txt")), "ZIP traversal created an outside file");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private static AssetFixture asset(byte[] content, String id) throws NoSuchAlgorithmException
    {
        return new AssetFixture(new FFMpegDownloadManager.Asset(
            OS.WINDOWS, "x86_64", id, FFMpegDownloadManager.PackageType.EXECUTABLE,
            id, "ffmpeg.exe", content.length, sha256(content), URI.create("https://github.com/example/" + id)));
    }

    private static byte[] bytes(String value)
    {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String name, byte[] content) throws IOException
    {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(output))
        {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }

        return output.toByteArray();
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException
    {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String javaExecutable()
    {
        return Path.of(System.getProperty("java.home"), "bin",
            OS.CURRENT == OS.WINDOWS ? "java.exe" : "java").toString();
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static boolean treeHasNoFileNamed(Path root, String name) throws IOException
    {
        try (var paths = Files.walk(root))
        {
            return paths.noneMatch(path -> path.getFileName().toString().equals(name));
        }
    }

    private static boolean treeHasFileEndingWith(Path root, String suffix) throws IOException
    {
        try (var paths = Files.walk(root))
        {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(suffix));
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    private record AssetFixture(FFMpegDownloadManager.Asset asset)
    {
        private FFMpegDownloadManager manager(Path root, RecordingHttpClient client,
                                               FFMpegDownloadManager.CandidateValidator validator)
        {
            return new FFMpegDownloadManager(root, client, this.asset, validator);
        }
    }

    private static final class RecordingHttpClient extends HttpClient
    {
        private final byte[] content;
        private final boolean ignoreRange;
        private final boolean failProxy;
        private final List<HttpRequest> requests = new ArrayList<>();

        private RecordingHttpClient(byte[] content, boolean ignoreRange, boolean failProxy)
        {
            this.content = content;
            this.ignoreRange = ignoreRange;
            this.failProxy = failProxy;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException
        {
            this.requests.add(request);

            if (this.failProxy && "gh-proxy.cn".equals(request.uri().getHost()))
            {
                throw new IOException("simulated proxy outage");
            }

            String rangeHeader = request.headers().firstValue("Range").orElse(null);
            int offset = 0;
            int status = 200;
            byte[] body = this.content;
            Map<String, List<String>> headers = Map.of();

            if (!this.ignoreRange && rangeHeader != null)
            {
                Matcher matcher = RANGE.matcher(rangeHeader);

                if (!matcher.matches())
                {
                    throw new IOException("malformed test range");
                }

                offset = Integer.parseInt(matcher.group(1));
                status = 206;
                body = Arrays.copyOfRange(this.content, offset, this.content.length);
                headers = Map.of("Content-Range", List.of("bytes " + offset + "-" + (this.content.length - 1) + "/" + this.content.length));
            }

            HttpResponse<InputStream> response = new TestResponse(
                status, request, request.uri(), new ByteArrayInputStream(body), headers);

            @SuppressWarnings("unchecked")
            HttpResponse<T> cast = (HttpResponse<T>) response;

            return cast;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler)
        {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler,
                                                                PushPromiseHandler<T> pushPromiseHandler)
        {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1L)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    public static final class HangingProcess
    {
        public static void main(String[] args) throws Exception
        {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5L));
        }
    }

    public static final class LockedFileProcess
    {
        public static void main(String[] args) throws Exception
        {
            try (InputStream input = new java.io.FileInputStream(Path.of(args[0]).toFile()))
            {
                System.out.println("locked");
                System.out.flush();
                Thread.sleep(750L);

                if (input.read() < 0)
                {
                    throw new IOException("Lock fixture could not read the candidate");
                }
            }
        }
    }

    private static final class TestResponse implements HttpResponse<InputStream>
    {
        private final int status;
        private final HttpRequest request;
        private final URI uri;
        private final InputStream body;
        private final HttpHeaders headers;

        private TestResponse(int status, HttpRequest request, URI uri, InputStream body, Map<String, List<String>> headers)
        {
            this.status = status;
            this.request = request;
            this.uri = uri;
            this.body = body;
            this.headers = HttpHeaders.of(headers, (name, value) -> true);
        }

        @Override public int statusCode() { return this.status; }
        @Override public HttpRequest request() { return this.request; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return this.headers; }
        @Override public InputStream body() { return this.body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return this.uri; }
        @Override public Version version() { return Version.HTTP_1_1; }
    }
}
