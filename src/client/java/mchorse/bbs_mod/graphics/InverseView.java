package mchorse.bbs_mod.graphics;

import org.joml.Matrix3f;

/**
 * Holds the inverse of the active view rotation for shaders that still expose
 * {@code ViewRotationMat}. Minecraft 1.21.1 no longer keeps this matrix in
 * {@code RenderSystem}, so each render scope supplies its own camera rotation.
 */
public class InverseView
{
    private static final Matrix3f matrix = new Matrix3f();

    public static Matrix3f get()
    {
        return matrix;
    }

    public static void set(Matrix3f value)
    {
        matrix.set(value);
    }
}
