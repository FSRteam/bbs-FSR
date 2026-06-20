package mchorse.bbs_mod.api.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface BBSAddonServerNetworkReceiver
{
    void receive(MinecraftServer server, ServerPlayer player, ResourceLocation id, FriendlyByteBuf buf);
}
