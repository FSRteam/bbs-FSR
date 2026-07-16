package mchorse.bbs_mod.utils.joml;

import org.joml.Matrix3d;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Matrices
{
    public static final Matrix3f EMPTY_3F = new Matrix3f();
    public static final Matrix3d EMPTY_3D = new Matrix3d();
    public static final Matrix4f EMPTY_4F = new Matrix4f();
    public static final Matrix4d EMPTY_4D = new Matrix4d();

    /* Temporary matrices that can be used to avoid allocations */
    public static final Matrix3f TEMP_3F = new Matrix3f();
    public static final Matrix3d TEMP_3D = new Matrix3d();
    public static final Matrix4f TEMP_4F = new Matrix4f();
    public static final Matrix4d TEMP_4D = new Matrix4d();

    private static final Matrix3f rotation = new Matrix3f();
    private static final Vector3f forward = new Vector3f();

    private static final Matrix3f lerpA = new Matrix3f();
    private static final Matrix3f lerpB = new Matrix3f();
    private static final Quaternionf lerpQa = new Quaternionf();
    private static final Quaternionf lerpQb = new Quaternionf();
    private static final Vector3f lerpVa = new Vector3f();
    private static final Vector3f lerpVb = new Vector3f();

    public static Vector3f rotate(Vector3f vector, float pitch, float yaw)
    {
        rotation.identity();
        rotation.rotateY(yaw);
        rotation.rotateX(pitch);
        rotation.transform(vector);

        return vector;
    }

    public static Vector3f rotation(float pitch, float yaw)
    {
        return rotate(forward.set(0, 0, 1), pitch, yaw);
    }

    public static Matrix3f direction(Vector3f forward)
    {
        return direction(forward, new Matrix3f(), new Vector3f(), new Vector3f());
    }

    public static Matrix3f direction(Vector3f forward, Matrix3f direction, Vector3f right, Vector3f up)
    {
        direction.identity();
        right.set(0, 1, 0);
        up.set(forward);

        if (right.equals(forward))
        {
            right.set(1, 0, 0);
        }

        right.cross(forward);
        up.cross(right);

        direction.m00 = right.x;
        direction.m01 = right.y;
        direction.m02 = right.z;
        direction.m10 = forward.x;
        direction.m11 = forward.y;
        direction.m12 = forward.z;
        direction.m20 = up.x;
        direction.m21 = up.y;
        direction.m22 = up.z;

        return direction;
    }

    public static Matrix4f lerp(Matrix4f a, Matrix4f b, float t)
    {
        return lerp(a, b, t, new Matrix4f());
    }

    public static Matrix4f lerp(Matrix4f a, Matrix4f b, float t, Matrix4f dest)
    {
        Quaternionf q1 = lerpQa.setFromNormalized(lerpA.set(a));
        Quaternionf q2 = lerpQb.setFromNormalized(lerpB.set(b));

        q1.slerp(q2, t);

        dest.identity().rotate(q1);
        dest.setTranslation(a.getTranslation(lerpVa).lerp(b.getTranslation(lerpVb), t));

        return dest;
    }

    public static String toString(Matrix3f m)
    {
        return m.m00() + ", " + m.m10() + ", " + m.m20() + "\n" +
            m.m01() + ", " + m.m11() + ", " + m.m21() + "\n" +
            m.m02() + ", " + m.m12() + ", " + m.m22();
    }

    public static String toString(Matrix4f m)
    {
        return m.m00() + ", " + m.m10() + ", " + m.m20() + ", " + m.m30() + "\n" +
            m.m01() + ", " + m.m11() + ", " + m.m21() + ", " + m.m31() + "\n" +
            m.m02() + ", " + m.m12() + ", " + m.m22() + ", " + m.m32() + "\n" +
            m.m03() + ", " + m.m13() + ", " + m.m23() + ", " + m.m33() + "\n";
    }

    public static Vector3f getEulerXYZ(Matrix3f m)
    {
        double yaw = Math.atan2(m.m02, Math.sqrt(m.m00 * m.m00 + m.m01 * m.m01));
        double pitch = Math.atan2(m.m12, m.m22);
        double roll = Math.atan2(m.m01, m.m00);

        if (m.m00 < 0)
        {
            yaw = -yaw + Math.PI;
            pitch = -pitch;
        }
        else
        {
            yaw += Math.PI;
        }

        return new Vector3f((float) pitch, (float) yaw, (float) roll);
    }

    public static Quaternionf toQuaternionZYXDegrees(float xDeg, float yDeg, float zDeg)
    {
        float x = (float) Math.toRadians(xDeg);
        float y = (float) Math.toRadians(yDeg);
        float z = (float) Math.toRadians(zDeg);

        return new Quaternionf().rotationZYX(z, y, x);
    }

    public static Quaternionf toQuaternionZYXRadians(float x, float y, float z)
    {
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

    /**
     * The local rotation taking the rest frame {@code (restDir, restNormal)} to the
     * target frame {@code (toDir, toNormal)}, where each frame is an orthonormal
     * basis with its first axis along the direction and its roll fixed by the normal.
     * Unlike {@link #fromToMirroredX} — which only aligns the directions and picks the
     * shortest arc for the roll (so it flips at a 180° swing) — the normal pins the
     * roll, so the result never flips and carries a defined twist (the IK bend plane).
     *
     * <p>Both frames are built in the cubic model's X-mirrored space and the result is
     * conjugated back, exactly as {@link #fromToMirroredX} does, so the cubic geometry
     * bends the correct way. With {@code toDir == restDir} and {@code toNormal} sharing
     * {@code restNormal}'s plane the result is identity (no baseline twist at rest).
     */
    public static Quaternionf orientMirroredX(Vector3f restDir, Vector3f restNormal, Vector3f toDir, Vector3f toNormal)
    {
        return orientMirroredX(restDir, restNormal, toDir, toNormal, new Quaternionf(), new Matrix3f(), new Matrix3f(), new Matrix3f(), new Vector3f(), new Vector3f(), new Vector3f());
    }

    /** Allocation-free form for render/solver hot paths; all destination and scratch objects are caller-owned. */
    public static Quaternionf orientMirroredX(Vector3f restDir, Vector3f restNormal, Vector3f toDir, Vector3f toNormal, Quaternionf out, Matrix3f rest, Matrix3f to, Matrix3f rotation, Vector3f u, Vector3f v, Vector3f w)
    {
        mirroredFrame(restDir, restNormal, rest, u, v, w);
        mirroredFrame(toDir, toNormal, to, u, v, w);
        rotation.set(to).mul(rest.transpose());

        /* Conjugate by mirror(-X) without constructing two diagonal matrices. */
        rotation.m01 = -rotation.m01;
        rotation.m02 = -rotation.m02;
        rotation.m10 = -rotation.m10;
        rotation.m20 = -rotation.m20;

        return out.setFromNormalized(rotation);
    }

    /**
     * Orthonormal right-handed basis in X-mirrored space: first column along the
     * mirrored direction, the mirrored normal fixing the roll (its perpendicular
     * component). Falls back to an arbitrary perpendicular when the normal is parallel
     * to the direction (degenerate roll).
     */
    private static void mirroredFrame(Vector3f dir, Vector3f normal, Matrix3f out, Vector3f u, Vector3f v, Vector3f w)
    {
        u.set(-dir.x, dir.y, dir.z).normalize();
        w.set(u).cross(-normal.x, normal.y, normal.z);

        if (w.lengthSquared() < 1.0e-12f)
        {
            v.set(Math.abs(u.x) < 0.9F ? 1F : 0F, Math.abs(u.x) < 0.9F ? 0F : 1F, 0F);

            w.set(u).cross(v);
        }

        w.normalize();

        v.set(w).cross(u);

        out.m00 = u.x; out.m01 = u.y; out.m02 = u.z;
        out.m10 = v.x; out.m11 = v.y; out.m12 = v.z;
        out.m20 = w.x; out.m21 = w.y; out.m22 = w.z;
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
