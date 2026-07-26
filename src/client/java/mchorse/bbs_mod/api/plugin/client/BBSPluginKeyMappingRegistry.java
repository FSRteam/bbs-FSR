package mchorse.bbs_mod.api.plugin.client;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import net.minecraft.client.KeyMapping;

public interface BBSPluginKeyMappingRegistry
{
    BBSRegistrationResult register(KeyMapping mapping);
}
