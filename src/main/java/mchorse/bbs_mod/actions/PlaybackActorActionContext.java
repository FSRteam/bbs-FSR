package mchorse.bbs_mod.actions;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Limits private actor mutations to the authorized ActionPlayer action stack
 * that owns those exact actor instances.
 */
public final class PlaybackActorActionContext
{
    private static final ThreadLocal<Set<Object>> ACTORS = new ThreadLocal<>();

    private PlaybackActorActionContext()
    {}

    static void withActors(Iterable<? extends Entity> actors, Runnable runnable)
    {
        withIdentities(actors, runnable);
    }

    static void withIdentities(Iterable<?> actors, Runnable runnable)
    {
        Set<Object> previous = ACTORS.get();
        Set<Object> scoped = Collections.newSetFromMap(new IdentityHashMap<>());

        if (actors != null)
        {
            for (Object actor : actors)
            {
                if (actor != null)
                {
                    scoped.add(actor);
                }
            }
        }

        ACTORS.set(scoped);

        try
        {
            runnable.run();
        }
        finally
        {
            if (previous == null)
            {
                ACTORS.remove();
            }
            else
            {
                ACTORS.set(previous);
            }
        }
    }

    static boolean isScoped(@Nullable Object actor)
    {
        Set<Object> scoped = ACTORS.get();

        return actor != null && scoped != null && scoped.contains(actor);
    }

    public static boolean isAuthorizedFor(@Nullable Entity actor)
    {
        return isScoped(actor) && ActionCommandContext.isAuthorizedFor(actor);
    }
}
