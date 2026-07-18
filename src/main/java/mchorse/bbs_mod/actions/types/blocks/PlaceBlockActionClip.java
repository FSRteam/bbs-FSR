package mchorse.bbs_mod.actions.types.blocks;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.mc.ValueBlockState;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class PlaceBlockActionClip extends BlockActionClip
{
    public final ValueBlockState state = new ValueBlockState("state");
    public final ValueBoolean drop = new ValueBoolean("drop", false);

    public PlaceBlockActionClip()
    {
        super();

        this.add(this.state);
        this.add(this.drop);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            return;
        }

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            return;
        }

        BlockPos pos = new BlockPos(this.x.get(), this.y.get(), this.z.get());

        if (!InteractionActionSemantics.canReplayBlockAction(player, pos))
        {
            return;
        }

        BlockState state = this.state.get();

        if (state.getBlock() == Blocks.AIR)
        {
            player.level().destroyBlock(pos, this.drop.get(), player);
        }
        else if (state.getBlock().isEnabled(player.serverLevel().enabledFeatures()))
        {
            player.level().setBlockAndUpdate(pos, state);
        }
    }

    @Override
    protected Clip create()
    {
        return new PlaceBlockActionClip();
    }
}
