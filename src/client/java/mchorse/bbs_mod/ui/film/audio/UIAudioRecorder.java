package mchorse.bbs_mod.ui.film.audio;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.clips.misc.AudioClientClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Client-thread UI facade for one owned microphone capture session. */
public class UIAudioRecorder extends UIElement
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UIAudioRecorder.class);
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final Map<UIFilmPanel, UIAudioRecorder> ACTIVE = new WeakHashMap<>();
    private static final long COUNT_IN_MS = 1000L;
    private static final long HOLD_MS = 500L;
    private static final int HOLD_SQUARE = 60;
    private static String lastInput = "";

    private final OpenALRecorder recorder;
    private final UIFilmPanel filmPanel;
    private final int originCursor;
    private final long generation;
    private final Film originFilm;
    private final Clips originClips;
    private final String requestedName;
    private final String defaultName;
    private boolean transportOwned;
    private final long startTime = System.currentTimeMillis();

    private float volume;
    private float[][] waveform;
    private boolean recording;
    private boolean transportStarted;
    private boolean ended;
    private boolean cancelled;
    private boolean acceptingResult = true;
    private boolean resultHandled;
    private int holdButton = -1;
    private long holdStart;

    /** Legacy facade constructor retained for callers that only render a recorder overlay. */
    public UIAudioRecorder(OpenALRecorder recorder)
    {
        this(null, recorder, 0, 0L, null, null, "", "");
    }

    private UIAudioRecorder(UIFilmPanel filmPanel, OpenALRecorder recorder, int originCursor,
                            long generation, Film originFilm, Clips originClips,
                            String requestedName, String defaultName)
    {
        this.filmPanel = filmPanel;
        this.recorder = recorder;
        this.originCursor = originCursor;
        this.generation = generation;
        this.originFilm = originFilm;
        this.originClips = originClips;
        this.requestedName = requestedName;
        this.defaultName = defaultName;
        this.eventPropagataion(EventPropagation.BLOCK);
    }

    public static void addOption(UIFilmPanel filmPanel, ContextMenuManager menu)
    {
        UIContext context = filmPanel.getContext();
        String suggestion = suggestAudioName(filmPanel);
        String value = lastInput.isEmpty() ? suggestion : lastInput;

        menu.action(Icons.SOUND, UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE, () ->
        {
            UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
                UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_TITLE,
                UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_DESCRIPTION,
                (text) -> arm(filmPanel, context, suggestion, value, text)
            );

            panel.text.setText(value);
            panel.text.path();
            UIOverlay.addOverlay(context, panel);
        });
    }

    private static void arm(UIFilmPanel filmPanel, UIContext context, String suggestion,
                            String defaultName, String input)
    {
        String name = input == null || input.isEmpty() ? suggestion : input;
        Film film = filmPanel.getData();
        Clips clips = filmPanel.cameraEditor == null ? null : filmPanel.cameraEditor.clips.getClips();

        if (film == null || clips == null)
        {
            context.notifyError(UIKeys.GENERAL_ERROR);

            return;
        }

        int origin = filmPanel.getCursor();

        if (filmPanel.isRunning())
        {
            filmPanel.togglePlayback();
        }

        filmPanel.setCursor(origin);
        long generation = NEXT_GENERATION.incrementAndGet();
        AtomicReference<UIAudioRecorder> owner = new AtomicReference<>();
        Path tempDirectory = BBSMod.getAudioFolder().toPath();
        OpenALRecorder recorder = new OpenALRecorder(
            CaptureSpec.mono(),
            tempDirectory,
            result -> Minecraft.getInstance().execute(() ->
            {
                UIAudioRecorder ui = owner.get();

                if (ui != null)
                {
                    ui.handleResult(result);
                }
                else if (result != null && result.temporaryFile() != null)
                {
                    deleteQuietly(result.temporaryFile());
                }
            })
        );
        UIAudioRecorder ui = new UIAudioRecorder(filmPanel, recorder, origin, generation, film, clips, name, defaultName);
        ui.transportOwned = filmPanel.isRunning();
        owner.set(ui);

        synchronized (ACTIVE)
        {
            UIAudioRecorder previous = ACTIVE.put(filmPanel, ui);

            if (previous != null)
            {
                previous.end(context, true);
            }
        }

        ui.full(context.menu.overlay);
        ui.resize();
        context.menu.overlay.add(ui);
    }

    /** Cancel the panel-owned session before its UI owner is discarded. */
    public static void cancelActive(UIFilmPanel panel)
    {
        if (panel == null)
        {
            return;
        }

        UIAudioRecorder recorder;

        synchronized (ACTIVE)
        {
            recorder = ACTIVE.get(panel);
        }

        if (recorder != null)
        {
            UIContext context = panel.getContext();

            if (context != null)
            {
                recorder.cancelSession(context);
            }
            else
            {
                recorder.cancelSession(null);
            }

            try
            {
                recorder.recorder.awaitFinished(1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            recorder.recorder.discardResultFile();

            synchronized (ACTIVE)
            {
                if (ACTIVE.get(panel) == recorder)
                {
                    ACTIVE.remove(panel);
                }
            }
        }
    }

    private static String suggestAudioName(UIFilmPanel filmPanel)
    {
        Film film = filmPanel.getData();
        String base = film == null ? null : film.getId();

        if (base == null || base.isEmpty())
        {
            return StringUtils.createTimestampFilename();
        }

        File folder = BBSMod.getAudioFolder();
        int number = 1;

        while (new File(folder, base + "/" + number + ".wav").exists())
        {
            number++;
        }

        return base + "/" + number;
    }

    private void startRecording()
    {
        if (this.ended || this.filmPanel == null)
        {
            return;
        }

        Thread worker = this.recorder.startAsync();
        this.recording = worker != null
            && (this.recorder.getState() == CaptureState.OPENING || this.recorder.getState() == CaptureState.RECORDING);
    }

    private void startTransportWhenReady()
    {
        if (!this.recording || this.transportStarted || this.ended || this.recorder.getState() != CaptureState.RECORDING)
        {
            return;
        }

        if (!this.isCurrentGeneration())
        {
            this.end(this.filmPanel.getContext(), true);

            return;
        }

        /* The cursor is immutable for the whole session and is set immediately before film playback. */
        this.filmPanel.setCursor(this.originCursor);

        if (!this.filmPanel.isRunning())
        {
            this.filmPanel.togglePlayback();
        }

        this.transportOwned = true;
        this.transportStarted = true;
    }

    private void end(UIContext context, boolean cancel)
    {
        if (cancel)
        {
            this.cancelSession(context);

            return;
        }

        if (this.ended)
        {
            return;
        }

        this.ended = true;

        if (!this.recording || this.recorder.getState() != CaptureState.RECORDING)
        {
            this.cancelSession(context);

            return;
        }

        this.recorder.stop();

        this.retireTransport();

        if (context != null)
        {
            context.render.postRunnable(this::removeFromParent);
        }
    }

    private void cancelSession(UIContext context)
    {
        if (this.cancelled)
        {
            this.recorder.discardResultFile();

            return;
        }

        this.cancelled = true;
        this.acceptingResult = false;
        this.ended = true;

        this.recorder.cancel();
        this.retireTransport();

        if (context != null)
        {
            context.render.postRunnable(this::removeFromParent);
        }
        else if (this.hasParent())
        {
            this.removeFromParent();
        }
    }

    private void retireTransport()
    {
        if (this.filmPanel != null && this.transportOwned)
        {
            if (this.filmPanel.isRunning())
            {
                this.filmPanel.togglePlayback();
            }

            this.filmPanel.setCursor(this.originCursor);
            this.transportOwned = false;
        }
    }

    private boolean isCurrentGeneration()
    {
        if (this.filmPanel == null || this.originFilm == null || this.originClips == null)
        {
            return false;
        }

        synchronized (ACTIVE)
        {
            UIAudioRecorder active = ACTIVE.get(this.filmPanel);

            return active == this
                && active.generation == this.generation
                && this.acceptingResult
                && !this.cancelled
                && this.filmPanel.getData() == this.originFilm
                && this.filmPanel.cameraEditor != null
                && this.filmPanel.cameraEditor.clips.getClips() == this.originClips;
        }
    }

    private void handleResult(CaptureResult result)
    {
        if (this.resultHandled)
        {
            if (result != null && result.temporaryFile() != null)
            {
                deleteQuietly(result.temporaryFile());
            }

            return;
        }

        this.resultHandled = true;
        boolean current = this.isCurrentGeneration();

        if (result == null)
        {
            if (current)
            {
                this.retireTransport();
                this.notifyFailure(CaptureResult.failed(CaptureFailure.CALLBACK_FAILED,
                    new IllegalStateException("Microphone recording returned no result"),
                    CaptureSpec.DEFAULT_SAMPLE_RATE, 1));
            }
            this.retire();

            return;
        }

        if (!result.isReady())
        {
            if (current)
            {
                this.retireTransport();

                if (!result.isCancelled())
                {
                    this.notifyFailure(result);
                }
            }

            this.retire();

            return;
        }

        if (!current || this.cancelled || !this.acceptingResult)
        {
            deleteQuietly(result.temporaryFile());
            this.retire();

            return;
        }

        if (!this.commitFenceOpen(result))
        {
            deleteQuietly(result.temporaryFile());
            this.retire();

            return;
        }

        if (result.frames() <= 0L)
        {
            IllegalStateException cause = new IllegalStateException("The microphone did not capture any samples");

            deleteQuietly(result.temporaryFile());
            this.notifyFailure(CaptureResult.failed(CaptureFailure.DEVICE_READ_FAILED, cause,
                result.sampleRate(), result.channels()));
            this.retire();

            return;
        }

        this.retireTransport();

        Path committed = null;
        AudioClientClip clip = null;
        mchorse.bbs_mod.utils.clips.Clip previous = this.filmPanel.cameraEditor.getClip();
        boolean added = false;

        try
        {
            committed = commitOwnedFile(this.recorder, result, BBSMod.getAudioFolder().toPath(), this.requestedName);

            if (!this.commitFenceOpen(result))
            {
                throw new CommitCancelledException();
            }

            Clips clips = this.originClips;
            clip = new AudioClientClip();
            String relative = audioRelativePath(committed);

            clip.audio.set(Link.assets("audio/" + relative));
            clip.tick.set(this.originCursor);
            clip.duration.set(calculateDurationTicks(result.frames(), result.sampleRate()));
            clip.layer.set(clips.getTopLayer() + 1);

            if (!this.commitFenceOpen(result))
            {
                throw new CommitCancelledException();
            }

            added = true;
            clips.addClip(clip);
            this.filmPanel.cameraEditor.clips.clearSelection();
            this.filmPanel.cameraEditor.clips.pickClip(clip);

            if (!this.recorder.completeCommit(result))
            {
                throw new CommitCancelledException();
            }

            lastInput = this.requestedName.equals(this.defaultName) ? "" : this.requestedName;
        }
        catch (Exception e)
        {
            LOGGER.error("Failed to commit microphone recording", e);

            if (added && clip != null)
            {
                try
                {
                    this.originClips.remove(clip);
                }
                catch (RuntimeException cleanup)
                {
                    e.addSuppressed(cleanup);
                }
            }

            try
            {
                this.filmPanel.cameraEditor.clips.clearSelection();

                if (previous != null)
                {
                    this.filmPanel.cameraEditor.clips.pickClip(previous);
                }
            }
            catch (RuntimeException cleanup)
            {
                e.addSuppressed(cleanup);
            }

            deleteQuietly(committed == null ? result.temporaryFile() : committed);
            CaptureResult failure = this.recorder.markCommitFailed(e);

            if (failure != null && !failure.isCancelled())
            {
                this.notifyFailure(failure);
            }

            this.retire();

            return;
        }

        this.retire();
    }

    private boolean commitFenceOpen(CaptureResult result)
    {
        return this.isCurrentGeneration()
            && this.recorder.beginCommit(result)
            && this.recorder.ownsTemporaryFile(result.temporaryFile());
    }

    public static int calculateDurationTicks(long frames, int sampleRate)
    {
        if (frames <= 0L || sampleRate <= 0)
        {
            throw new IllegalArgumentException("Capture duration requires positive frames and sample rate");
        }

        long numerator = Math.addExact(Math.multiplyExact(frames, 20L), sampleRate - 1L);

        return Math.toIntExact(numerator / sampleRate);
    }

    /** Move only the exact recorder-owned result and never replace an existing asset. */
    public static Path commitOwnedFile(OpenALRecorder recorder, CaptureResult result, Path audioRoot, String name)
        throws IOException
    {
        Path temporary = result == null ? null : result.temporaryFile();

        if (recorder == null || result == null || audioRoot == null || !result.isReady()
            || temporary == null || name == null || name.isBlank()
            || !recorder.beginCommit(result) || !recorder.ownsTemporaryFile(temporary))
        {
            throw new IOException("Invalid recording path");
        }

        Path root = audioRoot.toAbsolutePath().normalize();
        String normalizedName = name.replace('\\', '/');
        Path target = root.resolve(normalizedName + ".wav").normalize();

        if (!target.startsWith(root) || Files.exists(target))
        {
            throw new IOException("Recording target exists or escapes the audio folder");
        }

        Path parent = target.getParent();

        if (parent == null)
        {
            throw new IOException("Recording target has no parent");
        }

        Files.createDirectories(parent);

        /* Without ATOMIC_MOVE, NIO guarantees that an existing target is not replaced. */
        Files.move(temporary, target);

        return target;
    }

    private String audioRelativePath(Path committed)
    {
        Path root = BBSMod.getAudioFolder().toPath().toAbsolutePath().normalize();

        return root.relativize(committed.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private void notifyFailure(CaptureResult result)
    {
        if (this.filmPanel != null && this.filmPanel.getContext() != null)
        {
            this.filmPanel.getContext().notifyError(UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_ERROR
                .format(result == null ? UIKeys.GENERAL_ERROR.get() : result.userMessage()));
        }
    }

    private void retire()
    {
        this.acceptingResult = false;

        synchronized (ACTIVE)
        {
            if (ACTIVE.get(this.filmPanel) == this)
            {
                ACTIVE.remove(this.filmPanel);
            }
        }

        if (this.hasParent())
        {
            this.removeFromParent();
        }
    }

    private static void deleteQuietly(Path path)
    {
        if (path == null)
        {
            return;
        }

        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException failure)
        {
            LOGGER.warn("Failed to remove stale microphone capture {}", path, failure);
        }
    }

    private static final class CommitCancelledException extends IOException
    {
        private CommitCancelledException()
        {
            super("Microphone capture was cancelled before clip commit completed");
        }
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (!this.ended && (context.mouseButton == 0 || context.mouseButton == 1))
        {
            this.holdButton = context.mouseButton;
            this.holdStart = System.currentTimeMillis();

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            this.end(context, true);

            return true;
        }

        return super.subKeyPressed(context);
    }

    private void renderHold(UIContext context)
    {
        if (!Window.isMouseButtonPressed(this.holdButton))
        {
            this.holdButton = -1;

            return;
        }

        float progress = Math.min(1F, (System.currentTimeMillis() - this.holdStart) / (float) HOLD_MS);
        int color = (this.holdButton == 1 ? BBSSettings.negativeColor() : BBSSettings.positiveColor()) & Colors.RGB;
        int cx = context.mouseX;
        int cy = context.mouseY;
        int half = HOLD_SQUARE / 2;
        int fill = (int) (half * progress);

        context.batcher.box(cx - half, cy - half, cx + half, cy + half, Colors.A50 | color);
        context.batcher.box(cx - fill, cy - fill, cx + fill, cy + fill, Colors.A100 | color);

        if (progress >= 1F)
        {
            boolean cancel = this.holdButton == 1;

            this.holdButton = -1;
            this.end(context, cancel);
        }
    }

    @Override
    public void render(UIContext context)
    {
        long elapsed = System.currentTimeMillis() - this.startTime;

        if (!this.recording && !this.ended && elapsed >= COUNT_IN_MS)
        {
            this.startRecording();
        }

        this.startTransportWhenReady();
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.A50);

        if (this.recording)
        {
            this.renderRecording(context);
        }
        else
        {
            this.renderCountdown(context, elapsed);
        }

        if (this.holdButton != -1)
        {
            this.renderHold(context);
        }

        super.render(context);
    }

    private void renderCountdown(UIContext context, long elapsed)
    {
        float remaining = Math.max(0F, (COUNT_IN_MS - elapsed) / 1000F);
        IKey key = UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_COUNTDOWN;
        String label = key == null ? String.format("Get ready in %.1f", remaining) : key.format(remaining).get();
        int x = this.area.mx();
        int y = this.area.my();
        int w = context.batcher.getFont().getWidth(label);

        context.batcher.icon(Icons.SPHERE, Colors.setA(Colors.RED, 0.5F), x - w / 2 - 12,
            y + context.batcher.getFont().getHeight() / 2, 0.5F, 0.5F);
        context.batcher.textShadow(label, x - w / 2, y);
    }

    private void renderRecording(UIContext context)
    {
        this.volume = Lerps.lerp(this.volume, Interpolations.CUBIC_OUT.interpolate(0F, 1F, this.recorder.getVolume()), 0.5F);
        String label = UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_LABEL
            .format(this.recorder.getTime() / 1000F).get();
        int x = this.area.mx();
        int y = this.area.my();
        int w = context.batcher.getFont().getWidth(label);
        double meter = Interpolations.EXP_OUT.interpolate(0F, 1F, this.volume);

        context.batcher.icon(Icons.SPHERE, Colors.RED | Colors.A100, x - w / 2 - 12,
            y + context.batcher.getFont().getHeight() / 2, 0.5F, 0.5F);
        context.batcher.textShadow(label, x - w / 2, y);
        label = UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_SUBLABEL.get();
        w = context.batcher.getFont().getWidth(label);
        context.batcher.textShadow(label, x - w / 2, this.area.y(0.75F));
        x -= w / 2;
        context.batcher.box(x, y + 16, x + w, y + 20, Colors.A100);
        context.batcher.box(x, y + 16, x + (int) (w * meter), y + 20, Colors.WHITE);

        this.waveform = this.recorder.getWaveform(this.waveform);
        this.renderWaveform(context, x, y + 24, w, 28);
    }

    private void renderWaveform(UIContext context, int x, int top, int width, int height)
    {
        if (width <= 0 || this.waveform == null)
        {
            return;
        }

        float[] peak = this.waveform[0];
        float[] average = this.waveform[1];
        int n = peak.length;
        int middle = top + height / 2;
        int half = height / 2;
        int averageColor = Colors.mulRGB(Colors.WHITE, 0.8F);

        context.batcher.box(x, top, x + width, top + height, Colors.A50);

        for (int px = 0; px < width; px++)
        {
            int index = Math.min(n - 1, (int) (px / (float) width * n));
            int cx = x + px;
            int peakAmp = (int) (Interpolations.EXP_OUT.interpolate(0F, 1F, Math.min(1F, peak[index])) * half);
            int averageAmp = (int) (Interpolations.EXP_OUT.interpolate(0F, 1F, Math.min(1F, average[index])) * half);

            context.batcher.box(cx, middle - peakAmp, cx + 1, middle + peakAmp + 1, Colors.WHITE);
            context.batcher.box(cx, middle - averageAmp, cx + 1, middle + averageAmp + 1, averageColor);
        }
    }
}
