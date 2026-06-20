package mchorse.bbs_mod.cubic.constraints;

import java.util.Map;

public record ModelConstraintsConfig(Map<String, BoneConstraint> bones)
{
    public record BoneConstraint(boolean enabled, float minX, float minY, float minZ, float maxX, float maxY, float maxZ)
    {}
}
