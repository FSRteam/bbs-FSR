package mchorse.bbs_mod.camera.clips.misc;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.SoundManager;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.IdentityHashMap;
import java.util.Map;

public class AudioClientClip extends AudioClip
{
    static final class Playback
    {
        final Link link;
        final float seconds;
        final float gain;

        Playback(Link link, float seconds, float gain)
        {
            this.link = link;
            this.seconds = seconds;
            this.gain = gain;
        }
    }

    public AudioClientClip()
    {
        super();
    }

    /** Erased JVM descriptor remains Map getPlayback(ClipContext). Keys now use clip identity (any AudioClip subclass). */
    public static Map<AudioClip, Playback> getPlayback(ClipContext context)
    {
        return context.clipData.get("audio.voices", IdentityHashMap::new);
    }

    /** Reconcile even when desired is empty so removed/expired/disabled clips cannot linger. */
    public static void manageSounds(ClipContext context)
    {
        SoundManager sounds = BBSModClient.getSounds();

        if (sounds == null)
        {
            return;
        }

        Map<AudioClip, Playback> playback = getPlayback(context);
        IdentityHashMap<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();
        boolean muteFilmAudioDuringVideoCapture = BBSSettings.videoMuteAudioWhileRender.get()
            && BBSModClient.getVideoRecorder() != null
            && BBSModClient.getVideoRecorder().isRecording();

        for (Map.Entry<AudioClip, Playback> entry : playback.entrySet())
        {
            Playback state = entry.getValue();
            float gain = muteFilmAudioDuringVideoCapture ? 0F : state.gain;

            desired.put(entry.getKey(), new SoundManager.VoiceRequest(state.link, state.seconds, gain));
        }

        sounds.reconcile(context.getPlaybackOwner(), desired, context.playing);
    }

    public static void releaseSounds(ClipContext context)
    {
        SoundManager sounds = BBSModClient.getSounds();

        if (sounds != null && context != null)
        {
            sounds.releaseOwner(context.getPlaybackOwner());
        }
    }

    @Override
    public boolean isGlobal()
    {
        return true;
    }

    @Override
    public void shutdown(ClipContext context)
    {}

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        Link link = this.audio.get();
        float localTick = context.relativeTick + context.transition;

        if (link == null || localTick < 0F || localTick >= this.duration.get())
        {
            return;
        }

        float seconds = TimeUtils.toSeconds(this.offset.get()) + localTick / 20F;

        if (seconds >= 0F)
        {
            getPlayback(context).put(this, new Playback(link, seconds, this.volume.get()));
        }
    }

    /**
     * Schedule a clip's audio track for this frame. Shared entry point for clips whose
     * audio rides this clip's playback machinery (the video clip's audio track) - the
     * audio clip itself keeps its own {@link #applyClip} gating.
     *
     * @param loopSeconds when positive, the playback position wraps around this
     *                    period (the video clip loops its audio with its picture)
     */
    public static void scheduleAudio(ClipContext context, AudioClip clip, float gain, float loopSeconds)
    {
        Link link = clip.audio.get();
        float localTick = context.relativeTick + context.transition;

        if (link == null || localTick < 0F || localTick >= clip.duration.get())
        {
            return;
        }

        float position = TimeUtils.toSeconds(clip.offset.get()) + localTick / 20F;

        if (loopSeconds > 0F)
        {
            position = position % loopSeconds;

            if (position < 0F)
            {
                position += loopSeconds;
            }
        }

        if (position >= 0F)
        {
            getPlayback(context).put(clip, new Playback(link, position, gain));
        }
    }

    @Override
    protected Clip create()
    {
        return new AudioClientClip();
    }
}
