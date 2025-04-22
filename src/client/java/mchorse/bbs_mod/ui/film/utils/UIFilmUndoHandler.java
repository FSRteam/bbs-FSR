package mchorse.bbs_mod.ui.film.utils;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.ValueGroup;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeState;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.undo.CompoundUndo;
import mchorse.bbs_mod.utils.undo.IUndo;
import mchorse.bbs_mod.utils.undo.UndoManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UIFilmUndoHandler
{
    private UndoManager<ValueGroup> undoManager;

    private Map<BaseValue, BaseType> cachedValues = new HashMap<>();
    private List<Integer> cachedCameraSelection = new ArrayList<>();
    private List<Integer> cachedActionSelection = new ArrayList<>();
    private List<Integer> cachedVoicelineSelection = new ArrayList<>();
    private KeyframeState cachedKeyframeState;
    private boolean cacheMarkLastUndoNoMerging;
    private int lastReplay = -2;

    private Timer undoTimer = new Timer(1000);
    private Timer actionsTimer = new Timer(100);
    private Set<BaseValue> syncData = new HashSet<>();

    private UIFilmPanel panel;

    public UIFilmUndoHandler(UIFilmPanel panel)
    {
        this.panel = panel;

        this.undoManager = new UndoManager<>(100);
        this.undoManager.setCallback(this::handleUndos);
    }

    public UndoManager<ValueGroup> getUndoManager()
    {
        return this.undoManager;
    }

    /**
     * Handle undo/redo. This method primarily updates the UI state, according to
     * the undo/redo changes were done.
     */
    private void handleUndos(IUndo<ValueGroup> undo, boolean redo)
    {
        IUndo<ValueGroup> anotherUndo = undo;

        if (anotherUndo instanceof CompoundUndo)
        {
            anotherUndo = ((CompoundUndo<ValueGroup>) anotherUndo).getFirst(ValueChangeUndo.class);
        }

        if (anotherUndo instanceof ValueChangeUndo)
        {
            ValueChangeUndo change = (ValueChangeUndo) anotherUndo;

            this.panel.showPanel(change.panel);
            this.panel.replayEditor.setReplay(CollectionUtils.getSafe(this.panel.getData().replays.getList(), change.replay), true, false);

            List<Integer> cameraSelection = change.cameraClips.getSelection(redo);
            List<Integer> voiceLineSelection = change.voiceLinesClips.getSelection(redo);

            if (cameraSelection.isEmpty())
            {
                this.panel.cameraEditor.pickClip(null);
            }
            else
            {
                this.panel.cameraEditor.clips.setSelection(cameraSelection);

                Clip last = this.panel.getData().camera.get(cameraSelection.get(cameraSelection.size() - 1));

                this.panel.cameraEditor.pickClip(last);
            }

            if (voiceLineSelection.isEmpty())
            {
                this.panel.screenplayEditor.editor.pickClip(null);
            }
            else
            {
                this.panel.screenplayEditor.editor.clips.setSelection(voiceLineSelection);

                Clip last = this.panel.getData().voiceLines.get(voiceLineSelection.get(voiceLineSelection.size() - 1));

                this.panel.screenplayEditor.editor.pickClip(last);
            }

            change.cameraClips.apply(this.panel.cameraEditor.clips);
            change.actionClips.apply(this.panel.actionEditor.clips);
            change.voiceLinesClips.apply(this.panel.screenplayEditor.editor.clips);

            this.panel.setCursor(change.tick);
            this.panel.getController().createEntities();
            this.panel.replayEditor.handleUndo(change, redo);
        }

        this.panel.replayEditor.replays.replays.update();
        this.panel.cameraEditor.handleUndo(undo, redo);
        this.panel.actionEditor.handleUndo(undo, redo);
        this.panel.screenplayEditor.editor.handleUndo(undo, redo);
        this.panel.fillData();
    }

    public void handlePreValues(BaseValue baseValue, int flag)
    {
        if (this.cachedCameraSelection.isEmpty()) this.cachedCameraSelection.addAll(this.panel.cameraEditor.clips.getSelection());
        if (this.cachedActionSelection.isEmpty()) this.cachedActionSelection.addAll(this.panel.actionEditor.clips.getSelection());
        if (this.cachedVoicelineSelection.isEmpty()) this.cachedVoicelineSelection.addAll(this.panel.screenplayEditor.editor.clips.getSelection());
        if (this.cachedKeyframeState == null && this.panel.replayEditor.keyframeEditor != null) this.cachedKeyframeState = this.panel.replayEditor.keyframeEditor.view.cacheState();
        if (this.lastReplay == -2) this.lastReplay = this.panel.getData().replays.getList().indexOf(this.panel.replayEditor.getReplay());
        if (!this.cachedValues.containsKey(baseValue)) this.cachedValues.put(baseValue, baseValue.toData());

        if ((flag & IValueListener.FLAG_UNMERGEABLE) != 0)
        {
            this.cacheMarkLastUndoNoMerging = true;
        }
    }

    public void submitUndo()
    {
        this.handleTimers();

        if (this.cachedValues.isEmpty())
        {
            return;
        }

        this.reduceUndoRedundancy();

        List<ValueChangeUndo> changeUndos = new ArrayList<>();

        for (Map.Entry<BaseValue, BaseType> entry : this.cachedValues.entrySet())
        {
            BaseValue value = entry.getKey();
            ValueChangeUndo undo = new ValueChangeUndo(value.getPath(), entry.getValue(), value.toData());

            undo.replay = this.lastReplay;
            undo.editor(this.panel);
            undo.selectedBefore(this.cachedCameraSelection, this.cachedActionSelection, this.cachedVoicelineSelection, this.cachedKeyframeState);
            changeUndos.add(undo);

            if (this.isReplayActions(value))
            {
                this.syncData.add(value);
                this.actionsTimer.mark();
            }
        }

        if (changeUndos.size() == 1)
        {
            this.undoManager.pushUndo(changeUndos.get(0));
        }
        else if (!changeUndos.isEmpty())
        {
            this.undoManager.pushUndo(new CompoundUndo<>(changeUndos.toArray(new IUndo[0])));
        }

        this.cachedValues.clear();
        this.cachedKeyframeState = null;
        this.lastReplay = -2;

        this.undoTimer.mark();

        if (this.cacheMarkLastUndoNoMerging)
        {
            this.cacheMarkLastUndoNoMerging = false;

            this.markLastUndoNoMerging();
        }
    }

    private void handleTimers()
    {
        if (this.undoTimer.checkReset())
        {
            this.markLastUndoNoMerging();
        }

        if (this.actionsTimer.checkReset())
        {
            for (BaseValue syncData : this.syncData)
            {
                ClientNetwork.sendSyncData(this.panel.getData().getId(), syncData);
            }

            this.syncData.clear();
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

    /**
     * Remove any child tree entries if one of the parents is present already.
     * For example, let's say the undo submitted at the same time:
     *
     * - film.clips
     * - film.clips.0
     * - film.clips.0.duration
     *
     * There is no point in caching .0 and .0.duration since films .clips will get
     * cached anyway. Therefore, it's smart to eliminate those from the cache, and
     * submit only films.clips.
     */
    private void reduceUndoRedundancy()
    {
        Iterator<BaseValue> it = this.cachedValues.keySet().iterator();

        while (it.hasNext())
        {
            BaseValue value = it.next().getParent();
            boolean remove = false;

            while (value != null)
            {
                if (this.cachedValues.containsKey(value))
                {
                    remove = true;

                    break;
                }

                value = value.getParent();
            }

            if (remove)
            {
                it.remove();
            }
        }
    }

    public void markLastUndoNoMerging()
    {
        IUndo<ValueGroup> undo = this.undoManager.getCurrentUndo();

        if (undo != null)
        {
            undo.noMerging();
        }
    }
}
