package mchorse.bbs_mod.ui.film.utils.keyframes;

import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

import java.util.function.Consumer;

public class UIFilmKeyframes extends UIKeyframes
{
    public IUIClipsDelegate editor;
    public boolean absolute;

    public UIFilmKeyframes(IUIClipsDelegate delegate, Consumer<Keyframe> callback)
    {
        super(callback);

        this.editor = delegate;
    }

    public UIFilmKeyframes absolute()
    {
        this.absolute = true;

        return this;
    }

    public long getClipOffset()
    {
        if (this.absolute)
        {
            return 0;
        }

        if (this.editor == null || this.editor.getClip() == null)
        {
            return 0;
        }

        return this.editor.getClip().tick.get();
    }

    public float getOffset()
    {
        if (this.editor == null)
        {
            return 0;
        }

        UIContext context = this.getContext();

        return this.editor.getKeyframeCursor(context == null ? 0F : context.getTransition()) - this.getClipOffset();
    }

    @Override
    public float getTick()
    {
        return this.getOffset();
    }

    @Override
    public float getPlayheadTick(UIContext context)
    {
        return this.editor == null ? 0F : this.editor.getTimelineCursor(context.getTransition()) - this.getClipOffset();
    }

    @Override
    protected boolean hasCursor()
    {
        return this.editor != null;
    }

    @Override
    protected void selectNextKeyframe(int direction)
    {
        super.selectNextKeyframe(direction);

        Keyframe keyframe = this.getGraph().getSelected();

        this.seekToKeyframe(keyframe);
    }

    @Override
    protected void onKeyframePicked(Keyframe keyframe)
    {
        this.seekToKeyframe(keyframe);
    }

    /**
     * Convert a keyframe's clip-local tick into the shared film cursor.  Replay
     * editors opt into {@link #absolute()}, while camera clip editors retain
     * their clip start offset.
     */
    static int resolveCursorTick(float tick, long clipOffset)
    {
        return Math.max(0, (int) (tick + clipOffset));
    }

    private void seekToKeyframe(Keyframe keyframe)
    {
        if (this.editor != null && keyframe != null)
        {
            this.editor.setCursor(resolveCursorTick(keyframe.getTick(), this.getClipOffset()));
        }
    }

    @Override
    protected void moveNoKeyframes(UIContext context)
    {
        if (this.editor != null)
        {
            long offset = this.getClipOffset();

            this.editor.stopPlaybackOnScrub();
            this.editor.setCursor(Math.max(0F, this.fromGraphCursor(context.mouseX) + offset));
        }
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
