package mchorse.bbs_mod.actions.types.blocks;

import mchorse.bbs_mod.actions.ActionCommandContext;
import mchorse.bbs_mod.actions.BreakProgressContext;
import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

public class BreakBlockActionClip extends BlockActionClip
{
    public final ValueInt progress = new ValueInt("progress", 0);

    public BreakBlockActionClip()
    {
        super();

        this.add(this.progress);
    }

    @Override
    public void applyAction(LivingEntity actor, SuperFakePlayer player, Film film, Replay replay, int tick)
    {
        if (!ActionCommandContext.isAuthorizedFor(player))
        {
            BreakProgressContext.clearCurrent();

            return;
        }

        if (!this.tryApplyPositionRotation(player, replay, tick))
        {
            BreakProgressContext.clearCurrent();

            return;
        }

        BlockPos pos = new BlockPos(this.x.get(), this.y.get(), this.z.get());

        if (!InteractionActionSemantics.canReplayBlockAction(player, pos)
            || !InteractionActionSemantics.isValidBreakProgress(this.progress.get()))
        {
            BreakProgressContext.clearCurrent();

            return;
        }

        BreakProgressContext.updateOrDirect(player.getId(), player.serverLevel(), pos, this.progress.get());
    }

    @Override
    protected Clip create()
    {
        return new BreakBlockActionClip();
    }
}
