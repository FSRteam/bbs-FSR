package mchorse.bbs_mod.actions;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;

/** Executes persisted commands only as the current, real, permission-bearing player. */
public final class AuthorizedCommandExecutor
{
    private AuthorizedCommandExecutor()
    {}

    public static boolean isAuthorized(@Nullable ServerPlayer requester)
    {
        return FilmActionAuthorityPolicy.isRequesterAuthorized(requester);
    }

    public static boolean isAuthorized(@Nullable ServerPlayer requester, @Nullable MinecraftServer expectedServer)
    {
        return FilmActionAuthorityPolicy.isRequesterAuthorized(requester, expectedServer);
    }

    public static boolean isCurrentPlayer(@Nullable ServerPlayer requester, @Nullable MinecraftServer expectedServer)
    {
        if (requester == null || requester instanceof SuperFakePlayer || expectedServer == null)
        {
            return false;
        }

        try
        {
            return requester.getServer() == expectedServer
                && expectedServer.getPlayerList().getPlayer(requester.getUUID()) == requester;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    public static boolean execute(@Nullable ServerPlayer requester, String command, boolean commandAllowed, @Nullable Entity executionAnchor)
    {
        if (!commandAllowed || command == null || command.isEmpty() || !isAuthorized(requester))
        {
            return false;
        }

        try
        {
            MinecraftServer server = requester.getServer();
            CommandSourceStack source = requester.createCommandSourceStack();

            if (executionAnchor != null)
            {
                if (!(executionAnchor.level() instanceof ServerLevel level) || level.getServer() != server)
                {
                    return false;
                }

                source = source.withEntity(executionAnchor)
                    .withLevel(level)
                    .withPosition(executionAnchor.position())
                    .withRotation(new Vec2(executionAnchor.getXRot(), executionAnchor.getYRot()));
            }

            server.getCommands().performPrefixedCommand(source, command);

            return true;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }
}
