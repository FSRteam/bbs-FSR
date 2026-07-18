package mchorse.bbs_mod.api.client.ui;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable encoded bytes for an opaque UI mirror asset.
 */
public final class BBSUiAssetBytes
{
    private final BBSUiAssetRef asset;
    private final String mediaType;
    private final String contentHash;
    private final byte[] bytes;

    public BBSUiAssetBytes(BBSUiAssetRef asset, String mediaType, String contentHash, byte[] bytes)
    {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);

        if (this.mediaType.isBlank())
        {
            throw new IllegalArgumentException("asset media type is blank");
        }

        if (this.contentHash.isBlank())
        {
            throw new IllegalArgumentException("asset content hash is blank");
        }
    }

    public BBSUiAssetRef asset()
    {
        return this.asset;
    }

    public String mediaType()
    {
        return this.mediaType;
    }

    public String contentHash()
    {
        return this.contentHash;
    }

    public int length()
    {
        return this.bytes.length;
    }

    public byte[] bytes()
    {
        return Arrays.copyOf(this.bytes, this.bytes.length);
    }
}
