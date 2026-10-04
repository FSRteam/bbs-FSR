package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.List;
import java.util.Map;

public class WorldFilmController extends BaseFilmController
{
    protected CameraClipContext context;
    protected Position position = new Position();

    public int tick;
    public int duration;
    private final CameraPoseEvaluator cameraPoseEvaluator = new CameraPoseEvaluator();
    private long cameraFrameId;
    private int sampledTick = Integer.MIN_VALUE;
    private float sampledTransition;

    public WorldFilmController(Film film)
    {
        super(film);

        this.createEntities();

        this.duration = film.calculateDuration();
        this.context = new CameraClipContext();
        this.context.clips = film.camera;
        this.context.entities = this.getEntities();
    }

    @Override
    public Map<String, Integer> getActors()
    {
        return BBSModClient.getFilms().actors.get(this.film.getId());
    }

    @Override
    public int getTick()
    {
        return this.tick;
    }

    @Override
    public boolean hasFinished()
    {
        return this.tick >= this.duration;
    }

    @Override
    public void update()
    {
        if (!this.paused)
        {
            this.tick += 1;
        }

        super.update();
    }

    @Override
    public void startRenderFrame(float transition)
    {
        super.startRenderFrame(transition);
        this.sampleCameraFrame(transition);
    }

    public CameraClipContext getCameraContext()
    {
        return this.context;
    }

    public Position getOutputCameraPosition(float transition)
    {
        float delta = this.paused ? 0F : transition;

        if (this.sampledTick != Math.max(this.tick, 0) || this.sampledTransition != delta)
        {
            this.sampleCameraFrame(delta);
        }

        return this.cameraPoseEvaluator.evaluateOutput(this.position);
    }

    private void sampleCameraFrame(float transition)
    {
        float delta = this.paused ? 0F : transition;
        int tick = Math.max(this.tick, 0);

        /* Film.camera owns soundtrack, curves, subtitles and extension effects.
         * Camera cuts change visual sampling, never this playback owner. */
        this.context.clips = this.film.camera;

        List<Clip> clips = this.context.clips == null ? List.of() : this.context.clips.getClips(tick);

        this.context.clipData.clear();
        this.context.playing = !this.paused;
        this.context.setup(tick, delta);

        for (Clip clip : clips)
        {
            this.context.apply(clip, this.position);
        }

        this.context.currentLayer = 0;

        AudioClientClip.manageSounds(this.context);
        this.cameraPoseEvaluator.beginFrame(++this.cameraFrameId, this.film, tick, delta, !this.paused, this.getEntities());
        this.cameraPoseEvaluator.seedLegacyPose(this.position);
        this.sampledTick = tick;
        this.sampledTransition = delta;
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        AudioClientClip.releaseSounds(this.context);
        this.context.shutdown();
        this.context.resetPlaybackOwner();
        this.context.clipData.clear();
        this.cameraPoseEvaluator.close();
    }
}
