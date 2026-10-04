package mchorse.bbs_mod.camera.clips;

import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public class CameraClipContext extends ClipContext<CameraClip, Position>
{
    /** Keyed by their replay's stable id, in replay-list order. */
    public Map<String, IEntity> entities = new LinkedHashMap<>();
    private Position lastPosition = new Position();
    private Map<Clip, Position> snapshots = new HashMap<>();
    private boolean captureSnapshots;
    private boolean poseOnly;

    /**
     * Restrict evaluation to visual camera pose. This is used by secondary
     * views, which must not publish audio, subtitle, curve, or other playback
     * side effects.
     */
    public CameraClipContext poseOnly(boolean poseOnly)
    {
        this.poseOnly = poseOnly;

        return this;
    }

    public boolean isPoseOnly()
    {
        return this.poseOnly;
    }

    public void captureSnapshots()
    {
        this.captureSnapshots = true;
    }

    public Map<Clip, Position> getSnapshots()
    {
        return this.snapshots;
    }

    @Override
    public ClipContext setup(int ticks, int relativeTick, float transition, int currentLayer)
    {
        this.snapshots.clear();

        return super.setup(ticks, relativeTick, transition, currentLayer);
    }

    @Override
    public boolean applyUnderneath(int ticks, float transition, Position position, Predicate<Clip> filter)
    {
        boolean capture = this.captureSnapshots;

        if (capture) this.captureSnapshots = false;

        try
        {
            return super.applyUnderneath(ticks, transition, position, filter);
        }
        finally
        {
            if (capture) this.captureSnapshots = true;
        }
    }

    @Override
    public boolean apply(Clip clip, Position position)
    {
        return this.apply(clip, position, false);
    }

    /** Apply an editor endpoint through the same safety policy as ordinary sampling. */
    public boolean applyLast(Clip clip, Position position)
    {
        return this.apply(clip, position, true);
    }

    private boolean apply(Clip clip, Position position, boolean last)
    {
        if (clip == null || !clip.enabled.get() || (this.poseOnly && !CameraPosePolicy.allows(clip)))
        {
            return false;
        }

        if (clip instanceof CameraClip && position != null)
        {
            CameraState state = this.captureState();

            this.currentLayer = clip.layer.get();
            this.relativeTick = this.ticks - clip.tick.get();
            Position rollback = new Position();

            if (FilmPlaybackPolicy.isCameraPoseAllowed(position))
            {
                this.lastPosition.copy(position);
                rollback.copy(position);
            }
            else
            {
                rollback.copy(this.lastPosition);
            }

            try
            {
                if (last)
                {
                    ((CameraClip) clip).applyLast(this, position);
                }
                else
                {
                    ((CameraClip) clip).apply(this, position);
                }
            }
            catch (RuntimeException e)
            {
                position.copy(rollback);
                this.restoreState(state, rollback);

                throw e;
            }

            if (!FilmPlaybackPolicy.isCameraPoseAllowed(position))
            {
                position.copy(rollback);
                this.restoreState(state, rollback);

                return false;
            }

            if (this.captureSnapshots)
            {
                Position snapshot = new Position();

                snapshot.copy(position);
                this.snapshots.put(clip, snapshot);
            }

            double dx = position.point.x - this.lastPosition.point.x;
            double dy = position.point.y - this.lastPosition.point.y;
            double dz = position.point.z - this.lastPosition.point.z;

            if (Double.isNaN(this.distance))
            {
                this.distance = 0;
            }

            this.velocity = Math.sqrt(dx * dx + dy * dy + dz * dz);
            this.distance += this.velocity;

            this.lastPosition.copy(position);

            this.count += 1;

            return true;
        }

        return false;
    }

    /** Reset only camera-local sampling state; this never publishes playback effects. */
    public void resetPose(Position base)
    {
        this.lastPosition.copy(base);
        this.distance = 0D;
        this.velocity = 0D;
        this.clipData.clear();
        this.snapshots.clear();
    }

    private CameraState captureState()
    {
        Map<Clip, Position> snapshots = new HashMap<>();

        for (Map.Entry<Clip, Position> entry : this.snapshots.entrySet())
        {
            snapshots.put(entry.getKey(), entry.getValue().copy());
        }

        return new CameraState(
            this.ticks,
            this.relativeTick,
            this.transition,
            this.currentLayer,
            this.count,
            this.distance,
            this.velocity,
            this.captureSnapshots,
            snapshots
        );
    }

    private void restoreState(CameraState state, Position rollback)
    {
        this.ticks = state.ticks();
        this.relativeTick = state.relativeTick();
        this.transition = state.transition();
        this.currentLayer = state.currentLayer();
        this.count = state.count();
        this.distance = state.distance();
        this.velocity = state.velocity();
        this.lastPosition.copy(rollback);
        this.captureSnapshots = state.captureSnapshots();
        this.snapshots.clear();
        this.snapshots.putAll(state.snapshots());
    }

    private record CameraState(
        int ticks,
        int relativeTick,
        float transition,
        int currentLayer,
        int count,
        double distance,
        double velocity,
        boolean captureSnapshots,
        Map<Clip, Position> snapshots
    )
    {}

    public void shutdown()
    {
        if (this.poseOnly)
        {
            this.clipData.clear();
            this.snapshots.clear();

            return;
        }

        if (this.clips == null)
        {
            return;
        }

        for (Clip clip : this.clips.get())
        {
            if (clip instanceof CameraClip cameraClip)
            {
                cameraClip.shutdown(this);
            }
        }
    }
}
