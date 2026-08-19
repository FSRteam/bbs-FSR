package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.ITextColoring;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

public class UIToggle extends UIClickable<UIToggle> implements ITextColoring
{
    public IKey label;
    public int color = Colors.WHITE;
    public boolean textShadow = true;
    private boolean value;

    private final UITween hoverTween = new UITween();
    private final UITween valueTween = new UITween();

    public UIToggle(IKey label, Consumer<UIToggle> callback)
    {
        this(label, false, callback);
    }

    public UIToggle(IKey label, boolean value, Consumer<UIToggle> callback)
    {
        super(callback);

        this.label = label;
        this.value = value;
        this.valueTween.snap(value ? 1F : 0F);
        this.h(14);
    }

    @Override
    public void setColor(int color, boolean shadow)
    {
        this.color(color, shadow);
    }

    public UIToggle label(IKey label)
    {
        this.label = label;

        return this;
    }

    public UIToggle setValue(boolean value)
    {
        this.value = value;

        return this;
    }

    public UIToggle color(int color)
    {
        return this.color(color, true);
    }

    public UIToggle color(int color, boolean textShadow)
    {
        this.color = color;
        this.textShadow = textShadow;

        return this;
    }

    public boolean getValue()
    {
        return this.value;
    }

    @Override
    protected void click(int mouseWheel)
    {
        this.value = !this.value;

        super.click(mouseWheel);
    }

    @Override
    protected UIToggle get()
    {
        return this;
    }

    private static final int TRACK_W = 20;
    private static final int TRACK_H = 8;
    private static final int KNOB = 10;
    private static final int KNOB_COLOR = 0xffc4c4c4;

    @Override
    protected void renderSkin(UIContext context)
    {
        FontRenderer font = context.batcher.getFont();
        int radius = BBSSettings.cornerWidget();
        int labelTrackWidth = radius > 0 ? 28 : TRACK_W;
        String label = font.limitToWidth(this.label.get(), this.area.w - labelTrackWidth - 6);

        /* Colors.WHITE doubles as "follow the theme" for the default text color */
        int labelColor = this.color == Colors.WHITE ? BBSSettings.textColor() : this.color;

        context.batcher.text(label, this.area.x, this.area.my(font.getHeight()), labelColor, this.textShadow);

        this.hoverTween.to(this.hover ? 1F : 0F, UIMotions.hover());

        float hoverFactor = this.hoverTween.update();
        UIThemeMotion toggleMotion = UIMotions.toggle();

        this.valueTween.to(this.value ? 1F : 0F, toggleMotion);

        float valueFactor = this.valueTween.update();
        float visualValue = this.valueTween.isSettled() ? (this.value ? 1F : 0F) : MathUtils.clamp(valueFactor, 0F, 1F);
        boolean themedToggle = toggleMotion != null && toggleMotion.enabled;

        if (radius > 0)
        {
            float trackX = this.area.ex() - 30F;
            float trackY = this.area.my() - 6F;
            float trackW = 28F;
            float trackH = 12F;
            float trackR = Math.min(radius, trackH * 0.5F);
            int trackOff = BBSSettings.inputSurface();
            int trackOn = Colors.A100 | BBSSettings.accentColorRGB();
            int trackFill = this.valueTween.isSettled() ? (this.value ? trackOn : trackOff) : Colors.lerp(trackOff, trackOn, visualValue);
            int thumbOff = 0xffc9cdd2;
            int thumbBase = themedToggle ? Colors.lerp(thumbOff, Colors.WHITE, visualValue) : thumbOff;
            int thumbColor;

            if (this.hoverTween.isSettled())
            {
                thumbColor = this.hover ? Colors.lerp(thumbBase, Colors.WHITE, 0.2F) : thumbBase;
            }
            else
            {
                thumbColor = Colors.lerp(thumbBase, Colors.lerp(thumbBase, Colors.WHITE, 0.2F), hoverFactor);
            }

            context.batcher.roundedBox(trackX, trackY, trackW, trackH, trackR, trackFill);

            float pad = 2F;
            float thumbCx = trackX + pad + 5F + (trackW - 2F * pad - 10F) * visualValue;
            float thumbCy = this.area.my();

            context.batcher.filledCircle(thumbCx, thumbCy, 5F, thumbColor, 28);

            if (!this.isEnabled())
            {
                context.batcher.roundedBox(trackX, trackY, trackW, trackH, trackR, Colors.A50);
                context.batcher.outlinedIcon(Icons.LOCKED, trackX + trackW * 0.5F, thumbCy, 0.5F, 0.5F);
            }

            return;
        }

        int my = this.area.my();
        int trackRight = this.area.ex() - 2;
        int trackLeft = trackRight - TRACK_W;
        int knobTop = my - KNOB / 2;
        int trackBottom = knobTop + KNOB;
        int trackTop = trackBottom - TRACK_H;

        int trackOff = BBSSettings.deepSurface();
        int trackOn = Colors.A100 | BBSSettings.accentColorRGB();
        int trackFill = this.valueTween.isSettled() ? (this.value ? trackOn : trackOff) : Colors.lerp(trackOff, trackOn, visualValue);

        context.batcher.box(trackLeft, trackTop, trackRight, trackBottom, trackFill);

        int knobLeft = trackLeft + Math.round((TRACK_W - KNOB) * visualValue);
        int knobOff = KNOB_COLOR;
        int knobBase = themedToggle ? Colors.lerp(knobOff, Colors.WHITE, visualValue) : knobOff;
        int knobColor;

        if (this.hoverTween.isSettled())
        {
            knobColor = this.hover ? Colors.lerp(knobBase, Colors.WHITE, 0.2F) : knobBase;
        }
        else
        {
            knobColor = Colors.lerp(knobBase, Colors.lerp(knobBase, Colors.WHITE, 0.2F), hoverFactor);
        }

        context.batcher.surfaceBox(knobLeft, knobTop, knobLeft + KNOB, knobTop + KNOB, knobColor, true, false);

        if (!this.isEnabled())
        {
            context.batcher.box(knobLeft, knobTop, knobLeft + KNOB, knobTop + KNOB, Colors.A50);
            context.batcher.outlinedIcon(Icons.LOCKED, trackLeft + TRACK_W / 2, my, 0.5F, 0.5F);
        }
    }
}
