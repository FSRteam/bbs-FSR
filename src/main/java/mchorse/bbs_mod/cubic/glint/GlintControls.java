package mchorse.bbs_mod.cubic.glint;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.MapType;

import java.util.LinkedHashMap;
import java.util.Map;

/** One standalone enchantment-layer keyframe value, keyed by stable bone ID. */
public class GlintControls implements IMapSerializable
{
    public final Map<String, GlintControl> controls = new LinkedHashMap<>();

    public GlintControl get(String bone)
    {
        return this.controls.computeIfAbsent(bone, (key) -> new GlintControl());
    }

    public GlintControls copy()
    {
        GlintControls controls = new GlintControls();

        controls.copy(this);

        return controls;
    }

    public void copy(GlintControls other)
    {
        this.controls.clear();

        for (Map.Entry<String, GlintControl> entry : other.controls.entrySet())
        {
            this.controls.put(entry.getKey(), entry.getValue().copy());
        }
    }

    @Override
    public void toData(MapType data)
    {
        MapType glint = new MapType();

        for (Map.Entry<String, GlintControl> entry : this.controls.entrySet())
        {
            glint.put(entry.getKey(), entry.getValue().toData());
        }

        data.put("glint", glint);
    }

    @Override
    public void fromData(MapType data)
    {
        this.controls.clear();

        MapType glint = data.getMap("glint");

        for (String bone : glint.keys())
        {
            GlintControl control = new GlintControl();

            control.fromData(glint.getMap(bone));
            this.controls.put(bone, control);
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        return this == obj || obj instanceof GlintControls controls && this.controls.equals(controls.controls);
    }
}
