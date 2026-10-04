package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

import java.util.function.DoubleToIntFunction;

/**
 * Overlay shared by clip and actor-keyframe timelines. The coordinate mapper accepts absolute
 * film ticks and includes clip offset, scroll and zoom. Area is borrowed read-only.
 */
public final class TimelineEvents
{
    public static final FunctionalEvent<Overlay> OVERLAY = new FunctionalEvent<>(
        listeners -> (film, context, area, toX) ->
        {
            for (Overlay listener : listeners)
            {
                listener.render(film, context, area, toX);
            }
        });

    public interface Overlay
    {
        void render(Film film, UIContext context, Area area, DoubleToIntFunction toX);
    }

    private TimelineEvents()
    {}
}
