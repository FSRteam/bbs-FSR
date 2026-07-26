package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class UIIcon extends UIClickable<UIIcon>
{
    private static final int NO_BACKGROUND = Integer.MIN_VALUE;

    private Icon icon;
    private Supplier<Icon> iconSupplier;

    public int iconColor = Colors.WHITE;
    public int hoverColor = Colors.LIGHTEST_GRAY;
    public int activeColor = Colors.LIGHTEST_GRAY;
    public int disabledColor = 0x80404040;
    public int backgroundColor = NO_BACKGROUND;
    public int hoverBackgroundColor = NO_BACKGROUND;
    public int pressedBackgroundColor = NO_BACKGROUND;
    public int activeBackgroundColor = NO_BACKGROUND;
    public int disabledBackgroundColor = NO_BACKGROUND;

    private final UITween hoverTween = new UITween();

    private boolean active;

    public UIIcon(Icon icon, Consumer<UIIcon> callback)
    {
        super(callback);

        this.icon = icon;
        this.wh(20, 20);
    }

    public UIIcon(Supplier<Icon> iconSupplier, Consumer<UIIcon> callback)
    {
        super(callback);

        this.iconSupplier = iconSupplier;
        this.wh(20, 20);
    }

    public Icon getIcon()
    {
        if (this.iconSupplier != null)
        {
            Icon icon = this.iconSupplier.get();

            if (icon != null)
            {
                return icon;
            }
        }

        return this.icon;
    }

    public UIIcon both(Icon icon)
    {
        this.icon = icon;

        return this;
    }

    public UIIcon both(Supplier<Icon> icon)
    {
        this.iconSupplier = icon;

        return this;
    }

    public UIIcon iconColor(int color)
    {
        this.iconColor = color;

        return this;
    }

    public UIIcon hoverColor(int color)
    {
        this.hoverColor = color;

        return this;
    }

    public UIIcon disabledColor(int color)
    {
        this.disabledColor = color;

        return this;
    }

    public UIIcon activeColor(int color)
    {
        this.activeColor = color;

        return this;
    }

    public UIIcon backgroundColor(int color)
    {
        this.backgroundColor = color;

        return this;
    }

    public UIIcon hoverBackgroundColor(int color)
    {
        this.hoverBackgroundColor = color;

        return this;
    }

    public UIIcon pressedBackgroundColor(int color)
    {
        this.pressedBackgroundColor = color;

        return this;
    }

    public UIIcon activeBackgroundColor(int color)
    {
        this.activeBackgroundColor = color;

        return this;
    }

    public UIIcon disabledBackgroundColor(int color)
    {
        this.disabledBackgroundColor = color;

        return this;
    }

    public UIIcon active(boolean active)
    {
        this.active = active;

        return this;
    }

    public boolean isActive()
    {
        return this.active;
    }

    protected int defaultHoverBackgroundColor()
    {
        return BBSSettings.primaryColor(0xbb000000);
    }

    protected int defaultPressedBackgroundColor()
    {
        int themed = BBSSettings.iconPressedColor();

        return themed == 0 ? Colors.mulRGB(BBSSettings.primaryColor(Colors.A100), 0.85F) : themed;
    }

    protected int defaultActiveBackgroundColor()
    {
        int themed = BBSSettings.tabActiveGradientColor();

        return themed == 0 ? BBSSettings.primaryColor(Colors.A100) : themed;
    }

    protected int defaultDisabledBackgroundColor()
    {
        int themed = BBSSettings.iconDisabledColor();

        return themed == 0 ? NO_BACKGROUND : themed;
    }

    protected int getBackgroundColor()
    {
        if (!this.isEnabled())
        {
            return this.disabledBackgroundColor == NO_BACKGROUND ? this.defaultDisabledBackgroundColor() : this.disabledBackgroundColor;
        }

        if (this.active)
        {
            return this.activeBackgroundColor == NO_BACKGROUND ? this.defaultActiveBackgroundColor() : this.activeBackgroundColor;
        }

        if (this.pressed)
        {
            return this.pressedBackgroundColor == NO_BACKGROUND ? this.defaultPressedBackgroundColor() : this.pressedBackgroundColor;
        }

        if (this.hover)
        {
            return this.hoverBackgroundColor == NO_BACKGROUND ? this.defaultHoverBackgroundColor() : this.hoverBackgroundColor;
        }

        return this.backgroundColor;
    }

    /**
     * Only colors a caller explicitly configured — no theme defaults. This
     * is the square-corner path: explicit feedback (e.g. the overlay close
     * button's red) must survive with widget radius 0, while the implicit
     * rounded hover/active fills stay exclusive to rounded themes.
     */
    protected int getExplicitBackgroundColor()
    {
        if (!this.isEnabled())
        {
            return this.disabledBackgroundColor;
        }

        if (this.active && this.activeBackgroundColor != NO_BACKGROUND)
        {
            return this.activeBackgroundColor;
        }

        if (this.pressed && this.pressedBackgroundColor != NO_BACKGROUND)
        {
            return this.pressedBackgroundColor;
        }

        if (this.hover && this.hoverBackgroundColor != NO_BACKGROUND)
        {
            return this.hoverBackgroundColor;
        }

        return this.backgroundColor;
    }

    @Override
    protected UIIcon get()
    {
        return this;
    }

    @Override
    protected void renderSkin(UIContext context)
    {
        Icon icon = this.getIcon();
        int color;

        if (this.isEnabled())
        {
            if (this.active)
            {
                color = this.activeColor;
            }
            else
            {
                this.hoverTween.to(this.hover ? 1F : 0F, UIMotions.hover());

                float hoverFactor = this.hoverTween.update();

                if (this.hoverTween.isSettled())
                {
                    color = this.hover ? this.hoverColor : this.iconColor;
                }
                else
                {
                    color = Colors.lerp(this.iconColor, this.hoverColor, hoverFactor);
                }
            }
        }
        else
        {
            color = this.disabledColor;
        }

        int radius = BBSSettings.cornerWidget();
        int background = radius > 0 ? this.getBackgroundColor() : this.getExplicitBackgroundColor();

        if (background != NO_BACKGROUND)
        {
            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, background);
            }
            else
            {
                context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), background);
            }
        }

        context.batcher.icon(icon, color, this.area.mx(), this.area.my(), 0.5F, 0.5F);
    }
}
