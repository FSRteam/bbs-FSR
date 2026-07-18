package mchorse.bbs_mod.actions.types;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.values.ActionTarget;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.mc.ValueItemStack;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;

public class EntityInteractionActionClip extends ActionClip
{
    public final ActionTarget target = new ActionTarget("target");
    public final ValueBoolean hand = new ValueBoolean("hand", true);
    public final ValueItemStack itemStack = new ValueItemStack("stack");
    public final ValueBoolean secondaryUse = new ValueBoolean("secondary_use", false);
    public final ValueBoolean interactAt = new ValueBoolean("interact_at", false);
    public final ValuePoint location = new ValuePoint("location", new Point(0D, 0D, 0D));

    public EntityInteractionActionClip()
    {
        super();

        this.add(this.target);
        this.add(this.hand);
        this.add(this.itemStack);
        this.add(this.secondaryUse);
        this.add(this.interactAt);
        this.add(this.location);
    }

    @Override
    public void shift(double dx, double dy, double dz)
    {
        super.shift(dx, dy, dz);

        this.target.shift(dx, dy, dz);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        InteractionActionSemantics.withIsolatedInteractionState(player, () ->
            this.applyIsolatedAction(actor, player, film, replay, tick));
    }

    private void applyIsolatedAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!this.applyInteractionPositionRotation(player, replay, tick))
        {
            return;
        }

        ServerLevel level = player.serverLevel();
        Entity entity = this.target.resolve(level, (candidate) -> candidate != actor && candidate != player && !candidate.isRemoved());

        if (!InteractionActionSemantics.canReplayEntity(player, entity))
        {
            return;
        }

        InteractionHand hand = this.hand.get() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = this.itemStack.get().copy();

        if (!stack.isItemEnabled(level.enabledFeatures()))
        {
            return;
        }

        ItemStack previous = player.getItemInHand(hand);
        player.setItemInHand(hand, stack);

        try
        {
            InteractionActionSemantics.withSecondaryUse(player, this.secondaryUse.get(), () ->
                InteractionActionSemantics.withIsolatedItemCooldown(player, stack, () ->
                {
                    if (this.interactAt.get())
                    {
                        Point point = this.location.get();
                        Vec3 location = new Vec3(point.x, point.y, point.z);

                        if (!InteractionActionSemantics.isValidEntityHit(entity, location))
                        {
                            return;
                        }

                        InteractionResult result = CommonHooks.onInteractEntityAt(player, entity, location, hand);

                        if (result == null)
                        {
                            entity.interactAt(player, location, hand);
                        }
                    }
                    else
                    {
                        player.interactOn(entity, hand);
                    }
                }));
        }
        finally
        {
            player.setItemInHand(hand, previous);
        }
    }

    @Override
    protected Clip create()
    {
        return new EntityInteractionActionClip();
    }
}
