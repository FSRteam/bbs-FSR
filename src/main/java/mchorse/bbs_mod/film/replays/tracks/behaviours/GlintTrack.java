package mchorse.bbs_mod.film.replays.tracks.behaviours;

import mchorse.bbs_mod.cubic.glint.GlintControl;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.film.replays.tracks.TrackBehaviour;
import mchorse.bbs_mod.film.replays.tracks.TrackBlend;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The FSR enchantment layer changes only glint fields after all pose tracks. */
public class GlintTrack implements TrackBehaviour
{
    @Override
    public IKeyframeFactory factory(TrackId track) { return KeyframeFactories.GLINT; }

    @Override
    public void apply(TrackContext context, TrackId track, KeyframeChannel channel, float tick, float blend)
    {
        if (!(FormUtils.getForm(context.root(), track.formPath()) instanceof PoseForm form)) return;
        KeyframeSegment segment = channel.find(tick);
        if (segment == null) return;
        ValuePose pose = form.getPose();
        if (pose.getRuntimeValue() == null) pose.setRuntimeValue(pose.getOriginalValue().copy());
        Set<String> bones = new HashSet<>();
        for (Object object : channel.getKeyframes())
        {
            if (((Keyframe) object).getValue() instanceof GlintControls controls) bones.addAll(controls.controls.keySet());
        }
        GlintControls current = new GlintControls();
        for (String bone : bones) current.get(bone).copy(pose.get().get(bone));
        GlintControls controls = (GlintControls) TrackBlend.value(channel, current, segment, blend);
        for (Map.Entry<String, GlintControl> entry : controls.controls.entrySet())
            entry.getValue().apply(pose.get().getOrCreate(entry.getKey()));
    }

    @Override
    public void reset(Form root, TrackId track)
    {
        if (FormUtils.getForm(root, track.formPath()) instanceof PoseForm form) form.getPose().setRuntimeValue(null);
    }
}
