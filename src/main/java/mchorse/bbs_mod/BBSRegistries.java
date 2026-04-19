package mchorse.bbs_mod;

import mchorse.bbs_mod.entity.ActorEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

public final class BBSRegistries
{
    private BBSRegistries()
    {}

    public static void onEntityAttributes(EntityAttributeCreationEvent event)
    {
        event.put(BBSMod.ACTOR_ENTITY.get(), ActorEntity.createActorAttributes().build());
    }
}
