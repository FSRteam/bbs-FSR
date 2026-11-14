package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.ui.framework.elements.utils.UIUndoKeys;

/** Compatibility name; active editors use the shared undo owner. */
@Deprecated
public class UIFormEditorUndoKeys extends UIUndoKeys
{
    public UIFormEditorUndoKeys(UIFormEditor panel)
    {
        super(panel::undo, panel::redo);
    }
}
