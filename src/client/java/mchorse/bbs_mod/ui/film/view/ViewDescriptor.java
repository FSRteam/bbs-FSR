package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.settings.values.ui.ValueMotionPath;
import mchorse.bbs_mod.settings.values.ui.ValueOnionSkin;
import mchorse.bbs_mod.ui.film.UIFilmPanel;

/** Stable description of one film editor preview and its render policy. */
public final class ViewDescriptor
{
    public static final String PRIMARY_ID = "preview";
    public static final String SECONDARY_ID = "preview2";

    private final UIFilmPanel panel;
    private final String id;
    private final boolean primary;
    private final Camera camera = new Camera();
    private final ViewNavigationState navigation = new ViewNavigationState();
    private final ValueOnionSkin onionSkin = new ValueOnionSkin("onion_skin");
    private final ValueMotionPath motionPath = new ValueMotionPath("motion_path");
    private String cameraId = Film.LEGACY_CAMERA_ID;
    private boolean followOutput;
    private boolean visible = true;
    private boolean active = true;
    private boolean shadersEnabled = true;
    private boolean showName = true;
    private boolean showFrame = true;
    private boolean showThirds;
    private boolean showCenter;
    private boolean showCrosshair;
    private boolean showCameraObjects = true;
    private int width;
    private int height;
    private int refreshRate = 30;
    private int resolutionWidth;
    private float resolutionScale = 1F;
    private float orthoDistance = -1F;
    private long historyEpoch;
    private long lastRequestedFrame = -1L;

    public ViewDescriptor(UIFilmPanel panel, String id, boolean primary)
    {
        this.panel = panel;
        this.id = id;
        this.primary = primary;
        this.active = primary;
        this.visible = primary;
        this.onionSkin.fromData(BBSSettings.editorOnionSkin.toData());
        this.motionPath.fromData(BBSSettings.editorMotionPath.toData());
        this.showThirds = BBSSettings.editorRuleOfThirds.get();
        this.showCenter = BBSSettings.editorCenterLines.get();
        this.showCrosshair = BBSSettings.editorCrosshair.get();

        if (primary)
        {
            this.followOutput = true;
            this.refreshRate = 60;
        }
    }

    public UIFilmPanel getPanel()
    {
        return this.panel;
    }

    public String getId()
    {
        return this.id;
    }

    public boolean isPrimary()
    {
        return this.primary;
    }

    public Camera getCamera()
    {
        return this.camera;
    }

    public ViewNavigationState getNavigation()
    {
        return this.navigation;
    }

    public ValueOnionSkin getOnionSkin()
    {
        return this.onionSkin;
    }

    public ValueMotionPath getMotionPath()
    {
        return this.motionPath;
    }

    public boolean isShowName()
    {
        return this.showName;
    }

    public void setShowName(boolean showName)
    {
        this.showName = showName;
    }

    public boolean isShowFrame()
    {
        return this.showFrame;
    }

    public void setShowFrame(boolean showFrame)
    {
        this.showFrame = showFrame;
    }

    public boolean isShowThirds()
    {
        return this.showThirds;
    }

    public void setShowThirds(boolean showThirds)
    {
        this.showThirds = showThirds;
    }

    public boolean isShowCenter()
    {
        return this.showCenter;
    }

    public void setShowCenter(boolean showCenter)
    {
        this.showCenter = showCenter;
    }

    public boolean isShowCrosshair()
    {
        return this.showCrosshair;
    }

    public void setShowCrosshair(boolean showCrosshair)
    {
        this.showCrosshair = showCrosshair;
    }

    public boolean isShowCameraObjects()
    {
        return this.showCameraObjects;
    }

    public void setShowCameraObjects(boolean showCameraObjects)
    {
        this.showCameraObjects = showCameraObjects;
    }

    public String getCameraId()
    {
        return this.cameraId;
    }

    public void setCameraId(String cameraId)
    {
        String value = cameraId == null || cameraId.isEmpty() ? Film.LEGACY_CAMERA_ID : cameraId;

        if (!value.equals(this.cameraId))
        {
            this.cameraId = value;
            this.invalidateHistory();
        }
    }

    public boolean isFollowOutput()
    {
        return this.followOutput;
    }

    public void setFollowOutput(boolean followOutput)
    {
        if (this.followOutput != followOutput)
        {
            this.followOutput = followOutput;
            this.invalidateHistory();
        }
    }

    public boolean isVisible()
    {
        return this.visible;
    }

    public void setVisible(boolean visible)
    {
        this.visible = visible;
    }

    public boolean isActive()
    {
        return this.active;
    }

    public void setActive(boolean active)
    {
        this.active = active;
    }

    public boolean isShadersEnabled()
    {
        return this.shadersEnabled;
    }

