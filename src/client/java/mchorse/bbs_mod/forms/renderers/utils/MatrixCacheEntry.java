package mchorse.bbs_mod.forms.renderers.utils;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public record MatrixCacheEntry(Matrix4f matrix, Matrix4f origin, Vector3f rotationOffset, Vector3f evaluatedRotation)
{
    public MatrixCacheEntry(Matrix4f matrix, Matrix4f origin)
    {
        this(matrix, origin, null, null);
    }

    public MatrixCacheEntry(Matrix4f matrix, Matrix4f origin, Vector3f rotationOffset)
    {
        this(matrix, origin, rotationOffset, null);
    }
}
