package mchorse.bbs_mod.camera;

import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.clips.CameraPosePolicy;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.Map;

/** Resolves a camera edit against its own track and playhead, without playback effects. */
public final class CameraPoseEditing
{
    private CameraPoseEditing()
    {}

    public static Clip findEditableClip(Clips clips, Clip selected, int tick)
    {
        if (selected != null && clips.getIndex(selected) >= 0 && isEditable(selected)
            && tick >= selected.tick.get() && tick <= (long) selected.tick.get() + selected.duration.get())
        {
            return selected;
        }

        Clip editable = null;

        for (Clip clip : clips.getClips(tick))
        {
            if (isEditable(clip) && (editable == null || clip.layer.get() >= editable.layer.get()))
            {
                editable = clip;
            }
        }

        return editable;
    }

    private static boolean isEditable(Clip clip)
    {
        return clip.enabled.get() && CameraPosePolicy.allows(clip)
            && (clip instanceof IdleClip || clip instanceof KeyframeClip);
    }

    /** Remove the layers above this clip using this camera's snapshots, not the legacy runner's. */
    public static Position toClipPosition(Film film, String cameraId, Clip clip, int tick, Position pose,
        Map<String, IEntity> entities)
    {
        Position edited = pose.copy();

        if (film == null || !film.hasCamera(cameraId) || film.getCameraClips(cameraId).getIndex(clip) < 0)
        {
            return edited;
        }

        CameraClipContext context = new CameraClipContext().poseOnly(true);
        Position result = film.getCameraBasePosition(cameraId);

        context.clips = film.getCameraClips(cameraId);

        if (entities != null)
        {
            context.entities = entities;
        }

        context.captureSnapshots();
        context.resetPose(result);
        context.setup(tick, 0F);

        for (Clip active : context.clips.getClips(tick))
        {
            context.apply(active, result);
        }

        if ((long) clip.tick.get() + clip.duration.get() == tick)
        {
            context.applyLast(clip, result);
        }

        Position snapshot = context.getSnapshots().get(clip);

        if (snapshot != null)
        {
            edited.point.x -= result.point.x - snapshot.point.x;
            edited.point.y -= result.point.y - snapshot.point.y;
            edited.point.z -= result.point.z - snapshot.point.z;
            edited.angle.yaw -= result.angle.yaw - snapshot.angle.yaw;
            edited.angle.pitch -= result.angle.pitch - snapshot.angle.pitch;
            edited.angle.roll -= result.angle.roll - snapshot.angle.roll;
            edited.angle.fov -= result.angle.fov - snapshot.angle.fov;
        }

        return edited;
    }
}
