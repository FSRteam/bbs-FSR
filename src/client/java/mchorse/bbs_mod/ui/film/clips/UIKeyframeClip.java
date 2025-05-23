package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.utils.clips.Clips;

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
        this.keyframes.view.backgroundRenderer((context) ->
        {
            UIReplaysEditor.renderBackground(context, this.keyframes.view, (Clips) this.clip.getParent(), this.clip.tick.get());
        });
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("keyframe_keyframes");

        this.edit = new UIButton(UIKeys.GENERAL_EDIT, (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
        });
        this.edit.keys().register(Keys.FORMS_EDIT, () -> this.edit.clickItself());
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UIClip.label(UIKeys.CAMERA_PANELS_KEYFRAMES).marginTop(12));
        this.panels.add(this.edit);
    }

    @Override
    public void updateDuration(int duration)
    {
        super.updateDuration(duration);

        this.keyframes.updateConverter();
    }

    @Override
    public void editClip(Position position)
    {
        long tick = this.editor.getCursor() - this.clip.tick.get();

        this.clip.x.insert(tick, position.point.x);
        this.clip.y.insert(tick, position.point.y);
        this.clip.z.insert(tick, position.point.z);
        this.clip.yaw.insert(tick, (double) position.angle.yaw);
        this.clip.pitch.insert(tick, (double) position.angle.pitch);
        this.clip.roll.insert(tick, (double) position.angle.roll);
        this.clip.fov.insert(tick, (double) position.angle.fov);
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