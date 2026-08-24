package mchorse.bbs_mod.client.renderer;

import mchorse.bbs_mod.cubic.animation.ItemUsePose;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayItemUse;
import mchorse.bbs_mod.forms.entities.IEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.UseAnim;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Vanilla's eating and drinking effects for actors playing back a film: the
 * crumbs flying out of the mouth, the chewing and gulping, the burp at the end.
 *
 * <p>Live players get all of this from vanilla itself - it runs while the item
 * use ticks. A film's actor never ticks one: the use is a clip, so the schedule
 * ({@code LivingEntity.tickItemStackUsage} and its consumption effects, taken
 * off the 1.20.4 bytecode) is replayed from the clip's own phase instead. The
 * long window an author may give a clip is respected: crumbs keep flying for as
 * long as the eating lasts.</p>
 */
public class ItemUseEffects
{
    /** The last film tick each hand emitted on, per actor. */
    private static final Map<IEntity, int[]> LAST = new WeakHashMap<>();

    public static void tick(Replay replay, IEntity entity, int tick)
    {
        emit(replay, entity, tick, true);
        emit(replay, entity, tick, false);
    }

    public static void clear()
    {
        LAST.clear();
    }

    private static void emit(Replay replay, IEntity entity, int tick, boolean mainHand)
    {
        int[] last = LAST.computeIfAbsent(entity, (e) -> new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE});
        int index = mainHand ? 0 : 1;
        int previous = last[index];

        last[index] = tick;

        /* Effects ride a running film only: a paused timeline would spit crumbs
         * forever and a scrubbed one would spit a whole meal at once. */
        if (tick != previous + 1)
        {
            return;
        }

        LivingEntity user = ItemUsePose.livingOf(entity);
        ItemUsePose.Use use = ReplayItemUse.compute(replay, tick, mainHand, user);

        if (use == null)
        {
            /* The bite that just ended: vanilla's finishing burst and burp. */
            ItemUsePose.Use before = ReplayItemUse.compute(replay, tick - 1, mainHand, user);

            if (before != null && before.action() == UseAnim.EAT)
            {
                spawn(entity, before.stack(), UseAnim.EAT, 16);
                burp(entity, before.stack());
            }

            return;
        }

        if (use.action() != UseAnim.EAT && use.action() != UseAnim.DRINK)
        {
            return;
        }

        /* shouldSpawnConsumptionEffects: 1.21.1 dropped the snack exception the
         * older versions had - the crumbs start once 21.875% of the use is
         * behind, and then fly every 4th tick left. */
        int max = Math.round(use.window());
        int left = Math.round(use.window() - use.elapsed());
        boolean due = max - left > (int) (max * 0.21875F);

        if (due && left % 4 == 0)
        {
            spawn(entity, use.stack(), use.action(), 5);
        }
    }

    /** {@code LivingEntity.spawnConsumptionEffects} plus its {@code spawnItemParticles}. */
    private static void spawn(IEntity entity, ItemStack stack, UseAnim action, int count)
    {
        Level world = entity.getWorld();

        if (world == null || stack.isEmpty())
        {
            return;
        }

        RandomSource random = world.random;
        double x = entity.getX();
        double y = entity.getY() + entity.getEyeHeight();
        double z = entity.getZ();

        if (action == UseAnim.DRINK)
        {
            playSound(world, x, y, z, stack.getDrinkingSound(), 0.5F, Mth.nextFloat(random, 0.9F, 1F));

            return;
        }

        float pitch = -entity.getPitch() * 0.017453292F;
        float yaw = -entity.getYaw() * 0.017453292F;

        for (int i = 0; i < count; i++)
        {
            Vec3 velocity = new Vec3((random.nextFloat() - 0.5D) * 0.1D, random.nextDouble() * 0.1D + 0.1D, 0D)
                .xRot(pitch).yRot(yaw);
            Vec3 position = new Vec3((random.nextFloat() - 0.5D) * 0.3D, -random.nextFloat() * 0.6D - 0.3D, 0.6D)
                .xRot(pitch).yRot(yaw)
                .add(x, y, z);

            world.addParticle(new ItemParticleOption(ParticleTypes.ITEM, stack), position.x, position.y, position.z, velocity.x, velocity.y + 0.05D, velocity.z);
        }

        playSound(world, x, y, z, stack.getEatingSound(),
            random.nextBoolean() ? 0.5F : 1F,
            (random.nextFloat() - random.nextFloat()) * 0.2F + 1F);
    }

    /**
     * The sound eating or drinking this stack makes: the body gets to answer for itself
     * (ConsumableSoundProvider - vanilla asks the entity, not the item), and the component's
     * own sound otherwise.
     */
    /**
     * ⚠ {@code World#playSound(PlayerEntity except, ...)} means the opposite of
     * what it reads like on the client: {@link ClientWorld} plays the sound only
     * when {@code except} IS the local player (vanilla calls it from the local
     * player's own code, everyone else's sounds arrive as packets). Passing
     * {@code null} would be silence, so the client entry point is used directly.
     */
    private static void playSound(Level world, double x, double y, double z, SoundEvent sound, float volume, float pitch)
    {
        if (sound != null && world instanceof ClientWorld clientWorld)
        {
            clientWorld.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    /** {@code PlayerEntity.eatFood}'s tail: only actual food burps. */
    private static void burp(IEntity entity, ItemStack stack)
    {
        Level world = entity.getWorld();

        if (world == null || stack.get(DataComponents.FOOD) == null)
        {
            return;
        }

        playSound(world, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.ENTITY_PLAYER_BURP, 0.5F, world.random.nextFloat() * 0.1F + 0.9F);
    }
}
