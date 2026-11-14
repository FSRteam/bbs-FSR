package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPicker;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPresets;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Color GUI element
 *
 * This class is responsible for providing a way to edit colors, this element
 * itself is not editing the color, the picker element is the one that does color editing
 */
public class UIColor extends UIElement
{
    public UIColorPicker picker;
    public boolean label = true;
    public Direction direction;

    private UIElement target;
    private Supplier<Integer> defaultColor;
    private Runnable onReset;

    public UIColor(Consumer<Integer> callback)
    {
        super();

        this.picker = new UIColorPicker(callback);

        this.direction(Direction.BOTTOM).h(UIConstants.CONTROL_HEIGHT);
    }

    public UIColor withTarget(UIElement target)
    {
        this.target = target;

        return this;
    }

    public UIColor withAlpha()
    {
        this.picker.editAlpha();

        return this;
    }

    public UIColor direction(Direction direction)
    {
        this.direction = direction;
        this.picker.anchor(1 - direction.anchorX, 1 - direction.anchorY);

        return this;
    }

    public UIColor onTop()
    {
        return this.direction(Direction.TOP);
    }

    public UIColor noLabel()
    {
        this.label = false;

        return this;
    }

    public void setColor(int color)
    {
        this.picker.setColor(color);
    }

    @Override
    public boolean isUserEditing()
    {
        return this.picker.hasParent();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            if (!this.picker.hasParent())
            {
                int x = context.globalX(this.area.x(this.direction.anchorX) + 2 * this.direction.factorX);
                int y = context.globalY(this.area.y(this.direction.anchorY) + 2 * this.direction.factorY);

                UIElement target = this.target == null ? context.menu.overlay : this.target;

                target.add(this.picker);
                this.picker.setup(x, y);
                this.picker.bounds(context.menu.main, 2);
                this.picker.resize();
            }
            else
            {
                this.picker.removeFromParent();
            }

            return true;
        }

        if (this.area.isInside(context) && context.mouseButton == 1)
        {
            this.openPresets(context);

            return true;
        }

        return super.subMouseClicked(context);
    }

    public UIColor withDefault(Supplier<Integer> defaultColor, Runnable onReset)
    {
        this.defaultColor = defaultColor;
        this.onReset = onReset;

        return this;
    }

    private void openPresets(UIContext context)
    {
        UIColorPresets presets = new UIColorPresets(this.picker::applyOpaqueColor);
        Integer declared = this.defaultColor == null ? null : this.defaultColor.get();

        if (declared != null)
        {
            presets.withDefault(new Color().set(declared, this.picker.editAlpha), this.onReset);
        }

        this.picker.removeFromParent();
        presets.anchor(1 - this.direction.anchorX, 1 - this.direction.anchorY);

        UIElement popup = this.target == null ? context.menu.overlay : this.target;

        popup.add(presets);
        presets.setup(context.globalX(this.area.x(this.direction.anchorX) + 2 * this.direction.factorX),
            context.globalY(this.area.y(this.direction.anchorY) + 2 * this.direction.factorY));
        presets.bounds(context.menu.main, 2);
        presets.resize();
    }

    @Override
    public void render(UIContext context)
    {
        int radius = BBSSettings.cornerWidget();
        int padding = radius > 0 ? 1 : 0;

        if (radius > 0)
        {
            context.batcher.roundedFrame(
                this.area.x,
                this.area.y,
                this.area.w,
                this.area.h,
                radius,
                1F,
                BBSSettings.fieldBorderColor(),
                this.picker.color.getARGBColor()
            );
        }
        else
        {
            this.picker.renderRect(context.batcher, this.area.x, this.area.y, this.area.ex(), this.area.ey());
        }

        if (this.area.isInside(context))
        {
            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x + padding, this.area.y + padding, this.area.w - padding * 2, this.area.h - padding * 2, Math.max(0, radius - padding), Colors.A12);
            }
            else
            {
                this.area.render(context.batcher, Colors.A12, padding);
            }
        }

        if (this.label)
        {
            FontRenderer font = context.batcher.getFont();
            String label = this.picker.color.stringify(this.picker.editAlpha);

            context.batcher.textCard(label, this.area.mx(font.getWidth(label)), this.area.my(font.getHeight() - 1), BBSSettings.textColor(), Colors.A25, 1);
        }

        this.renderLockedArea(context);

        super.render(context);
    }
}
