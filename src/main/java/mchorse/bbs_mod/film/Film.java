package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.settings.values.ValueGroup;
import mchorse.bbs_mod.utils.clips.Clips;

public class Film extends ValueGroup
{
    public final Clips camera = new Clips("camera", BBSMod.getFactoryCameraClips());
    public final Replays replays = new Replays("replays");
    public final Clips voiceLines = new Clips("voice_lines", BBSMod.getFactoryScreenplayClips());

    public Film()
    {
        super("");

        this.add(this.camera);
        this.add(this.replays);
        this.add(this.voiceLines);
    }

    public Replay getFirstPersonReplay()
    {
        for (Replay replay : this.replays.getList())
        {
            if (replay.fp.get())
            {
                return replay;
            }
        }

        return null;
    }

    public boolean hasFirstPerson()
    {
        return this.getFirstPersonReplay() != null;
    }
}