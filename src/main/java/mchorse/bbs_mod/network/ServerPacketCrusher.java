package mchorse.bbs_mod.network;

import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ServerPacketCrusher extends PacketCrusher
{
    @Override
    protected void sendBuffer(PlayerEntity entity, Identifier identifier, PacketByteBuf buf)
    {
        NetworkCompat.sendToPlayer((ServerPlayerEntity) entity, identifier, buf);
    }
}
