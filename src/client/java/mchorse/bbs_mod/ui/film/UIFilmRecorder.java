package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.VideoExportSession;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIUtils;
import org.lwjgl.glfw.GLFW;

public class UIFilmRecorder extends UIElement
{
    public UIFilmPanel editor;

    public boolean resetReplays = true;

    private final PanelVideoExportSession session;
    private final UIExit exit = new UIExit(this);
    private long overlayGeneration;

    public UIFilmRecorder(UIFilmPanel editor)
    {
        super();

        this.editor = editor;
        this.session = new PanelVideoExportSession(this, editor);

        this.noCulling();
    }

    public boolean isRecording()
    {
        return this.session.isRecording();
    }

    public boolean isExporting()
    {
        return this.session.isExporting();
    }

    public void setFinishedListener(VideoExportSession.FinishedListener listener)
    {
        this.session.setFinishedListener(listener);
    }

    public void cancel()
    {
        this.session.cancel();
    }

    public void openMovies()
    {
        UIUtils.openFolder(BBSRendering.getVideoFolder());
    }

    public boolean reserveExport()
    {
        return this.session.reserveRecorder();
    }

    public void cancelPendingExport()
    {
        this.session.cancelPendingReservation();
    }

    public void startRecording(int duration, Texture texture)
    {
        this.tryStartRecording(duration, texture);
    }

    public boolean tryStartRecording(int duration, Texture texture)
    {
        return this.tryStartRecording(duration, texture.id, texture.width, texture.height);
    }

    public void startRecording(int duration, int id, int w, int h)
    {
        this.tryStartRecording(duration, id, w, h);
    }

    public boolean tryStartRecording(int duration, int id, int w, int h)
    {
        if (this.editor.isRunning() || duration <= 0)
        {
            this.session.cancel();

            return false;
        }

        return this.session.start(duration, id, w, h);
    }

    void attachOverlay()
    {
        UIContext context = this.editor.getContext();
        long generation = this.advanceOverlayGeneration();

        context.menu.runAfterCapturedMouseRelease(() ->
        {
            if (generation != this.overlayGeneration || !this.session.isExporting())
            {
                return;
            }

            context.menu.main.setEnabled(false);
            context.menu.overlay.add(this);
            context.menu.getRoot().add(this.exit);
        });
    }

    public void stop()
    {
        this.session.stop();
    }

    void detachOverlay()
    {
        UIContext context = this.editor.getContext();

        this.advanceOverlayGeneration();
        context.menu.main.setEnabled(true);
        context.menu.runAfterHierarchyMutation(() ->
        {
            this.exit.removeFromParent();
            this.removeFromParent();
        });
    }

    private long advanceOverlayGeneration()
    {
        this.overlayGeneration = this.overlayGeneration == Long.MAX_VALUE
            ? 1L
            : this.overlayGeneration + 1L;

        return this.overlayGeneration;
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        if (this.session.isWarmingUp() && BBSSettings.recordingOverlays.get())
        {
            long remainingMs = this.session.getWarmupRemainingMs();
            int countdown = Math.max(0, (int) Math.ceil(remainingMs / 50D));
            Area previewArea = this.editor.preview.getViewport();

            BBSRendering.renderRecordingTimerOverlay(context.batcher, String.valueOf(TimeUtils.toSeconds(countdown)), previewArea.x + 5, previewArea.y + 5);
        }

        this.session.update();
    }

    public static class UIExit extends UIElement
    {
        private UIFilmRecorder recorder;

        public UIExit(UIFilmRecorder recorder)
        {
            this.recorder = recorder;
        }

        @Override
        protected boolean subKeyPressed(UIContext context)
        {
            if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                this.recorder.cancel();

                return true;
            }

            return super.subKeyPressed(context);
        }
    }
}
