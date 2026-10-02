package mchorse.bbs_mod.camera.controller;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.function.Consumer;

public class RunnerCameraController extends CameraWorkCameraController
{
    public int ticks;

    private float cursorFraction;
    private float lastTransition;

    private Position manual;
    private UIFilmPanel panel;
    private final Position legacyPosition = new Position();
    private String editorCameraId = Film.LEGACY_CAMERA_ID;
    private long sampledFrame = Long.MIN_VALUE;
    private int sampledTick;
    private float sampledTransition;

    private Consumer<Boolean> callback;

    public RunnerCameraController(UIFilmPanel panel, Consumer<Boolean> callback)
    {
        super();

        this.panel = panel;
        this.callback = callback;
        this.context.playing = false;
    }

    public boolean isRunning()
    {
        return this.context.playing;
    }

    public void setPlaying(boolean playing)
    {
        if (this.context.playing && !playing)
        {
            this.cursorFraction = BBSSettings.editorSnapToTicks.get() ? 0F : this.getTransition(this.lastTransition);
        }

        this.context.playing = playing;
        AudioClientClip.manageSounds(this.context);

        if (this.callback != null)
        {
            this.callback.accept(this.context.playing);
        }
    }

    public void toggle(int ticks)
    {
        if (ticks != this.ticks)
        {
            this.setCursor(ticks);
        }

        this.setPlaying(!this.context.playing);
    }

    public void setCursor(float tick)
    {
        tick = Math.max(0F, tick);

        this.ticks = (int) tick;
        this.cursorFraction = tick - this.ticks;
        this.lastTransition = this.cursorFraction;
    }

    public float getTransition(float transition)
    {
        /* Resuming from a sub-tick must not move backwards before the next game tick. */
        return this.context.playing ? Math.max(this.cursorFraction, transition) : this.cursorFraction;
    }

    public float getCursor(float transition)
    {
        return this.ticks + this.getTransition(transition);
    }

    /** Editor selection never replaces the shared soundtrack or output-camera selection. */
    public void setEditorCameraId(String cameraId)
    {
        Film film = this.panel.getData();

        this.editorCameraId = film != null && film.hasCamera(cameraId) ? cameraId : Film.LEGACY_CAMERA_ID;
    }

    @Override
    public RunnerCameraController setWork(Clips clips)
    {
        if (this.context.clips != clips)
        {
            this.legacyPosition.copy(Position.ZERO);
            this.sampledFrame = Long.MIN_VALUE;
        }

        super.setWork(clips);

        return this;
    }

    public void setManual(Position manual)
    {
        /* The caller seeds this pose from its own preview. The shared runner's
         * last pose can belong to a different camera, or still be uninitialized. */
        this.manual = manual;
    }

    @Override
    public void update()
    {
        if (this.context.playing && this.manual == null)
        {
            if (this.context.clips == null)
            {
                this.setPlaying(false);
                return;
            }

            this.ticks += 1;
            this.cursorFraction = 0F;
            this.lastTransition = 0F;

            Film film = this.panel.getData();
            int duration = film == null ? this.context.clips.calculateDuration() : film.calculateDuration();

            if (this.ticks >= duration)
            {
                this.setPlaying(false);
            }
        }
    }

    @Override
    protected void applyEditedClipEnd(int ticks)
    {
        if (this.context.playing || this.cursorFraction != 0F || this.panel.recorder.isExporting() || !Film.LEGACY_CAMERA_ID.equals(this.editorCameraId))
        {
            return;
        }

        Clip clip = this.panel.cameraEditor.getClip();

        /* When editing a camera clip and the cursor rests on its exclusive end
         * boundary, show (and thus allow editing of) the clip's final point /
         * keyframe — which otherwise belongs to the next clip's first frame. */
        if (clip instanceof CameraClip && this.context.clips != null && this.context.clips.getIndex(clip) >= 0
            && (long) clip.tick.get() + clip.duration.get() == ticks)
        {
            this.context.applyLast(clip, this.position);
        }
    }

    @Override
    public void setup(Camera camera, float transition)
    {
        Film film = this.panel.getData();
        CameraPoseEvaluator evaluator = this.panel.getCameraPoseEvaluator();
        boolean exporting = this.panel.recorder.isExporting();
        float delta = this.getTransition(transition);

        this.lastTransition = transition;

        if (film != null)
        {
            this.setWork(film.camera);
        }

        if (this.context.clips != null)
        {
            long frame = evaluator.getFrameId();

            if (frame == Long.MIN_VALUE || this.sampledFrame != frame || this.sampledTick != this.ticks || this.sampledTransition != delta)
            {
                /* Keep the legacy pose separate from view navigation and other
                 * cameras. Its effects and audio run only at the shared frame boundary. */
                this.position.copy(this.legacyPosition);
                this.apply(null, this.ticks, delta);
                this.legacyPosition.copy(this.position);
                evaluator.seedLegacyPose(this.legacyPosition);
                this.sampledFrame = frame;
                this.sampledTick = this.ticks;
                this.sampledTransition = delta;
            }
        }

        if (exporting && film != null)
        {
            this.position.copy(evaluator.evaluateOutput(this.legacyPosition));
            this.position.apply(camera);

            return;
        }

        if (this.manual != null)
        {
            this.manual.apply(camera);
        }
        else if (this.context.clips != null)
        {
            this.position.copy(Film.LEGACY_CAMERA_ID.equals(this.editorCameraId) ? this.legacyPosition
                : evaluator.evaluateEditedEnd(this.editorCameraId, this.panel.cameraEditor.getClip(), this.legacyPosition));
            this.position.apply(camera);
        }

        this.panel.getController().handleCamera(camera, transition);
    }
}
