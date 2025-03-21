package mchorse.bbs_mod.camera.clips;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

import java.util.ArrayList;
import java.util.List;

public class CameraClipContext extends ClipContext<CameraClip, Position>
{
    public List<IEntity> entities = new ArrayList<>();
    private Position lastPosition = new Position();

    @Override
    public boolean apply(Clip clip, Position position)
    {
        if (clip instanceof CameraClip)
        {
            this.currentLayer = clip.layer.get();
            this.relativeTick = this.ticks - clip.tick.get();

            ((CameraClip) clip).apply(this, position);

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