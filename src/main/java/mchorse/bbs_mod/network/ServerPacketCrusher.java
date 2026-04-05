package mchorse.bbs_mod.network;

import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

public class ServerPacketCrusher extends PacketCrusher
{
    @Override
    protected void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf)
    {
        NetworkCompat.sendToPlayer((ServerPlayer) entity, identifier, buf);
    }
}
