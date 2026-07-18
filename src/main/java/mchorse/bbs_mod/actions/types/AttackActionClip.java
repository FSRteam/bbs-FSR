package mchorse.bbs_mod.actions.types;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

public class AttackActionClip extends ActionClip
{
    public final ValueFloat damage = new ValueFloat("damage", 0F);
    public final ActionTarget target = new ActionTarget("target");
    public final ValueBoolean primary = new ValueBoolean("primary", true);

    public AttackActionClip()
    {
        super();

        this.add(this.damage);
        this.add(this.target);
        this.add(this.primary);
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.target.shift(dx, dy, dz);
    }

    @Override
    protected boolean canPersist(BaseValue value)
    {
        if (!this.target.isPresent() && (value == this.target || value == this.primary))
        {
            return false;
        }

        return super.canPersist(value);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        float damage = this.damage.get();

        if (damage <= 0F || !FilmPlaybackPolicy.isDamageAllowed(damage))
        {
            return;
        }

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            return;
        }

        if (!this.target.isPresent())
        {
            double distance = 6D;
            HitResult blockHit = player.pick(distance, 1F, false);
            Vec3 origin = player.getEyePosition();
            Vec3 rotation = player.getViewVector(1F);
            Vec3 direction = origin.add(rotation.x * distance, rotation.y * distance, rotation.z * distance);

            double newDistance = blockHit != null ? blockHit.getLocation().distanceToSqr(origin) : distance * distance;
            AABB box = player.getBoundingBox().expandTowards(rotation.scale(distance)).inflate(1, 1, 1);
            EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(actor == null ? player : actor, origin, direction, box, candidate -> !candidate.isSpectator() && candidate.isPickable(), newDistance);

            if (entityHit != null)
            {
                Entity entity = entityHit.getEntity();

                if (entity != null)
                {
                    entity.hurt(player.damageSources().mobAttack(player), damage);
                }
            }

            return;
        }

        Entity entity = this.target.resolve(player.serverLevel(), (candidate) -> candidate != actor && candidate != player && !candidate.isRemoved());
        ItemStack weapon = player.getMainHandItem();

        if (!InteractionActionSemantics.canReplayEntity(player, entity)
            || !weapon.isItemEnabled(player.serverLevel().enabledFeatures()))
        {
            return;
        }

        boolean privatePlaybackActor = entity instanceof ActorEntity actorEntity
            && actorEntity.isPlaybackWorldIsolated();

        /* A private actor is a tracked visual projection. Posting it to the
         * global attack event or item left-click hook breaks that boundary. */
        if (this.primary.get()
            && ((!privatePlaybackActor && !CommonHooks.onPlayerAttackTarget(player, entity))
                || !entity.isAttackable()
                || entity.skipAttackInteraction(player)))
        {
            return;
        }

        entity.hurt(player.damageSources().playerAttack(player), damage);
    }

    @Override
    protected Clip create()
    {
        return new AttackActionClip();
    }
}
