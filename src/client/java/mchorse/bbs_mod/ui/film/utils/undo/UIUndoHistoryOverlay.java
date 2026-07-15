package mchorse.bbs_mod.ui.film.utils.undo;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.function.Supplier;

public class UIUndoHistoryOverlay extends UIOverlayPanel
{
    private UIUndoList<ValueGroup> list;

    public UIUndoHistoryOverlay(UIFilmPanel panel)
    {
        this(UIKeys.FILM_HISTORY_TITLE, panel.getUndoHandler().getUndoManager(), panel::getData, null);
    }

    public UIUndoHistoryOverlay(IKey title, UndoManager<ValueGroup> undoManager, Supplier<ValueGroup> context, Runnable onApplied)
    {
        super(title);

        this.list = new UIUndoList((l) ->
        {
            int index = this.list.getIndex();
            while (undoManager.getCurrentUndoIndex() != index)
            {
                if (undoManager.getCurrentUndoIndex() > index)
                {
                    undoManager.undo(context.get());
                }
                else
                {
                    undoManager.redo(context.get());
                }
            }

            if (onApplied != null)
            {
                onApplied.run();
            }

            UIUtils.playClick();
        });
        this.list.setList(undoManager.getUndos());
        this.list.full(this.content);
        this.list.setIndex(undoManager.getCurrentUndoIndex());

        this.content.add(this.list);
    }
}
