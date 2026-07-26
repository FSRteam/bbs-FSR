package mchorse.bbs_mod.api.plugin;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSPluginParticleRegistry
{
    BBSRegistrationResult registerComponent(String id, String componentClassName);
}
