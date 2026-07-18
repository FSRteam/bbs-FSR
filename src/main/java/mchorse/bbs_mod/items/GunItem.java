package mchorse.bbs_mod.items;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.AuthorizedCommandExecutor;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.utils.PermissionUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class GunItem extends Item
{
    /** @deprecated Use {@link #withActor(Entity, Runnable)} for scoped replay ownership. */
    @Deprecated
    public static Entity actor;

    private static final ThreadLocal<OwnerScope> ACTOR = new ThreadLocal<>();

    public GunItem(Properties settings)
    {
        super(settings);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);
        Entity owner = resolveOwner(level, player);

        if (owner == null)
        {
            return InteractionResultHolder.fail(stack);
        }

        GunProperties properties = this.getProperties(stack);

        if (!GunPropertiesPolicy.isAllowed(properties))
        {
            return InteractionResultHolder.fail(stack);
        }

        /* LocalPlayer's permission level is synchronized by vanilla. This
         * keeps hand selection and launch prediction aligned while the server
         * still revalidates the exact current requester below. */
        if (level.isClientSide && !GunPropertiesPolicy.isUseAllowed(
            properties,
            player.hasPermissions(PermissionUtils.ADMIN_PERMISSION_LEVEL)
        ))
        {
            return InteractionResultHolder.fail(stack);
        }

        ServerPlayer requester = null;

        if (!level.isClientSide)
        {
            requester = this.resolveRequester(level, player);

            if (requester == null || !GunPropertiesPolicy.isUseAllowed(
                properties,
                AuthorizedCommandExecutor.isAuthorized(requester, level.getServer())
            ))
            {
                return InteractionResultHolder.fail(stack);
            }
        }

        /* Launch the player */
        if (properties.launch)
        {
            Vec3 rotationVector = owner.getViewVector(1.0F).scale(properties.launchPower);

            if (properties.launchAdditive)
            {
                owner.addDeltaMovement(rotationVector);
            }
            else
            {
                owner.setDeltaMovement(rotationVector);
            }

            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (!level.isClientSide)
        {
            /* Shoot projectiles */
            int projectiles = Math.max(properties.projectiles, 1);
            boolean fired = false;

            for (int i = 0; i < projectiles; i++)
            {
                GunProjectileBudget.Lease lease = GunProjectileBudget.RUNTIME.tryReserve(requester.getUUID());

                if (lease == null)
                {
                    break;
                }

                boolean added = false;

                try
                {
                    GunProjectileEntity projectile = BBSMod.GUN_PROJECTILE_ENTITY.get().create(level);

                    if (projectile == null)
                    {
                        continue;
                    }

                    projectile.setBudgetLease(lease);
                    lease.attachCleanup(projectile::discard);

                    if (!lease.isActive())
                    {
                        continue;
                    }

                    float yaw = owner.getYHeadRot() + (float) (properties.scatterY * (Math.random() - 0.5D));
                    float pitch = owner.getXRot() + (float) (properties.scatterX * (Math.random() - 0.5D));

                    projectile.setProperties(properties);
                    projectile.setForm(FormUtils.copy(properties.projectileForm));
                    projectile.setOwner(owner);
                    projectile.setCommandRequester(requester);
                    projectile.setPos(owner.getX(), owner.getEyeY(), owner.getZ());
                    projectile.shootFromRotation(owner, pitch, yaw, 0F, properties.speed, 0F);
                    projectile.refreshDimensions();

                    added = level.addFreshEntity(projectile);
                    fired |= added;
                }
                finally
                {
                    if (!added)
                    {
                        lease.close();
                    }
                }
            }

            if (!fired)
            {
                return InteractionResultHolder.fail(stack);
            }

            AuthorizedCommandExecutor.execute(
                requester,
                properties.cmdFiring,
                GunPropertiesPolicy.isCommandAllowed(properties.cmdFiring),
                owner
            );
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    public static void withActor(Entity actor, Runnable runnable)
    {
        OwnerScope previous = ACTOR.get();

        /* Keep a real scope even for null. A non-actor replay must suppress
         * the deprecated global fallback instead of inheriting it. */
        ACTOR.set(new OwnerScope(actor));

        try
        {
            runnable.run();
        }
        finally
        {
            if (previous == null)
            {
                ACTOR.remove();
            }
            else
            {
                ACTOR.set(previous);
            }
        }
    }

    private GunProperties getProperties(ItemStack stack)
    {
        return GunProperties.get(stack);
    }

    private static Entity resolveOwner(Level level, Player player)
    {
        /* The deprecated global field was historically used by fake-player
         * replay. It must never redirect an ordinary player's interaction. */
        if (!(player instanceof SuperFakePlayer))
        {
            return player;
        }

        OwnerScope scope = ACTOR.get();

        if (scope != null)
        {
            Entity scopedActor = scope.actor();

            return scopedActor == null
                ? player
                : validOwner(level, scopedActor);
        }

        return actor == null ? player : validOwner(level, actor);
    }

    private static Entity validOwner(Level level, Entity owner)
    {
        return owner.level() == level && !owner.isRemoved() ? owner : null;
    }

    private ServerPlayer resolveRequester(Level level, Player player)
    {
        ServerPlayer scopedRequester = ActionCommandContext.currentRequester();

        if (scopedRequester != null)
        {
            return AuthorizedCommandExecutor.isAuthorized(scopedRequester, level.getServer())
                ? scopedRequester
                : null;
        }

        if (player instanceof ServerPlayer requester
            && AuthorizedCommandExecutor.isCurrentPlayer(requester, level.getServer()))
        {
            return requester;
        }

        return null;
    }

    private record OwnerScope(Entity actor)
    {}
}
