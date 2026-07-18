package mchorse.bbs_mod.utils;

import mchorse.bbs_mod.BBSMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

public class PermissionUtils
{
    public static final int ADMIN_PERMISSION_LEVEL = 2;

    public static boolean arePanelsAllowed(MinecraftServer server, ServerPlayer player)
    {
        GameRules.BooleanValue rule = server.overworld().getGameRules().getRule(BBSMod.BBS_EDITING_RULE);
        boolean allowed = rule.get() || server.getPlayerList().isOp(player.getGameProfile());

        return allowed;
    }

    /**
     * Use the command source permission contract so integrated-server owners
     * and platform permission providers behave exactly like /bbs admin
     * commands. The bbsEditing game rule alone is intentionally insufficient
     * for mutations that execute commands or replace player state.
     */
    public static boolean hasAdminPermission(ServerPlayer player)
    {
        int permissionLevel = player != null && player.createCommandSourceStack().hasPermission(ADMIN_PERMISSION_LEVEL)
            ? ADMIN_PERMISSION_LEVEL
            : 0;

        return isAdminPermissionLevel(permissionLevel);
    }

    /** Pure projection for permission-policy tests and non-Minecraft callers. */
    public static boolean isAdminPermissionLevel(int permissionLevel)
    {
        return permissionLevel >= ADMIN_PERMISSION_LEVEL;
    }
}
