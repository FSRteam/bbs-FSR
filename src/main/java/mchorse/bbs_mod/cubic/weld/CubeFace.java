package mchorse.bbs_mod.cubic.weld;

import org.joml.Vector3f;

/**
 * One of the six box faces of a cube, identified by the local normal baked into its quads. Lets a weld
 * name a side ("top", "bottom", ...) and the resolver find the matching quad by comparing normals.
 */
public enum CubeFace
{
    FRONT(0, 0, -1),
    BACK(0, 0, 1),
    RIGHT(1, 0, 0),
    LEFT(-1, 0, 0),
    TOP(0, 1, 0),
    BOTTOM(0, -1, 0);

    public final Vector3f normal;

    CubeFace(float x, float y, float z)
    {
        this.normal = new Vector3f(x, y, z);
    }

    public static CubeFace fromName(String name)
    {
        return fromCanonical(mchorse.bbs_mod.cubic.data.model.CubeFace.fromName(name));
    }

    public mchorse.bbs_mod.cubic.data.model.CubeFace toCanonical()
    {
        return mchorse.bbs_mod.cubic.data.model.CubeFace.valueOf(this.name());
    }

    public static CubeFace fromCanonical(mchorse.bbs_mod.cubic.data.model.CubeFace face)
    {
        return face == null ? null : valueOf(face.name());
    }
}
