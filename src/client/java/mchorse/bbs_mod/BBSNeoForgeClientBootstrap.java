package mchorse.bbs_mod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Transitional NeoForge client bootstrap that delegates to the existing client initializer.
 */
@EventBusSubscriber(modid = BBSMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BBSNeoForgeClientBootstrap
{
    private BBSNeoForgeClientBootstrap() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() -> new BBSModClient().onInitializeClient());
    }
}
