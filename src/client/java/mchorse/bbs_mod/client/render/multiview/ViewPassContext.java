package mchorse.bbs_mod.client.render.multiview;

import mchorse.bbs_mod.camera.Camera;
import org.joml.Matrix4f;

/** Camera and target dimensions for a single world pass, never GUI window dimensions. */
public final class ViewPassContext implements AutoCloseable
{
    private static ViewPassContext current;

    private final ViewRenderState view;
    private final Camera camera = new Camera();
    private final int width;
    private final int height;
    private final boolean managedCamera;
    private final boolean orthographic;
    private final float orthoDistance;
    private boolean captured;
    private boolean closed;

    public static ViewPassContext current()
    {
        return current;
    }

    public static ViewPassContext open(ViewRenderState view, Camera camera, int width, int height, float orthoDistance)
    {
        if (current != null)
        {
            throw new IllegalStateException("Nested Film world passes are not supported");
        }

        ViewPassContext context = new ViewPassContext(view, camera, width, height, orthoDistance);
        current = context;
        return context;
    }

    private ViewPassContext(ViewRenderState view, Camera camera, int width, int height, float orthoDistance)
    {
        this.view = view;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.managedCamera = camera != null;
        this.orthographic = orthoDistance > 0F;
        this.orthoDistance = orthoDistance;

        if (camera != null)
        {
            this.camera.copy(camera);
        }
    }

    public ViewRenderState view()
    {
        return this.view;
    }

    public Camera camera()
    {
        return this.camera;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public boolean managedCamera()
    {
        return this.managedCamera;
    }

    public boolean orthographic()
    {
        return this.orthographic;
    }

    public Matrix4f projection(float far)
    {
        this.camera.near = 0.05F;
        this.camera.far = far;

        if (this.orthographic)
        {
            float halfHeight = this.orthoDistance * (float) Math.tan(this.camera.fov * 0.5F);
            float halfWidth = halfHeight * this.width / this.height;
            return new Matrix4f().setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -20F, far);
        }

        return new Matrix4f().setPerspective(this.camera.fov, this.width / (float) this.height, this.camera.near, far);
    }

    /** Capture the matrices actually supplied to LevelRenderer, including other compatible hooks. */
    public void capture(net.minecraft.client.Camera worldCamera, Matrix4f viewMatrix, Matrix4f projection)
    {
        this.camera.position.set(worldCamera.getPosition().x, worldCamera.getPosition().y, worldCamera.getPosition().z);
        this.camera.view.set(viewMatrix);
        this.camera.projection.set(projection);
        this.captured = true;
    }

    public boolean captured()
    {
        return this.captured;
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        if (current != this)
        {
            throw new IllegalStateException("Film view context closed out of order");
        }

        this.closed = true;
        current = null;
    }
}
