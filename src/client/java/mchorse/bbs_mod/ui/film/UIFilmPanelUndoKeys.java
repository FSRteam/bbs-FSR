package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.ui.framework.elements.utils.UIUndoKeys;

/** Compatibility name; active editors use the shared undo owner. */
@Deprecated
public class UIFilmPanelUndoKeys extends UIUndoKeys
{
    public UIFilmPanelUndoKeys(UIFilmPanel panel)
    {
        super(panel::undo, panel::redo);
    }
}
