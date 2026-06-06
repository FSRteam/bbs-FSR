package mchorse.bbs_mod.api.network;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.resources.ResourceLocation;

public interface BBSNetworkRegistry
{
    BBSRegistrationResult registerLegacyServerReceiver(ResourceLocation id, NetworkCompat.ServerReceiver receiver);
}
