package mchorse.bbs_mod.ui.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-level regression for the film home start page: the film panel must
 * host the home panel (not the legacy selection screen), the home must render
 * through the card grid fed by batched metadata, saves must stamp
 * {@code updated_at}, and every new UI string must be localized.
 */
public final class UIFilmHomeSourceTest
{
    private static final Path FILM_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");
    private static final Path HOME_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/film/home/UIFilmHomePanel.java");
    private static final Path EN_US = Path.of("src/client/resources/assets/bbs/assets/strings/en_us.json");

    public static void runAll() throws IOException
    {
        String panel = Files.readString(FILM_PANEL);
        String home = Files.readString(HOME_PANEL);
        String lang = Files.readString(EN_US);

        check(panel.contains("public UIFilmHomePanel selectionPanel"),
            "film panel must host the film home panel");
        check(panel.contains("new UIFilmHomePanel(this)"),
            "film panel must construct the film home panel");
        check(!panel.contains("UIFilmSelectionPanel"),
            "legacy selection screen must not linger in the film panel");
        check(!Files.exists(Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmSelectionPanel.java")),
            "legacy UIFilmSelectionPanel must be deleted");

        int forceSave = panel.indexOf("public void forceSave()");
        int stamp = panel.indexOf("stampUpdatedTimeNow()", forceSave);

        check(forceSave >= 0 && stamp > forceSave,
            "forceSave override must stamp updated_at before persisting");

        check(home.contains("UICardGrid<"),
            "home must present films through the card grid");
        check(home.contains("ClientNetwork.requestFilmMeta"),
            "home must consume the batched film metadata pipeline");
        check(home.contains("RelativeTime.describe"),
            "home must format timestamps through RelativeTime");
        check(home.contains("UINewsStrip"),
            "home must host the news strip section");
        check(home.contains("UICommissionBoard"),
            "home must host the commission board column");
        check(home.contains("FilmThumbnails.getCached"),
            "film cards must render cached first-frame thumbnails");
        check(home.contains("FilmThumbnails.invalidate"),
            "the refresh-cover action must invalidate cached thumbnails");

        String events = Files.readString(
            Path.of("src/client/java/mchorse/bbs_mod/client/BBSClientNeoEvents.java")
        );

        check(events.contains("FilmThumbnails.clientTick()"),
            "thumbnail capture must advance on the client tick");
        check(panel.contains("FilmThumbnails.requestCapture"),
            "the film panel must schedule cover captures");

        int paletteStart = home.indexOf("static final int[][] THUMB_COLORS");
        int paletteEnd = home.indexOf("};", paletteStart);

        check(paletteStart >= 0 && paletteEnd > paletteStart,
            "procedural thumbnail palette must exist");

        String outsidePalette = home.substring(0, paletteStart) + home.substring(paletteEnd + 2);

        check(!outsidePalette.contains("0x"),
            "semantic colors must not be hardcoded outside the thumbnail palette");

        String[] keys = {
            "bbs.ui.film.home.count",
            "bbs.ui.film.home.empty",
            "bbs.ui.film.home.modified",
            "bbs.ui.film.home.new_film",
            "bbs.ui.film.home.relative_now",
            "bbs.ui.film.home.root",
            "bbs.ui.film.home.search",
            "bbs.ui.film.home.sort_updated"
        };

        for (String key : keys)
        {
            check(lang.contains("\"" + key + "\""), "missing l10n key: " + key);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private UIFilmHomeSourceTest()
    {}
}
