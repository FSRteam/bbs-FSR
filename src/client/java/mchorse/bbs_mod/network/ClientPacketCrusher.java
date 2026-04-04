package mchorse.bbs_mod.network;

import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public class ClientPacketCrusher extends PacketCrusher
{
    @Override
    protected void sendBuffer(PlayerEntity entity, Identifier identifier, PacketByteBuf buf)
    {
        NetworkCompatClient.sendToServer(identifier, buf);
    }
}
