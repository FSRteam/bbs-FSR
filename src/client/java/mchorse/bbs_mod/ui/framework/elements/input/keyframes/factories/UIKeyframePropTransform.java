package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIDeltaPropTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.List;
import java.util.function.Consumer;

/**
 * Delta transform editor whose selection spans keyframes, plus the film
 * recording layer: while transform recording is live the edit lands on the
 * recorded keyframes ({@link #applyDuringRecording}) instead of the selection,
 * and the fields mirror the recorded transform.
 */
public abstract class UIKeyframePropTransform extends UIDeltaPropTransform
{
    protected abstract void applyDuringRecording(int tick, Consumer<Transform> consumer);

    protected Transform getRecordedTransform(int tick)
    {
        return null;
    }

    /**
     * Resolves the film panel from the menu root rather than walking up the parent chain, so this
     * transform works even when it lives outside the film panel (e.g. the animation state editor,
     * where there is no film panel and recording simply doesn't apply). Mirrors the lookup in
     * {@link UIAnchorKeyframeFactory}.
     */
    protected UIFilmPanel getPanel()
    {
        UIContext context = this.getContext();

        if (context == null)
        {
            return null;
        }

        List<UIFilmPanel> panels = context.menu.main.getChildren(UIFilmPanel.class);

        return panels.isEmpty() ? null : panels.get(0);
    }

    protected boolean isTransformRecording()
    {
        UIFilmPanel panel = this.getPanel();

        return panel != null && panel.getController().isTransformRecording();
    }

    protected int getRecordingTick()
    {
        UIFilmPanel panel = this.getPanel();

        return panel == null ? 0 : panel.getCursor();
    }

    @Override
    protected Transform getTargetTransform()
    {
        if (this.isTransformRecording())
        {
            return this.getRecordedTransform(this.getRecordingTick());
        }

        return this.getTransform();
    }

    @Override
    protected void applyToTarget(Consumer<Transform> consumer)
    {
        if (this.isTransformRecording())
        {
            this.applyDuringRecording(this.getRecordingTick(), consumer);
        }
        else
        {
            this.applyToSelection(consumer);
        }
    }
}
