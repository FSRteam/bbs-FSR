package mchorse.bbs_mod.ui.film.controller;

import java.util.Arrays;
import java.util.List;
import org.joml.Vector2i;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIRecordOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.minecraft.client.Minecraft;
import static mchorse.bbs_mod.ui.film.controller.UIFilmController.*;

/** Recording countdown, takes and commit ownership. */
public class FilmRecordingController
{
    private final UIFilmController controller;

    public FilmRecordingController(UIFilmController controller)
    {
        this.controller = controller;
    }

    int recordingTick;

    boolean recording;

    int recordingCountdown;

    List<String> recordingGroups;

    BaseType recordingOld;


    public boolean isRecording()
    {
        return this.controller.sceneOwner ? this.recording : this.controller.panel.getController().isRecording();
    }

    public int getRecordingCountdown()
    {
        return this.controller.sceneOwner ? this.recordingCountdown : this.controller.panel.getController().getRecordingCountdown();
    }

    public List<String> getRecordingGroups()
    {
        return this.controller.sceneOwner ? this.recordingGroups : this.controller.panel.getController().getRecordingGroups();
    }

    boolean hasTransformRecordingGroup()
    {
        return this.recordingGroups != null && this.recordingGroups.contains(ReplayKeyframes.GROUP_TRANSFORM);
    }

    public boolean isTransformRecording()
    {
        if (!this.controller.sceneOwner)
        {
            return this.controller.panel.getController().isTransformRecording();
        }

        return this.recording
            && this.recordingCountdown <= 0
            && this.hasTransformRecordingGroup();
    }

