package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;

/** The encoder path keeps FSR's validation, download and dropped-file lifecycle. */
public class UIEncoderPathRow extends UIPathRow
{
    public UIEncoderPathRow(ValueString path)
    {
        super(path);
    }

    @Override
    protected UIElement createControls(UIElement ui)
    {
        return new UIFFMpegPath(this.path, false);
    }

    @Override
    protected void context(ContextMenuManager menu, UIElement controls)
    {
        ((UIFFMpegPath) controls).fillPathContextMenu(menu);
    }
}
