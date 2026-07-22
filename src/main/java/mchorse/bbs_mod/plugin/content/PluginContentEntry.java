package mchorse.bbs_mod.plugin.content;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One immutable, host-owned file in a content-only plugin snapshot.
 */
public final class PluginContentEntry
{
    private final PluginContentKind kind;
    private final String path;
    private final byte[] content;
    private final String sha256;

    public PluginContentEntry(PluginContentKind kind, String path, byte[] content)
    {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.path = validatePath(path);
        this.content = Objects.requireNonNull(content, "content").clone();
        this.sha256 = hash(this.content);
    }

    public PluginContentKind kind()
    {
        return this.kind;
    }

    public String path()
    {
        return this.path;
    }

    public int size()
    {
        return this.content.length;
    }

    public String sha256()
    {
        return this.sha256;
    }

    public byte[] content()
    {
        return this.content.clone();
    }

    public InputStream openStream()
    {
        return new ByteArrayInputStream(this.content);
    }

    @Override
    public boolean equals(Object object)
    {
        if (this == object)
        {
            return true;
        }

        if (!(object instanceof PluginContentEntry entry))
        {
            return false;
        }

        return this.kind == entry.kind
            && this.path.equals(entry.path)
            && Arrays.equals(this.content, entry.content);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(this.kind, this.path);

        return 31 * result + Arrays.hashCode(this.content);
    }

    static String validatePath(String path)
    {
        Objects.requireNonNull(path, "path");

        if (path.isBlank() || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
        {
            throw new IllegalArgumentException("Content path must be a non-empty relative archive path: " + path);
        }

        for (String segment : path.split("/", -1))
        {
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            {
                throw new IllegalArgumentException("Content path contains an unsafe segment: " + path);
            }
        }

        return path;
    }

    private static String hash(byte[] content)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
