package mchorse.bbs_mod.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class CameraUtils
{
    public static Vector3f getMouseDirection(Matrix4f projection, Matrix4f view, int mx, int my, int vx, int vy, int vw, int vh)
    {
        mx -= vx;
        my -= vy;

        float w2 = vw / 2F;
        float h2 = vh / 2F;

        float x = (mx - w2) / w2;
        float y = (-my + h2) / h2;

        return getMouseDirection(projection, view, x, y);
    }

    public static Vector3f getMouseDirection(Matrix4f projection, Matrix4f view, float mx, float my)
    {
        Matrix4f matrix4f = new Matrix4f(projection);

        matrix4f.mul(view);
        matrix4f.invert();

        Vector4f forward = new Vector4f(mx, my, 0, 1);

        matrix4f.transform(forward);

        return new Vector3f(forward.x, forward.y, forward.z);
    }

    /**
     * Compute the picking ray for a mouse position, correct for both perspective
     * and orthographic projections. The ray is strung between the unprojected
     * near and far plane points: under a perspective projection the origin
     * offset is a negligible nudge onto the near plane, while under an
     * orthographic one it carries the pixel's lateral shift (there, the
     * direction is the same for every pixel and the origin does all the work).
     *
     * The view matrix is expected to be rotation-only (the BBS convention), so
     * the origin offset comes out relative to the camera position &mdash; add it
     * to the camera position to get the ray's world-space origin.
     *
     * @return the normalized ray direction; {@code originOffset} is filled in.
     */
    public static Vector3f getMouseRay(Matrix4f projection, Matrix4f view, int mx, int my, int vx, int vy, int vw, int vh, Vector3f originOffset)
    {
        mx -= vx;
        my -= vy;

        float w2 = vw / 2F;
        float h2 = vh / 2F;

        return getMouseRay(projection, view, (mx - w2) / w2, (-my + h2) / h2, originOffset);
    }

    public static Vector3f getMouseRay(Matrix4f projection, Matrix4f view, float ndcX, float ndcY, Vector3f originOffset)
    {
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        Vector4f near = new Vector4f(ndcX, ndcY, -1F, 1F);
        Vector4f far = new Vector4f(ndcX, ndcY, 1F, 1F);

        inverse.transform(near);
        near.div(near.w);
        inverse.transform(far);
        far.div(far.w);

        Vector3f direction = new Vector3f(far.x - near.x, far.y - near.y, far.z - near.z).normalize();

        /* Slide the origin along the ray onto the camera's plane: the ortho
         * near plane does not sit at the camera (see
         * BBSRendering#getOrthoProjection), and an origin that lands somewhere
         * up or down the view axis would shift the finite segments the callers
         * trace. Only the lateral shift is kept. */
        originOffset.set(near.x, near.y, near.z);
        originOffset.fma(-originOffset.dot(direction), direction);

        return direction;
    }
}
