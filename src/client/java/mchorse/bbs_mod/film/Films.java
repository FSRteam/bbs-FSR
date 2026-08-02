package mchorse.bbs_mod.film;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.AudioRenderer;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.controller.PlayCameraController;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class Films
{
    private static final int MAX_PENDING_RECORDING_TERMINALS = 32;

    private List<BaseFilmController> controllers = new ArrayList<BaseFilmController>();
    private Recorder recorder;
    private final ArrayDeque<PendingRecordingTerminal> pendingRecordingTerminals = new ArrayDeque<>();

    public Map<String, Map<String, Integer>> actors = new HashMap<>();

    /* Static helpers */

    public static void playFilm(String filmId, boolean withCamera)
    {
        if (ClientNetwork.isIsBBSModOnServer())
        {
            ClientNetwork.sendToggleFilm(filmId, withCamera);
        }
        else
        {
            if (BBSModClient.getFilms().has(filmId))
            {
                stopFilm(filmId);
            }
            else
            {
                ContentType.FILMS.getRepository().load(filmId, (data) ->
                {
                    Minecraft.getInstance().execute(() -> playFilm((Film) data, withCamera));
                });
            }
        }
    }

    public static void playFilm(Film film, boolean withCamera)
    {
        FirstPersonFilmController filmController = new FirstPersonFilmController(film);

        if (withCamera && !film.hasFirstPerson())
        {
            PlayCameraController controller = new PlayCameraController(film.getId(), film.camera);

            controller.getContext().entities.putAll(filmController.getEntities());
            BBSModClient.getCameraController().add(controller);
        }

        BBSModClient.getFilms().add(filmController);
    }

    public static void pauseFilm(String filmId)
    {
        if (ClientNetwork.isIsBBSModOnServer())
        {
            ClientNetwork.sendPauseFilm(filmId);
        }
        else
        {
            if (BBSModClient.getFilms().has(filmId))
            {
                togglePauseFilm(filmId);
            }
        }
    }

    public static void togglePauseFilm(String filmId)
    {
        BaseFilmController controller = BBSModClient.getFilms().getController(filmId);

        if (controller != null)
        {
            controller.togglePause();
        }
    }

    public static void stopFilm(String filmId)
    {
        Film film = BBSModClient.getFilms().remove(filmId);
        BBSModClient.getCameraController().removeMatching(controller ->
            controller instanceof PlayCameraController play
                && play.isForFilm(filmId, film == null ? null : film.camera));
    }

    /* Instance API */

    public BaseFilmController getController(String filmId)
    {
        for (BaseFilmController controller : this.controllers)
        {
            if (controller.film.getId().equals(filmId))
            {
                return controller;
            }
        }

        return null;
    }

    public Recorder getRecorder()
    {
        return this.recorder;
    }

    public void startRecording(Film film, int replayId, int tick)
    {
        Morph morph = Morph.getMorph(Minecraft.getInstance().player);

        this.recorder = new Recorder(film, morph == null ? null : morph.getForm(), replayId, tick);

        if (ClientNetwork.isIsBBSModOnServer())
        {
            ClientNetwork.sendActionRecording(
                this.recorder.getRecordingFilmId(),
                this.recorder.getRecordingReplayId(),
                this.recorder.getRecordingTick(),
                this.recorder.countdown,
                true
            );
        }

        Replay replay = CollectionUtils.getSafe(film.replays.getList(), replayId);

        if (replay != null)
        {
            ClientNetwork.sendPlayerForm(replay.form.get());
        }
    }

    public Recorder stopRecording()
    {
        return this.stopRecording(this.recorder, true);
    }

    public Recorder stopRecordingFromServer(String filmId, int replayId, int tick)
    {
        Recorder recorder = this.recorder;

        if (recorder == null || !recorder.matchesRecording(filmId, replayId, tick))
        {
            return null;
        }

        return this.stopRecording(recorder, false);
    }

    public ManualRecordingTerminal consumeManualRecordingTerminal(String filmId, int replayId, int tick)
    {
        if (filmId == null)
        {
            return ManualRecordingTerminal.NONE;
        }

        RecordingIdentity identity = new RecordingIdentity(filmId, replayId, tick);
        Iterator<PendingRecordingTerminal> iterator = this.pendingRecordingTerminals.iterator();

        while (iterator.hasNext())
        {
            PendingRecordingTerminal terminal = iterator.next();

            if (terminal.identity().equals(identity))
            {
                iterator.remove();

                if (!terminal.started())
                {
                    return ManualRecordingTerminal.CANCELED_BEFORE_START;
                }

                return terminal.level() != null && terminal.level() == Minecraft.getInstance().level
                    ? ManualRecordingTerminal.STOPPED_AFTER_START
                    : ManualRecordingTerminal.STOPPED_AFTER_START_MERGE_BLOCKED;
            }
        }

        return ManualRecordingTerminal.NONE;
    }

    private Recorder stopRecording(Recorder recorder, boolean notifyServer)
    {
        return this.stopRecording(recorder, notifyServer, true);
    }

    public Recorder stopRecordingForClientLifecycle(Recorder recorder)
    {
        return this.stopRecording(recorder, false, false);
    }

    private Recorder stopRecording(Recorder recorder, boolean notifyServer, boolean restorePlayer)
    {
        if (recorder == null || this.recorder != recorder)
        {
            return null;
        }

        this.recorder = null;

        Throwable failure = null;

        for (KeyframeChannel<?> channel : recorder.keyframes.getChannels())
        {
            try
            {
                channel.simplify();
            }
            catch (RuntimeException | Error exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        for (Recorder.RecordedMob mob : recorder.mobs)
        {
            for (KeyframeChannel<?> channel : mob.keyframes.getChannels())
            {
                try
                {
                    channel.simplify();
                }
                catch (RuntimeException | Error exception)
                {
                    failure = appendFailure(failure, exception);
                }
            }
        }

        if (notifyServer && ClientNetwork.isIsBBSModOnServer())
        {
            PendingRecordingTerminal pending = this.markPendingRecordingTerminal(recorder);

            try
            {
                ClientNetwork.sendActionRecording(
                    recorder.getRecordingFilmId(),
                    recorder.getRecordingReplayId(),
                    recorder.getRecordingTick(),
                    0,
                    false
                );
            }
            catch (RuntimeException | Error exception)
            {
                this.removePendingRecordingTerminal(pending);
                failure = appendFailure(failure, exception);
            }
        }

        try
        {
            recorder.shutdown(restorePlayer);
        }
        catch (RuntimeException | Error exception)
        {
            failure = appendFailure(failure, exception);
        }

        rethrowFailure(failure);

        return recorder;
    }

    private PendingRecordingTerminal markPendingRecordingTerminal(Recorder recorder)
    {
        RecordingIdentity identity = new RecordingIdentity(
            recorder.getRecordingFilmId(),
            recorder.getRecordingReplayId(),
            recorder.getRecordingTick()
        );
        PendingRecordingTerminal pending = new PendingRecordingTerminal(
            identity,
            recorder.hasRecordedFrame(),
            recorder.getInitialLevel()
        );

        this.pendingRecordingTerminals.addLast(pending);

        while (this.pendingRecordingTerminals.size() > MAX_PENDING_RECORDING_TERMINALS)
        {
            this.pendingRecordingTerminals.removeFirst();
        }

        return pending;
    }

    private void removePendingRecordingTerminal(PendingRecordingTerminal pending)
    {
        Iterator<PendingRecordingTerminal> iterator = this.pendingRecordingTerminals.iterator();

        while (iterator.hasNext())
        {
            if (iterator.next() == pending)
            {
                iterator.remove();

                return;
            }
        }
    }

    private static Throwable appendFailure(Throwable failure, Throwable exception)
    {
        if (failure == null)
        {
            return exception;
        }

        if (failure != exception)
        {
            failure.addSuppressed(exception);
        }

        return failure;
    }

    private static void rethrowFailure(Throwable failure)
    {
        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }

    public void add(BaseFilmController controller)
    {
        this.controllers.add(controller);
    }

    public void freeze(Film film, int tick, boolean animated)
    {
        this.unfreeze(film.getId());
        this.controllers.add(new FrozenFilmController(film, tick, animated));
    }

    public void unfreeze(String filmId)
    {
        this.controllers.removeIf((controller) ->
        {
            boolean frozen = controller instanceof FrozenFilmController
                && controller.film.getId().equals(filmId);

            if (frozen)
            {
                controller.shutdown();
            }

            return frozen;
        });
    }

    public boolean has(String filmId)
    {
        for (BaseFilmController controller : this.controllers)
        {
            if (controller.film.getId().equals(filmId))
            {
                return true;
            }
        }

        return false;
    }

    public Film remove(String id)
    {
        Iterator<BaseFilmController> it = this.controllers.iterator();

        while (it.hasNext())
        {
            BaseFilmController next = it.next();

            if (next.film.getId().equals(id))
            {
                next.shutdown();
                it.remove();

                return next.film;
            }
        }

        return null;
    }

    public void updateActors(String filmId, Map<String, Integer> actors)
    {
        this.actors.put(filmId, actors);
    }

    public void startRenderFrame(float transition)
    {
        if (this.recorder != null)
        {
            this.recorder.startRenderFrame(transition);
        }

        for (BaseFilmController controller : this.controllers)
        {
            controller.startRenderFrame(transition);
        }
    }

    public void update()
    {
        this.controllers.removeIf((film) ->
        {
            film.update();

            if (film.hasFinished())
            {
                film.shutdown();
            }

            return film.hasFinished();
        });

        Recorder recorder = this.recorder;

        if (recorder != null && !recorder.isInCurrentLevel())
        {
            this.stopRecordingForClientLifecycle(recorder);

            return;
        }

        if (recorder != null)
        {
            recorder.update();
        }
    }

    public void updateEndWorld()
    {
        for (BaseFilmController controller : this.controllers)
        {
            controller.updateEndWorld();
        }
    }

    public void render(IBbsWorldRenderContext context)
    {
        if (this.controllers.isEmpty() && this.recorder == null)
        {
            return;
        }

        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);

        try
        {
            RenderSystem.enableDepthTest();

            for (BaseFilmController controller : this.controllers)
            {
                controller.render(context);
            }

            if (this.recorder != null)
            {
                this.recorder.render(context);
            }
        }
        finally
        {
            if (depthTestEnabled)
            {
                RenderSystem.enableDepthTest();
            }
            else
            {
                RenderSystem.disableDepthTest();
            }
        }
    }

    public void renderHud(Batcher2D batcher2D, float tickDelta)
    {
        Recorder recorder = BBSModClient.getFilms().getRecorder();

        if (recorder != null && BBSSettings.recordingOverlays.get())
        {
            String label = recorder.hasNotStarted() ?
                String.valueOf(TimeUtils.toSeconds(recorder.countdown)) :
                UIKeys.FILM_RECORDING.format(recorder.getTick()).get();
            int x = 5;
            int y = 5;
            int w = batcher2D.getFont().getWidth(label);

            batcher2D.box(x, y, x + 18 + w + 3, y + 16, Colors.A50);
            batcher2D.icon(Icons.SPHERE, Colors.RED | Colors.A100, x, y);
            batcher2D.textShadow(label, x + 18, y + 4);

            /* Render audio waveform (uses preview visibility setting) */
            if (BBSSettings.audioWaveformVisibleInPreview.get())
            {
                List<AudioClip> audioClips = new ArrayList<>();

                for (Clip clip : recorder.film.camera.get())
                {
                    if (clip instanceof AudioClip)
                    {
                        audioClips.add((AudioClip) clip);
                    }
                }

                int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
                int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
                w = (int) (sw * BBSSettings.audioWaveformWidth.get());
                x = sw / 2 - w / 2;
                y = sh / 2 + 100;

                int barH = BBSSettings.audioWaveformHeight.get();
                float playTick = recorder.getTick() + tickDelta;

                if (BBSSettings.audioWaveformPreviewCombined.get())
                {
                    AudioRenderer.renderPreviewCombined(batcher2D, audioClips, playTick, x, y, w, barH, sw, sh);
                }
                else
                {
                    AudioRenderer.renderAll(batcher2D, audioClips, playTick, x, y, w, barH, sw, sh);
                }
            }
        }
    }

    public void reset()
    {
        Throwable failure = null;
        Recorder recorder = this.recorder;
        List<BaseFilmController> controllers = new ArrayList<>(this.controllers);

        this.controllers.clear();
        this.actors.clear();
        this.pendingRecordingTerminals.clear();

        if (recorder != null)
        {
            try
            {
                this.stopRecordingForClientLifecycle(recorder);
            }
            catch (RuntimeException | Error exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        for (BaseFilmController controller : controllers)
        {
            try
            {
                controller.shutdown();
            }
            catch (RuntimeException | Error exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        rethrowFailure(failure);
    }

    private record RecordingIdentity(String filmId, int replayId, int tick)
    {}

    private record PendingRecordingTerminal(RecordingIdentity identity, boolean started, ClientLevel level)
    {}

    public enum ManualRecordingTerminal
    {
        NONE,
        CANCELED_BEFORE_START,
        STOPPED_AFTER_START,
        STOPPED_AFTER_START_MERGE_BLOCKED
    }
}
