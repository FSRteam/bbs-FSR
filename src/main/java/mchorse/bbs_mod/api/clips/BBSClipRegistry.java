package mchorse.bbs_mod.api.clips;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;

public interface BBSClipRegistry
{
    BBSRegistrationResult registerCameraClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data);

    BBSRegistrationResult registerActionClip(Link id, Class<? extends Clip> clipType, ClipFactoryData data);
}
