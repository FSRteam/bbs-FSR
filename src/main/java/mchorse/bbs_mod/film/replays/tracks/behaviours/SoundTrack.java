package mchorse.bbs_mod.film.replays.tracks.behaviours;

import mchorse.bbs_mod.film.replays.tracks.TrackBehaviour;
import mchorse.bbs_mod.film.replays.tracks.TrackBlend;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

/** FSR grouped sound tracks share runtime dispatch with ordinary form tracks. */
public class SoundTrack implements TrackBehaviour
{
    @Override
    public IKeyframeFactory factory(TrackId track)
    {
        SoundKeyframeValue.Group group = SoundKeyframeValue.groupFromChannel(track.toKey());
        return switch (group)
        {
            case SOUND -> KeyframeFactories.SOUND;
            case SHAPE -> KeyframeFactories.SOUND_SHAPE;
            case VISUALIZATION -> KeyframeFactories.SOUND_VISUALIZATION;
            case FALLOFF -> KeyframeFactories.SOUND_FALLOFF;
            case REFLECTIONS -> KeyframeFactories.SOUND_REFLECTIONS;
        };
    }

    @Override
    public void apply(TrackContext context, TrackId track, KeyframeChannel channel, float tick, float blend)
    {
        SoundKeyframeValue.Group group = SoundKeyframeValue.groupFromChannel(track.toKey());
        AbstractSoundForm form = SoundKeyframeValue.formFromChannel(context.root(), track.toKey());
        if (form == null) return;
        KeyframeSegment segment = channel.find(tick);
        if (segment != null)
        {
            SoundKeyframeValue value = (SoundKeyframeValue) TrackBlend.value(channel, SoundKeyframeValue.capture(form, group), segment, blend);
            value.applyRuntime(form, group);
        }
        else if (blend >= 1F) SoundKeyframeValue.clearRuntime(form, group);
    }

    @Override
    public void reset(Form root, TrackId track)
    {
        AbstractSoundForm form = SoundKeyframeValue.formFromChannel(root, track.toKey());
        if (form != null) SoundKeyframeValue.clearRuntime(form, SoundKeyframeValue.groupFromChannel(track.toKey()));
    }
}
