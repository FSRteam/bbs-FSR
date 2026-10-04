package mchorse.bbs_mod.ui.film;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard for restored dock state being available during panel construction. */
public final class FilmDockInitialStateSourceTest
{
    private static final Path FILM_PANEL = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");
    private static final String INITIAL_LOCK = ".locked(!BBSSettings.editorLayoutSettings.isDockUnlocked(ValueEditorLayout.FILM))";

    public static void runAll() throws IOException
    {
        String source = Files.readString(FILM_PANEL);
        int dockConstruction = source.indexOf("this.dock = new UIDockLayout()");
        int replayEditorConstruction = source.indexOf("this.replayEditor = new UIReplaysEditor(this)");
        int initialLock = source.indexOf(INITIAL_LOCK, dockConstruction);
        int dockConfiguration = source.indexOf("this.dock.source(this.createFilmLayoutSource())");

        check(dockConstruction >= 0, "film dock construction was removed");
        check(replayEditorConstruction > dockConstruction, "replay editor must be constructed after the dock");
        check(initialLock > dockConstruction && initialLock < replayEditorConstruction,
            "restored film dock lock state must be set before replay panels are constructed");
        check(dockConfiguration > replayEditorConstruction,
            "film dock source configuration must remain after replay panel construction");
        check(source.indexOf(INITIAL_LOCK, dockConfiguration) < 0,
            "film dock lock state must not be applied only after replay panel construction");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private FilmDockInitialStateSourceTest()
    {}
}
