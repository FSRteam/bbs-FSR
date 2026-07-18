package mchorse.bbs_mod.actions.types;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.FilmActionAuthorityPolicy;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public abstract class ActionClip extends Clip
{
    public final ValueInt frequency = new ValueInt("frequency", 0, 0, 1000);

    public ActionClip()
    {
        this.add(this.frequency);
    }

    public boolean isClient()
    {
        return false;
    }

    public final void applyClient(IEntity entity, Film film, Replay replay, int tick)
    {
        if (!this.shouldApply(tick))
        {
            return;
        }

        this.applyClientAction(entity, film, replay, tick);
    }

    protected void applyClientAction(IEntity entity, Film film, Replay replay, int tick)
    {}

    public final void apply(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!this.shouldApply(tick))
        {
            return;
        }

        if (FilmActionAuthorityPolicy.requiresAdministrator(this)
            && !ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        this.applyAction(actor, player, film, replay, tick);
    }

    private boolean shouldApply(int tick)
    {
        return isScheduledAt(
            this.enabled.get(),
            tick,
            this.tick.get(),
            this.duration.get(),
            this.frequency.get()
        );
    }

    public static boolean isScheduledAt(boolean enabled, int tick, int clipTick, int duration, int frequency)
    {
        if (!enabled)
        {
            return false;
        }

        long relative = (long) tick - clipTick;

        if (relative < 0L || relative >= duration)
        {
            return false;
        }

        return frequency == 0 ? relative == 0L : frequency > 0 && relative % frequency == 0L;
    }

    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {}

    /**
     * Retained for addons compiled against the original action clip helper.
     */
    protected void applyPositionRotation(SuperFakePlayer player, Replay replay, int tick)
    {
        ReplayKeyframes keyframes = replay.keyframes;

        player.setPos(keyframes.x.interpolate(tick), keyframes.y.interpolate(tick), keyframes.z.interpolate(tick));
        player.setYRot(keyframes.yaw.interpolate(tick).floatValue());
        player.setYHeadRot(keyframes.headYaw.interpolate(tick).floatValue());
        player.setYBodyRot(keyframes.bodyYaw.interpolate(tick).floatValue());
        player.setXRot(keyframes.pitch.interpolate(tick).floatValue());
        player.setItemInHand(InteractionHand.MAIN_HAND, keyframes.mainHand.interpolate(tick, ItemStack.EMPTY).copy());
        player.setItemInHand(InteractionHand.OFF_HAND, keyframes.offHand.interpolate(tick, ItemStack.EMPTY).copy());
    }

    protected boolean tryApplyPositionRotation(SuperFakePlayer player, Replay replay, int tick)
    {
        ReplayKeyframes keyframes = replay.keyframes;
        double x = keyframes.x.interpolate(tick);
        double y = keyframes.y.interpolate(tick);
        double z = keyframes.z.interpolate(tick);
        double yaw = keyframes.yaw.interpolate(tick);
        double headYaw = keyframes.headYaw.interpolate(tick);
        double bodyYaw = keyframes.bodyYaw.interpolate(tick);
        double pitch = keyframes.pitch.interpolate(tick);

        if (!FilmPlaybackPolicy.isPoseAllowed(x, y, z, yaw, headYaw, bodyYaw, pitch))
        {
            return false;
        }

        this.applyPositionRotation(player, replay, tick);

        return true;
    }

    /** Must run inside the fake-player interaction-state scope. */
    protected boolean applyInteractionPositionRotation(SuperFakePlayer player, Replay replay, int tick)
    {
        ReplayKeyframes keyframes = replay.keyframes;

        player.getInventory().selected = MathUtils.clamp(keyframes.selectedSlot.interpolate(tick), 0, 8);

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            return false;
        }

        player.getInventory().armor.set(EquipmentSlot.HEAD.getIndex(), keyframes.armorHead.interpolate(tick, ItemStack.EMPTY).copy());
        player.getInventory().armor.set(EquipmentSlot.CHEST.getIndex(), keyframes.armorChest.interpolate(tick, ItemStack.EMPTY).copy());
        player.getInventory().armor.set(EquipmentSlot.LEGS.getIndex(), keyframes.armorLegs.interpolate(tick, ItemStack.EMPTY).copy());
        player.getInventory().armor.set(EquipmentSlot.FEET.getIndex(), keyframes.armorFeet.interpolate(tick, ItemStack.EMPTY).copy());

        return true;
    }
}
