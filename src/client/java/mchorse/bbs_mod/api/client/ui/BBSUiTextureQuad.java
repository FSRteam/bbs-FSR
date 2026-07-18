package mchorse.bbs_mod.api.client.ui;

import java.util.Objects;

/**
 * Painter-ordered textured quad with transformed UI coordinates and
 * normalized texture coordinates.
 */
public final class BBSUiTextureQuad implements BBSUiDrawCommand
{
    private final BBSUiAssetRef asset;
    private final BBSUiTexturedVertex topLeft;
    private final BBSUiTexturedVertex bottomLeft;
    private final BBSUiTexturedVertex bottomRight;
    private final BBSUiTexturedVertex topRight;
    private final int tint;

    public BBSUiTextureQuad(
        BBSUiAssetRef asset,
        BBSUiTexturedVertex topLeft,
        BBSUiTexturedVertex bottomLeft,
        BBSUiTexturedVertex bottomRight,
        BBSUiTexturedVertex topRight,
        int tint
    )
    {
        this.asset = Objects.requireNonNull(asset, "asset");
        this.topLeft = Objects.requireNonNull(topLeft, "topLeft");
        this.bottomLeft = Objects.requireNonNull(bottomLeft, "bottomLeft");
        this.bottomRight = Objects.requireNonNull(bottomRight, "bottomRight");
        this.topRight = Objects.requireNonNull(topRight, "topRight");
        this.tint = tint;
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.TEXTURE_QUAD;
    }

    public BBSUiAssetRef asset()
    {
        return this.asset;
    }

    public BBSUiTexturedVertex topLeft()
    {
        return this.topLeft;
    }

    public BBSUiTexturedVertex bottomLeft()
    {
        return this.bottomLeft;
    }

    public BBSUiTexturedVertex bottomRight()
    {
        return this.bottomRight;
    }

    public BBSUiTexturedVertex topRight()
    {
        return this.topRight;
    }

    public int tint()
    {
        return this.tint;
    }
}
