package bbssmokefixture.v2;

import mchorse.bbs_mod.utils.clips.Clip;

/**
 * 2.0 action clip, registered alongside {@link SmokeCameraClip} to exercise
 * both halves of {@code BBSPluginClipRegistry}.
 */
public final class SmokeActionClip extends Clip
{
    @Override
    protected Clip create()
    {
        return new SmokeActionClip();
    }
}
