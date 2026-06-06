package mchorse.bbs_mod.api.particles;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSParticleRegistry
{
    BBSRegistrationResult registerComponent(String id, String componentClassName);
}
