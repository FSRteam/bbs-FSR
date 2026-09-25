package mchorse.bbs_mod.film.camera;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/** A timeline camera switch. Multiple cuts at a tick are resolved last-write-wins. */
public class CameraCut extends ValueGroup
{
    private BaseType unreadableData;
    public final ValueInt tick = new ValueInt("tick", 0, 0, Integer.MAX_VALUE);
    public final ValueString cameraId = new ValueString("camera", "");

    public CameraCut(String id)
    {
        super(id);
        this.add(this.tick);
        this.add(this.cameraId);
    }

    public CameraCut(String id, int tick, String cameraId)
    {
        this(id);
        this.tick.set(tick);
        this.cameraId.set(cameraId == null || cameraId.isEmpty() ? FilmCameraResolver.LEGACY_CAMERA_ID : cameraId);
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
