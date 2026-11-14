package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.IUITreeEventListener;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UIFileDialogs;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public class UIExportPathRow extends UIPathRow
{
    public UIExportPathRow(ValueString path)
    {
        super(path);
    }

    @Override
    protected UIElement createControls(UIElement ui)
    {
        return new ExportControls();
    }

    @Override
    protected void context(ContextMenuManager menu, UIElement controls)
    {
        menu.action(Icons.FOLDER, UIKeys.CAMERA_TOOLTIPS_OPEN_VIDEOS, () -> UIUtils.openFolder(BBSRendering.getVideoFolder()));
    }

    private class ExportControls extends UIElement implements IUITreeEventListener
    {
        private long requestGeneration;
        private final UITextbox textbox;

        private ExportControls()
        {
            this.textbox = UIValueFactory.stringUI(UIExportPathRow.this.path, (text) -> this.requestGeneration += 1L);
            UIIcon folder = new UIIcon(Icons.FOLDER, (button) -> this.pickFolder());

            this.textbox.tooltip(L10n.lang(UIValueFactory.getValueCommentKey(UIExportPathRow.this.path)));
            folder.tooltip(UIKeys.CAMERA_TOOLTIPS_PICK_EXPORT_FOLDER);
            folder.wh(Icons.FOLDER.w, UIConstants.CONTROL_HEIGHT);
            this.row(4).height(UIConstants.CONTROL_HEIGHT);
            this.add(this.textbox, folder);
        }

        private void pickFolder()
        {
            PathRequest request = captureRequest(this, UIExportPathRow.this.path, ++this.requestGeneration);

            if (request == null)
            {
                return;
            }

            UIFileDialogs.pickFolder(UIKeys.GENERAL_DIALOG_EXPORT_FOLDER, BBSRendering.getVideoFolder(), (file) ->
            {
                if (request.isCurrent(this, UIExportPathRow.this.path, this.requestGeneration))
                {
                    String value = file.getAbsolutePath();
                    UIExportPathRow.this.path.set(value);
                    this.textbox.setText(value);
                }
            });
        }

        @Override
        protected void onRemove(UIElement parent)
        {
            this.requestGeneration += 1L;
            super.onRemove(parent);
        }

        @Override
        public void onAddedToTree(UIElement element)
        {}

        @Override
        public void onRemovedFromTree(UIElement element)
        {
            this.requestGeneration += 1L;
        }
    }
}
