package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * Snapshot of camera, viewport and gizmo placement captured at drag start.
 */
public class GizmoDrag
{
    private static final float PARALLEL_EPSILON = 1.0E-4F;

    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f view = new Matrix4f();
    public final Vector3d cameraOrigin = new Vector3d();

    public int viewportX;
    public int viewportY;
    public int viewportW;
    public int viewportH;

    public final Vector3d gizmoOrigin = new Vector3d();
    public final Matrix3f translateJacobian = new Matrix3f();
    public final Matrix3f gizmoWorldAxes = new Matrix3f();
    public final Matrix3f rotateAxes = new Matrix3f();

    public GizmoDrag setup(Camera camera, Area viewport, Vector3f gizmoOrigin)
    {
        return this.setup(camera, viewport, gizmoOrigin.x, gizmoOrigin.y, gizmoOrigin.z);
    }

    public GizmoDrag setup(Camera camera, Area viewport, Vector3d gizmoOrigin)
    {
        return this.setup(camera, viewport, gizmoOrigin.x, gizmoOrigin.y, gizmoOrigin.z);
    }

    public static GizmoDrag fromRenderedGizmo(Camera camera, Area viewport)
    {
        Vector3d origin = new Vector3d();

        if (!Gizmo.INSTANCE.computeWorldOrigin(camera, origin))
        {
            return null;
        }

        GizmoDrag drag = new GizmoDrag().setup(camera, viewport, origin);

        Gizmo.INSTANCE.computeWorldAxes(camera, drag.gizmoWorldAxes);
        drag.rotateAxes.set(drag.gizmoWorldAxes);

        return drag;
    }

    public GizmoDrag setup(Camera camera, Area viewport, double gx, double gy, double gz)
    {
        this.projection.set(camera.projection);
        this.view.set(camera.view);
        this.cameraOrigin.set(camera.position);

        this.viewportX = viewport.x;
        this.viewportY = viewport.y;
        this.viewportW = viewport.w;
        this.viewportH = viewport.h;

        this.gizmoOrigin.set(gx, gy, gz);

        return this;
    }

    public Vector3f rayDirection(int mouseX, int mouseY, Vector3f out)
    {
        Vector3f dir = CameraUtils.getMouseDirection(this.projection, this.view, mouseX, mouseY, this.viewportX, this.viewportY, this.viewportW, this.viewportH);

        return out.set(dir).normalize();
    }

    public boolean projectToScreen(Vector3d world, Vector2f out)
    {
        return this.projectToScreen(world.x, world.y, world.z, out);
    }

    public boolean projectToScreen(double wx, double wy, double wz, Vector2f out)
    {
        Vector4f clip = new Vector4f(
            (float) (wx - this.cameraOrigin.x),
            (float) (wy - this.cameraOrigin.y),
            (float) (wz - this.cameraOrigin.z),
            1F
        );

        new Matrix4f(this.projection).mul(this.view).transform(clip);

        if (clip.w <= PARALLEL_EPSILON)
        {
            return false;
        }

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;

        out.x = this.viewportX + (ndcX + 1F) * (this.viewportW / 2F);
        out.y = this.viewportY + (1F - ndcY) * (this.viewportH / 2F);

        return true;
    }

    public boolean intersectPlane(int mouseX, int mouseY, Vector3f planeNormal, Vector3d out)
    {
        Vector3f dir = this.rayDirection(mouseX, mouseY, new Vector3f());
        double denom = dir.x * planeNormal.x + dir.y * planeNormal.y + dir.z * planeNormal.z;

        if (Math.abs(denom) < PARALLEL_EPSILON)
        {
            return false;
        }

        double t = ((this.gizmoOrigin.x - this.cameraOrigin.x) * planeNormal.x
            + (this.gizmoOrigin.y - this.cameraOrigin.y) * planeNormal.y
            + (this.gizmoOrigin.z - this.cameraOrigin.z) * planeNormal.z) / denom;

        if (t <= 0D)
        {
            return false;
        }

        out.set(this.cameraOrigin.x + dir.x * t, this.cameraOrigin.y + dir.y * t, this.cameraOrigin.z + dir.z * t);

        return true;
    }

