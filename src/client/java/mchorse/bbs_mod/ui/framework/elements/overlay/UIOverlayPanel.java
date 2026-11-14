package mchorse.bbs_mod.ui.framework.elements.overlay;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.events.UIOverlayCloseEvent;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Vector2i;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class UIOverlayPanel extends UIElement
{
    public static final int MIN_WIDTH = 160;
    public static final int MIN_HEIGHT = 80;
    private static final int GRIP = 12;

    private UIDraggable grip;
    private boolean resized;
    private boolean sizeless;

    public UILabel title;
    public UIElement icons;
    public UIIcon close;
    public UIElement content;

    private boolean moving;
    private final MouseGestureOwnership moveOwnership = new MouseGestureOwnership();
    private int lastX;
    private int lastY;

    private int initialOffsetX;
    private int initialOffsetY;

    public UIOverlayPanel(IKey title)
    {
        super();

        this.title = UI.label(title);
        this.close = new UIIcon(Icons.CLOSE, (b) -> this.close());
        this.close.hoverBackgroundColor(Colors.RED | Colors.A100).pressedBackgroundColor(Colors.RED | Colors.A100);
        this.content = new UIElement();
        this.icons = new UIElement();

        this.title.labelAnchor(0, 0.5F).relative(this).xy(6, 0).w(0.6F).h(20);
        this.icons.relative(this).x(1F, -20).y(0).w(20).h(1F).column(0).stretch();
        this.content.relative(this).xy(0, 20).w(1F, -20).h(1F, -20);

        this.icons.add(this.close);

        this.add(this.title, this.icons, this.content);

        this.mouseEventPropagataion(EventPropagation.BLOCK_INSIDE);
    }

    public boolean isResizable()
    {
        return true;
    }

    public void sizeless()
    {
        this.sizeless = true;
    }

    public int getPreferredWidth()
    {
        return -1;
    }

    public int getContentHeight()
    {
        return -1;
    }

    @Override
    public void resize()
    {
        super.resize();

        int height = this.getContentHeight();

        if (this.sizeless && height > 0 && height != this.area.h)
        {
            this.flex.h.max = 0;
            this.flex.h.set(0F, height);
            super.resize();
        }
    }

    public boolean wasResized()
    {
        return this.resized;
    }

    public void setupResize()
    {
        if (this.grip != null || !this.isResizable())
        {
            return;
        }

        this.grip = new UIDraggable(this::dragSize);
        this.grip.rendering(this::renderGrip);
        this.grip.cursors(GLFW.GLFW_RESIZE_NWSE_CURSOR, GLFW.GLFW_RESIZE_NWSE_CURSOR);
        this.grip.reference(() -> new Vector2i(this.area.ex(), this.area.ey()));
        this.grip.relative(this).x(1F, -GRIP).y(1F, -GRIP).wh(GRIP, GRIP);
        this.add(this.grip);
    }

    private void dragSize(UIContext context)
    {
        UIElement parent = this.getParent();
        float anchorX = this.flex.x.anchor;
        float anchorY = this.flex.y.anchor;

        if (anchorX < 1F)
        {
            int origin = this.area.x + (int) (this.area.w * anchorX);
            int width = (int) ((context.mouseX - origin) / (1F - anchorX));

            this.flex.w.max = 0;
            this.flex.w.set(0F, MathUtils.clamp(width, MIN_WIDTH, Math.max(parent.area.w, MIN_WIDTH)));
        }

        if (anchorY < 1F)
        {
            int origin = this.area.y + (int) (this.area.h * anchorY);
            int height = (int) ((context.mouseY - origin) / (1F - anchorY));

            this.flex.h.max = 0;
            this.flex.h.set(0F, MathUtils.clamp(height, MIN_HEIGHT, Math.max(parent.area.h, MIN_HEIGHT)));
        }

        this.resized = true;
        parent.resize();
    }

    private void renderGrip(UIContext context)
    {
        int color = this.grip.area.isInside(context) ? BBSSettings.textColor() : BBSSettings.mutedTextColor();
        int x = this.grip.area.x + 1;
        int y = this.grip.area.y + 1;

        for (int row = 0; row < 3; row++)
        {
            for (int column = 2 - row; column < 3; column++)
            {
                context.batcher.box(x + column * 3, y + row * 3, x + column * 3 + 2, y + row * 3 + 2, color);
            }
        }
    }

    public void setInitialOffset(int x, int y)
    {
        this.initialOffsetX = x;
        this.initialOffsetY = y;
    }

    public void onClose(Consumer<UIOverlayCloseEvent> callback)
    {
        this.events.register(UIOverlayCloseEvent.class, callback);
    }

    public void close()
    {
        UIElement parent = this.getParent();

        if (parent instanceof UIOverlay)
        {
            ((UIOverlay) parent).closeItself();
        }
    }

    public void confirm()
    {}

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.title.area.isInside(context))
        {
            if (context.mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT && Window.isCtrlPressed())
            {
                this.flex.x.offset = this.initialOffsetX;
                this.flex.y.offset = this.initialOffsetY;

                this.getParent().resize();

                return true;
            }

            if (context.mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && this.moveOwnership.acquire(context.mouseButton))
            {
                this.moving = true;
                this.lastX = context.mouseX;
                this.lastY = context.mouseY;

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (!this.moveOwnership.release(context.mouseButton))
        {
            return super.subMouseReleased(context);
        }

        this.moving = false;

        return true;
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        if (this.moveOwnership.release(context.mouseButton))
        {
            this.moving = false;
        }
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (!context.isFocused() && context.isPressed(Keys.CLOSE))
        {
            this.close();

            return true;
        }

        /* Enter is not gated on focus, unlike the closing escape: children are offered the key
         * first, so whoever spends it on itself (a text area, a list) has already taken it, and
         * what reaches the panel is the Enter of someone done filling the dialog in */
        if (context.isPressed(Keys.CONFIRM) || context.isPressed(GLFW.GLFW_KEY_ENTER) || context.isPressed(GLFW.GLFW_KEY_KP_ENTER))
        {
            this.confirm();

            return true;
        }

        return super.subKeyPressed(context);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.moving && (context.mouseX != this.lastX || context.mouseY != this.lastY))
        {
            int dx = context.mouseX - this.lastX;
            int dy = context.mouseY - this.lastY;
            int lastX = this.area.x;
            int lastY = this.area.y;

            this.flex.x.offset += dx;
            this.flex.y.offset += dy;

            this.getParent().resize();

            if (lastX == this.area.x) this.flex.x.offset -= dx;
            if (lastY == this.area.y) this.flex.y.offset -= dy;

            this.lastX = context.mouseX;
            this.lastY = context.mouseY;
        }

        this.renderBackground(context);

        super.render(context);
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
            this.icons.area.render(context.batcher, BBSSettings.chromeSurface());
        }
        else
        {
            this.area.render(context.batcher, BBSSettings.raisedSurface());
            this.icons.area.render(context.batcher, BBSSettings.chromeSurface());

            if (this.close.area.isInside(context))
            {
                this.close.area.render(context.batcher, Colors.RED | Colors.A100);
            }
        }

        if (this.title.area.isInside(context))
        {
            context.batcher.icon(Icons.ALL_DIRECTIONS, Colors.GRAY, this.area.mx(), this.title.area.my(), 0.5F, 0.5F);
        }
    }

    public void onClose()
    {
        this.events.emit(new UIOverlayCloseEvent(this));
    }
}
