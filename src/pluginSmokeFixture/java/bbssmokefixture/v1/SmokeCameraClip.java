package bbssmokefixture.v1;

import mchorse.bbs_mod.utils.clips.Clip;

/**
 * 1.0 camera clip. Registered with a red {@link mchorse.bbs_mod.camera.clips.ClipFactoryData}
 * color so it is visually distinct from the 2.0 (green) camera clip in the
 * clip picker.
 */
public final class SmokeCameraClip extends Clip
{
    @Override
    protected Clip create()
    {
        return new SmokeCameraClip();
    }
}
