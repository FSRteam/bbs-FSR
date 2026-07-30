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

    /**
     * Model pixels make a legitimate translate Jacobian roughly {@code det(basis) / 16³} large, so a
     * plain "almost singular" threshold would reject perfectly usable bone frames. Condition the
     * matrix on its column lengths instead, which is scale free.
     */
    private static final float CONDITION_EPSILON = 1.0E-4F;

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
    public final Matrix3f rotate2Axes = new Matrix3f();
    public final Matrix3f rotateParentInverse = new Matrix3f();
    public final Matrix3f rotate2ParentInverse = new Matrix3f();
    public boolean hasRotateParentInverse;
    public boolean hasRotate2ParentInverse;
    /** The edited translation is in model pixels (a vanilla ModelPart bone) rather than in blocks. */
    public boolean modelPartTranslate;
    public final Vector3f rotationOffset = new Vector3f();

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
        drag.rotate2Axes.set(drag.gizmoWorldAxes);

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

    public GizmoDrag modelPartTranslate(boolean enabled)
    {
        this.modelPartTranslate = enabled;

        return this;
    }

    public GizmoDrag setRotateAxes(Matrix3f axes)
    {
        this.rotateAxes.set(axes);

        return this;
    }

    public GizmoDrag setRotate2Axes(Matrix3f axes)
    {
        this.rotate2Axes.set(axes);

        return this;
    }

    public GizmoDrag setRotationOffset(Vector3f offset)
    {
        this.rotationOffset.set(offset == null ? new Vector3f() : offset);

        return this;
    }

    public GizmoDrag setRotationParents(Transform transform, Vector3f offset, Supplier<Matrix4f> matrixSampler)
    {
        this.setRotationOffset(offset);
        this.hasRotateParentInverse = computeRotationParentInverse(
            transform,
            this.rotationOffset,
            false,
            matrixSampler,
            this.rotateParentInverse
        );
        this.hasRotate2ParentInverse = computeRotationParentInverse(
            transform,
            this.rotationOffset,
            true,
            matrixSampler,
            this.rotate2ParentInverse
        );

        return this;
    }

    private static boolean computeRotationParentInverse(
        Transform transform,
        Vector3f offset,
        boolean secondary,
        Supplier<Matrix4f> matrixSampler,
        Matrix3f out
    )
    {
        Vector3f rotation = secondary ? transform.rotate2 : transform.rotate;
        Vector3f neutral = secondary
            ? new Vector3f()
            : new Vector3f(offset == null ? new Vector3f() : offset).negate();
        Matrix3f parentAxes = computeRotateAxes(rotation, neutral, matrixSampler, false);

        if (Math.abs(parentAxes.determinant()) < 1.0E-4F)
        {
            out.zero();

            return false;
        }

        out.set(parentAxes).invert();

        return true;
    }

    public static Matrix3f computeTranslateJacobian(Transform transform, Supplier<Vector3f> worldPositionSampler)
    {
        Vector3f saved = new Vector3f(transform.translate);
        float epsilon = 1F;

        try
        {
            /* Sample around the live pose rather than from the rest translation. Feature layers
             * (notably humanoid equipment and head models) can change their model state based on
             * the current pose, so a rest-pose sample is not a valid local derivative. */
            Vector3f origin = new Vector3f(worldPositionSampler.get());

            transform.translate.set(saved.x + epsilon, saved.y, saved.z);
            Vector3f cx = new Vector3f(worldPositionSampler.get()).sub(origin).div(epsilon);

            transform.translate.set(saved.x, saved.y + epsilon, saved.z);
            Vector3f cy = new Vector3f(worldPositionSampler.get()).sub(origin).div(epsilon);

            transform.translate.set(saved.x, saved.y, saved.z + epsilon);
            Vector3f cz = new Vector3f(worldPositionSampler.get()).sub(origin).div(epsilon);

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

    /**
     * ModelPart translations are expressed in model pixels and are applied before the part's own
     * rotation. The origin matrix therefore already contains the complete parent-space basis; its
     * translation is irrelevant and each basis column only needs Minecraft's 1/16 conversion.
     *
     * <p>Unlike {@link #computeTranslateJacobian} this needs no perturbation sampling at all, so it
     * cannot be blurred by keyframe interpolation weights, pose overlays or equipment feature layers
     * re-entering the render between samples.</p>
     */
    public static Matrix3f computeModelPartTranslateJacobian(Matrix4f origin)
    {
        return origin.get3x3(new Matrix3f()).scale(1F / 16F);
    }

    /**
     * Whether a translate Jacobian can be inverted meaningfully. The test is on the matrix'
     * condition rather than on its raw determinant: model-pixel frames are legitimately three orders
     * of magnitude smaller than block-space ones, and a determinant cut-off rejects them wholesale.
     */
    public static boolean isUsableTranslateJacobian(Matrix3f jacobian)
    {
        float determinant = jacobian.determinant();

        if (!Float.isFinite(determinant))
        {
            return false;
        }

        float volume = 1F;

        for (int i = 0; i < 3; i++)
        {
            float length = jacobian.getColumn(i, new Vector3f()).length();

            if (!Float.isFinite(length) || length < 1.0E-8F)
            {
                return false;
            }

            volume *= length;
        }

        return Math.abs(determinant) >= volume * CONDITION_EPSILON;
    }

    /**
     * The translate Jacobian to actually drag with: the sampled one when it is invertible, otherwise
     * a rest basis in the same units. Falling back keeps a gizmo usable (slightly off, but live)
     * where bailing out would silently freeze the handle - see {@link #computeModelPartTranslateJacobian}.
     */
    public static Matrix3f resolveTranslateJacobian(Matrix3f jacobian, boolean modelPart)
    {
        if (jacobian != null && isUsableTranslateJacobian(jacobian))
        {
            return new Matrix3f(jacobian);
        }

        return modelPart ? new Matrix3f().scale(1F / 16F) : new Matrix3f();
    }

    public static Matrix3f computeRotateAxes(Transform transform, Supplier<Matrix4f> matrixSampler)
    {
        return computeRotateAxes(transform, false, matrixSampler);
    }

    public static Matrix3f computeRotateAxes(Transform transform, boolean secondary, Supplier<Matrix4f> matrixSampler)
    {
        Vector3f rotation = secondary ? transform.rotate2 : transform.rotate;

        return computeRotateAxes(rotation, new Vector3f(rotation), matrixSampler, true);
    }

    private static Matrix3f computeRotateAxes(
        Vector3f rotation,
        Vector3f baseRotation,
        Supplier<Matrix4f> matrixSampler,
        boolean fallbackIdentity
    )
    {
        Vector3f saved = new Vector3f(rotation);
        float delta = 0.05F;

        try
        {
            rotation.set(baseRotation);

            Matrix4f sampled = matrixSampler.get();

            if (sampled == null)
            {
                return new Matrix3f().zero();
            }

            Matrix3f base = new Matrix3f();

            sampled.get3x3(base);

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
                rotation.set(baseRotation);

                if (i == 0) rotation.x += delta;
                else if (i == 1) rotation.y += delta;
                else rotation.z += delta;

                Matrix4f sample = matrixSampler.get();

                if (sample == null)
                {
                    return new Matrix3f().zero();
                }

                sample.get3x3(perturbed);
                relative.set(perturbed).mul(baseInverse);

                col.set(
                    relative.m12 - relative.m21,
                    relative.m20 - relative.m02,
                    relative.m01 - relative.m10
                );

                float lenSq = col.lengthSquared();

                if (lenSq < 1.0E-12F)
                {
                    if (!fallbackIdentity)
                    {
                        return new Matrix3f().zero();
                    }

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
            rotation.set(saved);
        }
    }
}
