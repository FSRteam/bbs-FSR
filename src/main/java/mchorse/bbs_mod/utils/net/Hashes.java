package mchorse.bbs_mod.utils.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming digest helpers shared by the download kernel. Callers pass the
 * algorithm by name (SHA-256 today, MD5 kept available for external
 * checksum integrations), the hex comparison stays lowercase.
 */
public final class Hashes
{
    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashes()
    {}

    public static String hashHex(String algorithm, byte[] data)
    {
        return HexFormat.of().formatHex(digest(algorithm).digest(data));
    }

    public static String hashHex(String algorithm, Path file) throws IOException
    {
        MessageDigest digest = digest(algorithm);

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

        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Size-then-digest gate for a freshly transferred file: a wrong size
     * short-circuits before hashing, so truncated downloads never cost a
     * full pass.
     */
    public static boolean matches(Path file, String algorithm, String expectedHex, long expectedSize) throws IOException
    {
        if (!Files.isRegularFile(file) || Files.size(file) != expectedSize)
        {
            return false;
        }

        return hashHex(algorithm, file).equals(expectedHex);
    }

    public static MessageDigest digest(String algorithm)
    {
        try
        {
            return MessageDigest.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalArgumentException("Unknown digest algorithm: " + algorithm, e);
        }
    }
}
