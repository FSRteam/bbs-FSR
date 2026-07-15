package mchorse.bbs_mod.film;

import com.mojang.blaze3d.platform.Window;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.utils.WorldExportWindowSession;
import net.minecraft.client.Minecraft;

/**
 * Live-world video export used by F4 and by F6 when a film is played at the
 * same time. It owns the temporary window and renderer resolution changes.
 */
public class WorldVideoExportSession extends VideoExportSession
{
    private static final long FILM_CONTROLLER_TIMEOUT_MS = 10000L;

    private final WorldExportWindowSession windowSession = new WorldExportWindowSession();

    private String filmId;
    private boolean firstTickPaused;
    private boolean filmControllerSeen;
    private long filmControllerDeadlineMs;

    public String getFilmId()
    {
        return this.filmId;
    }

    /** Pass {@code null} for plain F4 world recording, or a film id for F6. */
    public boolean start(String filmId)
    {
        if (this.isExporting() || this.getRecorder() == null || this.getRecorder().isRecording())
        {
            return false;
        }

        Window window = Minecraft.getInstance().getWindow();
        VideoSize size = this.getVideoSize(window);

        this.applyWindowSize(size);

        this.filmId = filmId;
        this.firstTickPaused = false;
        this.filmControllerSeen = false;
        this.filmControllerDeadlineMs = filmId == null ? 0L : System.currentTimeMillis() + FILM_CONTROLLER_TIMEOUT_MS;

        long delayMs = (long) (Math.max(0F, BBSSettings.videoSettings.delay.get()) * 1000F);
        boolean started = this.begin(BBSRendering.getTexture().id, size.width, size.height, delayMs);

        if (!started)
        {
            this.windowSession.restore();
            this.clearFilmState();

            return false;
        }

        if (filmId != null)
        {
            Films.playFilm(filmId, false);
        }

        return true;
    }

    @Override
    protected boolean prepare()
    {
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
        if (cancelled && this.filmId != null && BBSModClient.getFilms().has(this.filmId))
        {
            Films.playFilm(this.filmId, false);
        }

        BBSRendering.setCustomSize(false, 0, 0);
        this.windowSession.restore();
        this.clearFilmState();
    }

    private void clearFilmState()
    {
        this.filmId = null;
        this.firstTickPaused = false;
        this.filmControllerSeen = false;
        this.filmControllerDeadlineMs = 0L;
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
