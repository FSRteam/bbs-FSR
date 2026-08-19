package mchorse.bbs_mod.ui.framework.elements.context;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.ui.themes.UIThemeMotionTracks;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

public abstract class UIContextMenu extends UIElement
{
    private final UITween appear = new UITween();

    /* Exit state: the menu is semantically closed (no input, no hit testing),
     * it only lingers for the reverse animation before the real removal */
    private boolean closing;
    private boolean removalQueued;

    public UIContextMenu()
    {
        super();

        this.eventPropagataion(EventPropagation.BLOCK_INSIDE);
    }

    public abstract boolean isEmpty();

    /**
     * Set mouse coordinate
     *
     * In this method for subclasses, you should setup the resizer
     */
    public abstract void setMouse(UIContext context);

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            context.closeContextMenu();
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            context.closeContextMenu();

            return true;
        }

        return super.subKeyPressed(context);
    }

    public boolean isClosing()
    {
        return this.closing;
    }

    /**
     * Start the exit animation. Returns false when motion is off (or the
     * entry is disabled), in which case the caller must remove the menu
     * immediately, exactly like v1.
     */
    public boolean beginExit()
    {
        if (this.closing)
        {
            return true;
        }

        if (UIMotions.duration(UIMotions.contextMenu()) <= 0)
        {
            return false;
        }

        this.closing = true;
        this.setEnabled(false);
        this.appear.to(0F, UIMotions.contextMenu());

        return true;
    }

    @Override
    public void render(UIContext context)
    {
        /* Appear animation is render-only: the menu is clickable at its
         * final position from frame one. The exit animation is the one
         * exception: the menu is already semantically closed and only
         * lingers visually until the reverse tween settles */
        if (!this.closing)
        {
            this.appear.to(1F, UIMotions.contextMenu());
        }

        float factor = this.appear.update();

        if (this.closing && this.appear.isSettled())
        {
            if (!this.removalQueued)
            {
                this.removalQueued = true;

                context.render.postRunnable(() -> context.removeExitedContextMenu(this));
            }

            return;
        }

        if (this.appear.isSettled())
        {
            this.renderBackground(context);

            super.render(context);

            return;
        }

        UIThemeMotion spec = UIMotions.contextMenu();
        UIThemeMotionTracks tracks = spec == null ? null : spec.tracks;
        PoseStack pose = context.batcher.getContext().pose();
        float scale = tracks == null ? 0.97F + 0.03F * factor : tracks.scaleAt(factor);

        pose.pushPose();

        if (tracks != null)
        {
            pose.translate(tracks.xAt(factor), tracks.yAt(factor), 0F);
        }

        pose.translate(this.area.x, this.area.y, 0F);
        pose.scale(scale, scale, 1F);
        pose.translate(-this.area.x, -this.area.y, 0F);

        if (tracks != null)
        {
            context.batcher.pushAlpha(tracks.alphaAt(factor));
        }

        this.renderBackground(context);

        super.render(context);

        if (tracks != null)
        {
            context.batcher.popAlpha();
        }

        pose.popPose();
    }

    protected void renderBackground(UIContext context)
    {
        if (BBSSettings.hasOverlayGradientBorder())
        {
            context.batcher.dropShadow(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 10, BBSSettings.panelShadowOpaqueColor(), BBSSettings.panelShadowTransparentColor());
        }
        int radius = BBSSettings.cornerChrome();

        if (radius > 0)
        {
            context.batcher.roundedFrame(this.area.x, this.area.y, this.area.w, this.area.h, radius, 1F, BBSSettings.chromeSurface(), BBSSettings.raisedSurface());
        }
        else
        {
            this.area.render(context.batcher, BBSSettings.raisedSurface());
        }
    }
}
