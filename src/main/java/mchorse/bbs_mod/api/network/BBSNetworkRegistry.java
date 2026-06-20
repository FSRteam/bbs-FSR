package mchorse.bbs_mod.api.network;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public interface BBSNetworkRegistry
{
    /**
     * Binds a server receiver to a BBS-owned, already registered C2S payload id.
     *
     * This is a legacy raw-buffer bridge for controlled compatibility. It does
     * not create NeoForge CustomPacketPayload types at addon registration time,
     * and arbitrary addon namespaces are rejected by NetworkCompat unless BBS
     * first promotes a channel into the frozen payload table.
     */
    BBSRegistrationResult registerLegacyServerReceiver(ResourceLocation id, NetworkCompat.ServerReceiver receiver);

    /**
     * Registers an addon sub-protocol receiver on the BBS-owned C2S broker.
     *
     * The NeoForge payload type remains the frozen BBS broker channel. The
     * provided id is only the addon message id inside the broker frame and must
     * use one of the addon's declared namespaces.
     */
    default BBSRegistrationResult registerServerReceiver(ResourceLocation id, BBSAddonServerNetworkReceiver receiver)
    {
        return BBSRegistrationResult.rejected(id == null ? "<null>" : id.toString(), "addon broker network registry is not available");
    }

    default FriendlyByteBuf createBuffer()
    {
        return NetworkCompat.createBuffer();
    }

    default boolean sendToPlayer(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
    {
        return false;
    }

    default boolean sendToPlayersTrackingEntity(Entity entity, ResourceLocation id, FriendlyByteBuf payload)
    {
        return false;
    }

    default boolean sendToPlayersTrackingEntityAndSelf(ServerPlayer player, ResourceLocation id, FriendlyByteBuf payload)
    {
        return false;
    }
}
