package mchorse.bbs_mod.cubic.constraints;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.HashMap;
import java.util.Map;

public final class ModelConstraintsIO
{
    private static final float DEFAULT_MIN = -180F;
    private static final float DEFAULT_MAX = 180F;

    private ModelConstraintsIO()
    {
    }

    public static ModelConstraintsConfig fromData(MapType root)
    {
        if (root == null || !root.has("bones", BaseType.TYPE_MAP))
        {
            return null;
        }

        MapType bones = root.getMap("bones");
        Map<String, ModelConstraintsConfig.BoneConstraint> out = new HashMap<>();

        for (String key : bones.keys())
        {
            if (!bones.has(key, BaseType.TYPE_MAP))
            {
                continue;
            }

            MapType entry = bones.getMap(key);

            if (!entry.getBool("enabled", true))
            {
                continue;
            }

            float minX = DEFAULT_MIN;
            float minY = DEFAULT_MIN;
            float minZ = DEFAULT_MIN;
            float maxX = DEFAULT_MAX;
            float maxY = DEFAULT_MAX;
            float maxZ = DEFAULT_MAX;

            if (entry.has("min", BaseType.TYPE_LIST))
            {
                ListType list = entry.getList("min");
                minX = getFloat(list, 0, minX);
                minY = getFloat(list, 1, minY);
                minZ = getFloat(list, 2, minZ);
            }

            if (entry.has("max", BaseType.TYPE_LIST))
            {
                ListType list = entry.getList("max");
                maxX = getFloat(list, 0, maxX);
                maxY = getFloat(list, 1, maxY);
                maxZ = getFloat(list, 2, maxZ);
            }

            out.put(key, new ModelConstraintsConfig.BoneConstraint(true, minX, minY, minZ, maxX, maxY, maxZ));
        }

        return out.isEmpty() ? null : new ModelConstraintsConfig(out);
    }

    public static MapType toData(ModelConstraintsConfig config)
    {
        MapType root = new MapType();
        MapType bones = new MapType();

        if (config != null && config.bones() != null)
        {
            for (Map.Entry<String, ModelConstraintsConfig.BoneConstraint> entry : config.bones().entrySet())
            {
                String bone = entry.getKey();
                ModelConstraintsConfig.BoneConstraint constraint = entry.getValue();

                if (bone == null || bone.isEmpty() || constraint == null || !constraint.enabled())
                {
                    continue;
                }

                MapType map = new MapType();
                ListType min = new ListType();
                ListType max = new ListType();

                map.putBool("enabled", true);
                min.addFloat(constraint.minX());
                min.addFloat(constraint.minY());
                min.addFloat(constraint.minZ());
                max.addFloat(constraint.maxX());
                max.addFloat(constraint.maxY());
                max.addFloat(constraint.maxZ());
                map.put("min", min);
                map.put("max", max);
                bones.put(bone, map);
            }
        }

        root.put("bones", bones);

        return root;
    }

    private static float getFloat(ListType list, int index, float fallback)
    {
        BaseType element = list == null ? null : list.get(index);

        return BaseType.isNumeric(element) ? element.asNumeric().floatValue() : fallback;
    }
}