    public void startRecording(List<String> groups)
    {
        if (!this.controller.sceneOwner)
        {
            this.controller.panel.getController().startRecording(groups);

            return;
        }

        if (groups != null && groups.contains("outside"))
        {
            Film film = this.controller.panel.getData();
            Replay replay = this.controller.panel.replayEditor.getReplay();
            int index = film == null ? -1 : CollectionUtils.getIndex(film.replays.getList(), replay);
            int cursor = this.controller.panel.getCursor();

            if (film == null || index < 0)
            {
                return;
            }

            /* Closing the dashboard synchronously tears down the Film panel. Keep
             * every recording input before that boundary; the world recorder must
             * never reach back into a panel which has already disappeared. */
            Minecraft.getInstance().setScreen(null);
            /* On the mark: started from the editor, at a cursor the editor chose,
             * so the take begins where the replay itself stands at that tick */
            BBSModClient.getFilms().startRecording(film, index, cursor, true);

            return;
        }

        this.recordingTick = this.controller.getTick();
        this.recording = true;
        this.recordingCountdown = Math.max(0, TimeUtils.toTick(BBSSettings.recordingCountdown.get()));
        this.recordingGroups = groups;
        boolean transformRecording = groups != null && groups.contains(ReplayKeyframes.GROUP_TRANSFORM);

        this.recordingOld = transformRecording ? this.controller.getReplay().properties.toData() : this.controller.getReplay().keyframes.toData();

        if (transformRecording)
        {
            if (this.controller.controlled != null)
            {
                this.controller.toggleControl();
            }

            this.controller.setMouseMode(0);
        }
        else if (groups != null)
        {
            if (groups.contains(ReplayKeyframes.GROUP_LEFT_STICK))
            {
                this.controller.setMouseMode(1);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_RIGHT_STICK))
            {
                this.controller.setMouseMode(2);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_TRIGGERS))
            {
                this.controller.setMouseMode(3);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_EXTRA1))
            {
                this.controller.setMouseMode(4);
            }
            else if (groups.contains(ReplayKeyframes.GROUP_EXTRA2))
            {
                this.controller.setMouseMode(5);
            }
            else
            {
                this.controller.setMouseMode(0);
            }
        }

        if (!transformRecording && this.controller.controlled == null)
        {
            this.controller.toggleControl();
        }

        this.controller.toggleMousePointer(!transformRecording && this.controller.controlled != null);

        /* No countdown means the take starts now. Without this nothing ever started it: the countdown
         * branch in handleRecording is what calls togglePlayback, and it only runs while the counter
         * is still above zero — with the setting at 0 the very first tick fell straight through to
         * "the film is not running, so the take is over" and stopped the recording before a single
         * frame was written. */
        this.startPlayback();
    }

    void startPlayback()
    {
        if (this.recordingCountdown <= 0 && !this.controller.panel.getRunner().isRunning())
        {
            this.controller.panel.togglePlayback();
        }
    }

    public void stopRecording()
    {
        if (!this.controller.sceneOwner)
        {
            this.controller.panel.getController().stopRecording();

            return;
        }

        if (!this.recording)
        {
            return;
        }

        boolean transformRecording = this.hasTransformRecordingGroup();

        this.recording = false;
        this.recordingGroups = null;

        if (!transformRecording && this.controller.controlled != null)
        {
            this.controller.toggleControl();
        }

        this.controller.panel.setCursor(this.recordingTick);

        if (this.controller.panel.getRunner().isRunning())
        {
            this.controller.panel.togglePlayback();
        }

        if (this.recordingCountdown > 0)
        {
            return;
        }

        Replay replay = this.controller.getReplay();

        if (replay != null && this.recordingOld != null)
        {
            if (transformRecording)
            {
                for (KeyframeChannel<?> channel : replay.properties.properties.values())
                {
                    if (PerLimbService.isPoseBoneChannel(channel.getId()))
                    {
                        channel.simplify();
                    }
                }

                BaseType newData = replay.properties.toData();

                replay.properties.fromData(this.recordingOld);
                replay.properties.preNotify();
                replay.properties.fromData(newData);
                replay.properties.postNotify();

                if (this.controller.panel.replayEditor.getReplay() == replay)
                {
                    this.controller.panel.replayEditor.setReplay(replay, false, UIReplaysEditor.OrbitReaction.SWITCH);
                }
            }
            else
            {
                for (KeyframeChannel<?> channel : replay.keyframes.getChannels())
                {
                    channel.simplify();
                }

                BaseType newData = replay.keyframes.toData();

                replay.keyframes.fromData(this.recordingOld);
                replay.keyframes.preNotify();
                replay.keyframes.fromData(newData);
                replay.keyframes.postNotify();
            }

            this.recordingOld = null;
        }

        this.controller.setMouseMode(ClientNetwork.isIsBBSModOnServer() ? 0 : 1);
    }

    public void pickRecording()
    {
        if (!this.controller.sceneOwner)
        {
            this.controller.panel.getController().pickRecording();

            return;
        }

        if (this.controller.panel.replayEditor.getReplay() == null)
        {
            return;
        }

        if (this.recording)
        {
            this.stopRecording();

            return;
        }

        this.controller.toggleMousePointer(false);

        UIRecordOverlayPanel panel = new UIRecordOverlayPanel(
            UIKeys.FILM_CONTROLLER_RECORD_TITLE,
            UIKeys.FILM_CONTROLLER_RECORD_DESCRIPTION,
            this::startRecording
        );
        UIIcon icon = new UIIcon(Icons.UPLOAD, (b) -> panel.submit(Arrays.asList("outside")));

        icon.tooltip(UIKeys.FILM_GROUPS_OUTSIDE);
        panel.bar.add(icon);
        panel.keys().register(Keys.RECORDING_GROUP_OUTSIDE, icon::clickItself);

        UIOverlay.addOverlay(this.controller.getContext(), panel);
    }

    void handleRecording(RunnerCameraController runner)
    {
        if (this.recording)
        {
            if (this.recordingCountdown > 0)
            {
                this.recordingCountdown -= 1;

                if (this.recordingCountdown <= 0)
                {
                    this.startPlayback();
                }
            }

            if (this.recordingCountdown <= 0)
            {
                boolean stopped = !runner.isRunning();

                if (BBSSettings.editorLoop.get())
                {
                    Vector2i loop = this.controller.panel.getLoopingRange();
                    int min = loop.x;
                    int max = loop.y;
                    int ticks = this.controller.panel.getCursor();

                    if (min >= 0 && max >= 0 && min < max && (ticks >= max - 1 || ticks < min) || stopped)
                    {
                        this.stopRecording();
                    }
                }
                else if (stopped)
                {
                    this.stopRecording();
                }
            }
        }
    }
}
