package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.GameRules;

public class PermissionUtils
{
    public static boolean arePanelsAllowed(MinecraftServer server, ServerPlayer player)
    {
        GameRules.BooleanRule rule = server.getOverworld().getGameRules().get(BBSMod.BBS_EDITING_RULE);
        boolean allowed = rule.get() || server.getPlayerList().isOp(player.getGameProfile());

        return allowed;
    }
}
