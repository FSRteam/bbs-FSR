package mchorse.bbs_mod.forms.forms.utils;

import mchorse.bbs_mod.cubic.ik.BoneIKIO;
import mchorse.bbs_mod.cubic.physics.BonePhysicsIO;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.core.ValueWindControl;

import java.util.Objects;

/**
 * Compatibility view of the old IK/physics blob. The bone tree is the only stored state;
 * existing panels, presets and addon callers can still exchange the old map shape.
 */
public final class ValueBoneConfig extends ValueData
{
    private final ValueBones bones;
    private final ValueWindControl wind;
    private long revision = Long.MIN_VALUE;
    private WindControl previousWind;

    public ValueBoneConfig(String id, ValueBones bones, ValueWindControl wind)
    {
        super(id);
        this.bones = bones;
        this.wind = wind;
    }

    @Override
    public BaseType getOriginalValue()
    {
        WindControl currentWind = this.wind == null ? null : this.wind.getOriginalValue();

        if (this.revision != this.bones.getRevision()
            || currentWind != null && !currentWind.equals(this.previousWind))
        {
            MapType data = this.wind == null ? BoneIKIO.write(this.bones) : BonePhysicsIO.write(this.bones, this.wind);

            this.value = data.isEmpty() ? null : data;
            this.revision = this.bones.getRevision();
            this.previousWind = currentWind == null ? null : currentWind.copy();
        }

        return this.value;
    }

    @Override
    public BaseType get()
    {
        return this.runtimeValue == null ? this.getOriginalValue() : this.runtimeValue;
    }

    @Override
    public BaseType toData()
    {
        BaseType data = this.getOriginalValue();

        return data == null ? null : data.copy();
    }

    @Override
    public boolean isDefault()
    {
        return this.getOriginalValue() == null;
    }

    @Override
    public boolean equals(Object object)
    {
        return object instanceof ValueData data
            && Objects.equals(this.getOriginalValue(), data.getOriginalValue());
    }

    @Override
    public void set(BaseType data, int flag)
    {
        this.preNotify(flag);
        this.fromData(data);
        this.postNotify(flag);
    }

    @Override
    public void fromData(BaseType data)
    {
        /* One legacy panel edit is one value edit. Internal field writes must not become
         * separate undo/network mutations while replacing this complete configuration. */
        BaseValue bonesParent = this.bones.getParent();
        BaseValue windParent = this.wind == null ? null : this.wind.getParent();

        this.bones.setParent(null);
        if (this.wind != null) this.wind.setParent(null);

        try
        {
            MapType map = data instanceof MapType value ? value : null;

            if (this.wind == null) BoneIKIO.read(map, this.bones, true);
            else BonePhysicsIO.read(map, this.bones, this.wind, true);
        }
        finally
        {
            this.bones.setParent(bonesParent);
            if (this.wind != null) this.wind.setParent(windParent);
            this.revision = Long.MIN_VALUE;
        }
    }
}
