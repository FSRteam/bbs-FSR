package mchorse.bbs_mod.camera.clips;

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.actions.FilmPlaybackPolicy;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public class CameraClipContext extends ClipContext<CameraClip, Position>
{
    public IntObjectMap<IEntity> entities = new IntObjectHashMap<>();
    private Position lastPosition = new Position();
    private Map<Clip, Position> snapshots = new HashMap<>();
    private boolean captureSnapshots;

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
                ((CameraClip) clip).apply(this, position);
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
