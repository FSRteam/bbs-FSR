package mchorse.bbs_mod.utils.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resumable single-file HTTP transfer, the common denominator of every
 * downloader in the mod: byte-range resume with strict Content-Range
 * validation, full-response fallback, streaming digest, size enforcement
 * and throttled progress reporting.
 *
 * <p>Semantics (guarded by {@code FFMpegDownloadManagerTest}):
 * <ul>
 * <li>a partial file smaller than {@code expectedSize} resumes with a
 * {@code Range} request, and a 206 answer must match the requested offset
 * exactly or the transfer fails;</li>
 * <li>a 200 answer replaces the partial file instead of appending;</li>
 * <li>416 is only tolerated when the partial already holds the full size;</li>
 * <li>a response longer than the expected size (or {@code maxBytes} when the
 * size is unknown) aborts the transfer.</li>
 * </ul>
 */
public final class HttpTransfer
{
    public static final Duration REQUEST_TIMEOUT = Duration.ofHours(2L);

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_NS = Duration.ofMillis(200L).toNanos();

    private HttpTransfer()
    {}

    @FunctionalInterface
    public interface Listener
    {
        void onProgress(long downloadedBytes, long totalBytes, double bytesPerSecond, long etaSeconds);
    }

    /**
     * Downloads {@code uri} into {@code partial}.
     *
     * @param expectedSize exact payload size, or a negative value when the
     *                     size is unknown (disables resume and size equality,
     *                     {@code maxBytes} becomes the only limit)
     * @param maxBytes     hard response cap, or a negative value for no cap
     * @param digest       optional streaming digest; when resuming, the
     *                     existing prefix is hashed first so a single final
     *                     verification covers the whole file
     */
    public static void download(HttpClient client, URI uri, Path partial, long expectedSize, long maxBytes,
                                String userAgent, MessageDigest digest, Listener listener) throws IOException
    {
        Files.createDirectories(partial.getParent());

        long existing = Files.exists(partial) ? Files.size(partial) : 0L;

        if (expectedSize >= 0L)
        {
            if (existing > expectedSize)
            {
                Files.delete(partial);
                existing = 0L;
            }

            if (existing == expectedSize)
            {
                hashPrefix(partial, digest, 0L, existing);

                return;
            }
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/octet-stream")
            .header("Accept-Encoding", "identity");

        if (userAgent != null && !userAgent.isEmpty())
        {
            request.header("User-Agent", userAgent);
        }

        if (existing > 0L && expectedSize >= 0L)
        {
            request.header("Range", "bytes=" + existing + "-");
        }

        long prefix = existing > 0L && expectedSize >= 0L ? existing : 0L;

        hashPrefix(partial, digest, 0L, prefix);

        HttpResponse<InputStream> response;

        try
        {
            response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new IOException("Interrupted while downloading " + uri, e);
        }

        try (InputStream input = response.body())
        {
            int statusCode = response.statusCode();
            long writeOffset;
            boolean append;
            long declaredTotal = expectedSize >= 0L ? expectedSize : parseLength(response);

            if (statusCode == 206)
            {
                ContentRange range = parseContentRange(response.headers().firstValue("Content-Range").orElse(null));

                if (range == null || range.start() != prefix || (expectedSize >= 0L
                    && (range.end() != expectedSize - 1L || range.total() != expectedSize)))
                {
                    throw new IOException("Invalid Content-Range response");
                }

                writeOffset = prefix;
                append = prefix > 0L;
            }
            else if (statusCode == 200)
            {
                writeOffset = 0L;
                append = false;
            }
            else if (statusCode == 416 && expectedSize >= 0L && existing == expectedSize)
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

                    if (digest != null)
                    {
                        digest.update(buffer, 0, count);
                    }

                    if (expectedSize >= 0L && written > expectedSize)
                    {
                        throw new IOException("Response exceeded the expected size");
                    }

                    if (maxBytes >= 0L && written > maxBytes)
                    {
                        throw new IOException("Response exceeded the maximum allowed size");
                    }

                    long now = System.nanoTime();

                    if (now - lastProgress >= PROGRESS_INTERVAL_NS || written == declaredTotal)
                    {
                        notify(listener, written, declaredTotal, writeOffset, started, now);
                        lastProgress = now;
                    }
                }
            }

            if (expectedSize >= 0L && written != expectedSize)
            {
                throw new IOException("Response ended before the expected size");
            }

            notify(listener, written, declaredTotal, writeOffset, started, System.nanoTime());
        }
    }

    /** Hashes a byte range of an existing file into {@code digest}. */
    private static void hashPrefix(Path file, MessageDigest digest, long from, long to) throws IOException
    {
        if (digest == null || to <= from)
        {
            return;
        }

        try (InputStream input = Files.newInputStream(file))
        {
            input.skipNBytes(from);

            byte[] buffer = new byte[64 * 1024];
            long remaining = to - from;
            int count;

            while (remaining > 0L && (count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining))) >= 0)
            {
                digest.update(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private static void notify(Listener listener, long written, long total, long initial, long started, long now)
    {
        if (listener == null)
        {
            return;
        }

        double seconds = Math.max((now - started) / 1_000_000_000D, 0.001D);
        double speed = Math.max(written - initial, 0L) / seconds;
        long eta = speed <= 0D || total < 0L ? -1L : Math.max(0L, (long) Math.ceil((total - written) / speed));

        listener.onProgress(written, total, speed, eta);
    }

    private static long parseLength(HttpResponse<?> response)
    {
        String length = response.headers().firstValue("Content-Length").orElse(null);

        if (length == null)
        {
            return -1L;
        }

        try
        {
            return Long.parseLong(length.trim());
        }
        catch (NumberFormatException e)
        {
            return -1L;
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

    private record ContentRange(long start, long end, long total)
    {}
}
