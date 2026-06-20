package mchorse.bbs_mod.cubic.constraints;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class RotationMath
{
    private RotationMath()
    {
    }

    public static Quaternionf toQuaternionZYXDegrees(float xDeg, float yDeg, float zDeg)
    {
        float x = (float) Math.toRadians(xDeg);
        float y = (float) Math.toRadians(yDeg);
        float z = (float) Math.toRadians(zDeg);

        return new Quaternionf().rotationZYX(z, y, x);
    }

    public static Vector3f toEulerZYXDegrees(Quaternionf q)
    {
        Vector3f radZYX = new Vector3f();

        new Quaternionf(q).normalize().getEulerAnglesZYX(radZYX);

        return radZYX.mul((float) (180.0 / Math.PI));
    }

    public static Quaternionf fromToMirroredX(Vector3f restDirLocal, Vector3f desiredDirLocal)
    {
        Vector3f rest = new Vector3f(restDirLocal);
        Vector3f desired = new Vector3f(desiredDirLocal);

        rest.x = -rest.x;
        desired.x = -desired.x;

        rest.normalize();
        desired.normalize();

        Quaternionf mirrored = new Quaternionf().rotationTo(rest, desired);
        Matrix3f mirroredMatrix = new Matrix3f().set(mirrored);
        Matrix3f mirror = new Matrix3f().scaling(-1F, 1F, 1F);
        Matrix3f matrix = new Matrix3f(mirror).mul(mirroredMatrix).mul(mirror);

        return new Quaternionf().setFromNormalized(matrix);
    }

    public static Quaternionf twistAbout(Quaternionf q, Vector3f axis)
    {
        float dot = q.x * axis.x + q.y * axis.y + q.z * axis.z;
        Quaternionf twist = new Quaternionf(axis.x * dot, axis.y * dot, axis.z * dot, q.w);

        if (twist.lengthSquared() < 1.0e-12f)
        {
            return new Quaternionf();
        }

        return twist.normalize();
    }
}
