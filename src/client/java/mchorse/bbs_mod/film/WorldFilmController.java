package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;

import java.util.List;
import java.util.Map;

public class WorldFilmController extends BaseFilmController
{
    protected CameraClipContext context;
    protected Position position = new Position();

    public int tick;
    public int duration;

    public WorldFilmController(Film film)
    {
        super(film);

        this.createEntities();

        this.duration = film.camera.calculateDuration();
        this.context = new CameraClipContext();
        this.context.clips = film.camera;
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
    public void render(IBbsWorldRenderContext context)
    {
        super.render(context);

        int tick = Math.max(this.tick, 0);
        List<Clip> clips = this.context.clips.getClips(tick);

        this.context.clipData.clear();
        this.context.playing = !this.paused;
        this.context.setup(tick, context.tickDelta());

        for (Clip clip : clips)
        {
            this.context.apply(clip, this.position);
        }

        this.context.currentLayer = 0;

        AudioClientClip.manageSounds(this.context);
    }

    @Override
    public void shutdown()
    {
        AudioClientClip.releaseSounds(this.context);
        this.context.shutdown();
        this.context.resetPlaybackOwner();
        this.context.clipData.clear();
    }
}