    public void setShadersEnabled(boolean shadersEnabled)
    {
        if (this.shadersEnabled != shadersEnabled)
        {
            this.shadersEnabled = shadersEnabled;
            this.invalidateHistory();
        }
    }

    public int getWidth()
    {
        return this.width;
    }

    public int getHeight()
    {
        return this.height;
    }

    public void setSize(int width, int height)
    {
        int nextWidth = Math.max(2, width);
        int nextHeight = Math.max(2, height);

        if (this.width != nextWidth || this.height != nextHeight)
        {
            this.width = nextWidth;
            this.height = nextHeight;
        }
    }

    public int getRefreshRate()
    {
        return this.refreshRate;
    }

    public void setRefreshRate(int refreshRate)
    {
        this.refreshRate = Math.max(1, Math.min(240, refreshRate));
    }

    public float getResolutionScale()
    {
        return this.resolutionScale;
    }

    public int getResolutionWidth()
    {
        return this.resolutionWidth;
    }

    public void setResolutionWidth(int width)
    {
        this.resolutionWidth = ViewPerformanceSettings.normalizeResolutionWidth(width);
    }

    public void setResolutionScale(float resolutionScale)
    {
        this.resolutionScale = Float.isFinite(resolutionScale) ? Math.max(0.1F, Math.min(1F, resolutionScale)) : 1F;
    }

    public long getHistoryEpoch()
    {
        return this.historyEpoch;
    }

    public float getOrthoDistance()
    {
        return this.orthoDistance;
    }

    public void setOrthoDistance(float distance)
    {
        this.orthoDistance = Float.isFinite(distance) && distance > 0F ? distance : -1F;
    }

    public void invalidateHistory()
    {
        this.historyEpoch++;
        this.lastRequestedFrame = -1L;
    }

    public long getLastRequestedFrame()
    {
        return this.lastRequestedFrame;
    }

    public void requestRefresh(long frameId)
    {
        this.lastRequestedFrame = frameId;
    }

    public String resolveCameraId()
    {
        Film film = this.panel.getData();
        String id = this.followOutput && film != null ? film.resolveCameraId(this.panel.getCursor()) : this.cameraId;

        return film != null && film.hasCamera(id) ? id : Film.LEGACY_CAMERA_ID;
    }

    public MapType toData()
    {
        MapType data = new MapType();

        data.putString("camera", this.cameraId);
        data.putBool("follow_output", this.followOutput);
        data.putBool("shaders", this.shadersEnabled);
        data.putBool("show_name", this.showName);
        data.putBool("show_frame", this.showFrame);
        data.putBool("thirds", this.showThirds);
        data.putBool("center", this.showCenter);
        data.putBool("crosshair", this.showCrosshair);
        data.putBool("camera_objects", this.showCameraObjects);
        data.putInt("resolution_width", this.resolutionWidth);
        data.put("navigation", this.navigation.toData());
        data.put("onion_skin", this.onionSkin.toData());
        data.put("motion_path", this.motionPath.toData());

        return data;
    }

    public void fromData(MapType data)
    {
        this.setCameraId(data.getString("camera", Film.LEGACY_CAMERA_ID));
        this.setFollowOutput(data.getBool("follow_output", this.primary));
        this.setShadersEnabled(data.getBool("shaders", this.primary));
        this.showName = data.getBool("show_name", true);
        this.showFrame = data.getBool("show_frame", true);
        this.showThirds = data.getBool("thirds", BBSSettings.editorRuleOfThirds.get());
        this.showCenter = data.getBool("center", BBSSettings.editorCenterLines.get());
        this.showCrosshair = data.getBool("crosshair", BBSSettings.editorCrosshair.get());
        this.showCameraObjects = data.getBool("camera_objects", true);
        double resolutionWidth = data.getDouble("resolution_width");

        this.setResolutionWidth(Double.isFinite(resolutionWidth) ? (int) resolutionWidth : 0);

        this.navigation.fromData(data.getMap("navigation"));
        this.onionSkin.fromData(BBSSettings.editorOnionSkin.toData());
        this.motionPath.fromData(BBSSettings.editorMotionPath.toData());

        if (data.has("onion_skin"))
        {
            this.onionSkin.fromData(data.getMap("onion_skin"));
        }

        if (data.has("motion_path"))
        {
            this.motionPath.fromData(data.getMap("motion_path"));
        }
    }

    /**
     * Resolve a camera without advancing the film runner. The panel owns the
     * shared evaluator and can replace this fallback as the renderer comes up.
     */
    public Camera resolveCamera(float transition)
    {
        this.panel.resolveViewCamera(this, transition);

        return this.camera;
    }

    public Position getBasePosition()
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return new Position();
        }

        CameraTrack track = film.getCameraTrack(this.resolveCameraId());

        return track == null ? film.getCameraBasePosition(Film.LEGACY_CAMERA_ID) : track.copyPosition();
    }
}
