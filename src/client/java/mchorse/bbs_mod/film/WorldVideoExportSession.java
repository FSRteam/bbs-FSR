package mchorse.bbs_mod.film;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.utils.WorldExportWindowSession;
import mchorse.bbs_mod.utils.clips.Clips;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.io.File;
import java.util.List;

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

    public String getFilmId()
    {
        return this.filmId;
    }

    /** Pass {@code null}/{@code null} for F4, or a film id and its data for F6. */
    public boolean start(String filmId, Film film)
    {
        if ((filmId == null) != (film == null)
            || this.isExporting()
            || (filmId != null && BBSModClient.getFilms().has(filmId))
            || !this.reserveRecorder())
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
            /* Audio preparation can be slow. Start the controller timeout only
             * after the play request has actually been issued. */
            this.filmControllerDeadlineMs = 0L;

            this.applyWindowSize(size);
            this.exportFrameGeneration = BBSRendering.getExportFrameGeneration();

            long delayMs = (long) (Math.max(0F, BBSSettings.videoSettings.delay.get()) * 1000F);
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

            if (filmId != null)
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
        /* F6 can mux the film's audio track; F4 deliberately has no Film and remains silent. */
        if (this.film != null && BBSSettings.videoSettings.audio.get())
        {
            try
            {
                Clips camera = this.film.camera;
                List<AudioClip> audioClips = camera.getClips(AudioClip.class);
                File file = this.createTemporaryAudio();

                if (AudioRenderer.renderAudio(file, audioClips, camera.calculateDuration(), 48000, 0, 0))
                {
                    this.attachTemporaryAudio(file);
                }
                else
                {
                    this.deleteTemporaryAudio();
                }
            }
            catch (Exception e)
            {
                /* Audio is best-effort: keep the world export running without a track. */
                this.deleteTemporaryAudio();
                LOGGER.warn("Failed to render F6 film audio; continuing video export without audio", e);
            }
        }

        return true;
    }

    @Override
    protected void applyExportTarget()
    {
        /* Warm-up also uses the export target so the first captured frame is settled. */
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

        if (ClientNetwork.isIsBBSModOnServer())
        {
            ClientNetwork.sendActionState(this.filmId, ActionState.PAUSE, controller.getTick());
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
                tick = Math.max(controller.getTick(), 0);

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
