package mchorse.bbs_mod.camera.controller;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.WorldFilmController;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.Objects;

public class PlayCameraController extends CameraWorkCameraController
{
    private int ticks;
    private int duration;
    private final String filmId;
    private final Clips sourceClips;
    private BaseFilmController pairedController;

    public PlayCameraController(Clips clips)
    {
        this(null, clips);
    }

    public PlayCameraController(String filmId, Clips clips)
    {
        super();

        this.filmId = filmId;
        this.sourceClips = clips;
        this.context.poseOnly(filmId != null);
        this.setWork(clips);

        this.duration = clips.calculateDuration();
    }

    public PlayCameraController(BaseFilmController controller)
    {
        this(controller.film.getId(), controller.film.camera);

        this.pairedController = controller;
        this.duration = controller.film.calculateDuration();
    }

    public boolean isForFilm(String filmId, Clips clips)
    {
        return this.filmId != null ? Objects.equals(this.filmId, filmId) : this.sourceClips == clips;
    }

    @Override
    protected boolean managesAudio()
    {
        return this.filmId == null;
    }

    @Override
    public CameraClipContext getContext()
    {
        BaseFilmController controller = this.getPairedController();

        return controller instanceof WorldFilmController world ? world.getCameraContext() : super.getContext();
    }

    @Override
    public void setup(Camera camera, float transition)
    {
        BaseFilmController controller = this.getPairedController();
        boolean paused = controller != null && controller.paused;
        Film film = controller == null ? null : controller.film;

        this.context.playing = !paused;

        if (film == null)
        {
            this.apply(camera, this.ticks, paused ? 0F : transition);

            return;
        }

        this.ticks = Math.max(0, controller.getTick());
        this.duration = film.calculateDuration();

        if (controller instanceof WorldFilmController world)
        {
            this.position.copy(world.getOutputCameraPosition(transition));
        }
        else
        {
            String cameraId = film.resolveCameraId(this.ticks);

            this.setWork(film.getCameraClips(cameraId));
            this.position.set(film.getCameraBasePosition(cameraId));
            this.apply(null, this.ticks, paused ? 0F : transition);
        }

        if (camera != null)
        {
            this.position.apply(camera);
        }
    }

    @Override
    public void update()
    {
        super.update();

        BaseFilmController controller = this.getPairedController();

        if (controller != null)
        {
            this.ticks = Math.max(0, controller.getTick());
            this.duration = controller.film.calculateDuration();
        }
        else if (this.filmId == null)
        {
            this.ticks += 1;
        }

        if (this.ticks >= this.duration || (this.filmId != null && controller == null))
        {
            BBSModClient.getCameraController().remove(this);
        }
    }

    protected BaseFilmController getPairedController()
    {
        if (this.pairedController != null)
        {
            return this.pairedController;
        }

        if (this.filmId == null || BBSModClient.getFilms() == null)
        {
            return null;
        }

        return BBSModClient.getFilms().getController(this.filmId);
    }
}
