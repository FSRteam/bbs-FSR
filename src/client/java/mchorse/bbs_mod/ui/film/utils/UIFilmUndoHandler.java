package mchorse.bbs_mod.ui.film.utils;

import mchorse.bbs_mod.client.film.collaboration.BBSFilmCollaborationBridge;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class UIFilmUndoHandler extends UIFormUndoHandler
{
    private Timer actionsTimer = new Timer(100);
    private Set<BaseValue> syncData = Collections.newSetFromMap(new IdentityHashMap<>());

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

        if (baseValue.getPath().getLast().equals("expanded") && baseValue.getPath().strings.contains("replay_categories"))
        {
            return;
        }

        super.handlePreValues(baseValue, flag);
    }

    @Override
    protected void handleValue(BaseValue value)
    {
        super.handleValue(value);

        if (value.isSynced())
        {
            this.syncData.add(getSyncValue(value));
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

    /** New property channels need their factory metadata before the server can resolve child ids. */
    private static BaseValue getSyncValue(BaseValue value)
    {
        BaseValue channel = null;

        for (BaseValue parent = value; parent != null; parent = parent.getParent())
        {
            if (parent instanceof FormProperties)
            {
                return parent;
            }

            if (parent instanceof KeyframeChannel<?>)
            {
                channel = parent;
            }
        }

        return channel == null ? value : channel;
    }
}
