package mchorse.bbs_mod.ui.film.audio;

import mchorse.bbs_mod.audio.Wave;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One owner for a microphone device, bounded PCM sink, worker and terminal result.
 * The legacy consumer constructor is retained as a deliberately bounded adapter.
 */
public class OpenALRecorder implements Runnable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenALRecorder.class);
    private static final int SAMPLE_RATE = CaptureSpec.DEFAULT_SAMPLE_RATE;
    private static final int FORMAT = AL10.AL_FORMAT_MONO16;
    private static final int BUFFER_SAMPLES = SAMPLE_RATE;
    private static final int CHUNK_FRAMES = 4096;
    private static final int WAVEFORM_RESOLUTION = 256;
    private static final int WAVEFORM_POINTS_PER_POLL = 8;
    /** Legacy Wave materialization is bounded even though the production path is file-backed. */
    private static final long LEGACY_MAX_BYTES = 64L * 1024L * 1024L;

    private final CaptureBackend backend;
    private final CaptureSpec spec;
    private final Path tempDirectory;
    private final Consumer<CaptureResult> resultConsumer;
    private final Consumer<Wave> legacyConsumer;
    private final AtomicReference<CaptureState> state = new AtomicReference<>(CaptureState.ARMED);
    private final AtomicReference<CaptureResult> result = new AtomicReference<>();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean terminalDelivered = new AtomicBoolean();
    /** Claims the one resource-finalization path (including manual callers). */
    private final AtomicBoolean finalizationStarted = new AtomicBoolean();
    /** Becomes true only after the client has inserted the committed clip. */
    private final AtomicBoolean commitComplete = new AtomicBoolean();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final Object terminalLock = new Object();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final float[] waveformPeak = new float[WAVEFORM_RESOLUTION];
    private final float[] waveformAverage = new float[WAVEFORM_RESOLUTION];

    private volatile long captureDevice;
    private volatile StreamingWavSink sink;
    private volatile ByteBuffer chunkBuffer;
    private volatile Thread worker;
    private volatile long startTime;
    private volatile float volume;
    private volatile int waveformHead;
    private volatile boolean manuallyInitialized;
    private volatile Throwable cleanupFailure;

    /** Legacy constructor retained for source and binary compatibility. */
    public OpenALRecorder(Consumer<Wave> consumer)
    {
        this(new OpenALCaptureBackend(), legacySpec(), defaultTempDirectory(), null, consumer);
    }

    /** Result-oriented recorder used by the UI and by deterministic fake-backend tests. */
    public OpenALRecorder(CaptureSpec spec, Path tempDirectory, Consumer<CaptureResult> resultConsumer)
    {
        this(new OpenALCaptureBackend(), spec, tempDirectory, resultConsumer, null);
    }

    /** Injectable backend constructor. The backend is session-owned and used on the worker thread. */
    public OpenALRecorder(CaptureBackend backend, CaptureSpec spec, Path tempDirectory,
                          Consumer<CaptureResult> resultConsumer)
    {
        this(backend, spec, tempDirectory, resultConsumer, null);
    }

    /**
     * Combined adapter seam used to verify legacy callback and typed terminal ordering.
     * Production UI callers should use the result-oriented constructor above.
     */
    public OpenALRecorder(CaptureBackend backend, CaptureSpec spec, Path tempDirectory,
                          Consumer<CaptureResult> resultConsumer, Consumer<Wave> legacyConsumer)
    {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.tempDirectory = tempDirectory == null ? defaultTempDirectory() : tempDirectory;
        this.resultConsumer = resultConsumer;
        this.legacyConsumer = legacyConsumer;
    }

    private static Path defaultTempDirectory()
    {
        return Paths.get(System.getProperty("java.io.tmpdir"), "bbs-audio-captures");
    }

    private static CaptureSpec legacySpec()
    {
        long maxFrames = LEGACY_MAX_BYTES / 2L;

        return new CaptureSpec(SAMPLE_RATE, 1, BUFFER_SAMPLES, maxFrames);
    }

    public CaptureSpec getSpec()
    {
        return this.spec;
    }

    public CaptureState getState()
    {
        return this.state.get();
    }

    public CaptureResult getResult()
    {
        return this.result.get();
    }

    public Throwable getCleanupFailure()
    {
        return this.cleanupFailure;
    }

    public boolean isRecording()
    {
        return this.state.get() == CaptureState.RECORDING;
    }

    /** Request a draining stop. The worker performs the final device drain before success. */
    public void stop()
    {
        this.stopRequested.set(true);

        while (true)
        {
            CaptureState current = this.state.get();

            if (current == CaptureState.ARMED)
            {
                if (this.state.compareAndSet(current, CaptureState.STOPPING))
                {
                    this.publishCancelledIfNeverStarted();
                    return;
                }
            }
            else if (current == CaptureState.RECORDING)
            {
                if (this.state.compareAndSet(current, CaptureState.STOPPING))
                {
                    this.interruptWorker();
                    return;
                }
            }
            else
            {
                this.interruptWorker();
                return;
            }
        }
    }

    /** Cancel and discard all session-owned data. Cancellation wins any later success race. */
    public void cancel()
    {
        Path discard = null;
        boolean finishHere;

        synchronized (this.terminalLock)
        {
            CaptureState current = this.state.get();

            if (this.commitComplete.get() || current == CaptureState.FAILED || current == CaptureState.CANCELLED)
            {
                return;
            }

            this.cancelRequested.set(true);
            this.stopRequested.set(true);
            this.state.set(CaptureState.CANCELLED);

            CaptureResult currentResult = this.result.get();

            if (currentResult != null && (currentResult.isReady() || currentResult.isSuccess()))
            {
                discard = currentResult.temporaryFile();
                this.result.set(CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels()));
                this.sink = null;
            }

            finishHere = this.worker == null;
        }

        this.interruptWorker();
        this.deletePath(discard);

        Thread captureThread = this.worker;

        if (finishHere || captureThread == null || !captureThread.isAlive())
        {
            this.finishCancellation();
            this.finished.countDown();
        }
    }

    /** Delete a published temporary result when its client-side owner is cancelled. */
    public void discardResultFile()
    {
        CaptureResult terminal;

        synchronized (this.terminalLock)
        {
            terminal = this.result.get();
        }

        if (terminal != null)
        {
            this.deletePath(terminal.temporaryFile());
        }
    }

    /**
     * Validate that this exact delivered success still owns its temporary file.
     * Cancellation may still win until {@link #completeCommit(CaptureResult)}.
     */
    public boolean beginCommit(CaptureResult candidate)
    {
        synchronized (this.terminalLock)
        {
            CaptureResult current = this.result.get();

            return candidate != null
                && candidate == current
                && candidate.isReady()
                && candidate.temporaryFile() != null
                && this.state.get() == CaptureState.READY
                && !this.cancelRequested.get()
                && !this.commitComplete.get();
        }
    }

    /** Verify that a callback still refers to this session's own finalized path. */
    public boolean ownsTemporaryFile(Path candidate)
    {
        if (candidate == null)
        {
            return false;
        }

        synchronized (this.terminalLock)
        {
            CaptureResult current = this.result.get();

            return current != null && current.isReady() && current.temporaryFile() != null
                && current.temporaryFile().toAbsolutePath().normalize()
                    .equals(candidate.toAbsolutePath().normalize());
        }
    }

    /** Mark the client-thread file/clip transaction complete, closing the cancellation window. */
    public boolean completeCommit(CaptureResult candidate)
    {
        synchronized (this.terminalLock)
        {
            if (candidate == null
                || candidate != this.result.get()
                || !candidate.isReady()
                || this.state.get() != CaptureState.READY
                || this.cancelRequested.get())
            {
                return false;
            }

            CaptureResult success = CaptureResult.success(candidate.temporaryFile(), candidate.frames(),
                candidate.sampleRate(), candidate.channels());

            this.result.set(success);
            this.state.set(CaptureState.SUCCEEDED);
            this.commitComplete.set(true);
            this.sink = null;

            return true;
        }
    }

    /** Convert a delivered capture success into the typed client-side commit terminal. */
    public CaptureResult markCommitFailed(Throwable cause)
    {
        CaptureResult failure = CaptureResult.failed(CaptureFailure.COMMIT_FAILED, cause,
            this.spec.sampleRate(), this.spec.channels());
        CaptureResult output = failure;

        synchronized (this.terminalLock)
        {
            CaptureResult current = this.result.get();

            if (this.cancelRequested.get() || this.state.get() == CaptureState.CANCELLED)
            {
                output = current != null && current.isCancelled()
                    ? current : CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels());
                this.result.set(output);
                this.state.set(CaptureState.CANCELLED);
            }
            else if (!this.commitComplete.get() && current != null && current.isReady())
            {
                this.result.set(failure);
                this.state.set(CaptureState.FAILED);
                this.sink = null;
            }
            else if (current != null)
            {
                output = current;
            }
        }

        if (output == failure)
        {
            this.logFailure(CaptureFailure.COMMIT_FAILED, cause);
        }

        return output;
    }

    private void interruptWorker()
    {
        Thread thread = this.worker;

        if (thread != null && thread != Thread.currentThread())
        {
            thread.interrupt();
        }
    }

    private void publishCancelledIfNeverStarted()
    {
        this.state.set(CaptureState.CANCELLED);
        this.finishCancellation();

        Thread captureThread = this.worker;

        if (captureThread == null || !captureThread.isAlive())
        {
            this.finished.countDown();
        }
    }

    private void finishCancellation()
    {
        if (!this.finalizationStarted.compareAndSet(false, true))
        {
            return;
        }

        this.abortResources();
        this.publish(CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels()));
    }

    public long getTime()
    {
        long started = this.startTime;

        return started == 0L ? 0L : Math.max(0L, System.currentTimeMillis() - started);
    }

    public float getVolume()
    {
        return this.volume;
    }

    /** Copy a bounded chronological waveform snapshot for UI rendering. */
    public synchronized float[][] getWaveform(float[][] out)
    {
        int n = this.waveformPeak.length;

        if (out == null || out.length != 2 || out[0] == null || out[1] == null
            || out[0].length != n || out[1].length != n)
        {
            out = new float[][] { new float[n], new float[n] };
        }

        for (int i = 0; i < n; i++)
        {
            int index = (this.waveformHead + i) % n;

            out[0][i] = this.waveformPeak[index];
            out[1][i] = this.waveformAverage[index];
        }

        return out;
    }

    /** Compatibility initialization entry point. Prefer starting a Runnable for new code. */
    public void init()
    {
        if (this.state.compareAndSet(CaptureState.ARMED, CaptureState.OPENING))
        {
            this.startTime = System.currentTimeMillis();

            try
            {
                this.openCapture();

                if (this.cancelRequested.get())
                {
                    this.state.set(CaptureState.CANCELLED);
                    this.finishCancellation();
                }
                else if (this.stopRequested.get())
                {
                    this.state.set(CaptureState.STOPPING);
                    this.finishCapture();
                }
                else if (this.state.compareAndSet(CaptureState.OPENING, CaptureState.RECORDING))
                {
                    this.manuallyInitialized = true;
                }
            }
            catch (CaptureException e)
            {
                this.finishFailure(e.failure, e.cause);
                throw e;
            }
        }
    }

    /** Poll one bounded chunk for compatibility callers that drive the recorder manually. */
    public void pollAndProcess()
    {
        if (this.state.get() != CaptureState.RECORDING || this.captureDevice == 0L)
        {
            return;
        }

        try
        {
            this.pollAvailable();
        }
        catch (CaptureException e)
        {
            this.finishFailure(e.failure, e.cause);
        }
    }

    /** Idempotent compatibility cleanup. Manual callers get the same drain/finalize order. */
    public void cleanup()
    {
        if (this.manuallyInitialized && this.worker == null)
        {
            this.stopRequested.set(true);
            this.state.compareAndSet(CaptureState.RECORDING, CaptureState.STOPPING);
            this.finishCapture();
            this.finished.countDown();

            return;
        }

        this.stop();

        Thread thread = this.worker;

        if (thread != null && thread != Thread.currentThread())
        {
            try
            {
                thread.join(2000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Start the worker once; useful for callers that do not need to construct Thread manually. */
    public synchronized Thread startAsync()
    {
        if (this.worker == null)
        {
            Thread created;

            try
            {
                created = this.createWorker();
                this.worker = Objects.requireNonNull(created, "Capture worker factory returned null");
                created.start();
            }
            catch (RuntimeException | Error failure)
            {
                this.worker = null;
                this.finishFailure(CaptureFailure.DEVICE_START_FAILED, failure);
                this.finished.countDown();

                return null;
            }
        }

        return this.worker;
    }

    /** Test seam for failures that occur before the recorder worker can start. */
    protected Thread createWorker()
    {
        return new Thread(this, "BBS microphone recorder");
    }

    public void awaitFinished(long timeoutMillis) throws InterruptedException
    {
        this.finished.await(Math.max(0L, timeoutMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void run()
    {
        if (this.worker == null)
        {
            this.worker = Thread.currentThread();
        }

        try
        {
            if (this.cancelRequested.get())
            {
                this.state.set(CaptureState.CANCELLED);
                this.finishCancellation();
                return;
            }

            if (!this.state.compareAndSet(CaptureState.ARMED, CaptureState.OPENING))
            {
                if (this.state.get() == CaptureState.CANCELLED)
                {
                    this.finishCancellation();
                }

                return;
            }

            this.startTime = System.currentTimeMillis();
            this.openCapture();

            if (this.cancelRequested.get())
            {
                this.state.set(CaptureState.CANCELLED);
                this.finishCancellation();
                return;
            }

            if (this.stopRequested.get())
            {
                this.state.compareAndSet(CaptureState.OPENING, CaptureState.STOPPING);
                this.finishCapture();

                return;
            }

            if (!this.state.compareAndSet(CaptureState.OPENING, CaptureState.RECORDING))
            {
                this.finishCancellation();

                return;
            }

            while (this.state.get() == CaptureState.RECORDING && !this.stopRequested.get() && !this.cancelRequested.get())
            {
                this.pollAvailable();

                try
                {
                    Thread.sleep(25L);
                }
                catch (InterruptedException e)
                {
                    if (!this.stopRequested.get() && !this.cancelRequested.get())
                    {
                        this.finishFailure(CaptureFailure.DEVICE_READ_FAILED, e);

                        Thread.currentThread().interrupt();

                        return;
                    }

                    /* stop()/cancel() interrupts the polling sleep to enter the terminal path below. */
                    Thread.currentThread().interrupt();
                }
            }

            if (this.cancelRequested.get() || this.state.get() == CaptureState.CANCELLED)
            {
                this.state.set(CaptureState.CANCELLED);
                this.finishCancellation();
            }
            else
            {
                this.state.compareAndSet(CaptureState.RECORDING, CaptureState.STOPPING);
                this.finishCapture();
            }
        }
        catch (CaptureException e)
        {
            this.finishFailure(e.failure, e.cause);
        }
        catch (RuntimeException e)
        {
            this.finishFailure(CaptureFailure.DEVICE_READ_FAILED, e);
        }
        finally
        {
            this.finished.countDown();
        }
    }

    private void openCapture()
    {
        try
        {
            this.spec.validate();
        }
        catch (RuntimeException e)
        {
            throw new CaptureException(CaptureFailure.UNSUPPORTED_MODE, e);
        }

        try
        {
            this.chunkBuffer = MemoryUtil.memAlloc(Math.multiplyExact(CHUNK_FRAMES, this.spec.bytesPerFrame()));
        }
        catch (RuntimeException | Error e)
        {
            throw new CaptureException(CaptureFailure.STORAGE_FAILED, e);
        }

        final String deviceName;

        try
        {
            deviceName = this.backend.defaultDevice();
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_ENUMERATION_FAILED, e);
        }

        if (deviceName == null || deviceName.isBlank())
        {
            throw new CaptureException(CaptureFailure.NO_DEVICE, null);
        }

        try
        {
            this.captureDevice = this.backend.open(deviceName, this.spec);
        }
        catch (CaptureBackend.UnsupportedModeException e)
        {
            throw new CaptureException(CaptureFailure.UNSUPPORTED_MODE, e);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_OPEN_FAILED, e);
        }

        if (this.captureDevice == 0L)
        {
            throw new CaptureException(CaptureFailure.DEVICE_OPEN_FAILED, null);
        }

        try
        {
            this.backend.start(this.captureDevice);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_START_FAILED, e);
        }

        try
        {
            this.sink = StreamingWavSink.create(this.tempDirectory, this.spec);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.STORAGE_FAILED, e);
        }
    }

    private void pollAvailable()
    {
        int available;

        try
        {
            available = this.backend.availableFrames(this.captureDevice);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED, e);
        }

        if (available < 0)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED,
                new IllegalStateException("Negative capture sample count"));
        }

        if (available >= this.spec.ringSamples())
        {
            throw new CaptureException(CaptureFailure.CAPTURE_OVERFLOW,
                new IllegalStateException("Capture ring is full; samples may have been dropped"));
        }

        while (available > 0)
        {
            if (this.cancelRequested.get() || this.stopRequested.get())
            {
                return;
            }

            StreamingWavSink currentSink = this.sink;

            if (currentSink == null)
            {
                throw new CaptureException(CaptureFailure.STORAGE_FAILED,
                    new IllegalStateException("Capture sink is unavailable"));
            }

            long remaining = this.spec.maxFrames() - currentSink.frames();
            int count = Math.min(available, CHUNK_FRAMES);

            if (remaining < count)
            {
                throw new CaptureException(CaptureFailure.DURATION_LIMIT,
                    new IllegalStateException("Capture duration limit exceeded"));
            }

            ByteBuffer chunk = this.readChunk(count);

            try
            {
                this.captureWaveform(chunk, count);
                this.beforeSinkWrite(count);
                currentSink.write(chunk, count);
            }
            catch (CaptureException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new CaptureException(CaptureFailure.STORAGE_FAILED, e);
            }

            try
            {
                available = this.backend.availableFrames(this.captureDevice);
            }
            catch (Exception e)
            {
                throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED, e);
            }

            if (available >= this.spec.ringSamples())
            {
                throw new CaptureException(CaptureFailure.CAPTURE_OVERFLOW,
                    new IllegalStateException("Capture ring is full; samples may have been dropped"));
            }
        }
    }

    private ByteBuffer readChunk(int frames)
    {
        ByteBuffer chunk = this.chunkBuffer;
        int bytes = Math.multiplyExact(frames, this.spec.bytesPerFrame());

        if (chunk == null || chunk.capacity() < bytes)
        {
            throw new CaptureException(CaptureFailure.STORAGE_FAILED,
                new IllegalStateException("Capture chunk buffer is unavailable"));
        }

        chunk.clear();
        chunk.order(ByteOrder.LITTLE_ENDIAN);
        chunk.limit(bytes);

        try
        {
            this.backend.read(this.captureDevice, chunk, frames);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED, e);
        }

        if (chunk.position() != bytes)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED,
                new IllegalStateException("Capture backend returned an incomplete chunk"));
        }

        chunk.flip();

        return chunk;
    }

    private synchronized void captureWaveform(ByteBuffer chunk, int frames)
    {
        this.volume = 0F;
        int channels = this.spec.channels();
        int points = Math.min(WAVEFORM_POINTS_PER_POLL, Math.max(1, frames));
        int per = Math.max(1, (frames + points - 1) / points);
        int actualPoints = Math.max(1, (frames + per - 1) / per);

        for (int frame = 0; frame < frames; frame++)
        {
            for (int channel = 0; channel < channels; channel++)
            {
                int offset = (frame * channels + channel) * 2;
                float sample = Math.abs(chunk.getShort(offset) / 32768F);

                this.volume = Math.max(this.volume, sample);
            }
        }

        for (int point = 0; point < actualPoints; point++)
        {
            int start = point * per;
            int end = Math.min(frames, start + per);
            float peak = 0F;
            float sum = 0F;
            int count = 0;

            for (int frame = start; frame < end; frame++)
            {
                for (int channel = 0; channel < channels; channel++)
                {
                    int offset = (frame * channels + channel) * 2;
                    float sample = Math.abs(chunk.getShort(offset) / 32768F);

                    peak = Math.max(peak, sample);
                    sum += sample;
                    count++;
                }
            }

            this.waveformPeak[this.waveformHead] = peak;
            this.waveformAverage[this.waveformHead] = count == 0 ? 0F : sum / count;
            this.waveformHead = (this.waveformHead + 1) % this.waveformPeak.length;
        }
    }

    /** stop -> final drain -> sink finalize -> device close -> publish. */
    private void finishCapture()
    {
        if (!this.finalizationStarted.compareAndSet(false, true))
        {
            return;
        }

        if (this.cancelRequested.get() || this.state.get() == CaptureState.CANCELLED)
        {
            this.abortResources();
            this.publish(CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels()));

            return;
        }

        if (this.captureDevice == 0L && this.sink == null)
        {
            this.publish(CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels()));

            return;
        }

        CaptureFailure failure = null;
        Throwable cause = null;

        if (this.captureDevice != 0L)
        {
            try
            {
                this.backend.stop(this.captureDevice);
            }
            catch (Exception e)
            {
                failure = CaptureFailure.DEVICE_STOP_FAILED;
                cause = e;
            }

            if (failure == null)
            {
                try
                {
                    this.drainTail();
                }
                catch (CaptureException e)
                {
                    failure = e.failure;
                    cause = e.cause;
                }
            }
        }

        StreamingWavSink currentSink = this.sink;
        Path path = currentSink == null ? null : currentSink.path();
        long frames = currentSink == null ? 0L : currentSink.frames();

        if (failure == null && currentSink != null)
        {
            try
            {
                this.beforeSinkFinalize();
                currentSink.finish();
                frames = currentSink.frames();
            }
            catch (Exception e)
            {
                failure = CaptureFailure.STORAGE_FAILED;
                cause = e;
            }
        }

        Throwable closeFailure = this.closeDevice();
        this.releaseChunkBuffer();

        if (closeFailure != null)
        {
            if (failure == null)
            {
                failure = CaptureFailure.DEVICE_CLOSE_FAILED;
                cause = closeFailure;
            }
            else if (cause != null)
            {
                cause.addSuppressed(closeFailure);
            }
        }

        if (failure == null && frames <= 0L)
        {
            failure = CaptureFailure.DEVICE_READ_FAILED;
            cause = new IllegalStateException("The microphone did not capture any complete frames");
        }

        if (failure != null || this.cancelRequested.get() || this.state.get() == CaptureState.CANCELLED)
        {
            this.abortSinkOnly();

            if (failure != null)
            {
                this.logFailure(failure, cause);
            }

            this.state.set(this.cancelRequested.get() ? CaptureState.CANCELLED : CaptureState.FAILED);
            this.publish(this.cancelRequested.get()
                ? CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels())
                : CaptureResult.failed(failure == null ? CaptureFailure.STORAGE_FAILED : failure, cause,
                    this.spec.sampleRate(), this.spec.channels()));

            return;
        }

        CaptureResult ready = CaptureResult.ready(path, frames, this.spec.sampleRate(), this.spec.channels());

        if (!this.state.compareAndSet(CaptureState.STOPPING, CaptureState.READY))
        {
            /* A cancel can win while the sink was being finalized. */
            this.abortSinkOnly();
            this.publish(CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels()));

            return;
        }

        this.publish(ready);
    }

    private void drainTail()
    {
        int available;

        try
        {
            available = this.backend.availableFrames(this.captureDevice);
        }
        catch (Exception e)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED, e);
        }

        if (available < 0)
        {
            throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED,
                new IllegalStateException("Negative capture sample count"));
        }

        if (available > this.spec.ringSamples())
        {
            throw new CaptureException(CaptureFailure.CAPTURE_OVERFLOW,
                new IllegalStateException("Capture ring filled before final drain"));
        }

        while (available > 0)
        {
            if (this.cancelRequested.get() || this.state.get() == CaptureState.CANCELLED)
            {
                return;
            }

            int count = Math.min(available, CHUNK_FRAMES);
            StreamingWavSink currentSink = this.sink;
            long remaining = this.spec.maxFrames() - (currentSink == null ? 0L : currentSink.frames());

            if (remaining < count)
            {
                throw new CaptureException(CaptureFailure.DURATION_LIMIT,
                    new IllegalStateException("Capture duration limit exceeded during final drain"));
            }

            ByteBuffer chunk = this.readChunk(count);

            try
            {
                this.captureWaveform(chunk, count);
                this.beforeSinkWrite(count);
                currentSink.write(chunk, count);
            }
            catch (Exception e)
            {
                throw new CaptureException(CaptureFailure.STORAGE_FAILED, e);
            }

            try
            {
                available = this.backend.availableFrames(this.captureDevice);
            }
            catch (Exception e)
            {
                throw new CaptureException(CaptureFailure.DEVICE_READ_FAILED, e);
            }

            if (available > this.spec.ringSamples())
            {
                throw new CaptureException(CaptureFailure.CAPTURE_OVERFLOW,
                    new IllegalStateException("Capture ring filled during final drain"));
            }
        }
    }

    private Throwable closeDevice()
    {
        long device = this.captureDevice;

        if (device == 0L)
        {
            return null;
        }

        try
        {
            this.backend.close(device);
            return null;
        }
        catch (Exception e)
        {
            return e;
        }
        finally
        {
            this.captureDevice = 0L;
        }
    }

    /** Deterministic storage seam used by lifecycle tests; production performs no extra work. */
    protected void beforeSinkWrite(int frames) throws Exception
    {}

    /** Deterministic finalization seam used by lifecycle tests; production performs no extra work. */
    protected void beforeSinkFinalize() throws Exception
    {}

    private void abortResources()
    {
        long device = this.captureDevice;

        if (device != 0L)
        {
            try
            {
                this.backend.stop(device);
            }
            catch (Exception e)
            {
                this.cleanupFailure = appendFailure(this.cleanupFailure, e);
            }

            try
            {
                this.backend.close(device);
            }
            catch (Exception e)
            {
                this.cleanupFailure = appendFailure(this.cleanupFailure, e);
            }
            finally
            {
                this.captureDevice = 0L;
            }
        }

        this.abortSinkOnly();
        this.releaseChunkBuffer();
    }

    private void releaseChunkBuffer()
    {
        ByteBuffer buffer = this.chunkBuffer;

        if (buffer != null)
        {
            this.chunkBuffer = null;
            MemoryUtil.memFree(buffer);
        }
    }

    private void abortSinkOnly()
    {
        StreamingWavSink currentSink = this.sink;

        if (currentSink != null)
        {
            try
            {
                currentSink.abort();
            }
            catch (Exception e)
            {
                this.cleanupFailure = appendFailure(this.cleanupFailure, e);
            }
            finally
            {
                this.sink = null;
            }
        }
    }

    private Throwable appendFailure(Throwable first, Throwable next)
    {
        if (first == null)
        {
            return next;
        }

        first.addSuppressed(next);

        return first;
    }

    private void finishFailure(CaptureFailure failure, Throwable cause)
    {
        if (this.cancelRequested.get())
        {
            this.state.set(CaptureState.CANCELLED);
            this.finishCancellation();

            return;
        }

        if (!this.finalizationStarted.compareAndSet(false, true))
        {
            return;
        }

        this.state.set(CaptureState.FAILED);
        this.logFailure(failure, cause);
        this.abortResources();
        this.publish(CaptureResult.failed(failure, cause, this.spec.sampleRate(), this.spec.channels()));
    }

    private void logFailure(CaptureFailure failure, Throwable cause)
    {
        if (failure == null || !this.failureLogged.compareAndSet(false, true))
        {
            return;
        }

        if (cause == null)
        {
            LOGGER.error("Microphone capture failed at {}", failure);
        }
        else
        {
            LOGGER.error("Microphone capture failed at {}", failure, cause);
        }
    }

    private void publish(CaptureResult terminal)
    {
        CaptureResult delivery = terminal;
        Path discard = null;

        synchronized (this.terminalLock)
        {
            if (this.terminalDelivered.get())
            {
                return;
            }

            if ((delivery.isReady() || delivery.isSuccess()) && this.cancelRequested.get())
            {
                discard = delivery.temporaryFile();
                delivery = CaptureResult.cancelled(this.spec.sampleRate(), this.spec.channels());
                this.state.set(CaptureState.CANCELLED);
            }

            this.result.set(delivery);
            this.state.set(delivery.isReady() ? CaptureState.READY
                : delivery.isSuccess() ? CaptureState.SUCCEEDED
                : delivery.isCancelled() ? CaptureState.CANCELLED : CaptureState.FAILED);
            this.terminalDelivered.set(true);
        }

        this.deletePath(discard);
        this.deliver(delivery);
    }

    private void deliver(CaptureResult terminal)
    {
        CaptureResult observerResult = terminal;

        if (terminal.isReady() && this.legacyConsumer != null)
        {
            try
            {
                Wave wave = this.materializeLegacy(terminal);
                boolean invoke;

                synchronized (this.terminalLock)
                {
                    invoke = this.result.get() == terminal && !this.cancelRequested.get();
                    observerResult = this.result.get();
                }

                if (invoke)
                {
                    this.legacyConsumer.accept(wave);
                }

                synchronized (this.terminalLock)
                {
                    if (this.result.get() == terminal && !this.cancelRequested.get())
                    {
                        CaptureResult success = CaptureResult.success(terminal.temporaryFile(), terminal.frames(),
                            terminal.sampleRate(), terminal.channels());

                        this.result.set(success);
                        this.state.set(CaptureState.SUCCEEDED);
                        this.commitComplete.set(true);
                        observerResult = success;
                    }
                    else
                    {
                        observerResult = this.result.get();
                    }
                }
            }
            catch (Exception e)
            {
                this.logFailure(CaptureFailure.CALLBACK_FAILED, e);
                this.cleanupFailure = appendFailure(this.cleanupFailure, e);

                synchronized (this.terminalLock)
                {
                    if (!this.cancelRequested.get() && this.result.get() == terminal)
                    {
                        CaptureResult failure = CaptureResult.failed(CaptureFailure.CALLBACK_FAILED, e,
                            this.spec.sampleRate(), this.spec.channels());

                        this.state.set(CaptureState.FAILED);
                        this.result.set(failure);
                        observerResult = failure;
                    }
                    else
                    {
                        observerResult = this.result.get();
                    }
                }
            }
            finally
            {
                this.deletePath(terminal.temporaryFile());
            }
        }

        if (this.resultConsumer != null)
        {
            synchronized (this.terminalLock)
            {
                CaptureResult current = this.result.get();

                if (current != null)
                {
                    observerResult = current;
                }
            }

            try
            {
                this.resultConsumer.accept(observerResult);
            }
            catch (Exception e)
            {
                this.logFailure(CaptureFailure.CALLBACK_FAILED, e);
                this.cleanupFailure = appendFailure(this.cleanupFailure, e);
                this.deletePath(observerResult.temporaryFile());

                synchronized (this.terminalLock)
                {
                    if (!this.cancelRequested.get() && this.result.get() == observerResult
                        && observerResult.isReady() && !this.commitComplete.get())
                    {
                        this.state.set(CaptureState.FAILED);
                        this.result.set(CaptureResult.failed(CaptureFailure.CALLBACK_FAILED, e,
                            this.spec.sampleRate(), this.spec.channels()));
                    }
                }
            }
        }
    }

    private Wave materializeLegacy(CaptureResult terminal) throws Exception
    {
        if (terminal.temporaryFile() == null)
        {
            throw new IllegalStateException("Successful capture has no temporary file");
        }

        long bytes = Math.multiplyExact(terminal.frames(), (long) this.spec.bytesPerFrame());

        if (bytes > LEGACY_MAX_BYTES || bytes > Integer.MAX_VALUE)
        {
            throw new IllegalStateException("Legacy capture adapter limit exceeded");
        }

        byte[] pcm = java.nio.file.Files.readAllBytes(terminal.temporaryFile());
        int header = 44;

        if (pcm.length < header || pcm.length - header < bytes)
        {
            throw new IllegalStateException("Finalized WAV is truncated");
        }

        byte[] payload = new byte[Math.toIntExact(bytes)];
        System.arraycopy(pcm, header, payload, 0, payload.length);

        return new Wave(1, this.spec.channels(), this.spec.sampleRate(), 16, payload);
    }

    private void deletePath(Path path)
    {
        if (path == null)
        {
            return;
        }

        try
        {
            java.nio.file.Files.deleteIfExists(path);
        }
        catch (Exception e)
        {
            this.cleanupFailure = appendFailure(this.cleanupFailure, e);
        }
    }

    private static final class CaptureException extends RuntimeException
    {
        private final CaptureFailure failure;
        private final Throwable cause;

        private CaptureException(CaptureFailure failure, Throwable cause)
        {
            super(failure == null ? null : failure.name(), cause);
            this.failure = failure;
            this.cause = cause;
        }
    }

    private static final class OpenALCaptureBackend implements CaptureBackend
    {
        @Override
        public String defaultDevice()
        {
            clearError(0L);
            String name = ALC11.alcGetString(0, ALC11.ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER);
            checkError(0L, "enumerate capture devices");

            return name;
        }

        @Override
        public long open(String deviceName, CaptureSpec spec) throws CaptureBackend.UnsupportedModeException
        {
            int format = spec.channels() == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            clearError(0L);
            long device = ALC11.alcCaptureOpenDevice(deviceName, spec.sampleRate(), format, spec.ringSamples());
            int error = ALC10.alcGetError(device == 0L ? 0L : device);

            if (device == 0L && spec.channels() == 2)
            {
                throw new CaptureBackend.UnsupportedModeException(
                    "The selected capture device does not expose stereo PCM16 capture");
            }

            if (error != ALC10.ALC_NO_ERROR)
            {
                IllegalStateException failure = new IllegalStateException(
                    "OpenAL failed to open capture device (ALC error " + error + ")");

                if (device != 0L && !ALC11.alcCaptureCloseDevice(device))
                {
                    failure.addSuppressed(new IllegalStateException(
                        "OpenAL failed to close the partially opened capture device"));
                }

                throw failure;
            }

            return device;
        }

        @Override
        public void start(long device)
        {
            clearError(device);
            ALC11.alcCaptureStart(device);
            checkError(device, "start capture device");
        }

        @Override
        public int availableFrames(long device)
        {
            clearError(device);
            int available = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
            checkError(device, "query capture samples");

            return available;
        }

        @Override
        public void read(long device, ByteBuffer destination, int frames)
        {
            clearError(device);
            ALC11.alcCaptureSamples(device, destination, frames);
            checkError(device, "read capture samples");
            destination.position(destination.limit());
        }

        @Override
        public void stop(long device)
        {
            clearError(device);
            ALC11.alcCaptureStop(device);
            checkError(device, "stop capture device");
        }

        @Override
        public void close(long device)
        {
            clearError(device);

            if (!ALC11.alcCaptureCloseDevice(device))
            {
                throw new IllegalStateException("OpenAL failed to close capture device");
            }
        }

        private static void clearError(long device)
        {
            while (ALC10.alcGetError(device) != ALC10.ALC_NO_ERROR)
            {}
        }

        private static void checkError(long device, String operation)
        {
            int error = ALC10.alcGetError(device);

            if (error != ALC10.ALC_NO_ERROR)
            {
                throw new IllegalStateException("OpenAL failed to " + operation + " (ALC error " + error + ")");
            }
        }
    }
}
