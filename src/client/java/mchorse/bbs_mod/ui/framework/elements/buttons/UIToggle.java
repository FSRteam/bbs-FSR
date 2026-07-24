package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.ITextColoring;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

public class UIToggle extends UIClickable<UIToggle> implements ITextColoring
{
    public IKey label;
    public int color = Colors.WHITE;
    public boolean textShadow = true;
    private boolean value;

    private final UITween hoverTween = new UITween();

    public UIToggle(IKey label, Consumer<UIToggle> callback)
    {
        this(label, false, callback);
    }

    public UIToggle(IKey label, boolean value, Consumer<UIToggle> callback)
    {
        super(callback);

        this.label = label;
        this.value = value;
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

    private static final int TRACK_W = 22;
    private static final int TRACK_H = 10;
    private static final int KNOB = 12;

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

        if (radius > 0)
        {
            float trackX = this.area.ex() - 30F;
            float trackY = this.area.my() - 6F;
            float trackW = 28F;
            float trackH = 12F;
            float trackR = Math.min(radius, trackH * 0.5F);
            int trackFill = this.value ? Colors.A100 | BBSSettings.accentColorRGB() : BBSSettings.inputSurface();
            int thumbBase = 0xffc9cdd2;
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
            float thumbCx = trackX + pad + 5F + (trackW - 2F * pad - 10F) * (this.value ? 1F : 0F);
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
        int trackTop = my + KNOB / 2 - TRACK_H;
        int trackBottom = my + KNOB / 2;

        int trackFill = this.value ? Colors.A100 | BBSSettings.accentColorRGB() : 0xff3a3d41;

        context.batcher.bevelBox(trackLeft, trackTop, trackRight, trackBottom, trackFill, false, true);

        int knobLeft = trackLeft + (this.value ? TRACK_W - KNOB : 0);
        int knobTop = my - KNOB / 2;
        int knobBase = 0xffc9cdd2;
        int knobColor;

        if (this.hoverTween.isSettled())
        {
            knobColor = this.hover ? Colors.lerp(knobBase, Colors.WHITE, 0.2F) : knobBase;
        }
        else
        {
            knobColor = Colors.lerp(knobBase, Colors.lerp(knobBase, Colors.WHITE, 0.2F), hoverFactor);
        }

        context.batcher.bevelBox(knobLeft, knobTop, knobLeft + KNOB, knobTop + KNOB, knobColor, true, true);

        if (!this.isEnabled())
        {
            context.batcher.box(knobLeft, knobTop, knobLeft + KNOB, knobTop + KNOB, Colors.A50);
            context.batcher.outlinedIcon(Icons.LOCKED, trackLeft + TRACK_W / 2, my, 0.5F, 0.5F);
        }
    }
}
