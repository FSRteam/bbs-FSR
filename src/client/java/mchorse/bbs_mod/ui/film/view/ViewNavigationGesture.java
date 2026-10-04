package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** A preview gesture owns its initial pose until a matching release or cancellation. */
public final class ViewNavigationGesture
{
    private final MouseGestureOwnership ownership = new MouseGestureOwnership();
    private final Position startPose = new Position();
    private final Matrix3f inverseView = new Matrix3f();
    private ViewNavigationState navigation;
    private Target target;
    private Mode mode;
    private int button = -1;
    private int startX;
    private int startY;
    private float panX;
    private float panY;
    private boolean changed;

    public boolean begin(int button, int x, int y, Mode mode, ViewNavigationState navigation, Camera camera, Target target)
    {
        if (!this.ownership.acquire(button))
        {
            return false;
        }

        this.button = button;
        this.startX = x;
        this.startY = y;
        this.mode = mode;
        this.navigation = navigation;
        this.target = target;
        this.startPose.set(mode == Mode.FREE ? navigation.getFreePose() : new Position(camera));
        this.inverseView.set(camera.view).invert();
        this.panX = navigation.getFramePanX();
        this.panY = navigation.getFramePanY();
        this.changed = false;

        if (mode == Mode.CAMERA)
        {
            navigation.beginCameraEdit(camera);
        }

        return true;
    }

    public boolean isActive()
    {
        return this.ownership.isActive();
    }

    public boolean isOwnedBy(int button)
    {
        return this.ownership.isOwnedBy(button);
    }

    public int getButton()
    {
        return this.button;
    }

    public void drag(int x, int y, int width, int height, boolean slow)
    {
        if (!this.isActive())
        {
            return;
        }

        int dx = x - this.startX;
        int dy = y - this.startY;

        this.changed = dx != 0 || dy != 0;

        if (this.mode == Mode.FRAME)
        {
            this.navigation.panFrame(this.panX + dx / (float) Math.max(1, width) - this.navigation.getFramePanX(),
                this.panY + dy / (float) Math.max(1, height) - this.navigation.getFramePanY());

            return;
        }

        Position pose = this.mode == Mode.CAMERA ? this.navigation.getEditingPose() : this.navigation.getFreePose();

        pose.set(this.startPose);

        if (!this.changed)
        {
            return;
        }

        if (this.button == 1)
        {
            pose.angle.yaw += dx * 0.2F;
            pose.angle.pitch = Math.max(-89.9F, Math.min(89.9F, pose.angle.pitch + dy * 0.2F));
        }
        else
        {
            float speed = slow ? 0.0025F : 0.01F;
            Vector3f delta = this.inverseView.transform(new Vector3f(-dx * speed, dy * speed, 0F));

            pose.point.x += delta.x;
            pose.point.y += delta.y;
            pose.point.z += delta.z;
        }
    }

    public Completion release(int button, Target current)
    {
        if (!this.isOwnedBy(button))
        {
            return new Completion(false, null, false);
        }

        if (current == null || this.target.film != current.film || (this.mode == Mode.CAMERA && !this.target.matches(current)))
        {
            this.cancel();

            return new Completion(true, null, false);
        }

        Position pose = this.mode == Mode.CAMERA && this.changed ? this.navigation.getEditingPose().copy() : null;
        boolean contextClick = this.mode == Mode.FREE && this.button == 1 && !this.changed;

        this.ownership.release(button);
        this.navigation.endCameraEdit();
        this.clear();

        return new Completion(true, pose, contextClick);
    }

    public void cancel()
    {
        if (!this.isActive())
        {
            return;
        }

        if (this.mode == Mode.FRAME)
        {
            this.navigation.panFrame(this.panX - this.navigation.getFramePanX(), this.panY - this.navigation.getFramePanY());
        }
        else if (this.mode == Mode.FREE)
        {
            this.navigation.getFreePose().set(this.startPose);
        }

        this.ownership.cancel();
        this.navigation.endCameraEdit();
        this.clear();
    }

    private void clear()
    {
        this.navigation = null;
        this.target = null;
        this.mode = null;
        this.button = -1;
        this.changed = false;
    }

    public enum Mode
    {
        FRAME,
        CAMERA,
        FREE
    }

    public record Target(Object film, String cameraId, int tick)
    {
        private boolean matches(Target other)
        {
            return other != null && this.film == other.film && this.cameraId.equals(other.cameraId) && this.tick == other.tick;
        }
    }

    public record Completion(boolean consumed, Position cameraPose, boolean contextClick)
    {}
}
