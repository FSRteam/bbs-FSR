package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIconToggles;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Registry for settings rows that render more than one value together. */
public final class UISettingsLayout
{
    private static Map<BaseValue, IValueRow> rows;

    private UISettingsLayout() {}

    public static IValueRow getRow(BaseValue value)
    {
        build();
        return rows.get(value);
    }

    private static void build()
    {
        if (rows != null)
        {
            return;
        }

        rows = new HashMap<>();
        register(new ToggleStripRow(L10n.lang("bbs.config.transformation.gizmo_elements"))
            .add(BBSSettings.gizmoShowTranslate, Icons.ALL_DIRECTIONS)
            .add(BBSSettings.gizmoShowScale, Icons.SCALE)
            .add(BBSSettings.gizmoShowRotate, Icons.ORBIT)
            .add(BBSSettings.gizmoShowViewRotate, Icons.OUTLINE_SPHERE)
            .add(BBSSettings.gizmoShowSphere, Icons.SPHERE));
        register(new UIResolutionRow(BBSSettings.videoWidth, BBSSettings.videoHeight, true));
        register(new UIResolutionRow(BBSSettings.editorPreviewCustomWidth, BBSSettings.editorPreviewCustomHeight, false));
        register(new UIExportPathRow(BBSSettings.videoExportPath));
        register(new UIEncoderPathRow(BBSSettings.videoEncoderPath));
    }

    private static void register(IValueRow row)
    {
        rows.put(row.getValues().get(0), row);
    }

    public interface IValueRow
    {
        List<BaseValue> getValues();
        List<UIElement> create(UIElement ui);
    }

    public static class ToggleStripRow implements IValueRow
    {
        private final IKey label;
        private final List<ValueBoolean> values = new ArrayList<>();
        private final List<Icon> icons = new ArrayList<>();

        public ToggleStripRow(IKey label)
        {
            this.label = label;
        }

        public ToggleStripRow add(ValueBoolean value, Icon icon)
        {
            this.values.add(value);
            this.icons.add(icon);

            return this;
        }

        @Override
        public List<BaseValue> getValues()
        {
            return new ArrayList<>(this.values);
        }

        @Override
        public List<UIElement> create(UIElement ui)
        {
            UIIconToggles toggles = new UIIconToggles(null);

            for (int i = 0; i < this.values.size(); i++)
            {
                ValueBoolean value = this.values.get(i);

                toggles.add(this.icons.get(i),
                    L10n.lang(UIValueFactory.getValueLabelKey(value)),
                    L10n.lang(UIValueFactory.getValueCommentKey(value)), () -> value);
            }

            toggles.w(toggles.getPreferredWidth());
            UIElement row = new UIElement();

            row.row(0).preferred(0).height(20);
            row.add(UI.label(this.label, 0).labelAnchor(0, 0.5F), toggles);

            return Collections.singletonList(row);
        }
    }
}
