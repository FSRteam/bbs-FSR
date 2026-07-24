package mchorse.bbs_mod.ui.framework.elements.buttons;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.interps.Lerps;

import java.util.function.Consumer;

public abstract class UIClickable <T> extends UIElement
{
    public Consumer<T> callback;

    private final MouseGestureOwnership pressOwnership = new MouseGestureOwnership();
    private long pressGeneration;
    private ProgrammaticPress programmaticPress;
    protected boolean hover;
    protected boolean pressed;

    /* Render-only hover/press scale (never affects hit testing) */
    private UITween hoverScaleTween;
    private UITween pressScaleTween;

    public UIClickable(Consumer<T> callback)
    {
        super();

        this.callback = callback;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.isAllowed(context.mouseButton) && this.area.isInside(context))
        {
            this.startPress(context.mouseButton);

            return true;
        }

        return super.subMouseClicked(context);
    }

    private long startPress(int mouseButton)
    {
        long generation = this.pressOwnership.acquireToken(mouseButton);

        if (generation == 0L)
        {
            return 0L;
        }

        this.pressGeneration = generation;
        ProgrammaticPress programmatic = this.programmaticPress;

        if (programmatic != null && programmatic.button == mouseButton && programmatic.generation == 0L)
        {
            programmatic.generation = generation;
        }

        boolean clicked = false;

        try
        {
            this.pressed = true;
            this.playClickSound();
            this.click(mouseButton);
            clicked = true;

            return generation;
        }
        finally
        {
            if (!clicked)
            {
                this.finishPress(mouseButton, generation);
            }
        }
    }

    private boolean finishPress(int mouseButton, long generation)
    {
        if (!this.pressOwnership.release(mouseButton, generation))
        {
            return false;
        }

        this.pressGeneration = 0L;
        this.pressed = false;

        return true;
    }

    @Override
    public void clickItself(UIContext context, int mouseButton)
    {
        ProgrammaticPress previous = this.programmaticPress;
        ProgrammaticPress current = new ProgrammaticPress(mouseButton);
        int mouseX = context.mouseX;
        int mouseY = context.mouseY;
        int button = context.mouseButton;

        this.programmaticPress = current;
        try
        {
            super.clickItself(context, mouseButton);
        }
        finally
        {
            try
            {
                this.finishPress(mouseButton, current.generation);
            }
            finally
            {
                this.programmaticPress = previous;
                context.mouseX = mouseX;
                context.mouseY = mouseY;
                context.mouseButton = button;
            }
        }
    }

    protected boolean isAllowed(int mouseButton)
    {
        return mouseButton == 0;
    }

    protected void playClickSound()
    {
        UIUtils.playClick();
    }

    protected void click(int mouseButton)
    {
        if (this.callback != null)
        {
            this.callback.accept(this.get());
        }
    }

    protected abstract T get();

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        long generation = this.pressGeneration;

        this.finishPress(context.mouseButton, generation);

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.finishPress(context.mouseButton, this.pressGeneration);
    }

    @Override
    public void render(UIContext context)
    {
        this.hover = this.area.isInside(context);

        float scale = this.updateRenderScale();

        if (scale == 1F)
        {
            this.renderSkin(context);
        }
        else
        {
            PoseStack pose = context.batcher.getContext().pose();
            float mx = this.area.x + this.area.w / 2F;
            float my = this.area.y + this.area.h / 2F;

            pose.pushPose();
            pose.translate(mx, my, 0F);
            pose.scale(scale, scale, 1F);
            pose.translate(-mx, -my, 0F);

            this.renderSkin(context);

            pose.popPose();
        }

        super.render(context);
    }

    /**
     * Combined hover x press scale factor. Returns exactly 1 (no pose push,
     * bit-identical to the pre-motion path) when both entries are disabled
     * or their scales equal 1.
     */
    private float updateRenderScale()
    {
        float scale = this.updateScaleFactor(UIMotions.hoverScale(), this.hover, true);

        scale *= this.updateScaleFactor(UIMotions.press(), this.pressed, false);

        return scale;
    }

    private float updateScaleFactor(UIThemeMotion spec, boolean active, boolean hoverTween)
    {
        boolean applicable = UIMotions.enabled() && spec != null && spec.enabled && spec.scale != 1F;
        UITween tween = hoverTween ? this.hoverScaleTween : this.pressScaleTween;

        if (!applicable)
        {
            /* Settle any leftover animation instantly so re-enabling starts clean */
            if (tween != null)
            {
                tween.snap(active ? 1F : 0F);
            }

            return 1F;
        }

        if (tween == null)
        {
            tween = new UITween();

            if (hoverTween)
            {
                this.hoverScaleTween = tween;
            }
            else
            {
                this.pressScaleTween = tween;
            }
        }

        tween.to(active ? 1F : 0F, spec);

        float factor = tween.update();

        if (tween.isSettled())
        {
            return active ? spec.scale : 1F;
        }

        return Lerps.lerp(1F, spec.scale, factor);
    }

    protected abstract void renderSkin(UIContext context);

    private static final class ProgrammaticPress
    {
        private final int button;
        private long generation;

        private ProgrammaticPress(int button)
        {
            this.button = button;
        }
    }
}
