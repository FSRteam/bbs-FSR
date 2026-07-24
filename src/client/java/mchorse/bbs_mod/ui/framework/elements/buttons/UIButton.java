package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.ITextColoring;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

public class UIButton extends UIClickable<UIButton> implements ITextColoring
{
    public IKey label;

    public int textColor = Colors.WHITE;
    public boolean textShadow = true;

    public boolean custom;
    public int customColor;
    public int customHighlightColor;
    public boolean background = true;

    private final UITween hoverTween = new UITween();

    public UIButton(IKey label, Consumer<UIButton> callback)
    {
        super(callback);

        this.label = label;
        this.h(UIConstants.CONTROL_HEIGHT);
    }

    public UIButton color(int color)
    {
        this.custom = true;
        this.customColor = color & Colors.RGB;
        this.customHighlightColor = this.customColor;

        return this;
    }

    public UIButton color(int color, int highlightColor)
    {
        this.custom = true;
        this.customColor = color;
        this.customHighlightColor = highlightColor;

        return this;
    }

    public UIButton textColor(int color, boolean shadow)
    {
        this.textColor = color;
        this.textShadow = shadow;

        return this;
    }

    public UIButton background(boolean background)
    {
        this.background = background;

        return this;
    }

    @Override
    public void setColor(int color, boolean shadow)
    {
        this.textColor = color;
        this.textShadow = shadow;
    }

    @Override
    protected UIButton get()
    {
        return this;
    }

    @Override
    protected void renderSkin(UIContext context)
    {
        int color = this.custom ? this.customColor : BBSSettings.accentColorRGB() | Colors.A100;
        int highlight = this.custom ? this.customHighlightColor : Colors.mulRGB(color, 0.85F);

        this.hoverTween.to(this.hover ? 1F : 0F, UIMotions.hover());

        float hoverFactor = this.hoverTween.update();

        if (!this.hoverTween.isSettled())
        {
            color = Colors.lerp(color, highlight, hoverFactor);
        }
        else if (this.hover)
        {
            color = highlight;
        }

        if (this.background)
        {
            context.batcher.bevelBox(this.area.x, this.area.y, this.area.ex(), this.area.ey(), color | Colors.A100, true, false);
        }

        FontRenderer font = context.batcher.getFont();
        String label = font.limitToWidth(this.label.get(), this.area.w - 4);
        int x = this.area.mx(font.getWidth(label));
        int y = this.area.my(font.getHeight());

        /* Colors.WHITE doubles as "follow the theme" for the default text color */
        int labelColor = this.textColor == Colors.WHITE ? BBSSettings.textColor() : this.textColor;

        context.batcher.text(label, x, y, Colors.mulRGB(labelColor, 1F - 0.1F * hoverFactor), this.textShadow);

        this.renderLockedArea(context);
    }
}
