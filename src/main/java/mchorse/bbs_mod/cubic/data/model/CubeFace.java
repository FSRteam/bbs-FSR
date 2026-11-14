package mchorse.bbs_mod.cubic.data.model;

import org.joml.Vector3f;

import java.util.Locale;

/** The six cube faces shared by geometry, UV editing and weld resolution. */
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
        if (name == null) return null;

        try
        {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception)
        {
            return null;
        }
    }
}
