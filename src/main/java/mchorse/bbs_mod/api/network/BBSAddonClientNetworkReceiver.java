package mchorse.bbs_mod.api.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

@FunctionalInterface
public interface BBSAddonClientNetworkReceiver
{
    void receive(ResourceLocation id, FriendlyByteBuf buf);
}
