package mchorse.bbs_mod.ui.forms.editors.states.keyframes;

import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.function.Consumer;

public class UIAnimationStateKeyframes extends UIKeyframes
{
    public UIFormEditor editor;

    public UIAnimationStateKeyframes(UIFormEditor delegate, Consumer<Keyframe> callback)
    {
        super(callback);

        this.editor = delegate;
    }

    public float getOffset()
    {
        if (this.editor == null)
        {
            return 0;
        }

        UIContext context = this.getContext();

        return this.editor.getKeyframeCursor(context == null ? 0F : context.getTransition());
    }

    @Override
    public float getTick()
    {
        return this.getOffset();
    }

    @Override
    public float getPlayheadTick(UIContext context)
    {
        return this.editor == null ? 0F : this.editor.getTimelineCursor(context.getTransition());
    }

    @Override
    protected void selectNextKeyframe(int direction)
    {
        super.selectNextKeyframe(direction);

        Keyframe keyframe = this.getGraph().getSelected();

        if (keyframe != null)
        {
            this.editor.setCursor(keyframe.getTick());
        }
    }

    @Override
    protected boolean hasCursor()
    {
        return this.editor != null;
    }

    @Override
    protected void moveNoKeyframes(UIContext context)
    {
        if (this.editor != null)
        {
            this.editor.stopPlaybackOnScrub();
            this.editor.setCursor(Math.max(0F, this.fromGraphCursor(context.mouseX)));
        }
    }

    @Override
    protected void renderBackground(UIContext context)
    {
        super.renderBackground(context);
    }

    @Override
    protected void renderOverlay(UIContext context)
    {
        if (this.editor != null)
        {
            float cursor = this.getPlayheadTick(context);
            int cx = this.toGraphX(cursor);
            String label = TimeUtils.formatCursorTime(cursor) + "/" + TimeUtils.formatTime(this.getDuration());

            context.batcher.clip(this.graphArea, context);
            UIClips.renderCursor(context, label, this.area, cx - 1);
            context.batcher.unclip(context);
        }

        super.renderOverlay(context);
    }
}