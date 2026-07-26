package mchorse.bbs_mod.ui.framework.elements.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.ui.themes.UIThemeMotionTracks;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.ui.utils.resizers.Flex;
import mchorse.bbs_mod.utils.colors.Colors;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public class UIOverlay extends UIElement
{
    private static final Map<String, Vector2i> offsets = new HashMap<>();

    private int background = Colors.A50;

    private final UITween appear = new UITween();

    /* Exit state: the overlay is semantically closed (no input, no hit
     * testing, close events already fired), it only lingers for the reverse
     * animation before the real removal */
    private boolean closing;
    private boolean removalQueued;

    public static UIOverlay addOverlay(UIContext context, UIOverlayPanel panel)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).xy(0.5F, 0.5F).wh(0.5F, 0.5F).anchor(0.5F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static UIOverlay addOverlay(UIContext context, UIOverlayPanel panel, float w, float h)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).xy(0.5F, 0.5F).wh(w, h).anchor(0.5F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static UIOverlay addOverlay(UIContext context, UIOverlayPanel panel, int w, int h)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).xy(0.5F, 0.5F).wh(w, h).anchor(0.5F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static UIOverlay addOverlay(UIContext context, UIOverlayPanel panel, int w, float h)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).xy(0.5F, 0.5F).w(w).h(h).anchor(0.5F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static UIOverlay addOverlayLeft(UIContext context, UIOverlayPanel panel, int w)
    {
        return addOverlayLeft(context, panel, w, 10);
    }

    public static UIOverlay addOverlayLeft(UIContext context, UIOverlayPanel panel, int w, int padding)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).x(padding).y(padding).w(w).h(1F, -padding * 2).anchor(0F, 0F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static UIOverlay addOverlayRight(UIContext context, UIOverlayPanel panel, int w)
    {
        return addOverlayRight(context, panel, w, 10);
    }

    public static UIOverlay addOverlayRight(UIContext context, UIOverlayPanel panel, int w, int padding)
    {
        UIOverlay overlay = new UIOverlay();

        panel.relative(overlay).x(1F, -padding).y(padding).w(w).h(1F, -padding * 2).anchor(1F, 0F).bounds(overlay, 0);
        setupPanel(context, overlay, panel);

        return overlay;
    }

    public static void setupPanel(UIContext context, UIOverlay overlay, UIOverlayPanel panel)
    {
        if (panel.hasParent())
        {
            return;
        }

        context.menu.runAfterCapturedMouseRelease(() ->
        {
            if (panel.hasParent())
            {
                return;
            }

            Flex flex = panel.getFlex();
            Vector2i offset = offsets.get(panel.getClass().getSimpleName());

            panel.setInitialOffset(flex.x.offset, flex.y.offset);

            if (offset != null)
            {
                flex.x.offset = offset.x;
                flex.y.offset = offset.y;
            }

            overlay.full(context.menu.overlay);
            context.menu.overlay.add(overlay);
            UIElement root = context.menu.getRoot();

            if (root != null)
            {
                root.moveToFront(context.menu.overlay);
            }

            overlay.add(panel);
            context.menu.overlay.resize();
        });
    }

    public static boolean has(UIContext context)
    {
        for (UIOverlayPanel panel : context.menu.getRoot().getChildren(UIOverlayPanel.class))
        {
            if (!(panel.getParent() instanceof UIOverlay) || !((UIOverlay) panel.getParent()).isClosing())
            {
                return true;
            }
        }

        return false;
    }

    public UIOverlay()
    {
        this.eventPropagataion(EventPropagation.BLOCK).markContainer();
    }

    public UIOverlay background(int background)
    {
        this.background = background;

        return this;
    }

    public UIOverlay noBackground()
    {
        return this.background(0);
    }

    public boolean isClosing()
    {
        return this.closing;
    }

    public void closeItself()
    {
        if (this.closing)
        {
            return;
        }

        if (UIMotions.duration(UIMotions.overlay()) <= 0)
        {
            this.removeNow();

            return;
        }

        /* Semantic close happens right now: events fire, offsets are saved,
         * input is dead. Only the pixels stick around for the reverse
         * animation */
        this.closing = true;
        this.setEnabled(false);
        UIUtils.playClick();

        UIContext context = this.getContext();

        for (UIOverlayPanel element : this.getChildren(UIOverlayPanel.class))
        {
            element.onClose();

            /* Save offset */
            Vector2i offset = new Vector2i(element.getFlex().x.offset, element.getFlex().y.offset);

            offsets.put(element.getClass().getSimpleName(), offset);
        }

        if (context != null && context.activeElement instanceof UIElement && this.isDescendant((UIElement) context.activeElement))
        {
            context.unfocus();
        }

        this.appear.to(0F, UIMotions.overlay());
    }

    /** The v1 immediate close path, bit-identical when motion is off. */
    private void removeNow()
    {
        this.removeFromParent();
        UIUtils.playClick();

        for (UIOverlayPanel element : this.getChildren(UIOverlayPanel.class))
        {
            element.removeFromParent();
            element.onClose();

            /* Save offset */
            Vector2i offset = new Vector2i(element.getFlex().x.offset, element.getFlex().y.offset);

            offsets.put(element.getClass().getSimpleName(), offset);
        }
    }

    /* Don't pass user input down the line... */

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0)
        {
            this.closeItself();

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    public void render(UIContext context)
    {
        /* Appear animation is render-only: hit testing, focus and lifecycle
         * always use the final layout from frame one. The exit animation is
         * the one exception: the overlay is already semantically closed and
         * only lingers visually until the reverse tween settles */
        if (!this.closing)
        {
            this.appear.to(1F, BBSSettings.textEntryOpenAnimationEnabled() ? UIMotions.overlay() : null);
        }

        float factor = this.appear.update();

        if (this.closing && this.appear.isSettled())
        {
            if (!this.removalQueued)
            {
                this.removalQueued = true;

                context.render.postRunnable(() ->
                {
                    this.removeFromParent();

                    for (UIOverlayPanel element : this.getChildren(UIOverlayPanel.class))
                    {
                        element.removeFromParent();
                    }
                });
            }

            return;
        }

        if (this.appear.isSettled())
        {
            if (Colors.getA(this.background) > 0F)
            {
                this.area.render(context.batcher, this.background);
            }

            super.render(context);

            return;
        }

        if (Colors.getA(this.background) > 0F)
        {
            this.area.render(context.batcher, Colors.mulA(this.background, factor));
        }

        UIThemeMotion spec = UIMotions.overlay();
        UIThemeMotionTracks tracks = spec == null ? null : spec.tracks;
        PoseStack pose = context.batcher.getContext().pose();
        float scale = tracks == null ? 0.95F + 0.05F * factor : tracks.scaleAt(factor);
        float cx = this.area.mx();
        float cy = this.area.my();

        pose.pushPose();

        if (tracks != null)
        {
            pose.translate(tracks.xAt(factor), tracks.yAt(factor), 0F);
        }

        pose.translate(cx, cy, 0F);
        pose.scale(scale, scale, 1F);
        pose.translate(-cx, -cy, 0F);

        if (tracks != null)
        {
            context.batcher.pushAlpha(tracks.alphaAt(factor));
        }

        super.render(context);

        if (tracks != null)
        {
            context.batcher.popAlpha();
        }

        pose.popPose();
    }
}
