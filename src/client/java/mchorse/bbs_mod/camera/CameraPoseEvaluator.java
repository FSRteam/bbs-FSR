package mchorse.bbs_mod.camera;

import io.netty.util.collection.IntObjectMap;
import io.netty.util.collection.IntObjectHashMap;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.HashMap;
import java.util.Map;

/**
 * Evaluates camera tracks once per scene frame without advancing film state.
 * The evaluator is deliberately client-side because entity bindings are
 * client runtime objects, while Film and its tracks remain serializable data.
 */
public final class CameraPoseEvaluator
{
    private final Map<String, Position> cache = new HashMap<>();
    private final Map<String, CameraClipContext> contexts = new HashMap<>();
    private final IntObjectMap<IEntity> emptyEntities = new IntObjectHashMap<>();
    private Film film;
    private IntObjectMap<IEntity> entities;
    private int ticks;
    private float transition;
    private boolean playing;
    private long frameId = Long.MIN_VALUE;

    public void beginFrame(long frameId, Film film, int ticks, float transition, boolean playing, IntObjectMap<IEntity> entities)
    {
        ticks = Math.max(0, ticks);
        transition = playing && Float.isFinite(transition) ? transition : 0F;

        if (this.film != film)
        {
            this.contexts.clear();
        }

        if (this.frameId != frameId || this.film != film || this.ticks != ticks || this.transition != transition
            || this.playing != playing || this.entities != entities)
        {
            this.cache.clear();
        }

        this.frameId = frameId;
        this.film = film;
        this.ticks = ticks;
        this.transition = transition;
        this.playing = playing;
        this.entities = entities;

        if (film != null)
        {
            this.contexts.keySet().removeIf(id -> !film.hasCamera(id));
        }
    }

    /** Evaluate a stable camera id and return a defensive position snapshot. */
    public Position evaluate(String cameraId, Position fallback)
    {
        if (this.film == null)
        {
            return this.copyFallback(fallback);
        }

        String id = this.film.hasCamera(cameraId) ? cameraId : Film.LEGACY_CAMERA_ID;
        Position cached = this.cache.get(id);

        if (cached != null)
        {
            return cached.copy();
        }

        Position result = this.copyFallback(Film.LEGACY_CAMERA_ID.equals(id) ? fallback : this.film.getCameraBasePosition(id));
        CameraClipContext context = this.getContext(id);

        context.resetPose(result);
        context.setup(this.ticks, this.transition);

        if (context.clips != null)
        {
            for (Clip clip : context.clips.getClips(this.ticks))
            {
                context.apply(clip, result);
            }

            context.currentLayer = 0;
        }

        this.cache.put(id, result.copy());

        return result;
    }

    /** The sole shared playback owner seeds legacy pose after publishing its effects once. */
    public void seedLegacyPose(Position position)
    {
        this.cache.put(Film.LEGACY_CAMERA_ID, this.copyFallback(position));
    }

    /** Preserve the editable exclusive-end pose without running non-pose clip callbacks. */
    public Position evaluateEditedEnd(String cameraId, Clip clip, Position fallback)
    {
        Position result = this.evaluate(cameraId, fallback);

        if (this.film != null && !this.playing && this.film.hasCamera(cameraId)
            && clip != null && this.film.getCameraClips(cameraId).getIndex(clip) >= 0
            && (long) clip.tick.get() + clip.duration.get() == this.ticks)
        {
            CameraClipContext context = this.getContext(cameraId);

            context.applyLast(clip, result);
            context.currentLayer = 0;
            this.cache.put(cameraId, result.copy());
        }

        return result;
    }

    private CameraClipContext getContext(String cameraId)
    {
        CameraClipContext context = this.contexts.computeIfAbsent(cameraId, id -> new CameraClipContext().poseOnly(true));
        Clips clips = this.film.getCameraClips(cameraId);

        context.clips = clips;
        context.entities = this.entities == null ? this.emptyEntities : this.entities;
        context.playing = this.playing;

        return context;
    }

    private Position copyFallback(Position fallback)
    {
        return fallback != null && FilmPlaybackPolicy.isCameraPoseAllowed(fallback) ? fallback.copy() : new Position();
    }

    public Position evaluateOutput(Position fallback)
    {
        String id = this.film == null ? Film.LEGACY_CAMERA_ID : this.film.resolveCameraId(this.ticks);

        return this.evaluate(id, fallback);
    }

    public long getFrameId()
    {
        return this.frameId;
    }

    public void invalidate()
    {
        this.cache.clear();
        this.frameId = Long.MIN_VALUE;
    }

    public void close()
    {
        this.invalidate();
        this.contexts.clear();
        this.film = null;
        this.entities = null;
    }
}
