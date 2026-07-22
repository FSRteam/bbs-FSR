package mchorse.bbs_mod.camera.controller;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.Objects;

public class PlayCameraController extends CameraWorkCameraController
{
    private int ticks;
    private int duration;
    private final String filmId;

    public PlayCameraController(Clips clips)
    {
        this(null, clips);
    }

    public PlayCameraController(String filmId, Clips clips)
    {
        super();

        this.filmId = filmId;
        this.setWork(clips);

        this.duration = clips.calculateDuration();
    }

    public boolean isForFilm(String filmId, Clips clips)
    {
        return this.filmId != null ? Objects.equals(this.filmId, filmId) : this.context.clips == clips;
    }

    @Override
    protected boolean managesAudio()
    {
        return false;
    }

    @Override
    public void setup(Camera camera, float transition)
    {
        boolean paused = this.isPairedFilmPaused();

        this.context.playing = !paused;
        this.apply(camera, this.ticks, paused ? 0F : transition);
    }

    @Override
    public void update()
    {
        super.update();

        if (!this.isPairedFilmPaused())
        {
            this.ticks += 1;
        }

        if (this.ticks >= this.duration)
        {
            BBSModClient.getCameraController().remove(this);
        }
    }

    private boolean isPairedFilmPaused()
    {
        if (this.filmId == null || BBSModClient.getFilms() == null)
        {
            return false;
        }

        BaseFilmController controller = BBSModClient.getFilms().getController(this.filmId);

        return controller != null && controller.paused;
    }
}
