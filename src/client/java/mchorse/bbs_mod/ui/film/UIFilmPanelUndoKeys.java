package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * Invisible overlay that captures Ctrl+Z and Ctrl+Y for undo/redo
 * so they work on top of all other keybinds in the film panel (e.g. Y for body fix toggle).
 *
 * <p>Both are strict: sitting above everything else means nothing downstream gets a look in, so an
 * extra modifier has to make this step aside, or longer combos ending in Z or Y (Ctrl+Alt+Z for the
 * layout history) would be swallowed here and silently run the wrong undo.
 */
public class UIFilmPanelUndoKeys extends UIElement
{
    public UIFilmPanelUndoKeys(UIFilmPanel panel)
    {
        this.keys().ignoreFocus();
        this.keys().register(Keys.UNDO, panel::undo).strict().category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
        this.keys().register(Keys.REDO, panel::redo).strict().category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
        this.noCulling();
    }
}
