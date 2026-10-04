package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UIFileDialogs;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.Collections;
import java.util.List;

public class UIExportPathRow implements UISettingsLayout.IValueRow
{
    private final ValueString path;

    public UIExportPathRow(ValueString path)
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
        UITextbox textbox = UIValueFactory.stringUI(this.path, null);
        UIIcon folder = new UIIcon(Icons.FOLDER, (b) -> UIFileDialogs.pickFolder(
            UIKeys.GENERAL_DIALOG_EXPORT_FOLDER, BBSRendering.getVideoFolder(), (file) ->
        {
            String value = file.getAbsolutePath();
            this.path.set(value);
            textbox.setText(value);
        }));

        textbox.tooltip(L10n.lang(UIValueFactory.getValueCommentKey(this.path)));
        folder.tooltip(UIKeys.CAMERA_TOOLTIPS_PICK_EXPORT_FOLDER);
        folder.wh(Icons.FOLDER.w, UIConstants.CONTROL_HEIGHT);

        UIElement row = new UIElement();
        row.row(4).height(UIConstants.CONTROL_HEIGHT);
        row.add(textbox, folder);

        UILabel label = UIValueFactory.label(this.path);
        label.h(10);
        UIElement column = new UIElement();
        column.column(1).vertical().stretch();
        column.add(label, row);
        column.context((menu) -> this.context(menu));

        return Collections.singletonList(UIValueFactory.commetTooltip(column, this.path));
    }

    private void context(ContextMenuManager menu)
    {
        menu.action(Icons.FOLDER, UIKeys.CAMERA_TOOLTIPS_OPEN_VIDEOS, () -> UIUtils.openFolder(BBSRendering.getVideoFolder()));
    }
}
