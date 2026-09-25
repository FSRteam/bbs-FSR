package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

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
        register(new UIResolutionRow(BBSSettings.videoWidth, BBSSettings.videoHeight, true));
        register(new UIResolutionRow(BBSSettings.editorPreviewCustomWidth, BBSSettings.editorPreviewCustomHeight, false));
        register(new UIExportPathRow(BBSSettings.videoExportPath));
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
}
