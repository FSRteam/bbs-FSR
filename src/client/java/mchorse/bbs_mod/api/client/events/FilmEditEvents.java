package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Completed editor transactions, on the client thread. One callback per film per batch, after
 * values have changed, including undo/redo. Values are live and must not be retained for
 * background work. Loading, playback and intermediate drag samples do not post edits.
 */
public final class FilmEditEvents
{
    public enum Cause { EDIT, UNDO, REDO }

    public static final FunctionalEvent<Changed> CHANGED = new FunctionalEvent<>(
        listeners -> (film, values, cause) ->
        {
            for (Changed listener : listeners)
            {
                listener.changed(film, values, cause);
            }
        });

    public interface Changed
    {
        void changed(Film film, List<BaseValue> values, Cause cause);
    }

    /** Dispatches a completed batch; values outside films are ignored. */
    public static void notifyChanges(List<BaseValue> values, Cause cause)
    {
        Map<Film, List<BaseValue>> films = new IdentityHashMap<>();

        for (BaseValue value : values)
        {
            for (BaseValue parent = value; parent != null; parent = parent.getParent())
            {
                if (parent instanceof Film film)
                {
                    films.computeIfAbsent(film, key -> new ArrayList<>()).add(value);
                    break;
                }
            }
        }

        films.forEach((film, changes) -> CHANGED.invoker().changed(film, List.copyOf(changes), cause));
    }

    private FilmEditEvents()
    {}
}
