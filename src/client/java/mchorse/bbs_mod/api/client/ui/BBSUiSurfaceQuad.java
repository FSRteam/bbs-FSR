package mchorse.bbs_mod.api.client.ui;

import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;

import java.util.Objects;

/**
 * Painter-ordered placement of an independently encoded dynamic surface.
 * Carries only a logical kind and browser-neutral vertices, never a GL id.
 */
public final class BBSUiSurfaceQuad implements BBSUiDrawCommand
{
    private final BBSRenderSurfaceKind surfaceKind;
    private final BBSUiTexturedVertex topLeft;
    private final BBSUiTexturedVertex bottomLeft;
    private final BBSUiTexturedVertex bottomRight;
    private final BBSUiTexturedVertex topRight;
    private final int tint;

    public BBSUiSurfaceQuad(
        BBSRenderSurfaceKind surfaceKind,
        BBSUiTexturedVertex topLeft,
        BBSUiTexturedVertex bottomLeft,
        BBSUiTexturedVertex bottomRight,
        BBSUiTexturedVertex topRight,
        int tint
    )
    {
        this.surfaceKind = Objects.requireNonNull(surfaceKind, "surfaceKind");
        this.topLeft = Objects.requireNonNull(topLeft, "topLeft");
        this.bottomLeft = Objects.requireNonNull(bottomLeft, "bottomLeft");
        this.bottomRight = Objects.requireNonNull(bottomRight, "bottomRight");
        this.topRight = Objects.requireNonNull(topRight, "topRight");
        this.tint = tint;
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.SURFACE_QUAD;
    }

    public BBSRenderSurfaceKind surfaceKind()
    {
        return this.surfaceKind;
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