    public Vector3f planeNormalForAxis(int mouseX, int mouseY, Matrix3f basis, Axis axis, Vector3f out)
    {
        Vector3f axisDir = basis.getColumn(axis.ordinal(), new Vector3f());
        Vector3f viewDir = this.rayDirection(mouseX, mouseY, new Vector3f());
        Vector3f temp = new Vector3f();

        axisDir.cross(viewDir, temp);
        temp.cross(axisDir, out);

        if (out.lengthSquared() < PARALLEL_EPSILON)
        {
            Vector3f fallback = Math.abs(axisDir.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);

            axisDir.cross(fallback, temp);
            temp.cross(axisDir, out);
        }

        return out.normalize();
    }

    public Vector3f planeNormalForPlane(Matrix3f basis, Axis axisA, Axis axisB, Vector3f out)
    {
        Vector3f a = basis.getColumn(axisA.ordinal(), new Vector3f());
        Vector3f b = basis.getColumn(axisB.ordinal(), new Vector3f());

        return a.cross(b, out).normalize();
    }

    public GizmoDrag setJacobian(Matrix3f jacobian)
    {
        this.translateJacobian.set(jacobian);

        return this;
    }

    public GizmoDrag setRotateAxes(Matrix3f axes)
    {
        this.rotateAxes.set(axes);

        return this;
    }

    public static Matrix3f computeTranslateJacobian(Transform transform, Supplier<Vector3f> worldPositionSampler)
    {
        Vector3f saved = new Vector3f(transform.translate);

        try
        {
            transform.translate.set(0F, 0F, 0F);
            Vector3f origin = new Vector3f(worldPositionSampler.get());

            transform.translate.set(1F, 0F, 0F);
            Vector3f cx = new Vector3f(worldPositionSampler.get()).sub(origin);

            transform.translate.set(0F, 1F, 0F);
            Vector3f cy = new Vector3f(worldPositionSampler.get()).sub(origin);

            transform.translate.set(0F, 0F, 1F);
            Vector3f cz = new Vector3f(worldPositionSampler.get()).sub(origin);

            return new Matrix3f(
                cx.x, cx.y, cx.z,
                cy.x, cy.y, cy.z,
                cz.x, cz.y, cz.z
            );
        }
        finally
        {
            transform.translate.set(saved);
        }
    }

    public static Matrix3f computeRotateAxes(Transform transform, Supplier<Matrix4f> matrixSampler)
    {
        Vector3f saved = new Vector3f(transform.rotate);
        float delta = 0.05F;

        try
        {
            Matrix3f base = new Matrix3f();

            matrixSampler.get().get3x3(base);

            Matrix3f baseInverse = new Matrix3f(base);

            if (Math.abs(baseInverse.determinant()) < 1.0E-8F)
            {
                return new Matrix3f();
            }

            baseInverse.invert();

            Matrix3f axes = new Matrix3f();
            Vector3f col = new Vector3f();
            Matrix3f perturbed = new Matrix3f();
            Matrix3f relative = new Matrix3f();

            for (int i = 0; i < 3; i++)
            {
                transform.rotate.set(saved);

                if (i == 0) transform.rotate.x += delta;
                else if (i == 1) transform.rotate.y += delta;
                else transform.rotate.z += delta;

                matrixSampler.get().get3x3(perturbed);
                relative.set(perturbed).mul(baseInverse);

                col.set(
                    relative.m12 - relative.m21,
                    relative.m20 - relative.m02,
                    relative.m01 - relative.m10
                );

                float lenSq = col.lengthSquared();

                if (lenSq < 1.0E-12F)
                {
                    col.set(i == 0 ? 1F : 0F, i == 1 ? 1F : 0F, i == 2 ? 1F : 0F);
                }
                else
                {
                    col.div((float) Math.sqrt(lenSq));
                }

                axes.setColumn(i, col);
            }

            return axes;
        }
        finally
        {
            transform.rotate.set(saved);
        }
    }
}
