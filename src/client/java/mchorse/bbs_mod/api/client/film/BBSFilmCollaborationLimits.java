package mchorse.bbs_mod.api.client.film;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Shared API/wire allocation limits for semantic Film collaboration. */
public final class BBSFilmCollaborationLimits
{
    public static final int MAX_MUTATIONS = 256;
    public static final int MAX_PRESENCE_SELECTIONS = 256;
    public static final int MAX_SELECTED_REPLAYS = 256;
    public static final int MAX_SELECTED_KEYFRAMES = 256;
    public static final int MAX_PRESENCE_SHEET_ID_UTF8_BYTES = 256;
    public static final int MAX_PRESENCE_SELECTION_SHEETS = 32;
    public static final int MAX_PRESENCE_SELECTION_SHEET_UTF8_BYTES = 512;
    public static final int MAX_PRESENCE_CURSOR_ROW = 1_000_000;
    public static final int MAX_PATH_SEGMENTS = 64;
    public static final int MAX_SEGMENT_UTF8_BYTES = 1024;
    public static final int MAX_PATH_UTF8_BYTES = 16 * 1024;
    public static final int MAX_MUTATION_BYTES = 16 * 1024 * 1024;
    public static final int MAX_BATCH_BYTES = 32 * 1024 * 1024;
    public static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    private BBSFilmCollaborationLimits()
    {}

    static void requireSession(long sessionId)
    {
        if (sessionId <= 0)
        {
            throw new IllegalArgumentException("sessionId must be positive");
        }
    }

    static void requireRevision(long revision, String name)
    {
        if (revision < 0)
        {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    static void requireServerSeq(long serverSeq)
    {
        if (serverSeq < BBSFilmMutationBatch.NO_SERVER_SEQUENCE)
        {
            throw new IllegalArgumentException("serverSeq must be -1 or non-negative");
        }
    }

    static void requirePath(List<String> path)
    {
        if (path.isEmpty() || path.size() > MAX_PATH_SEGMENTS)
        {
            throw new IllegalArgumentException("path must contain 1.." + MAX_PATH_SEGMENTS + " segments");
        }

        int totalBytes = 0;

        for (String segment : path)
        {
            if (segment == null || segment.isEmpty())
            {
                throw new IllegalArgumentException("path contains an empty segment");
            }

            int bytes = segment.getBytes(StandardCharsets.UTF_8).length;

            if (bytes > MAX_SEGMENT_UTF8_BYTES)
            {
                throw new IllegalArgumentException("path segment exceeds its UTF-8 limit");
            }

            totalBytes += bytes;

            if (totalBytes > MAX_PATH_UTF8_BYTES)
            {
                throw new IllegalArgumentException("path exceeds its UTF-8 limit");
            }
        }
    }

    static byte[] copyEncoded(byte[] encoded, int maximum, String name)
    {
        if (encoded == null || encoded.length == 0 || encoded.length > maximum)
        {
            throw new IllegalArgumentException(name + " must contain 1.." + maximum + " bytes");
        }

        return encoded.clone();
    }

    static String requireText(String value, int maximumUtf8Bytes, String name)
    {
        Objects.requireNonNull(value, name);

        if (value.isBlank()
            || value.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes
            || value.codePoints().anyMatch(Character::isISOControl))
        {
            throw new IllegalArgumentException(name + " is blank, too large or contains control characters");
        }

        return value;
    }

    static String requireOptionalText(String value, int maximumUtf8Bytes, String name)
    {
        Objects.requireNonNull(value, name);

        if (value.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes
            || value.codePoints().anyMatch(Character::isISOControl))
        {
            throw new IllegalArgumentException(name + " is too large or contains control characters");
        }

        return value;
    }
}
