package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.ui.framework.elements.utils.UIUndoKeys;

/** Compatibility name; active editors use the shared undo owner. */
@Deprecated
public class UIModelEditorUndoKeys extends UIUndoKeys
{
    public UIModelEditorUndoKeys(UIModelEditorPanel panel)
    {
        super(panel::undo, panel::redo);
    }
}
