package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.UINumericInput;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UIResolutionRow implements UISettingsLayout.IValueRow
{
    private final ValueInt width;
    private final ValueInt height;
    private final boolean presets;

    public UIResolutionRow(ValueInt width, ValueInt height, boolean presets)
    {
        this.width = width;
        this.height = height;
        this.presets = presets;
    }

    @Override
    public List<BaseValue> getValues()
    {
        return Arrays.asList(this.width, this.height);
    }

    @Override
    public List<UIElement> create(UIElement ui)
    {
        UINumericInput<?> width = UIValueFactory.intUI(this.width, null);
        UINumericInput<?> height = UIValueFactory.intUI(this.height, null);
        UIIcon swap = new UIIcon(Icons.REFRESH, (b) -> this.swap(ui));

        width.tooltip(this.tooltip(this.width));
        height.tooltip(this.tooltip(this.height));
        swap.tooltip(UIKeys.VIDEO_SETTINGS_SWAP);
        swap.wh(Icons.REFRESH.w, UIConstants.CONTROL_HEIGHT);

        if (this.presets && ui instanceof UISettingsOverlayPanel panel)
        {
            width.context(panel::addVideoPresets);
            height.context(panel::addVideoPresets);
        }

        UIElement row = new UIElement();
        row.row(4).height(UIConstants.CONTROL_HEIGHT);
        row.add(new UIIconMark(Icons.HORIZONTAL), width, swap, height, new UIIconMark(Icons.VERTICAL));

        return Collections.singletonList(row);
    }

    private IKey tooltip(ValueInt value)
    {
        return IKey.comp(Arrays.asList(
            L10n.lang(UIValueFactory.getValueLabelKey(value)),
            IKey.constant("\n"),
            L10n.lang(UIValueFactory.getValueCommentKey(value))
        ));
    }

    private void swap(UIElement ui)
    {
        int value = this.width.get();
        this.width.set(this.height.get());
        this.height.set(value);

        if (ui instanceof UISettingsOverlayPanel panel)
        {
            panel.refresh();
        }
    }

    public static class UIIconMark extends UIElement
    {
        private final Icon icon;

        public UIIconMark(Icon icon)
        {
            this.icon = icon;
            this.wh(icon.w, UIConstants.CONTROL_HEIGHT);
        }

        @Override
        public void render(UIContext context)
        {
            context.batcher.icon(this.icon, Colors.WHITE, this.area.mx(), this.area.my(), 0.5F, 0.5F);
            super.render(context);
        }
    }
}
