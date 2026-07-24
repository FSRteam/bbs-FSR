package mchorse.bbs_mod.ui.framework.elements.context;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

public abstract class UIContextMenu extends UIElement
{
    private final UITween appear = new UITween();

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

    @Override
    public void render(UIContext context)
    {
        /* Appear animation is render-only: the menu is clickable at its
         * final position from frame one */
        this.appear.to(1F, UIMotions.contextMenu());

        float factor = this.appear.update();

        if (this.appear.isSettled())
        {
            this.renderBackground(context);

            super.render(context);

            return;
        }

        PoseStack pose = context.batcher.getContext().pose();
        float scale = 0.97F + 0.03F * factor;

        pose.pushPose();
        pose.translate(this.area.x, this.area.y, 0F);
        pose.scale(scale, scale, 1F);
        pose.translate(-this.area.x, -this.area.y, 0F);

        this.renderBackground(context);

        super.render(context);

        pose.popPose();
    }

    protected void renderBackground(UIContext context)
    {
        context.batcher.dropShadow(this.area.x, this.area.y, this.area.ex(), this.area.ey(), 10, BBSSettings.panelShadowOpaqueColor(), BBSSettings.panelShadowTransparentColor());
        this.area.render(context.batcher, BBSSettings.raisedSurface());
    }
}
