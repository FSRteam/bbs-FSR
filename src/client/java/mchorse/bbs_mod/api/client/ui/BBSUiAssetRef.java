package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

/**
 * Opaque reference to an asset used by UI mirror draw commands.
 *
 * The id is meaningful only to the mirror API and never contains a resource
 * link, filesystem path, or renderer texture id.
 */
public final class BBSUiAssetRef
{
    private final String id;
    private final int width;
    private final int height;

    public BBSUiAssetRef(String id, int width, int height)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);

        if (this.id.isBlank())
        {
            throw new IllegalArgumentException("asset id is blank");
        }
    }

    public String id()
    {
        return this.id;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }
}
