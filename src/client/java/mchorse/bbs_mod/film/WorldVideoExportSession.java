package mchorse.bbs_mod.film;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.audio.AudioRenderResult;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.utils.WorldExportWindowSession;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.clips.Clip;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Live-world video export used by F4 and by F6 when a film is played at the
 * same time. It owns the temporary window and renderer resolution changes.
 */
public class WorldVideoExportSession extends VideoExportSession
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long FILM_CONTROLLER_TIMEOUT_MS = 10000L;

    private final WorldExportWindowSession windowSession = new WorldExportWindowSession();

    private String filmId;
    private Film film;
    private boolean firstTickPaused;
    private boolean filmControllerSeen;
    private boolean filmPlaybackRequested;
    private boolean filmPlaybackRemote;
    private long filmControllerDeadlineMs;
    private long exportFrameGeneration;
    private List<AudioClip> sessionAudioClips = List.of();
    private int sessionAudioDuration;
    private volatile WorldVideoExportSnapshot activeSnapshot;
    private volatile boolean snapshotStarted;
    private boolean snapshotFinished;
    private final Object worldListenerLock = new Object();
    private final CopyOnWriteArrayList<WorldVideoExportListener> worldExportListeners = new CopyOnWriteArrayList<>();

    public String getFilmId()
    {
        return this.filmId;
    }

    /** Add a persistent world-export observer without replacing any listener. */
    public boolean addWorldVideoExportListener(WorldVideoExportListener listener)
    {
        if (listener == null)
        {
            return false;
        }

        synchronized (this.worldListenerLock)
        {
            if (!this.worldExportListeners.addIfAbsent(listener))
            {
                return false;
            }

            /* A listener registered while a generation is already active still
             * receives its immutable start fence before its terminal callback.
             * Holding the same lock as terminal delivery preserves that order. */
            WorldVideoExportSnapshot snapshot = this.activeSnapshot;
            if (snapshot != null && this.snapshotStarted && !this.snapshotFinished)
            {
                this.notifyWorldStarted(listener, snapshot);
            }
        }

        return true;
    }

    public boolean removeWorldVideoExportListener(WorldVideoExportListener listener)
    {
        synchronized (this.worldListenerLock)
        {
            return listener != null && this.worldExportListeners.remove(listener);
        }
    }

    /** Pass {@code null}/{@code null} for F4, or a film id and its data for F6. */
    public boolean start(String filmId, Film film)
    {
        if ((filmId == null) != (film == null)
            || this.isExporting()
            || (filmId != null && BBSModClient.getFilms().has(filmId)))
        {
            return false;
        }

        try
        {
            Window window = Minecraft.getInstance().getWindow();
            VideoSize size = this.getVideoSize(window);

            this.filmId = filmId;
            this.film = film;
            this.firstTickPaused = false;
            this.filmControllerSeen = false;
            this.filmPlaybackRequested = false;
            this.filmPlaybackRemote = false;
            synchronized (this.worldListenerLock)
            {
                this.activeSnapshot = null;
                this.snapshotStarted = false;
                this.snapshotFinished = false;
            }
            /* Audio preparation can be slow. Start the controller timeout only
             * after the play request has actually been issued. */
            this.filmControllerDeadlineMs = 0L;

            this.exportFrameGeneration = BBSRendering.getExportFrameGeneration();

            long delayMs = (long) (Math.max(0F, BBSSettings.videoSettings.delay.get()) * 1000F);
            long expectedGeneration = this.getNextExportGeneration();
            boolean started = this.begin(BBSRendering.getTexture().id, size.width, size.height, delayMs);

            if (!started)
            {
                /* A synchronous listener may already have started a new export
                 * on this reusable session. Never roll back the new owner. */
                if (!this.isExporting())
                {
                    this.rollbackUnstartedExport(null);
                }

                return false;
            }

            WorldVideoExportSnapshot snapshot = this.activeSnapshot;
            if (snapshot == null || snapshot.generation() != expectedGeneration)
            {
                /* A deferred terminal listener may already own the next
                 * generation on this reusable session. */
                return false;
            }

            if (this.isExporting() && this.activeSnapshot == snapshot && filmId != null)
            {
                this.startFilmPlayback();
                this.filmControllerDeadlineMs = System.currentTimeMillis() + FILM_CONTROLLER_TIMEOUT_MS;
            }

            return true;
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("Failed to start world video export", e);

            if (this.isExporting())
            {
                this.fail(e);
            }
            else
            {
                this.rollbackUnstartedExport(e);
            }

            return false;
        }
    }

    @Override
    protected boolean prepare()
    {
        /* Source values and deep copies are captured by onOwnedExportStarted
         * before any external listener can mutate the Film. */
        return this.activeSnapshot != null;
    }

    @Override
    protected void onOwnedExportStarted(VideoExportRequest request)
    {
        if (this.film != null)
        {
            this.sessionAudioDuration = this.film.camera.calculateDuration();
            this.sessionAudioClips = copyAudioClips(this.film.camera);
        }
        else
        {
            this.sessionAudioDuration = 0;
            this.sessionAudioClips = List.of();
        }

        WorldVideoExportSnapshot snapshot = this.createSnapshot(request,
            this.film == null ? null : this.film.camera);

        synchronized (this.worldListenerLock)
        {
            this.activeSnapshot = snapshot;
            this.snapshotStarted = snapshot != null;
            this.snapshotFinished = false;
            if (snapshot != null)
            {
                this.notifyWorldStarted(snapshot);
            }
        }
    }

    @Override
    protected VideoExportRequest createExportRequest(int width, int height) throws Exception
    {
        boolean filmAudio = this.film != null && BBSSettings.videoSettings.audio.get();
        boolean minecraftAudio = BBSSettings.videoExportMinecraftSounds != null
            && BBSSettings.videoExportMinecraftSounds.get();

        if (this.film == null)
        {
            return this.createOwnedRequest("", 0D, 0D, true, false, minecraftAudio);
        }

        int duration = this.film.camera.calculateDuration();
        if (duration <= 0) throw new IllegalArgumentException("Film export range is empty");
        return this.createOwnedRequest(this.filmId, 0D, duration, false, filmAudio, minecraftAudio);
    }

    @Override
    protected AudioRenderResult renderFilmAudio(VideoExportRequest request, File output,
                                                BooleanSupplier cancelled,
                                                BiConsumer<Long, Long> progress)
    {
        return AudioRenderer.renderAudioResult(output, this.sessionAudioClips,
            this.sessionAudioDuration, request.sampleRate(),
            (float) (request.sourceStart() / 20D), (float) (request.sourceEnd() / 20D),
            request.layout(), cancelled, progress);
    }

    @Override
    protected void applyExportTarget()
    {
        /* Warm-up also uses the export target so the first captured frame is settled. */
        this.applyWindowSize(new VideoSize(this.width, this.height));
        BBSRendering.setCustomSize(true, this.width, this.height);
    }

    @Override
    protected boolean shouldAbortWarmup()
    {
        return this.filmId != null && this.filmHasEndedOrTimedOut();
    }

    @Override
    protected boolean isWarmupReady()
    {
        if (!BBSRendering.isExportFrameReadyAfter(this.exportFrameGeneration, this.width, this.height))
        {
            return false;
        }

        if (this.filmId == null || this.firstTickPaused)
        {
            return true;
        }

        BaseFilmController controller = BBSModClient.getFilms().getController(this.filmId);

        if (controller == null || controller.getTick() < 1)
        {
            return false;
        }

        this.filmControllerSeen = true;

        if (!controller.paused)
        {
            controller.togglePause();
        }

        /* The controller may have advanced one tick while the world render
         * target warmed up.  Rewind both local and remote playback to the
         * request origin before the first captured frame. */
        if (controller instanceof WorldFilmController worldController)
        {
            worldController.tick = 0;
        }

        if (ClientNetwork.isIsBBSModOnServer())
        {
            ClientNetwork.sendActionState(this.filmId, ActionState.PAUSE, 0);
        }

        this.firstTickPaused = true;

        return true;
    }

    @Override
    protected void onRecordingStarted()
    {
        if (this.firstTickPaused)
        {
            BaseFilmController controller = BBSModClient.getFilms().getController(this.filmId);
            int tick = 0;

            if (controller != null)
            {
                tick = 0;

                if (controller.paused)
                {
                    controller.togglePause();
                }
            }

            if (ClientNetwork.isIsBBSModOnServer())
            {
                ClientNetwork.sendActionState(this.filmId, ActionState.PLAY, tick);
            }
        }

        BBSRendering.setCustomSize(this.getRecorder().isRecording(), this.width, this.height);
    }

    @Override
    protected boolean isFinished()
    {
        return this.filmId != null && this.filmHasEndedOrTimedOut();
    }

    private boolean filmHasEndedOrTimedOut()
    {
        if (BBSModClient.getFilms().has(this.filmId))
        {
            this.filmControllerSeen = true;

            return false;
        }

        return this.filmControllerSeen || System.currentTimeMillis() > this.filmControllerDeadlineMs;
    }

    @Override
    protected void teardown(boolean cancelled)
    {
        this.runCleanupSteps(
            () ->
            {
                if (cancelled && this.filmPlaybackRequested)
                {
                    this.stopFilmPlayback();
                }
            },
            () -> BBSRendering.setCustomSize(false, 0, 0),
            this.windowSession::restore,
            this::clearFilmState
        );
    }

    private void rollbackUnstartedExport(Throwable startupFailure)
    {
        try
        {
            this.runCleanupSteps(
                this::cancelPendingReservation,
                this.windowSession::restore,
                this::clearFilmState
            );
        }
        catch (Exception | LinkageError cleanupFailure)
        {
            if (startupFailure != null && startupFailure != cleanupFailure)
            {
                startupFailure.addSuppressed(cleanupFailure);
            }

            LOGGER.error("Failed to roll back world video export startup", cleanupFailure);
        }
    }

    private void clearFilmState()
    {
        this.filmId = null;
        this.film = null;
        this.firstTickPaused = false;
        this.filmControllerSeen = false;
        this.filmPlaybackRequested = false;
        this.filmPlaybackRemote = false;
        this.filmControllerDeadlineMs = 0L;
        this.exportFrameGeneration = 0L;
        this.sessionAudioClips = List.of();
        this.sessionAudioDuration = 0;
    }

    private static List<AudioClip> copyAudioClips(Clips camera)
    {
        List<AudioClip> copies = new ArrayList<>();

        for (Clip clip : camera.get())
        {
            if (clip instanceof AudioClip audioClip && audioClip.enabled.get())
            {
                copies.add((AudioClip) audioClip.copy());
            }
        }

        return List.copyOf(copies);
    }

    WorldVideoExportSnapshot createSnapshot(VideoExportRequest request, Clips camera)
    {
        if (request == null)
        {
            return null;
        }

        List<WorldVideoExportSnapshot.AudioClipSnapshot> clips = new ArrayList<>();
        if (camera != null)
        {
            List<Clip> source = camera.get();
            for (int index = 0; index < source.size(); index++)
            {
                Clip clip = source.get(index);
                if (!(clip instanceof AudioClip audioClip) || !audioClip.enabled.get())
                {
                    continue;
                }

                String identity = audioClip.getId();
                if (identity == null || identity.isEmpty())
                {
                    identity = String.valueOf(index);
                }

                clips.add(new WorldVideoExportSnapshot.AudioClipSnapshot(index, identity,
                    audioClip.audio.get(), audioClip.tick.get(), audioClip.duration.get(),
                    audioClip.offset.get(), audioClip.volume.get()));
            }
        }

        WorldVideoExportSnapshot.Kind kind = this.film == null
            ? WorldVideoExportSnapshot.Kind.LIVE_WORLD_F4
            : WorldVideoExportSnapshot.Kind.FILM_F6;

        return new WorldVideoExportSnapshot(request.sessionId(), request.generation(), kind,
            request.sourceId(), request.sourceStart(), request.sourceEnd(), request.openEnd(),
            request.layout(), this.filmId == null ? "" : this.filmId, clips,
            request.width(), request.height(), request.captureFrameRate(),
            request.outputFrameRate(), request.motionBlurPasses());
    }

    private void notifyWorldStarted(WorldVideoExportSnapshot snapshot)
    {
        for (WorldVideoExportListener listener : this.worldExportListeners)
        {
            this.notifyWorldStarted(listener, snapshot);
        }
    }

    private void notifyWorldStarted(WorldVideoExportListener listener, WorldVideoExportSnapshot snapshot)
    {
        try
        {
            listener.onStarted(snapshot);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("World video export start listener failed", e);
        }
    }

    private void notifyWorldFinished(WorldVideoExportSnapshot snapshot, VideoExportResult result)
    {
        synchronized (this.worldListenerLock)
        {
            this.snapshotFinished = true;
            for (WorldVideoExportListener listener : this.worldExportListeners)
            {
                try
                {
                    listener.onFinished(snapshot, result);
                }
                catch (Exception | LinkageError e)
                {
                    LOGGER.warn("World video export finish listener failed", e);
                }
            }
        }
    }

    @Override
    protected void onTerminalResult(VideoExportResult result)
    {
        synchronized (this.worldListenerLock)
        {
            WorldVideoExportSnapshot snapshot = this.activeSnapshot;
            if (snapshot != null && this.snapshotStarted
                && result != null && snapshot.sessionId().equals(result.sessionId())
                && snapshot.generation() == result.generation())
            {
                this.notifyWorldFinished(snapshot, result);

                /* A listener may synchronously start the next generation. Never
                 * clear that new generation's snapshot during this callback. */
                if (this.activeSnapshot == snapshot)
                {
                    this.activeSnapshot = null;
                    this.snapshotStarted = false;
                    this.snapshotFinished = false;
                }
            }
        }
    }

    private void startFilmPlayback()
    {
        this.filmPlaybackRemote = ClientNetwork.isIsBBSModOnServer();

        if (this.filmPlaybackRemote)
        {
            ClientNetwork.sendToggleFilm(this.filmId, false);
        }
        else
        {
            /* The panel already supplied the current Film, so avoid an
             * uncancellable repository callback that could outlive export. */
            Films.playFilm(this.film, false);
        }

        this.filmPlaybackRequested = true;
    }

    private void stopFilmPlayback()
    {
        if (this.filmPlaybackRemote)
        {
            /* STOP is idempotent when the start request was rejected or lost;
             * another toggle could start the film during cancellation. */
            ClientNetwork.sendActionState(this.filmId, ActionState.STOP, 0);
        }
        else
        {
            Films.stopFilm(this.filmId);
        }
    }

    private void applyWindowSize(VideoSize size)
    {
        if (BBSSettings.worldExportResizeWindow.get())
        {
            this.windowSession.begin(size.width, size.height);
        }
        else
        {
            this.windowSession.clear();
        }
    }

    private VideoSize getVideoSize(Window window)
    {
        if (BBSSettings.worldExportResizeWindow.get())
        {
            return new VideoSize(even(BBSSettings.videoSettings.width.get()), even(BBSSettings.videoSettings.height.get()));
        }

        /* NeoForge Window#getScreen* is the physical framebuffer size used by capture. */
        return new VideoSize(even(window.getScreenWidth()), even(window.getScreenHeight()));
    }

    private static int even(int value)
    {
        value = Math.max(value, 2);

        return value % 2 == 0 ? value : value - 1;
    }

    private record VideoSize(int width, int height)
    {}
}
