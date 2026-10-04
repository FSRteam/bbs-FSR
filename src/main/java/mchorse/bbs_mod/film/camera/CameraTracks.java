package mchorse.bbs_mod.film.camera;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.core.ValueList;
import mchorse.bbs_mod.utils.CollectionUtils;

import java.util.UUID;

/** Ordered storage for camera tracks; ordering is presentation only. */
public class CameraTracks extends ValueList<CameraTrack>
{
    public CameraTracks(String id)
    {
        super(id);
    }

    public CameraTrack addTrack(String cameraId, String name, Position position)
    {
        String id = cameraId == null || cameraId.isEmpty() ? this.nextId() : cameraId;

        if (id.isBlank() || FilmCameraResolver.LEGACY_CAMERA_ID.equals(id) || this.indexOf(id) >= 0)
        {
            throw new IllegalArgumentException("Camera id must be unique and cannot use the reserved legacy id: " + id);
        }

        CameraTrack track = new CameraTrack(id, name, position);

        this.preNotify(IValueListener.FLAG_UNMERGEABLE);
        this.add(track);
        this.sync();
        this.postNotify(IValueListener.FLAG_UNMERGEABLE);

        return track;
    }

    public void removeTrack(String cameraId)
    {
        int index = this.indexOf(cameraId);

        if (CollectionUtils.inRange(this.list, index))
        {
            this.preNotify(IValueListener.FLAG_UNMERGEABLE);
            this.list.remove(index);
            this.sync();
            this.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }
    }

    public CameraTrack getById(String cameraId)
    {
        if (cameraId == null || cameraId.isBlank() || FilmCameraResolver.LEGACY_CAMERA_ID.equals(cameraId))
        {
            return null;
        }

        int index = this.indexOf(cameraId);

        if (!CollectionUtils.inRange(this.list, index))
        {
            return null;
        }

        for (int i = index + 1; i < this.list.size(); i++)
        {
            if (cameraId.equals(this.list.get(i).cameraId.get()))
            {
                return null;
            }
        }

        return this.list.get(index);
    }

    public int indexOf(String cameraId)
    {
        if (cameraId == null)
        {
            return -1;
        }

        for (int i = 0; i < this.list.size(); i++)
        {
            if (cameraId.equals(this.list.get(i).cameraId.get()))
            {
                return i;
            }
        }

        return -1;
    }

    public boolean containsId(String cameraId)
    {
        return this.getById(cameraId) != null;
    }

    private String nextId()
    {
        String id;

        do
        {
            id = "camera-" + UUID.randomUUID();
        }
        while (this.indexOf(id) >= 0);

        return id;
    }

    @Override
    protected CameraTrack create(String id)
    {
        CameraTrack track = new CameraTrack("");

        /* A ValueList index is a path component, never a camera identity. Missing
         * or ambiguous ids remain in the data for repair instead of being rebound. */
        track.setId(id);

        return track;
    }
}
