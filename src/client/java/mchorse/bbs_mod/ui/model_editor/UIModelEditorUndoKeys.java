package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

public class UIModelEditorUndoKeys extends UIElement
{
    public UIModelEditorUndoKeys(UIModelEditorPanel panel)
    {
        this.keys().ignoreFocus();
        this.keys().register(Keys.UNDO, panel::undo).strict().category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
        this.keys().register(Keys.REDO, panel::redo).strict().category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
        this.noCulling();
    }
}
