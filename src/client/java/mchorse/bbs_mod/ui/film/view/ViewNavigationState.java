package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.MapType;

/** Per-preview navigation state. It deliberately contains no UI element references. */
public final class ViewNavigationState
{
    private final Position freePose = new Position();
    private final Position savedFreePose = new Position();
    private final Position editingPose = new Position();
    private boolean initialized;
    private boolean hasSavedFreePose;
    private boolean inCameraView;
    private boolean lockCameraToView;
    private boolean editingCamera;
    private int freeMode = 1;
    private float frameZoom = 1F;
    private float framePanX;
    private float framePanY;

    public Position getFreePose()
    {
        return this.freePose;
    }

    public void initialize(Camera camera)
    {
        if (!this.initialized)
        {
            this.freePose.set(camera);
            this.initialized = true;
        }
    }

    public int getFreeMode()
    {
        return this.freeMode;
    }

    public void setFreeMode(int mode)
    {
        this.freeMode = Math.max(1, Math.min(5, mode));
    }

    public boolean isInCameraView()
    {
        return this.inCameraView;
    }

    public boolean isLockCameraToView()
    {
        return this.lockCameraToView;
    }

    public void setLockCameraToView(boolean value)
    {
        this.lockCameraToView = value;
    }

    public void enterCameraView(Camera current, Position cameraPose)
    {
        if (!this.inCameraView)
        {
            this.savedFreePose.set(current);
            this.freePose.set(current);
            this.initialized = true;
            this.hasSavedFreePose = true;
        }

        if (cameraPose != null)
        {
            this.editingPose.set(cameraPose);
        }

        this.inCameraView = true;
    }

    public void exitCameraView(Camera target)
    {
        if (this.inCameraView && this.hasSavedFreePose)
        {
            this.savedFreePose.apply(target);
            this.freePose.set(this.savedFreePose);
        }

        this.inCameraView = false;
        this.editingCamera = false;
    }

    public Position beginCameraEdit(Camera camera)
    {
        this.editingPose.set(camera);
        this.editingCamera = true;

        return this.editingPose;
    }

    public boolean isEditingCamera()
    {
        return this.editingCamera;
    }

    public Position getEditingPose()
    {
        return this.editingPose;
    }

    public void endCameraEdit()
    {
        this.editingCamera = false;
    }

    public float getFrameZoom()
    {
        return this.frameZoom;
    }

    public void setFrameZoom(float zoom)
    {
        this.frameZoom = Float.isFinite(zoom) ? clamp(zoom, 0.25F, 8F) : 1F;
    }

    public void zoomFrame(float amount)
    {
        this.setFrameZoom(this.frameZoom * (float) Math.pow(1.1D, amount));
    }

    public float getFramePanX()
    {
        return this.framePanX;
    }

    public float getFramePanY()
    {
        return this.framePanY;
    }

    public void panFrame(float x, float y)
    {
        this.framePanX = clamp(this.framePanX + x, -1F, 1F);
        this.framePanY = clamp(this.framePanY + y, -1F, 1F);
    }

    public void resetFrame()
    {
        this.frameZoom = 1F;
        this.framePanX = 0F;
        this.framePanY = 0F;
    }

    public MapType toData()
    {
        MapType data = new MapType();

        data.put("free_pose", this.freePose.toData());
        data.put("saved_free_pose", this.savedFreePose.toData());
        data.putBool("initialized", this.initialized);
        data.putBool("saved", this.hasSavedFreePose);
        data.putBool("camera_view", this.inCameraView);
        data.putBool("locked", this.lockCameraToView);
        data.putInt("free_mode", this.freeMode);
        data.putFloat("zoom", this.frameZoom);
        data.putFloat("pan_x", this.framePanX);
        data.putFloat("pan_y", this.framePanY);

        return data;
    }

    public void fromData(MapType data)
    {
        boolean hasFreePose = readPose(this.freePose, data.getMap("free_pose"));
        boolean hasSavedPose = readPose(this.savedFreePose, data.getMap("saved_free_pose"));

        this.editingPose.set(new Position());
        this.initialized = data.getBool("initialized") && hasFreePose;
        this.hasSavedFreePose = data.getBool("saved") && hasSavedPose;
        this.inCameraView = data.getBool("camera_view");
        this.lockCameraToView = data.getBool("locked");
        this.setFreeMode(data.getInt("free_mode", 1));
        this.setFrameZoom(data.getFloat("zoom", 1F));
        this.framePanX = clamp(data.getFloat("pan_x"), -1F, 1F);
        this.framePanY = clamp(data.getFloat("pan_y"), -1F, 1F);
        this.editingCamera = false;
    }

    private static boolean readPose(Position target, MapType data)
    {
        target.set(new Position());
        target.fromData(data);

        boolean valid = data.has("point") && data.has("angle")
            && Double.isFinite(target.point.x) && Double.isFinite(target.point.y) && Double.isFinite(target.point.z)
            && Float.isFinite(target.angle.yaw) && Float.isFinite(target.angle.pitch)
            && Float.isFinite(target.angle.roll) && Float.isFinite(target.angle.fov) && target.angle.fov > 0F;

        if (!valid)
        {
            target.set(new Position());
        }

        return valid;
    }

    private static float clamp(float value, float min, float max)
    {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : Math.max(min, Math.min(max, 0F));
    }
}
