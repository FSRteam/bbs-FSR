package mchorse.bbs_mod.ui.film;

import java.util.HashMap;
import java.util.Map;

/** Dependency-light state and geometry rules shared by the migrated film editor behaviors. */
final class FilmEditorMigrationLogic
{
    static boolean shouldMoveCursorWithWheel(boolean controlPressed, boolean flying, boolean cursorOverTimeline)
    {
        return controlPressed && !flying && cursorOverTimeline;
    }

    static double centeredVerticalScroll(int scrollSize, int viewportHeight, int layerHeight, int minLayer, int maxLayer)
    {
        return scrollSize - (viewportHeight + layerHeight * (minLayer + maxLayer + 1)) / 2D;
    }

    static final class TimelineScrollMemory
    {
        private final Map<String, TimelineScroll> values = new HashMap<>();

        void capture(String filmId, double camera, double action, double replay)
        {
            if (filmId != null)
            {
                this.values.put(filmId, new TimelineScroll(camera, action, replay));
            }
        }

        TimelineScroll get(String filmId)
        {
            return filmId == null ? null : this.values.get(filmId);
        }
    }

    static final class TimelineScroll
    {
        final double camera;
        final double action;
        final double replay;

        TimelineScroll(double camera, double action, double replay)
        {
            this.camera = camera;
            this.action = action;
            this.replay = replay;
        }
    }

    private FilmEditorMigrationLogic()
    {}
}
