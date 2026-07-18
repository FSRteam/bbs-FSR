package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Keeps the primary attack target available while vanilla applies sweep damage. */
public final class AttackRecordingContext
{
    private static final ThreadLocal<Entity> PRIMARY_TARGET = new ThreadLocal<>();

    private AttackRecordingContext()
    {}

    public static void withPrimaryTarget(Entity target, Runnable runnable)
    {
        Entity previous = PRIMARY_TARGET.get();
        PRIMARY_TARGET.set(target);

        try
        {
            runnable.run();
        }
        finally
        {
            if (previous == null)
            {
                PRIMARY_TARGET.remove();
            }
            else
            {
                PRIMARY_TARGET.set(previous);
            }
        }
    }

    public static boolean isPrimaryTarget(Entity target)
    {
        return PRIMARY_TARGET.get() == target;
    }

    public static boolean shouldRecordNonLivingAttack(boolean successful, boolean livingTarget)
    {
        return successful && !livingTarget;
    }

    public static void recordDamage(ServerPlayer player, Entity target, float amount)
    {
        BBSMod.getActions().addAction(player, () ->
        {
            AttackActionClip clip = new AttackActionClip();

            clip.damage.set(amount);
            clip.target.capture(target);
            clip.primary.set(isPrimaryTarget(target));

            return clip;
        });
    }
}
