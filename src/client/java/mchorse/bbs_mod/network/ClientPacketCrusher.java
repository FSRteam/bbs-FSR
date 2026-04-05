package mchorse.bbs_mod.network;

import mchorse.bbs_mod.network.compat.NetworkCompatClient;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class ClientPacketCrusher extends PacketCrusher
{
    @Override
    protected void sendBuffer(Player entity, ResourceLocation identifier, FriendlyByteBuf buf)
    {
        NetworkCompatClient.sendToServer(identifier, buf);
    }
}
