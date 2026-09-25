package mchorse.bbs_mod.film.camera;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.camera.values.ValuePosition;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.utils.clips.Clips;

/** A persistable camera track identified independently from its ValueList index. */
public class CameraTrack extends ValueGroup
{
    private BaseType unreadableData;
    public final ValueString cameraId = new ValueString("id", "");
    public final ValueString name = new ValueString("name", "Camera");
    public final ValuePosition position = new ValuePosition("position");
    public final Clips clips = new Clips("clips", BBSMod.getFactoryCameraClips());

    public CameraTrack(String id)
    {
        super(id);
        this.cameraId.set(id);
        this.add(this.cameraId);
        this.add(this.name);
        this.add(this.position);
        this.add(this.clips);
    }

    public CameraTrack(String id, String name, Position position)
    {
        this(id);
        this.name.set(name == null || name.isEmpty() ? "Camera" : name);
        this.position.set(position == null ? new Position() : position);
    }

    public Position copyPosition()
    {
        return this.position.get().copy();
    }

    @Override
    public void fromData(BaseType data)
    {
        this.unreadableData = BaseType.isMap(data) ? null : data.copy();

        if (this.unreadableData == null)
        {
            super.fromData(data);
        }
    }

    @Override
    public BaseType toData()
    {
        return this.unreadableData == null ? super.toData() : this.unreadableData.copy();
    }

}
