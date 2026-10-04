package mchorse.bbs_mod.ui.framework.elements.input.color;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

/**
 * The color model switch of the picker's popup: two text tabs, HSV and RGB, drawn as one
 * segmented control.
 *
 * <p>FSR has no tab strip, so this is the popup's own two-state control. The chosen model is
 * not kept here — the picker stores it in the settings like the settings screen does — this
 * element only reports the press and shows which one is on.</p>
 */
public class UIColorPickerTabs extends UIElement
{
    public static final IKey HSV = IKey.constant("HSV");
    public static final IKey RGB = IKey.constant("RGB");

    public Consumer<Boolean> callback;

    private final Area hsvArea = new Area();
    private final Area rgbArea = new Area();

    private boolean hsv;

    public UIColorPickerTabs(Consumer<Boolean> callback)
    {
        super();

        this.callback = callback;
    }

    /** Which model is on, for the highlight and the press test. */
    public void setHsv(boolean hsv)
    {
        this.hsv = hsv;
    }

    @Override
    public void resize()
    {
        super.resize();

        int half = this.area.w / 2;

        this.hsvArea.set(this.area.x, this.area.y, half, this.area.h);
        this.rgbArea.set(this.area.x + half, this.area.y, this.area.w - half, this.area.h);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton != 0)
        {
            return super.subMouseClicked(context);
        }

        boolean overHsv = this.hsvArea.isInside(context);
        boolean overRgb = this.rgbArea.isInside(context);

        if ((overHsv && !this.hsv) || (overRgb && this.hsv))
        {
            this.hsv = overHsv;

            if (this.callback != null)
            {
                this.callback.accept(overHsv);
            }
        }

        return overHsv || overRgb || super.subMouseClicked(context);
    }

    @Override
    public void render(UIContext context)
    {
        this.renderWell(context);
        this.renderTab(context, this.hsvArea, HSV, this.hsv);
        this.renderTab(context, this.rgbArea, RGB, !this.hsv);

        super.render(context);
    }

    /** The groove both tabs sit in, one step below the popup like every other field. */
    private void renderWell(UIContext context)
    {
        int radius = BBSSettings.cornerWidget();

        if (radius > 0)
        {
            context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, BBSSettings.inputSurface());
        }
        else
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.inputSurface());
        }
    }

    private void renderTab(UIContext context, Area area, IKey label, boolean active)
    {
        if (active)
        {
            int radius = BBSSettings.cornerWidget();
            int color = BBSSettings.accentColorRGB() | Colors.A100;

            if (radius > 0)
            {
                context.batcher.roundedBox(area.x, area.y, area.w, area.h, radius, color);
            }
            else
            {
                context.batcher.box(area.x, area.y, area.ex(), area.ey(), color);
            }
        }

        FontRenderer font = context.batcher.getFont();
        String text = label.get();
        int color = active || area.isInside(context) ? BBSSettings.textColor() : BBSSettings.mutedTextColor();

        context.batcher.text(text, area.mx(font.getWidth(text)), area.my(font.getHeight()), color);
    }
}
