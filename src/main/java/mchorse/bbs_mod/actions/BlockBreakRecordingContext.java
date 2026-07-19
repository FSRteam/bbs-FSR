package mchorse.bbs_mod.actions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Invocation-local state shared by the block-break recording injections. */
public record BlockBreakRecordingContext(
    ActionManager actions,
    ActionRecorder recorder,
    ServerPlayer player,
    ServerLevel level,
    BlockPos pos,
    BlockState state
)
{}
