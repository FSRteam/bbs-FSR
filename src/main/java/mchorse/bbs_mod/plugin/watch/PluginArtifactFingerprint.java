package mchorse.bbs_mod.plugin.watch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A stable size/mtime/content identity captured without following symlinks.
 */
public record PluginArtifactFingerprint(long size, FileTime modifiedTime, String sha256)
{
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PluginArtifactFingerprint
    {
        if (size < 0)
        {
            throw new IllegalArgumentException("size must not be negative");
        }

        Objects.requireNonNull(modifiedTime, "modifiedTime");
        Objects.requireNonNull(sha256, "sha256");

        sha256 = sha256.toLowerCase(java.util.Locale.ROOT);

        if (!SHA_256.matcher(sha256).matches())
        {
            throw new IllegalArgumentException("sha256 must be a SHA-256 hex string");
        }
    }

    public static PluginArtifactFingerprint capture(Path path) throws IOException
    {
        Objects.requireNonNull(path, "path");

        BasicFileAttributes before = readAttributes(path);

        if (!before.isRegularFile() || Files.isSymbolicLink(path))
        {
            throw new IOException("Plugin artifact must be a regular non-symlink file: " + path);
        }

        MessageDigest digest = sha256Digest();

        try (InputStream input = Files.newInputStream(path))
        {
            byte[] buffer = new byte[64 * 1024];
            int read;

            while ((read = input.read(buffer)) >= 0)
            {
                if (read > 0)
                {
                    digest.update(buffer, 0, read);
                }
            }
        }

        BasicFileAttributes after = readAttributes(path);

        if (!after.isRegularFile()
            || Files.isSymbolicLink(path)
            || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey()))
        {
            throw new UnstablePluginArtifactException(path);
        }

        return new PluginArtifactFingerprint(
            after.size(),
            after.lastModifiedTime(),
            HexFormat.of().formatHex(digest.digest())
        );
    }

    public boolean sameContent(PluginArtifactFingerprint other)
    {
        return other != null && this.sha256.equals(other.sha256);
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException
    {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static MessageDigest sha256Digest()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
