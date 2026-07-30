package bbssmokefixture.v2;

import mchorse.bbs_mod.utils.clips.Clip;

/**
 * 2.0 camera clip. Registered with a green {@link mchorse.bbs_mod.camera.clips.ClipFactoryData}
 * color so it is visually distinct from the 1.0 (red) camera clip in the
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
