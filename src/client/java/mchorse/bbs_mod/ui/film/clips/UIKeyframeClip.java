package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import org.joml.Vector3f;

public class UIKeyframeClip extends UIClip<KeyframeClip>
{
    public UIButton edit;
    public UIKeyframeEditor keyframes;
    public UIToggle additive;

    public UIKeyframeClip(KeyframeClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void addEnvelopes()
    {
        super.addEnvelopes();

        this.additive = new UIToggle(UIKeys.CAMERA_PANELS_ADDITIVE, (b) ->
        {
            this.clip.additive.set(b.getValue());
        });

        this.panels.add(this.additive);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.rulerRenderer((context) ->
        {
            UIReplaysEditor.renderRuler(context, this.keyframes.view, (UIClipsPanel) this.editor, (Clips) this.clip.getParent(), this.clip.tick.get());
        });
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("keyframe_keyframes");

        this.edit = new UIButton(UIKeys.GENERAL_EDIT, (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.getGraph().clearSelection();
        });
        this.edit.keys().register(Keys.FORMS_EDIT, () -> this.edit.clickItself());
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(UIKeys.CAMERA_PANELS_KEYFRAMES, this.edit));
    }

    @Override
    public void editClip(Position position)
    {
        Position newPos = position.copy();
        UIContext context = this.getContext();
        float tick = this.editor.getKeyframeCursor(context == null ? 0F : context.getTransition()) - this.clip.tick.get();

        if (!this.clip.distance.isEmpty())
        {
            double distance = this.clip.distance.interpolate(tick);

            if (distance != 0D)
            {
                Vector3f rotation = Matrices.rotation(
                    MathUtils.toRad(newPos.angle.pitch),
                    MathUtils.toRad(-newPos.angle.yaw - 180)
                );

                newPos.point.x -= rotation.x * distance;
                newPos.point.y -= rotation.y * distance;
                newPos.point.z -= rotation.z * distance;
            }
        }

        this.insertKeyframe(tick, this.clip.x, newPos.point.x);
        this.insertKeyframe(tick, this.clip.y, newPos.point.y);
        this.insertKeyframe(tick, this.clip.z, newPos.point.z);
        this.insertKeyframe(tick, this.clip.yaw, newPos.angle.yaw);
        this.insertKeyframe(tick, this.clip.pitch, newPos.angle.pitch);
        this.insertKeyframe(tick, this.clip.roll, newPos.angle.roll);
        this.insertKeyframe(tick, this.clip.fov, newPos.angle.fov);
    }

    private void insertKeyframe(float tick, KeyframeChannel<Double> channel, double x)
    {
        /* Was findSegment + an unconditional copyOverExtra. When a keyframe already sat on the tick,
         * findSegment's `a` WAS the keyframe being written, so the copy re-laid the extras it had
         * just been given: state-neutral, but it raised a second pre/post pair for a child of a
         * channel insert() had already notified. UIFormUndoHandler.reduceUndoRedundancy drops that
         * child (its ancestor is cached), so the user sees no difference — as long as insert keeps
         * notifying the channel first and that ancestor rule stays. Keep them, or this is a no-op. */
        channel.insertInheriting(tick, x);
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.updateDuration(this.clip.duration.get());
        this.keyframes.setClip(this.clip);
        this.additive.setValue(this.clip.additive.get());
    }

    @Override
    public void applyUndoData(MapType data)
    {
        super.applyUndoData(data);

        if (data.getString("embed").equals("keyframe"))
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
        }
    }

    @Override
    public void collectUndoData(MapType data)
    {
        super.collectUndoData(data);

        if (this.keyframes.hasParent())
        {
            data.putString("embed", "keyframe");
        }
    }
}
