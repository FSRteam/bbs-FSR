package mchorse.bbs_mod.actions;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** Carries the real ActionPlayer requester without granting the fake player authority. */
public final class ActionCommandContext
{
    private static final ThreadLocal<ServerPlayer> REQUESTER = new ThreadLocal<>();

    private ActionCommandContext()
    {}

    public static void withRequester(@Nullable ServerPlayer requester, Runnable runnable)
    {
        ServerPlayer previous = REQUESTER.get();

        if (requester == null)
        {
            REQUESTER.remove();
        }
        else
        {
            REQUESTER.set(requester);
        }

        try
        {
            runnable.run();
        }
        finally
        {
            if (previous == null)
            {
                REQUESTER.remove();
            }
            else
            {
                REQUESTER.set(previous);
            }
        }
    }

    public static boolean isAuthorized()
    {
        return AuthorizedCommandExecutor.isAuthorized(REQUESTER.get());
    }

    public static boolean isAuthorizedFor(@Nullable Entity executionAnchor)
    {
        return executionAnchor != null
            && executionAnchor.level() instanceof net.minecraft.server.level.ServerLevel level
            && AuthorizedCommandExecutor.isAuthorized(REQUESTER.get(), level.getServer());
    }

    @Nullable
    public static ServerPlayer currentRequester()
    {
        return REQUESTER.get();
    }

    public static boolean execute(String command, int frequency, @Nullable Entity executionAnchor)
    {
        return AuthorizedCommandExecutor.execute(
            REQUESTER.get(),
            command,
            FilmPlaybackPolicy.isCommandActionAllowed(command, frequency),
            executionAnchor
        );
    }
}
