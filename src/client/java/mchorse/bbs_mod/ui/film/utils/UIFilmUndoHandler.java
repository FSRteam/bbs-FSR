package mchorse.bbs_mod.ui.film.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.client.film.collaboration.BBSFilmCollaborationBridge;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UIFilmUndoHandler extends UIFormUndoHandler
{
    private Timer actionsTimer = new Timer(100);
    private Set<BaseValue> syncData = new HashSet<>();

    public UIFilmUndoHandler(UIFilmPanel panel)
    {
        super(panel);
    }

    @Override
    public void handlePreValues(BaseValue baseValue, int flag)
    {
        /* time_spent_active is a passive counter updated every second; it should not
         * pollute undo history with dozens of entries per minute */
        if (baseValue.getPath().getLast().equals("time_spent_active"))
        {
            return;
        }

        super.handlePreValues(baseValue, flag);
    }

    @Override
    protected void handleValue(BaseValue value)
    {
        super.handleValue(value);

        if (this.isReplayActions(value))
        {
            this.syncData.add(value);
            this.actionsTimer.mark();
        }
    }

    @Override
    protected void handleTimers()
    {
        super.handleTimers();

        if (this.actionsTimer.checkReset())
        {
            for (BaseValue syncData : this.syncData)
            {
                ClientNetwork.sendSyncData(((UIFilmPanel) this.uiElement).getData().getId(), syncData);
            }

            this.syncData.clear();
        }
    }

    @Override
    protected void handleCommittedValues(List<BaseValue> values)
    {
        BBSFilmCollaborationBridge.captureCommittedValues((UIFilmPanel) this.uiElement, values);
    }

    @Override
    protected void handleUndoApplied(IUndo<ValueGroup> undo, boolean redo)
    {
        List<List<String>> paths = new ArrayList<>();

        collectPaths(undo, paths);
        BBSFilmCollaborationBridge.captureCommittedPaths((UIFilmPanel) this.uiElement, paths);
    }

    private static void collectPaths(IUndo<?> undo, List<List<String>> paths)
    {
        if (undo instanceof ValueChangeUndo change)
        {
            paths.add(List.copyOf(change.getName().strings));
        }
        else if (undo instanceof CompoundUndo<?> compound)
        {
            for (IUndo<?> child : compound.getUndos())
            {
                collectPaths(child, paths);
            }
        }
    }

    private boolean isReplayActions(BaseValue value)
    {
        String path = value.getPath().toString();

        if (
            path.endsWith("/replays") ||
            path.endsWith("/keyframes") ||
            path.contains("/keyframes/x") ||
            path.contains("/keyframes/y") ||
            path.contains("/keyframes/z") ||
            path.contains("/keyframes/item_main_hand") ||
            path.contains("/keyframes/item_off_hand") ||
            path.contains("/keyframes/item_head") ||
            path.contains("/keyframes/item_chest") ||
            path.contains("/keyframes/item_legs") ||
            path.contains("/keyframes/item_feet") ||
            path.endsWith("/actor") ||
            path.endsWith("/fp") ||
            path.endsWith("/enabled") ||
            path.endsWith("/form")
        ) {
            return true;
        }

        /* Specifically for overwriting full replay like what's done when recording
         * data in the world! */
        if (value.getParent() != null && value.getParent().getId().equals("replays"))
        {
            return true;
        }

        while (value != null)
        {
            if (value instanceof Clips clips && clips.getFactory() == BBSMod.getFactoryActionClips())
            {
                return true;
            }

            value = value.getParent();
        }

        return false;
    }
}
