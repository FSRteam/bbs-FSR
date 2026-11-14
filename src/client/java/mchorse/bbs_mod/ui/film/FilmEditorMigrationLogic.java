package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.ui.framework.elements.utils.ScrollMemory;

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
        private final ScrollMemory<String> camera = new ScrollMemory<>();
        private final ScrollMemory<String> action = new ScrollMemory<>();
        private final ScrollMemory<String> replay = new ScrollMemory<>();

        void capture(String filmId, double camera, double action, double replay)
        {
            if (filmId != null)
            {
                this.camera.save(filmId, camera);
                this.action.save(filmId, action);
                this.replay.save(filmId, replay);
            }
        }

        TimelineScroll get(String filmId)
        {
            return !this.camera.has(filmId) ? null : new TimelineScroll(this.camera.get(filmId), this.action.get(filmId), this.replay.get(filmId));
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
