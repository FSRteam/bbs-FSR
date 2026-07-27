package mchorse.bbs_mod.film;

import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import net.minecraft.world.entity.Entity;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Carries evaluated Film timeline state into actors rendered by Minecraft. */
public final class FilmActorTimeline
{
    private static final Map<Entity, State> states = new WeakHashMap<>();

    private FilmActorTimeline()
    {}

    public static void update(Object owner, Entity entity, FormProperties properties, float tick, boolean playing)
    {
        if (owner != null && entity != null && properties != null)
        {
            states.put(entity, new State(owner, properties, tick, playing));
        }
    }

    public static void apply(Entity entity, FormRenderingContext context)
    {
        State state = entity == null ? null : states.get(entity);

        if (state != null)
        {
            context.timeline(state.properties, state.tick, state.playing);
        }
    }

    /** Remove only the state installed by the given controller. */
    public static boolean clear(Object owner, Entity entity)
    {
        State state = entity == null ? null : states.get(entity);

        if (state != null && state.owner == owner)
        {
            states.remove(entity);

            return true;
        }

        return false;
    }

    public static void clearOwner(Object owner, Consumer<Entity> release)
    {
        Iterator<Map.Entry<Entity, State>> iterator = states.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<Entity, State> entry = iterator.next();

            if (entry.getValue().owner == owner)
            {
                Entity entity = entry.getKey();

                iterator.remove();

                if (entity != null)
                {
                    release.accept(entity);
                }
            }
        }
    }

    private record State(Object owner, FormProperties properties, float tick, boolean playing)
    {}
}
