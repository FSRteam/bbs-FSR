package mchorse.bbs_mod.settings.values.misc;

import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import org.joml.Vector3f;

public class ValueVector3f extends BaseValueBasic<Vector3f>
{
    public ValueVector3f(String id, Vector3f value)
    {
        super(id, value);
    }

    @Override
    protected Vector3f copyValue(Vector3f value)
    {
        return value == null ? null : new Vector3f(value);
    }

    @Override
    public BaseType toData()
    {
        return DataStorageUtils.vector3fToData(this.value);
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data.isList())
        {
            this.value = DataStorageUtils.vector3fFromData(data.asList());
        }
    }
}
