package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.film.replays.tracks.TrackId;

import java.util.function.BiPredicate;

/** Registers addon timeline categories before the film editor is opened. */
public final class RegisterTrackCategoriesEvent
{
    public void register(TrackCategory category, BiPredicate<TrackId, Boolean> matches)
    {
        TrackCategories.register(category, matches);
    }
}
