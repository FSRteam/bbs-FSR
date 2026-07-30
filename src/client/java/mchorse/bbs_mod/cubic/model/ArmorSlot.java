package mchorse.bbs_mod.cubic.model;

import mchorse.bbs_mod.data.IDataSerializable;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.pose.Transform;

public class ArmorSlot implements IDataSerializable
{
    public String group = "";
    public final Transform transform = new Transform();

    /* Optional lower bone for bending armor. When it's set (or inferred from the
     * bone naming convention), armor vertices get skinned between this slot's
     * group and the lower bone, so the armor bends along with the limb instead
     * of staying rigid. Empty means the armor renders rigidly on the group. */
    public String lowerGroup = "";

    /* Vertical range (in model pixels, same scale as ModelPart.Cube's minY/maxY)
     * over which the skinning weight transitions from the group to the lower
     * bone. Below the start it's fully the group, above the end fully the lower
     * bone, in between it interpolates linearly. An empty range means the slot
     * didn't specify one and the armor type's default applies. */
    public float bendStart;
    public float bendEnd;

    public boolean hasLowerGroup()
    {
        return !this.lowerGroup.isEmpty();
    }

    public boolean hasBendRange()
    {
        return this.bendEnd > this.bendStart;
    }

    @Override
    public BaseType toData()
    {
        /* Unnecessary yet */
        return new MapType();
    }

    @Override
    public void fromData(BaseType data)
    {
        if (data.isString())
        {
            this.transform.identity();
            this.group = data.asString();
            this.lowerGroup = "";
            this.bendStart = 0F;
            this.bendEnd = 0F;
        }
        else if (data.isMap())
        {
            MapType map = data.asMap();

            this.transform.fromData(map.getMap("transform"));
            this.transform.toRad();
            this.group = map.getString("group");
            this.lowerGroup = map.getString("lower_group", "");
            this.bendStart = map.getFloat("bend_start", 0F);
            this.bendEnd = map.getFloat("bend_end", 0F);
        }
    }
}
