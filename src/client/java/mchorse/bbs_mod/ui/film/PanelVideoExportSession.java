package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.audio.AudioRenderResult;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.VideoExportSession;
import mchorse.bbs_mod.film.VideoExportRequest;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clips;
import org.joml.Vector2i;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Video export session for the film panel's preview texture. */
public class PanelVideoExportSession extends VideoExportSession
{
    private final UIFilmRecorder ui;
    private final UIFilmPanel editor;

    private int duration;
    private int start;
    private int end;
    private int restoreCursor;
    private boolean restorePaused;
    private boolean setupStarted;
    private boolean setupCompleted;
    private List<AudioClip> sessionAudioClips = List.of();
    private int sessionAudioDuration;

    public PanelVideoExportSession(UIFilmRecorder ui, UIFilmPanel editor)
    {
        this.ui = ui;
        this.editor = editor;
    }

    public boolean start(int duration, int textureId, int width, int height)
    {
        this.duration = duration;

        long delayMs = (long) (Math.max(0F, BBSSettings.videoSettings.delay.get()) * 1000F);

        return this.begin(textureId, width, height, delayMs);
    }

    @Override
    protected boolean prepare()
    {
        Clips camera = this.editor.getData().camera;
        this.sessionAudioDuration = camera.calculateDuration();

        if (this.getExportRequest().filmAudio())
        {
            List<AudioClip> copies = new ArrayList<>();
            for (AudioClip clip : camera.getClips(AudioClip.class))
            {
                copies.add((AudioClip) clip.copy());
            }
            this.sessionAudioClips = List.copyOf(copies);
        }
        else
        {
            this.sessionAudioClips = List.of();
        }

        this.restorePaused = this.editor.getController().isPaused();
        this.restoreCursor = this.editor.getCursor();
        this.setupStarted = false;
        this.setupCompleted = false;

        return true;
    }

    @Override
    protected VideoExportRequest createExportRequest(int width, int height) throws Exception
    {
        boolean looping = BBSSettings.editorLoop.get();

        if (looping
            && this.editor.cameraEditor.clips.loopMin != this.editor.cameraEditor.clips.loopMax)
        {
            int min = this.editor.cameraEditor.clips.loopMin;
            int max = this.editor.cameraEditor.clips.loopMax;
            this.start = Math.max(0, Math.min(min, max));
            this.end = Math.min(this.duration, Math.max(min, max));
        }
        else
        {
            this.start = 0;
            this.end = this.duration;
        }

        if (this.end <= this.start)
        {
            throw new IllegalArgumentException("Video export range is empty");
        }

        boolean filmAudio = BBSSettings.videoSettings.audio.get();
        boolean minecraftAudio = BBSSettings.videoExportMinecraftSounds != null
            && BBSSettings.videoExportMinecraftSounds.get();

        Film film = this.editor.getData();
        return this.createOwnedRequest(film == null ? "" : film.getId(), this.start, this.end,
            false, filmAudio, minecraftAudio);
    }

    @Override
    protected AudioRenderResult renderFilmAudio(VideoExportRequest request, File output,
                                                BooleanSupplier cancelled,
                                                BiConsumer<Long, Long> progress)
    {
        return AudioRenderer.renderAudioResult(output, this.sessionAudioClips,
            this.sessionAudioDuration, request.sampleRate(),
            TimeUtils.toSeconds((float) request.sourceStart()),
            TimeUtils.toSeconds((float) request.sourceEnd()),
            request.layout(), cancelled, progress);
    }

    @Override
    protected void applyExportTarget()
    {
        /* All mutations happen after the base session entered teardown-owned WARMUP. */
        this.setupStarted = true;
        this.editor.setCursor(this.start);
        this.editor.notifyServer(ActionState.RESTART);

        if (this.ui.resetReplays)
        {
            this.editor.getController().createEntities();
        }

        this.ui.attachOverlay();
        this.setupCompleted = true;
    }

    @Override
    protected String getMovieName()
    {
        Film film = this.editor.getData();
        String base = StringUtils.resolveExportFilename(
            BBSSettings.videoExportFilenameFormat.get(),
            film == null ? "" : film.getId(),
            this.width,
            this.height,
            BBSRendering.getVideoFrameRate(),
            film == null ? 0 : film.camera.calculateDuration()
        );

        return base;
    }

    @Override
    protected void onWarmupStarted()
    {
        this.editor.getController().setPaused(true);
    }

    @Override
    protected void onRecordingStarted()
    {
        this.editor.getController().setPaused(false);

        if (!this.editor.isRunning())
        {
            this.editor.togglePlayback();
        }
    }

    @Override
    protected boolean isFinished()
    {
        return !this.editor.isRunning() || this.editor.getCursor() >= this.end;
    }

    @Override
    protected void teardown(boolean cancelled)
    {
        boolean rollbackPartialSetup = this.setupStarted && !this.setupCompleted;

        this.runCleanupSteps(
            () ->
            {
                if (rollbackPartialSetup)
                {
                    this.editor.setCursor(this.restoreCursor);
                }
            },
            () ->
            {
                if (rollbackPartialSetup)
                {
                    this.editor.notifyServer(ActionState.RESTART);
                }
            },
            () ->
            {
                if (rollbackPartialSetup && this.ui.resetReplays)
                {
                    this.editor.getController().createEntities();
                }
            },
            () -> this.editor.getController().setPaused(this.restorePaused),
            this.editor::restorePreviewSize,
            () ->
            {
                if (this.editor.isRunning())
                {
                    this.editor.togglePlayback();
                }
            },
            this.ui::detachOverlay,
            () ->
            {
                this.setupStarted = false;
                this.setupCompleted = false;
                this.sessionAudioClips = List.of();
                this.sessionAudioDuration = 0;
            }
        );
    }

    @Override
    protected void onTerminalResult(mchorse.bbs_mod.film.VideoExportResult result)
    {
        if (result.kind() == mchorse.bbs_mod.film.VideoExportResult.Kind.DEGRADED
            || (result.kind() != mchorse.bbs_mod.film.VideoExportResult.Kind.SUCCESS
                && result.kind() != mchorse.bbs_mod.film.VideoExportResult.Kind.CANCELLED))
        {
            String message = result.message() == null || result.message().isBlank()
                ? result.kind().name() : result.message();
            UIOverlay.addOverlay(this.editor.getContext(),
                new UIMessageOverlayPanel(UIKeys.GENERAL_ERROR, IKey.constant(message)));
        }
    }
}
