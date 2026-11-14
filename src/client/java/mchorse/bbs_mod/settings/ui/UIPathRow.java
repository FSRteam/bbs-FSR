package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A full-width path field with its name above it and actions on the whole row. */
public abstract class UIPathRow implements UISettingsLayout.IValueRow
{
    protected final ValueString path;

    public UIPathRow(ValueString path)
    {
        this.path = path;
    }

    @Override
    public List<BaseValue> getValues()
    {
        return Collections.singletonList(this.path);
    }

    @Override
    public List<UIElement> create(UIElement ui)
    {
        UIElement controls = this.createControls(ui);
        UIElement column = column(this.path, controls);

        column.context((menu) -> this.context(menu, controls));

        return Collections.singletonList(column);
    }

    /** Shared by the encoder's validation/download control and the plain export path. */
    public static UIElement column(ValueString path, UIElement controls)
    {
        UILabel label = UIValueFactory.label(path);
        UIElement column = new UIElement();

        label.h(10);
        column.column(1).vertical().stretch();
        column.add(label, controls);

        return UIValueFactory.commetTooltip(column, path);
    }

    protected abstract UIElement createControls(UIElement ui);

    protected abstract void context(ContextMenuManager menu, UIElement controls);

    static PathRequest captureRequest(UIElement owner, ValueString path, long generation)
    {
        UIContext context = owner.getContext();
        Screen screen = Minecraft.getInstance().screen;

        return isActiveOwner(owner, context, screen) ? new PathRequest(generation, path.get(), context, screen) : null;
    }

    private static boolean isActiveOwner(UIElement owner, UIContext context, Screen screen)
    {
        if (context == null || owner.getContext() != context || Minecraft.getInstance().screen != screen
            || !(screen instanceof UIScreen uiScreen) || uiScreen.getMenu() != context.menu)
        {
            return false;
        }

        /* Closing overlays remain attached during their exit animation, but already reject input. */
        for (UIElement element = owner; element != null; element = element.getParent())
        {
            if (!element.isEnabled())
            {
                return false;
            }
        }

        return context.menu.getRoot().isDescendant(owner);
    }

    record PathRequest(long generation, String value, UIContext context, Screen screen)
    {
        boolean isCurrent(UIElement owner, ValueString path, long currentGeneration)
        {
            return this.generation == currentGeneration && Objects.equals(this.value, path.get())
                && isActiveOwner(owner, this.context, this.screen);
        }
    }
}
