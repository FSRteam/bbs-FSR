package mchorse.bbs_mod.audio;

import mchorse.bbs_mod.audio.wav.WaveReader;
import mchorse.bbs_mod.audio.wav.WaveWriter;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.ICameraController;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.renderers.sound.SoundFormPlayback;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.audio.CaptureBackend;
import mchorse.bbs_mod.ui.film.audio.CaptureFailure;
import mchorse.bbs_mod.ui.film.audio.CaptureResult;
import mchorse.bbs_mod.ui.film.audio.CaptureSpec;
import mchorse.bbs_mod.ui.film.audio.CaptureState;
import mchorse.bbs_mod.ui.film.audio.OpenALRecorder;
import mchorse.bbs_mod.ui.film.audio.UIAudioRecorder;
import org.lwjgl.openal.AL10;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic fake-backend coverage for capture and playback ownership contracts. */
public final class AudioLifecycleTest
{
    public static void main(String[] args) throws Exception
    {
        try (ExpectedErrorLogCapture capture = ExpectedErrorLogCapture.install())
        {
            captureStopDrainsTailAndPreservesStereo();
            captureCancelWinsDuringClose();
            captureImmediateStopAndPublishedCancel();
            captureRepeatedAndQueuedCancel();
            captureEmptyStartAndStorageFailures();
            captureFullRingTailIsBoundedAndDrained();
            captureCommitFenceProvenanceAndCeilingTicks();
            captureLegacyCallbackTerminalOrdering();
            captureWorkerInterruptUsesDispatcherOnce();
            captureRepeatedStartAndCleanupOwnResourcesOnce();
            captureFinalDrainAndConsumerFailuresOwnResourcesOnce();
            captureFailuresAreDistinctAndBounded();
            playbackIdentityAndGlobalCleanup();
            playbackSpatialParametersAndExplicitSeek();
            soundFormLoopIntervalLifecycle();
            playbackSpatialStereoDownmixAndCleanup();
            previewRestartReplacesUniqueOnly();
            playbackCleanupRetriesAndRetiredOwners();
            playbackContextDispatchDefersBackend();
            cameraControllerRemovalShutsDown();
            capture.assertExpectedErrors();
        }

        System.out.println("AudioLifecycleTest: all tests passed; captured 26 expected failure diagnostics");
    }

    /** Captures deliberate fake-device failures while rejecting unexpected production diagnostics. */
    private static final class ExpectedErrorLogCapture extends AbstractAppender implements AutoCloseable
    {
        private static final int EXPECTED_ERROR_COUNT = 26;
        private final List<LoggerState> states;
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private ExpectedErrorLogCapture(List<Logger> loggers)
        {
            super("audio-lifecycle-expected-errors", null, PatternLayout.createDefaultLayout(),
                false, Property.EMPTY_ARRAY);
            this.states = loggers.stream().map(LoggerState::new).toList();
            this.start();

            for (LoggerState state : this.states)
            {
                for (Appender appender : state.previousAppenders().values())
                {
                    state.logger().removeAppender(appender);
                }

                state.logger().setAdditive(false);
                state.logger().addAppender(this);
                state.logger().setLevel(Level.ALL);
            }
        }

        private static ExpectedErrorLogCapture install()
        {
            return new ExpectedErrorLogCapture(List.of(
                (Logger) LogManager.getLogger(OpenALRecorder.class),
                (Logger) LogManager.getLogger(SoundManager.class)));
        }

        @Override
        public void append(LogEvent event)
        {
            this.events.add(event.toImmutable());
        }

        private void assertExpectedErrors()
        {
            List<LogEvent> errors = this.events.stream()
                .filter((event) -> event.getLevel().isMoreSpecificThan(Level.ERROR))
                .toList();

            if (errors.size() != EXPECTED_ERROR_COUNT)
            {
                throw new AssertionError("Expected " + EXPECTED_ERROR_COUNT
                    + " captured audio lifecycle errors, got " + errors.size() + ": "
                    + describe(errors));
            }

            List<LogEvent> unexpected = errors.stream()
                .filter((event) -> !isExpectedError(event))
                .toList();

            if (!unexpected.isEmpty())
            {
                throw new AssertionError("Unexpected audio lifecycle diagnostics: "
                    + describe(unexpected));
            }
        }

        private static boolean isExpectedError(LogEvent event)
        {
            String message = event.getMessage().getFormattedMessage();
            Throwable error = event.getThrown();
            String cause = error == null || error.getMessage() == null ? "" : error.getMessage();
            boolean expectedMessage = message.startsWith("Microphone capture failed at ")
                || message.equals("Failed to delete sound source; cleanup continued")
                || message.startsWith("Failed to delete sound buffer ");
            boolean expectedCause = (cause.isEmpty() && message.endsWith("NO_DEVICE"))
                || cause.startsWith("injected ")
                || cause.startsWith("The microphone ")
                || cause.startsWith("Capture ")
                || cause.equals("sleep interrupted")
                || cause.equals("occupied target")
                || error instanceof java.nio.file.FileAlreadyExistsException;

            return expectedMessage && expectedCause;
        }

        private static String describe(List<LogEvent> events)
        {
            return events.stream()
                .map((event) -> event.getMessage().getFormattedMessage() + " -> "
                    + (event.getThrown() == null ? "no cause" : event.getThrown().getMessage()))
                .toList().toString();
        }

        @Override
        public void close()
        {
            for (LoggerState state : this.states)
            {
                state.logger().removeAppender(this);

                for (Appender appender : state.previousAppenders().values())
                {
                    state.logger().addAppender(appender);
                }

                state.logger().setAdditive(state.previousAdditive());
                state.logger().setLevel(state.previousLevel());
            }

            this.stop();
        }

        private record LoggerState(Logger logger, Level previousLevel,
                                   boolean previousAdditive,
                                   Map<String, Appender> previousAppenders)
        {
            private LoggerState(Logger logger)
            {
                this(logger, logger.getLevel(), logger.isAdditive(),
                    Map.copyOf(logger.getAppenders()));
            }
        }
    }

    private static void captureStopDrainsTailAndPreservesStereo() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-capture-tail");
        CaptureSpec spec = new CaptureSpec(8000, 2, 800, 1000);
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        byte[] live = stereoFrames(new int[][]{{1000, -1000}, {2000, -2000}});
        byte[] tail = stereoFrames(new int[][]{{3000, -3000}});
        backend.enqueueLive(live, 2);
        backend.enqueueTail(tail, 2);
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend, spec, directory, value ->
        {
            result.set(value);
            callback.countDown();
        });

        recorder.startAsync();
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "capture first read");
        recorder.stop();
        check(callback.await(3, TimeUnit.SECONDS), "capture result callback");
        CaptureResult terminal = result.get();
        check(terminal != null && terminal.isReady(), "capture finalized for client commit");
        check(terminal.frames() == 3, "final drain frame count");
        check(backend.stopCalls.get() == 1 && backend.closeCalls.get() == 1, "device cleanup exactly once");
        check(backend.readCalls.get() == 2, "live and tail reads");

        byte[] wav = Files.readAllBytes(terminal.temporaryFile());
        Wave decoded = new WaveReader().read(new ByteArrayInputStream(wav));
        check(decoded.getFormat().channels() == 2, "stereo channel count retained");
        check(decoded.getFrameCount() == 3, "WAV frame count retained");
        check(Arrays.equals(decoded.data, concat(live, tail)), "tail PCM bytes retained in order");
        check(recorder.getVolume() > 0.09F, "meter sees signed samples");
        Files.deleteIfExists(terminal.temporaryFile());
        Files.deleteIfExists(directory);
    }

    private static void captureCancelWinsDuringClose() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-capture-cancel");
        CaptureSpec spec = new CaptureSpec(8000, 1, 800, 1000);
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(100, 200), 1);
        backend.blockClose = true;
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend, spec, directory, value ->
        {
            result.set(value);
            callback.countDown();
        });

        recorder.startAsync();
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "cancel first read");
        recorder.stop();
        check(backend.closeEntered.await(3, TimeUnit.SECONDS), "close entered before cancel");
        recorder.cancel();
        backend.allowClose.countDown();
        check(callback.await(3, TimeUnit.SECONDS), "cancel callback");
        check(result.get() != null && result.get().isCancelled(), "cancel wins finalization race");
        check(recorder.getState().name().equals("CANCELLED"), "cancel terminal state");
        try (var files = Files.list(directory))
        {
            check(files.findAny().isEmpty(), "cancel removes owned temporary file");
        }
        check(backend.stopCalls.get() == 1 && backend.closeCalls.get() == 1, "cancel cleanup exactly once");
        Files.deleteIfExists(directory);
    }

    private static void captureImmediateStopAndPublishedCancel() throws Exception
    {
        Path immediateDirectory = Files.createTempDirectory("bbs-capture-immediate");
        AtomicInteger immediateCallbacks = new AtomicInteger();
        AtomicReference<CaptureResult> immediateResult = new AtomicReference<>();
        OpenALRecorder immediate = new OpenALRecorder(new FakeCaptureBackend("fake"),
            new CaptureSpec(8000, 1, 800, 1000), immediateDirectory, result ->
            {
                immediateCallbacks.incrementAndGet();
                immediateResult.set(result);
            });

        immediate.startAsync();
        immediate.stop();
        immediate.awaitFinished(3000L);
        check(immediateResult.get() != null, "immediate start/stop terminal result");
        check(immediateCallbacks.get() == 1, "immediate start/stop callback exactly once");
        check(immediate.getState() != mchorse.bbs_mod.ui.film.audio.CaptureState.STOPPING,
            "immediate start/stop cannot strand STOPPING");
        if (immediateResult.get().temporaryFile() != null)
        {
            Files.deleteIfExists(immediateResult.get().temporaryFile());
        }
        Files.deleteIfExists(immediateDirectory);

        Path publishedDirectory = Files.createTempDirectory("bbs-capture-published-cancel");
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(2, 100, 200), 1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<CaptureResult> published = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend, CaptureSpec.mono(), publishedDirectory, result ->
        {
            callbacks.incrementAndGet();
            published.set(result);
            callback.countDown();
        });

        recorder.startAsync();
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "published-cancel first read");
        recorder.stop();
        check(callback.await(3, TimeUnit.SECONDS), "published-cancel success callback");
        check(published.get() != null && published.get().isReady(), "published-cancel ready result exists");
        Path temporary = published.get().temporaryFile();
        check(Files.exists(temporary), "published temp exists before cancellation");
        recorder.cancel();
        check(callbacks.get() == 1, "published-cancel callback exactly once");
        check(!Files.exists(temporary), "published temp removed after cancellation");
        Files.deleteIfExists(publishedDirectory);
    }

    private static void captureRepeatedAndQueuedCancel() throws Exception
    {
        Path immediateDirectory = Files.createTempDirectory("bbs-capture-repeat-cancel");
        FakeCaptureBackend immediateBackend = new FakeCaptureBackend("fake");
        AtomicInteger immediateCallbacks = new AtomicInteger();
        AtomicReference<CaptureResult> immediateResult = new AtomicReference<>();
        OpenALRecorder immediate = new OpenALRecorder(immediateBackend,
            new CaptureSpec(8000, 1, 800, 1000), immediateDirectory, result ->
            {
                immediateCallbacks.incrementAndGet();
                immediateResult.set(result);
            });

        immediate.cancel();
        immediate.cancel();
        Thread immediateWorker = immediate.startAsync();
        immediate.awaitFinished(3000L);

        if (immediateWorker != null)
        {
            immediateWorker.join(3000L);
        }
        check(immediateCallbacks.get() == 1, "repeated immediate cancel callback exactly once");
        check(immediateResult.get() != null && immediateResult.get().isCancelled(),
            "repeated immediate cancel publishes cancellation");
        check(immediateBackend.openCalls.get() == 0, "cancel before start never opens device");
        Files.deleteIfExists(immediateDirectory);

        Path queuedDirectory = Files.createTempDirectory("bbs-capture-queued-cancel");
        FakeCaptureBackend queuedBackend = new FakeCaptureBackend("fake");
        queuedBackend.enqueueLive(monoFrames(1234), 1);
        List<CaptureResult> queued = new ArrayList<>();
        CountDownLatch callbackQueued = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(queuedBackend,
            new CaptureSpec(8000, 1, 800, 1000), queuedDirectory, result ->
            {
                queued.add(result);
                callbackQueued.countDown();
            });

        recorder.startAsync();
        check(queuedBackend.firstRead.await(3, TimeUnit.SECONDS), "queued cancel first read");
        recorder.stop();
        check(callbackQueued.await(3, TimeUnit.SECONDS), "queued success callback");
        CaptureResult delivered = queued.get(0);
        check(delivered.isReady() && recorder.beginCommit(delivered), "queued result is initially committable");
        recorder.cancel();
        recorder.cancel();
        check(recorder.getResult().isCancelled(), "queued cancel replaces pending success terminal");
        check(!recorder.beginCommit(delivered) && !recorder.completeCommit(delivered),
            "queued stale success cannot commit after cancellation");
        check(!Files.exists(delivered.temporaryFile()), "queued cancel discards finalized temporary file");
        check(queued.size() == 1, "queued cancellation does not duplicate result callback");
        Files.deleteIfExists(queuedDirectory);
    }

    private static void captureEmptyStartAndStorageFailures() throws Exception
    {
        Path emptyDirectory = Files.createTempDirectory("bbs-capture-empty");
        AtomicReference<CaptureResult> emptyResult = new AtomicReference<>();
        CountDownLatch emptyCallback = new CountDownLatch(1);
        OpenALRecorder empty = new OpenALRecorder(new FakeCaptureBackend("fake"),
            new CaptureSpec(8000, 1, 800, 1000), emptyDirectory, result ->
            {
                emptyResult.set(result);
                emptyCallback.countDown();
            });

        empty.startAsync();
        check(waitForState(empty, CaptureState.RECORDING, 3000L), "empty capture reaches recording");
        empty.stop();
        check(emptyCallback.await(3, TimeUnit.SECONDS), "empty capture callback");
        check(emptyResult.get().isFailure()
                && emptyResult.get().failure() == CaptureFailure.DEVICE_READ_FAILED,
            "empty capture is a typed failure, never an empty success");
        checkDirectoryEmpty(emptyDirectory, "empty capture removes temporary file");
        Files.deleteIfExists(emptyDirectory);

        Path startDirectory = Files.createTempDirectory("bbs-capture-start-failure");
        FakeCaptureBackend startBackend = new FakeCaptureBackend("fake");
        AtomicReference<CaptureResult> startResult = new AtomicReference<>();
        CountDownLatch startCallback = new CountDownLatch(1);
        OpenALRecorder startFailure = new OpenALRecorder(startBackend,
            new CaptureSpec(8000, 1, 800, 1000), startDirectory, result ->
            {
                startResult.set(result);
                startCallback.countDown();
            })
        {
            @Override
            protected Thread createWorker()
            {
                throw new SecurityException("injected worker start failure");
            }
        };

        check(startFailure.startAsync() == null, "worker start failure returns no worker");
        check(startCallback.await(3, TimeUnit.SECONDS), "worker start failure callback");
        check(startResult.get().failure() == CaptureFailure.DEVICE_START_FAILED,
            "worker start failure is typed");
        check(startBackend.openCalls.get() == 0, "worker start failure opens no device");
        Files.deleteIfExists(startDirectory);

        captureStorageHookFailure(false);
        captureStorageHookFailure(true);

        Path slowDirectory = Files.createTempDirectory("bbs-capture-slow-overflow");
        CaptureSpec slowSpec = new CaptureSpec(8000, 1, 800, 2000);
        FakeCaptureBackend slowBackend = new FakeCaptureBackend("fake");
        slowBackend.enqueueLive(monoFrames(100, 200), 1);
        slowBackend.availableAfterFirstRead = slowSpec.ringSamples();
        AtomicReference<CaptureResult> slowResult = new AtomicReference<>();
        CountDownLatch slowCallback = new CountDownLatch(1);
        OpenALRecorder slow = new OpenALRecorder(slowBackend, slowSpec, slowDirectory, result ->
        {
            slowResult.set(result);
            slowCallback.countDown();
        })
        {
            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                Thread.sleep(25L);
            }
        };

        slow.startAsync();
        check(slowCallback.await(3, TimeUnit.SECONDS), "slow sink overflow callback");
        check(slowResult.get().failure() == CaptureFailure.CAPTURE_OVERFLOW,
            "slow sink pressure fails instead of silently dropping samples");
        checkDirectoryEmpty(slowDirectory, "overflow removes partial temporary file");
        Files.deleteIfExists(slowDirectory);
    }

    private static void captureStorageHookFailure(boolean finalize) throws Exception
    {
        Path directory = Files.createTempDirectory(finalize
            ? "bbs-capture-finalize-failure" : "bbs-capture-write-failure");
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(100), 1);
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend,
            new CaptureSpec(8000, 1, 800, 1000), directory, value ->
            {
                result.set(value);
                callback.countDown();
            })
        {
            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                if (!finalize)
                {
                    throw new IOException("injected mid-write failure");
                }
            }

            @Override
            protected void beforeSinkFinalize() throws Exception
            {
                if (finalize)
                {
                    throw new IOException("injected finalize failure");
                }
            }
        };

        recorder.startAsync();

        if (finalize)
        {
            check(backend.firstRead.await(3, TimeUnit.SECONDS), "finalize failure first read");
            recorder.stop();
        }

        check(callback.await(3, TimeUnit.SECONDS), "storage hook failure callback");
        check(result.get().failure() == CaptureFailure.STORAGE_FAILED,
            "write/finalize failures use storage terminal");
        checkDirectoryEmpty(directory, "storage failure removes partial temporary file");
        Files.deleteIfExists(directory);
    }

    private static void captureFullRingTailIsBoundedAndDrained() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-capture-full-tail");
        CaptureSpec spec = new CaptureSpec(8000, 1, 800, 1000);
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(1000), 1);
        backend.enqueueTail(monoFrames(new int[800]), 1);
        CompletedCapture completed = recordSuccessfulCapture(backend, spec, directory);

        check(completed.result.frames() == 801L, "a stopped full-ring tail is drained within the bound");
        check(backend.readCalls.get() == 2, "full-ring tail uses one bounded final read");
        Files.deleteIfExists(completed.result.temporaryFile());
        Files.deleteIfExists(directory);
    }

    private static void captureCommitFenceProvenanceAndCeilingTicks() throws Exception
    {
        check(UIAudioRecorder.calculateDurationTicks(1L, 44100) == 1, "partial tick rounds up");
        check(UIAudioRecorder.calculateDurationTicks(400L, 8000) == 1, "exact tick remains exact");
        check(UIAudioRecorder.calculateDurationTicks(401L, 8000) == 2, "next frame rounds to next tick");

        Path directory = Files.createTempDirectory("bbs-capture-commit");
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(4321), 1);
        CompletedCapture completed = recordSuccessfulCapture(backend,
            new CaptureSpec(8000, 1, 800, 1000), directory);
        Path foreign = Files.createTempFile(directory, "foreign-", ".wav.tmp");
        CaptureResult foreignResult = CaptureResult.ready(foreign, 1L, 8000, 1);

        try
        {
            UIAudioRecorder.commitOwnedFile(completed.recorder, foreignResult, directory, "foreign");
            throw new AssertionError("foreign capture provenance accepted");
        }
        catch (IOException expected)
        {}

        check(Files.exists(foreign), "foreign path is never moved or deleted");
        Path occupied = directory.resolve("take.wav");
        Files.write(occupied, new byte[] {1});

        try
        {
            UIAudioRecorder.commitOwnedFile(completed.recorder, completed.result, directory, "take");
            throw new AssertionError("existing capture target was replaced");
        }
        catch (IOException expected)
        {}

        check(Files.readAllBytes(occupied)[0] == 1, "existing asset bytes remain unchanged");
        CaptureResult commitFailure = completed.recorder.markCommitFailed(new IOException("occupied target"));
        check(commitFailure.failure() == CaptureFailure.COMMIT_FAILED
                && completed.recorder.getResult().failure() == CaptureFailure.COMMIT_FAILED,
            "client commit failure updates the typed recorder terminal");
        Files.deleteIfExists(completed.result.temporaryFile());
        Files.deleteIfExists(foreign);
        Files.deleteIfExists(occupied);
        Files.deleteIfExists(directory);

        Path successDirectory = Files.createTempDirectory("bbs-capture-commit-success");
        FakeCaptureBackend successBackend = new FakeCaptureBackend("fake");
        successBackend.enqueueLive(monoFrames(99), 1);
        CompletedCapture successful = recordSuccessfulCapture(successBackend,
            new CaptureSpec(8000, 1, 800, 1000), successDirectory);
        check(successful.recorder.beginCommit(successful.result), "ready result begins commit");
        Path committed = UIAudioRecorder.commitOwnedFile(successful.recorder, successful.result,
            successDirectory, "committed");
        check(successful.recorder.completeCommit(successful.result), "client transaction completes commit");
        check(successful.recorder.getResult().isSuccess()
                && successful.recorder.getState() == CaptureState.SUCCEEDED,
            "successful client commit publishes the final typed terminal");
        successful.recorder.cancel();
        check(Files.exists(committed), "cancel after completed commit cannot discard asset");
        Files.deleteIfExists(committed);
        Files.deleteIfExists(successDirectory);

        Path staleDirectory = Files.createTempDirectory("bbs-capture-commit-stale");
        FakeCaptureBackend staleBackend = new FakeCaptureBackend("fake");
        staleBackend.enqueueLive(monoFrames(55), 1);
        CompletedCapture stale = recordSuccessfulCapture(staleBackend,
            new CaptureSpec(8000, 1, 800, 1000), staleDirectory);
        stale.recorder.cancel();

        try
        {
            UIAudioRecorder.commitOwnedFile(stale.recorder, stale.result, staleDirectory, "stale");
            throw new AssertionError("cancelled capture bypassed the commit fence");
        }
        catch (IOException expected)
        {}

        check(!Files.exists(staleDirectory.resolve("stale.wav")),
            "cancelled capture cannot move or insert a stale result");
        Files.deleteIfExists(staleDirectory);
    }

    private static void captureLegacyCallbackTerminalOrdering() throws Exception
    {
        Path cancelDirectory = Files.createTempDirectory("bbs-capture-legacy-cancel");
        FakeCaptureBackend cancelBackend = new FakeCaptureBackend("fake");
        cancelBackend.enqueueLive(monoFrames(100, 200), 1);
        CountDownLatch legacyEntered = new CountDownLatch(1);
        CountDownLatch releaseLegacy = new CountDownLatch(1);
        CountDownLatch typedDelivered = new CountDownLatch(1);
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicInteger typedCalls = new AtomicInteger();
        AtomicReference<CaptureResult> typedResult = new AtomicReference<>();
        OpenALRecorder cancelled = new OpenALRecorder(cancelBackend,
            new CaptureSpec(8000, 1, 800, 1000), cancelDirectory, result ->
            {
                typedCalls.incrementAndGet();
                typedResult.set(result);
                typedDelivered.countDown();
            }, wave ->
            {
                legacyCalls.incrementAndGet();
                legacyEntered.countDown();

                while (releaseLegacy.getCount() != 0L)
                {
                    try
                    {
                        releaseLegacy.await(10L, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException ignored)
                    {}
                }
            });

        cancelled.startAsync();
        check(cancelBackend.firstRead.await(3, TimeUnit.SECONDS), "legacy cancel first read");
        cancelled.stop();
        check(legacyEntered.await(3, TimeUnit.SECONDS), "legacy callback entered before cancel");
        cancelled.cancel();
        releaseLegacy.countDown();
        check(typedDelivered.await(3, TimeUnit.SECONDS), "legacy cancellation typed terminal");
        cancelled.awaitFinished(3000L);
        check(legacyCalls.get() == 1 && typedCalls.get() == 1,
            "legacy cancellation delivers each callback exactly once");
        check(typedResult.get() != null && typedResult.get().isCancelled()
                && cancelled.getResult().isCancelled() && cancelled.getState() == CaptureState.CANCELLED,
            "cancel during legacy callback wins the terminal race");
        checkDirectoryEmpty(cancelDirectory, "legacy cancellation removes the temporary file");
        Files.deleteIfExists(cancelDirectory);

        Path failureDirectory = Files.createTempDirectory("bbs-capture-legacy-failure");
        FakeCaptureBackend failureBackend = new FakeCaptureBackend("fake");
        failureBackend.enqueueLive(monoFrames(300), 1);
        CountDownLatch failureDelivered = new CountDownLatch(1);
        AtomicInteger failureLegacyCalls = new AtomicInteger();
        AtomicInteger failureTypedCalls = new AtomicInteger();
        AtomicReference<CaptureResult> failureResult = new AtomicReference<>();
        OpenALRecorder failed = new OpenALRecorder(failureBackend,
            new CaptureSpec(8000, 1, 800, 1000), failureDirectory, result ->
            {
                failureTypedCalls.incrementAndGet();
                failureResult.set(result);
                failureDelivered.countDown();
            }, wave ->
            {
                failureLegacyCalls.incrementAndGet();
                throw new IllegalStateException("injected legacy callback failure");
            });

        failed.startAsync();
        check(failureBackend.firstRead.await(3, TimeUnit.SECONDS), "legacy failure first read");
        failed.stop();
        check(failureDelivered.await(3, TimeUnit.SECONDS), "legacy failure typed terminal");
        failed.awaitFinished(3000L);
        check(failureLegacyCalls.get() == 1 && failureTypedCalls.get() == 1,
            "legacy failure delivers each callback exactly once");
        check(failureResult.get() != null && failureResult.get().isFailure()
                && failureResult.get().failure() == CaptureFailure.CALLBACK_FAILED
                && failed.getResult() == failureResult.get() && failed.getState() == CaptureState.FAILED,
            "legacy callback failure replaces READY before typed delivery");
        checkDirectoryEmpty(failureDirectory, "legacy failure removes the temporary file");
        Files.deleteIfExists(failureDirectory);
    }

    private static void captureWorkerInterruptUsesDispatcherOnce() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-capture-worker-interrupt");
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(100, 200), 1);
        FakeDispatcher dispatcher = new FakeDispatcher();
        TempOwnershipTracker temps = new TempOwnershipTracker();
        AtomicInteger uiCallbacks = new AtomicInteger();
        AtomicReference<CaptureResult> uiResult = new AtomicReference<>();
        OpenALRecorder recorder = new OpenALRecorder(backend,
            new CaptureSpec(8000, 1, 800, 1000), directory, result -> dispatcher.accept(() ->
            {
                check(dispatcher.isDispatching(), "interrupted capture UI callback runs inside dispatcher");
                uiCallbacks.incrementAndGet();
                uiResult.set(result);
            }))
        {
            private boolean tempClaimed;

            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                if (!this.tempClaimed)
                {
                    temps.claimOnlyFile(directory);
                    this.tempClaimed = true;
                }
            }
        };

        Thread worker = recorder.startAsync();
        check(worker != null, "interrupt capture creates worker");
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "interrupt capture first read");
        worker.interrupt();
        worker.join(3000L);
        check(!worker.isAlive(), "unexpected worker interrupt terminates capture");
        check(recorder.getResult() != null && recorder.getResult().isFailure()
                && recorder.getResult().failure() == CaptureFailure.DEVICE_READ_FAILED,
            "unexpected worker interrupt is an observable read failure");
        check(backend.openCalls.get() == 1 && backend.startCalls.get() == 1,
            "interrupted capture acquires one device");
        check(backend.stopCalls.get() == 1 && backend.closeCalls.get() == 1,
            "interrupted capture releases device exactly once");
        check(backend.deviceState.get() == FakeCaptureBackend.DeviceState.CLOSED,
            "interrupted capture closes its device identity");
        check(recorder.getCleanupFailure() == null, "interrupted capture cleanup has no hidden failure");
        check(dispatcher.submitCalls.get() == 1 && dispatcher.runCalls.get() == 0,
            "worker only queues one UI terminal before dispatch");
        check(uiCallbacks.get() == 0 && uiResult.get() == null,
            "worker never invokes UI terminal directly");
        Path temporary = temps.singlePath();
        check(!Files.exists(temporary), "interrupt removes owned partial temp before UI delivery");
        temps.observeReleased(temporary);

        dispatcher.runAll();
        check(dispatcher.submitCalls.get() == 1 && dispatcher.runCalls.get() == 1,
            "interrupted terminal dispatcher task runs exactly once");
        check(uiCallbacks.get() == 1 && uiResult.get() == recorder.getResult(),
            "interrupted capture UI observes the one typed terminal");
        temps.assertFullyReleased("interrupted capture temp ownership");
        Files.delete(directory);
    }

    private static void captureRepeatedStartAndCleanupOwnResourcesOnce() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-capture-repeated-lifecycle");
        FakeCaptureBackend backend = new FakeCaptureBackend("fake");
        backend.enqueueLive(monoFrames(123, 456), 1);
        FakeDispatcher dispatcher = new FakeDispatcher();
        TempOwnershipTracker temps = new TempOwnershipTracker();
        AtomicInteger uiCallbacks = new AtomicInteger();
        AtomicReference<CaptureResult> uiResult = new AtomicReference<>();
        OpenALRecorder recorder = new OpenALRecorder(backend,
            new CaptureSpec(8000, 1, 800, 1000), directory, result -> dispatcher.accept(() ->
            {
                check(dispatcher.isDispatching(), "repeated lifecycle UI callback runs inside dispatcher");
                uiCallbacks.incrementAndGet();
                uiResult.set(result);
            }))
        {
            private boolean tempClaimed;

            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                if (!this.tempClaimed)
                {
                    temps.claimOnlyFile(directory);
                    this.tempClaimed = true;
                }
            }
        };

        Thread first = recorder.startAsync();
        Thread repeated = recorder.startAsync();
        check(first != null && first == repeated, "repeated start returns the one owned worker");
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "repeated lifecycle first read");
        recorder.cleanup();
        first.join(3000L);
        check(!first.isAlive(), "cleanup joins the one capture worker");
        recorder.cleanup();
        recorder.cleanup();
        check(recorder.startAsync() == first, "start after terminal cannot allocate another worker");
        check(recorder.getResult() != null && recorder.getResult().isReady(),
            "repeated lifecycle leaves one ready capture");
        check(backend.defaultDeviceCalls.get() == 1 && backend.openCalls.get() == 1
                && backend.startCalls.get() == 1,
            "repeated start performs acquisition operations exactly once");
        check(backend.stopCalls.get() == 1 && backend.closeCalls.get() == 1,
            "repeated cleanup performs release operations exactly once");
        check(backend.deviceState.get() == FakeCaptureBackend.DeviceState.CLOSED,
            "repeated cleanup leaves no live capture device");
        check(recorder.getCleanupFailure() == null, "strict device fake saw no duplicate release");
        check(dispatcher.submitCalls.get() == 1 && dispatcher.runCalls.get() == 0,
            "repeated cleanup queues one UI callback");
        check(uiCallbacks.get() == 0, "queued UI callback is not run by worker");

        Path temporary = recorder.getResult().temporaryFile();
        check(temps.owns(temporary) && Files.exists(temporary),
            "ready temp is owned until the dispatched UI terminal consumes it");
        dispatcher.runAll();
        check(uiCallbacks.get() == 1 && uiResult.get() == recorder.getResult(),
            "repeated lifecycle delivers one UI callback");
        check(dispatcher.submitCalls.get() == 1 && dispatcher.runCalls.get() == 1,
            "repeated lifecycle dispatcher owns exactly one task");
        temps.deleteOwned(temporary);
        temps.assertFullyReleased("repeated lifecycle temp ownership");
        Files.delete(directory);
    }

    private static void captureFinalDrainAndConsumerFailuresOwnResourcesOnce() throws Exception
    {
        Path drainDirectory = Files.createTempDirectory("bbs-capture-final-drain-failure");
        FakeCaptureBackend drainBackend = new FakeCaptureBackend("fake");
        drainBackend.enqueueLive(monoFrames(100), 1);
        drainBackend.enqueueTail(monoFrames(200), 1);
        drainBackend.finalDrainReadFailure = true;
        TempOwnershipTracker drainTemps = new TempOwnershipTracker();
        AtomicReference<CaptureResult> drainResult = new AtomicReference<>();
        CountDownLatch drainCallback = new CountDownLatch(1);
        OpenALRecorder drainRecorder = new OpenALRecorder(drainBackend,
            new CaptureSpec(8000, 1, 800, 1000), drainDirectory, result ->
            {
                drainResult.set(result);
                drainCallback.countDown();
            })
        {
            private boolean tempClaimed;

            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                if (!this.tempClaimed)
                {
                    drainTemps.claimOnlyFile(drainDirectory);
                    this.tempClaimed = true;
                }
            }
        };

        Thread drainWorker = drainRecorder.startAsync();
        check(drainBackend.firstRead.await(3, TimeUnit.SECONDS), "final-drain failure first live read");
        drainRecorder.stop();
        check(drainCallback.await(3, TimeUnit.SECONDS), "final-drain failure callback");
        drainWorker.join(3000L);
        check(drainResult.get() != null && drainResult.get().failure() == CaptureFailure.DEVICE_READ_FAILED,
            "final-drain backend failure stays distinct");
        check(drainBackend.liveReadCalls.get() == 1 && drainBackend.finalDrainReadCalls.get() == 1,
            "final-drain failure occurs after one live read and one tail read attempt");
        check(drainBackend.stopCalls.get() == 1 && drainBackend.closeCalls.get() == 1,
            "final-drain failure releases device exactly once");
        check(drainRecorder.getCleanupFailure() == null, "final-drain cleanup has no hidden failure");
        Path drainTemporary = drainTemps.singlePath();
        check(!Files.exists(drainTemporary), "final-drain failure removes partial temp");
        drainTemps.observeReleased(drainTemporary);
        drainTemps.assertFullyReleased("final-drain temp ownership");
        Files.delete(drainDirectory);

        Path consumerDirectory = Files.createTempDirectory("bbs-capture-consumer-failure");
        FakeCaptureBackend consumerBackend = new FakeCaptureBackend("fake");
        consumerBackend.enqueueLive(monoFrames(300), 1);
        TempOwnershipTracker consumerTemps = new TempOwnershipTracker();
        AtomicInteger consumerCalls = new AtomicInteger();
        AtomicReference<CaptureResult> delivered = new AtomicReference<>();
        CountDownLatch consumerEntered = new CountDownLatch(1);
        OpenALRecorder consumerRecorder = new OpenALRecorder(consumerBackend,
            new CaptureSpec(8000, 1, 800, 1000), consumerDirectory, result ->
            {
                consumerCalls.incrementAndGet();
                delivered.set(result);
                consumerEntered.countDown();
                throw new IllegalStateException("injected typed consumer failure");
            })
        {
            private boolean tempClaimed;

            @Override
            protected void beforeSinkWrite(int frames) throws Exception
            {
                if (!this.tempClaimed)
                {
                    consumerTemps.claimOnlyFile(consumerDirectory);
                    this.tempClaimed = true;
                }
            }
        };

        Thread consumerWorker = consumerRecorder.startAsync();
        check(consumerBackend.firstRead.await(3, TimeUnit.SECONDS), "consumer failure first read");
        consumerRecorder.stop();
        check(consumerEntered.await(3, TimeUnit.SECONDS), "typed consumer entered");
        consumerWorker.join(3000L);
        check(delivered.get() != null && delivered.get().isReady(),
            "typed consumer receives the finalized ready result");
        check(consumerRecorder.getResult() != null && consumerRecorder.getResult().isFailure()
                && consumerRecorder.getResult().failure() == CaptureFailure.CALLBACK_FAILED,
            "typed consumer failure replaces uncommitted ready terminal");
        check(consumerCalls.get() == 1, "failing typed consumer is invoked exactly once");
        check(consumerBackend.stopCalls.get() == 1 && consumerBackend.closeCalls.get() == 1,
            "consumer failure retains exactly-once device cleanup");
        Path consumerTemporary = consumerTemps.singlePath();
        check(!Files.exists(consumerTemporary), "consumer failure removes finalized temp");
        consumerTemps.observeReleased(consumerTemporary);
        consumerRecorder.cleanup();
        consumerRecorder.cleanup();
        check(consumerCalls.get() == 1 && consumerBackend.stopCalls.get() == 1
                && consumerBackend.closeCalls.get() == 1,
            "repeated cleanup cannot redeliver consumer or rerelease device");
        consumerTemps.assertFullyReleased("typed consumer temp ownership");
        Files.delete(consumerDirectory);
    }

    private static CompletedCapture recordSuccessfulCapture(FakeCaptureBackend backend, CaptureSpec spec,
                                                              Path directory) throws Exception
    {
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend, spec, directory, value ->
        {
            result.set(value);
            callback.countDown();
        });

        recorder.startAsync();
        check(backend.firstRead.await(3, TimeUnit.SECONDS), "successful capture first read");
        recorder.stop();
        check(callback.await(3, TimeUnit.SECONDS), "successful capture callback");
        check(result.get() != null && result.get().isReady(), "successful capture is ready to commit");

        return new CompletedCapture(recorder, result.get());
    }

    private static boolean waitForState(OpenALRecorder recorder, CaptureState state, long timeoutMillis)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (System.nanoTime() < deadline)
        {
            if (recorder.getState() == state)
            {
                return true;
            }

            Thread.sleep(5L);
        }

        return recorder.getState() == state;
    }

    private static void checkDirectoryEmpty(Path directory, String message) throws IOException
    {
        try (var files = Files.list(directory))
        {
            check(files.findAny().isEmpty(), message);
        }
    }

    private record CompletedCapture(OpenALRecorder recorder, CaptureResult result)
    {}

    private static void captureFailuresAreDistinctAndBounded() throws Exception
    {
        FakeCaptureBackend noDeviceBackend = new FakeCaptureBackend(null);
        CaptureResult noDevice = runCaptureFailure(noDeviceBackend, CaptureSpec.mono(), null);
        check(noDevice.failure() == CaptureFailure.NO_DEVICE, "no device failure");
        check(noDeviceBackend.defaultDeviceCalls.get() == 1 && noDeviceBackend.openCalls.get() == 0
                && noDeviceBackend.stopCalls.get() == 0 && noDeviceBackend.closeCalls.get() == 0,
            "no-device failure never acquires or releases a fake device");

        FakeCaptureBackend stereoUnsupported = new FakeCaptureBackend("fake");
        stereoUnsupported.unsupportedMode = true;
        CaptureResult unsupported = runCaptureFailure(stereoUnsupported, CaptureSpec.stereo(), null);
        check(unsupported.failure() == CaptureFailure.UNSUPPORTED_MODE, "unsupported stereo failure");
        check(stereoUnsupported.openCalls.get() == 1 && stereoUnsupported.startCalls.get() == 0
                && stereoUnsupported.stopCalls.get() == 0 && stereoUnsupported.closeCalls.get() == 0,
            "unsupported open acquires no device identity");

        FakeCaptureBackend stereoOpenFailure = new FakeCaptureBackend("fake");
        stereoOpenFailure.openFailure = true;
        CaptureResult stereoOpen = runCaptureFailure(stereoOpenFailure, CaptureSpec.stereo(), null);
        check(stereoOpen.failure() == CaptureFailure.DEVICE_OPEN_FAILED, "stereo open failure classification");
        check(stereoOpenFailure.openCalls.get() == 1 && stereoOpenFailure.startCalls.get() == 0
                && stereoOpenFailure.stopCalls.get() == 0 && stereoOpenFailure.closeCalls.get() == 0,
            "open failure has no phantom cleanup calls");

        FakeCaptureBackend startFailure = new FakeCaptureBackend("fake");
        startFailure.startFailure = true;
        CaptureResult started = runCaptureFailure(startFailure, CaptureSpec.mono(), null);
        check(started.failure() == CaptureFailure.DEVICE_START_FAILED, "device start failure classification");
        check(startFailure.openCalls.get() == 1 && startFailure.startCalls.get() == 1
                && startFailure.stopCalls.get() == 1 && startFailure.closeCalls.get() == 1,
            "start failure releases its opened device exactly once");
        check(startFailure.deviceState.get() == FakeCaptureBackend.DeviceState.CLOSED,
            "start failure closes the acquired device identity");

        FakeCaptureBackend pollFailure = new FakeCaptureBackend("fake");
        pollFailure.pollFailure = true;
        CaptureResult polled = runCaptureFailure(pollFailure, CaptureSpec.mono(), null);
        check(polled.failure() == CaptureFailure.DEVICE_READ_FAILED, "poll failure classification");
        check(pollFailure.liveAvailableCalls.get() == 1 && pollFailure.readCalls.get() == 0,
            "poll failure occurs before any capture read");
        check(pollFailure.stopCalls.get() == 1 && pollFailure.closeCalls.get() == 1,
            "poll failure releases the device exactly once");

        FakeCaptureBackend readFailure = new FakeCaptureBackend("fake");
        readFailure.readFailure = true;
        readFailure.enqueueLive(monoFrames(2, 100), 1);
        CaptureResult read = runCaptureFailure(readFailure, CaptureSpec.mono(), null);
        check(read.failure() == CaptureFailure.DEVICE_READ_FAILED, "read failure");
        check(readFailure.liveReadCalls.get() == 1 && readFailure.finalDrainReadCalls.get() == 0,
            "live read failure is not confused with final drain");
        check(readFailure.stopCalls.get() == 1 && readFailure.closeCalls.get() == 1,
            "read failure releases the device exactly once");

        Path occupied = Files.createTempFile("bbs-capture-file", ".tmp");
        FakeCaptureBackend storageBackend = new FakeCaptureBackend("fake");
        CaptureResult storage = runCaptureFailure(storageBackend, CaptureSpec.mono(), occupied);
        check(storage.failure() == CaptureFailure.STORAGE_FAILED, "storage failure");
        check(storageBackend.openCalls.get() == 1 && storageBackend.startCalls.get() == 1
                && storageBackend.stopCalls.get() == 1 && storageBackend.closeCalls.get() == 1,
            "storage-open failure releases its device exactly once");
        Files.deleteIfExists(occupied);

        FakeCaptureBackend stopFailure = new FakeCaptureBackend("fake");
        stopFailure.enqueueLive(monoFrames(2, 100), 1);
        stopFailure.stopFailure = true;
        CaptureResult stopped = runCaptureFailure(stopFailure, CaptureSpec.mono(), null);
        check(stopped.failure() == CaptureFailure.DEVICE_STOP_FAILED, "stop failure");
        check(stopFailure.stopCalls.get() == 1 && stopFailure.closeCalls.get() == 1,
            "stop failure still closes device exactly once");

        FakeCaptureBackend closeFailure = new FakeCaptureBackend("fake");
        closeFailure.enqueueLive(monoFrames(2, 100), 1);
        closeFailure.closeFailure = true;
        CaptureResult closed = runCaptureFailure(closeFailure, CaptureSpec.mono(), null);
        check(closed.failure() == CaptureFailure.DEVICE_CLOSE_FAILED, "close failure");
        check(closeFailure.stopCalls.get() == 1 && closeFailure.closeCalls.get() == 1,
            "close failure is attempted exactly once");
        check(closeFailure.deviceState.get() == FakeCaptureBackend.DeviceState.CLOSED,
            "strict fake retires the failed close identity");

        CaptureSpec bounded = new CaptureSpec(8000, 1, 800, 1);
        FakeCaptureBackend limited = new FakeCaptureBackend("fake");
        limited.enqueueLive(monoFrames(2, 100), 1);
        CaptureResult limit = runCaptureFailure(limited, bounded, null);
        check(limit.failure() == CaptureFailure.DURATION_LIMIT, "duration limit failure");
        check(limited.readCalls.get() == 0 && limited.stopCalls.get() == 1 && limited.closeCalls.get() == 1,
            "duration bound fails before read and cleans once");

        FakeCaptureBackend overflow = new FakeCaptureBackend("fake");
        overflow.forcedAvailable = 800;
        CaptureResult overflowResult = runCaptureFailure(overflow, new CaptureSpec(8000, 1, 800, 1000), null);
        check(overflowResult.failure() == CaptureFailure.CAPTURE_OVERFLOW, "capture ring overflow classification");
        check(overflow.liveAvailableCalls.get() == 1 && overflow.readCalls.get() == 0
                && overflow.stopCalls.get() == 1 && overflow.closeCalls.get() == 1,
            "ring overflow fails at poll and cleans once");
    }

    private static CaptureResult runCaptureFailure(FakeCaptureBackend backend, CaptureSpec spec, Path directoryOrFile)
        throws Exception
    {
        Path directory = directoryOrFile == null
            ? Files.createTempDirectory("bbs-capture-failure") : directoryOrFile;
        AtomicReference<CaptureResult> result = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        OpenALRecorder recorder = new OpenALRecorder(backend, spec, directory, value ->
        {
            result.set(value);
            callback.countDown();
        });

        recorder.startAsync();

        if (backend.stopFailure || backend.closeFailure)
        {
            check(backend.firstRead.await(3, TimeUnit.SECONDS), "terminal failure first read");
            recorder.stop();
        }

        check(callback.await(3, TimeUnit.SECONDS), "failure callback");
        CaptureResult output = result.get();
        check(output != null && output.isFailure(), "failure result");

        if (directoryOrFile == null)
        {
            try (var files = Files.list(directory))
            {
                check(files.findAny().isEmpty(), "failed capture temp cleanup");
            }
            Files.deleteIfExists(directory);
        }

        return output;
    }

    private static void playbackIdentityAndGlobalCleanup() throws Exception
    {
        Link first = Link.assets("audio/identity-one.wav");
        Link second = Link.assets("audio/identity-two.wav");
        Wave mono = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        Wave stereo = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.STEREO, 10),
            stereoTestFrames(100));
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(first, wav(mono));
        provider.put(second, wav(stereo));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);
        Object ownerOne = new Object();
        Object ownerTwo = new Object();
        Object clipOne = new Object();
        Object clipTwo = new Object();
        Object clipThree = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();
        desired.put(clipOne, new SoundManager.VoiceRequest(first, 1F, .25F));
        desired.put(clipTwo, new SoundManager.VoiceRequest(first, 2F, .75F));
        manager.reconcile(ownerOne, desired, true);
        check(manager.getOwnedVoiceCount(ownerOne) == 2, "same-link clips get independent voices");
        check(backend.bufferCreateCalls.get() == 1, "same-link decoded buffer reused");
        int clipOneSource = manager.getOwnedVoice(ownerOne, clipOne).getSource();
        int clipTwoSource = manager.getOwnedVoice(ownerOne, clipTwo).getSource();
        check(Math.abs(backend.source(manager.getOwnedVoice(ownerOne, clipOne)).gain - .25F) < .001F,
            "clip one gain independent");
        check(Math.abs(backend.source(manager.getOwnedVoice(ownerOne, clipTwo)).gain - .75F) < .001F,
            "clip two gain independent");

        IdentityHashMap<Object, SoundManager.VoiceRequest> secondOwnerDesired = new IdentityHashMap<>();
        secondOwnerDesired.put(clipThree, new SoundManager.VoiceRequest(first, 3F, .5F));
        manager.reconcile(ownerTwo, secondOwnerDesired, true);
        check(backend.bufferCreateCalls.get() == 1, "two owners share decoded buffer");
        manager.releaseOwner(ownerOne);
        check(manager.getOwnedVoiceCount(ownerOne) == 0 && manager.getOwnedVoiceCount(ownerTwo) == 1,
            "owner release is independent");
        check(backend.successfulSourceDeletes(clipOneSource) == 1
                && backend.successfulSourceDeletes(clipTwoSource) == 1,
            "each released owner voice deletes its source identity exactly once");

        manager.reconcile(ownerTwo,
            new IdentityHashMap<>(Map.of(clipThree, new SoundManager.VoiceRequest(second, 0.1F, .8F))), false);
        check(manager.getOwnedVoiceCount(ownerTwo) == 1, "relink retains one clip voice");
        SoundPlayer relinked = manager.getOwnedVoice(ownerTwo, clipThree);
        check(backend.source(relinked).state == FakeSoundBackend.State.INITIAL, "paused owner remains paused");
        check(Math.abs(backend.source(relinked).offset - .1F) < .001F, "paused scrub updates source position");
        manager.reconcile(ownerTwo,
            new IdentityHashMap<>(Map.of(clipThree, new SoundManager.VoiceRequest(second, 0.2F, .8F))), true);
        check(backend.source(relinked).state == FakeSoundBackend.State.PLAYING, "resume starts owner voice");
        check(Math.abs(backend.source(relinked).offset - .2F) < .001F, "resume seeks before play");

        int secondBufferHandle = relinked.getBuffer().getBuffer();
        manager.reconcile(ownerTwo, new IdentityHashMap<>(), false);
        check(manager.getOwnedVoiceCount(ownerTwo) == 0, "empty desired state releases stale voice");
        manager.deleteSound(second);
        check(backend.deletedBufferHandles.contains(secondBufferHandle), "link invalidation deletes buffer");

        /* Failure injection: source cleanup continues and buffer deletion happens afterward. */
        FakeSoundBackend cleanupBackend = new FakeSoundBackend();
        SoundManager cleanupManager = new SoundManager(provider, cleanupBackend, Runnable::run, () -> true);
        Object cleanupOwner = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> cleanupDesired = new IdentityHashMap<>();
        cleanupDesired.put(new Object(), new SoundManager.VoiceRequest(first, 1F, 1F));
        cleanupDesired.put(new Object(), new SoundManager.VoiceRequest(first, 2F, 1F));
        cleanupManager.reconcile(cleanupOwner, cleanupDesired, true);
        cleanupBackend.failNextStop = true;
        cleanupManager.deleteSounds();
        check(cleanupManager.getPlayers().isEmpty(), "global reset clears player collection");
        check(cleanupBackend.events.stream().anyMatch(event -> event.startsWith("detach:")), "source detach after stop failure");
        check(cleanupBackend.events.stream().anyMatch(event -> event.startsWith("deleteBuffer:")), "buffer deleted after sources");
        check(cleanupManager.getLastCleanupFailure() != null, "first cleanup failure retained");
        cleanupBackend.assertAllReleasedExactlyOnce("cleanup-continuation playback ownership");

        SoundBuffer stereoBuffer = manager.load(second, false);
        check(stereoBuffer != null && stereoBuffer.getWaveform() == null, "stereo asset loads without waveform");
        check(backend.buffer(stereoBuffer.getBuffer()).getFormat().channels() == 2, "stereo upload layout retained");
        manager.deleteSounds();
        check(manager.getLastCleanupFailure() == null, "strict playback fake saw no duplicate cleanup");
        backend.assertAllReleasedExactlyOnce("identity playback ownership");
    }

    private static void playbackSpatialParametersAndExplicitSeek() throws Exception
    {
        Link link = Link.assets("audio/spatial-parameters.wav");
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(wave));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);
        Object owner = new Object();
        Object identity = new Object();

        SoundManager.VoiceRequest initial = SoundManager.VoiceRequest.spatial(
            link, 1.25F, 0.6F, 2F, 3F, 4F, 1.5F, true, 96F, true);
        manager.reconcile(owner, new IdentityHashMap<>(Map.of(identity, initial)), true);

        SoundPlayer player = manager.getOwnedVoice(owner, identity);
        FakeSoundBackend.Source source = backend.source(player);

        check(!source.relative, "spatial voice is world-relative");
        check(Math.abs(source.x - 2F) < .001F && Math.abs(source.y - 3F) < .001F
                && Math.abs(source.z - 4F) < .001F,
            "spatial voice applies its world position");
        check(Math.abs(source.pitch - 1.5F) < .001F, "spatial voice applies form pitch");
        check(source.looping, "spatial voice enables native looping");
        check(Math.abs(source.maxDistance - 96F) < .001F, "spatial voice applies form range");

        source.offset = 5F;
        SoundManager.VoiceRequest continuous = SoundManager.VoiceRequest.spatial(
            link, 1.3F, 0.6F, 2F, 3F, 4F, 1.5F, true, 96F, false);
        manager.reconcile(owner, new IdentityHashMap<>(Map.of(identity, continuous)), true);
        check(Math.abs(source.offset - 5F) < .001F,
            "continuous pitched playback is not dragged back to the unpitched timeline offset");

        SoundManager.VoiceRequest scrubbed = SoundManager.VoiceRequest.spatial(
            link, 2F, 0.6F, 2F, 3F, 4F, 1.5F, true, 96F, true);
        manager.reconcile(owner, new IdentityHashMap<>(Map.of(identity, scrubbed)), false);
        check(Math.abs(source.offset - 2F) < .001F && source.state == FakeSoundBackend.State.PAUSED,
            "paused scrub explicitly seeks and pauses the spatial voice");

        manager.deleteSounds();
        backend.assertAllReleasedExactlyOnce("spatial playback parameters");
    }

    private static void soundFormLoopIntervalLifecycle() throws Exception
    {
        Link link = Link.assets("audio/form-loop-interval.wav");
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(wave));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);
        SoundFormPlayback playback = new SoundFormPlayback();
        SoundSphereForm form = new SoundSphereForm();
        Object owner = new Object();

        form.audio.set(link);
        form.playing.set(true);
        form.looping.set(true);
        form.loopInterval.set(2F);

        playback.update(manager, owner, form,
            0F, 0F, 0F, 1F, 0F, 0F,
            SoundFormPlayback.NO_SURFACES, 0, false, false,
            9.5F, true, true);

        SoundPlayer first = ownedDirectVoice(manager, owner, playback,
            "positive loop interval starts an audible direct voice");
        int firstSource = first.getSource();
        check(!backend.source(first).looping, "positive loop interval disables native looping");

        playback.update(manager, owner, form,
            0F, 0F, 0F, 1F, 0F, 0F,
            SoundFormPlayback.NO_SURFACES, 0, false, false,
            10.5F, true, false);

        check(manager.getOwnedVoiceCount(owner) == 0, "positive loop interval removes the source during its gap");
        check(backend.successfulSourceDeletes(firstSource) == 1,
            "gap removal deletes the previous source exactly once");

        playback.update(manager, owner, form,
            0F, 0F, 0F, 1F, 0F, 0F,
            SoundFormPlayback.NO_SURFACES, 0, false, false,
            12.25F, true, false);

        SoundPlayer restarted = ownedDirectVoice(manager, owner, playback,
            "next loop window recreates the direct voice");
        int restartedSource = restarted.getSource();
        check(restartedSource != firstSource && backend.sourceCreateCalls.get() == 2,
            "next loop window creates a fresh source identity");
        check(Math.abs(backend.source(restarted).offset - .25F) < .001F
                && backend.events.contains("seek:" + restartedSource),
            "recreated source seeks to the next audible window position");
        check(!backend.source(restarted).looping, "recreated positive-interval source keeps native looping disabled");

        form.loopInterval.set(0F);
        playback.update(manager, owner, form,
            0F, 0F, 0F, 1F, 0F, 0F,
            SoundFormPlayback.NO_SURFACES, 0, false, false,
            20.25F, true, false);

        check(ownedDirectVoice(manager, owner, playback, "zero loop interval retains the direct voice") == restarted,
            "zero loop interval does not recreate the active source");
        check(backend.source(restarted).looping, "zero loop interval retains native looping");

        manager.deleteSounds();
        backend.assertAllReleasedExactlyOnce("sound form loop interval lifecycle");
    }

    private static SoundPlayer ownedDirectVoice(SoundManager manager, Object owner,
        SoundFormPlayback playback, String label) throws Exception
    {
        java.lang.reflect.Field field = SoundFormPlayback.class.getDeclaredField("directKey");

        field.setAccessible(true);

        SoundPlayer player = manager.getOwnedVoice(owner, field.get(playback));

        check(player != null, label);

        return player;
    }

    private static void playbackSpatialStereoDownmixAndCleanup() throws Exception
    {
        Link link = Link.assets("audio/spatial-stereo.wav");
        int[][] frames = new int[100][2];
        frames[0][0] = 24576;
        frames[0][1] = -8192;
        Wave stereo = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.STEREO, 10),
            stereoFrames(frames));
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(stereo));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);
        Object owner = new Object();
        Object direct = new Object();

        manager.reconcile(owner, new IdentityHashMap<>(Map.of(
            direct, new SoundManager.VoiceRequest(link, 0.5F, 1F))), true);
        SoundPlayer listenerRelative = manager.getOwnedVoice(owner, direct);
        SoundBuffer buffer = listenerRelative.getBuffer();
        int originalHandle = buffer.getBuffer(false);
        int spatialHandle = buffer.getBuffer(true);

        check(originalHandle != spatialHandle, "stereo asset owns a distinct mono spatial buffer");
        check(backend.source(listenerRelative).buffer == originalHandle
                && backend.buffer(originalHandle).getFormat().layout() == ChannelLayout.STEREO,
            "listener-relative playback preserves the authored stereo buffer");
        Wave spatialWave = backend.buffer(spatialHandle);
        check(spatialWave.getFormat().layout() == ChannelLayout.MONO,
            "spatial playback variant is mono for OpenAL positioning");
        check(Math.abs(PcmSamples.readNormalized(spatialWave, 0, 0) - 0.25D) < 0.0001D,
            "spatial stereo downmix follows the 0.5L plus 0.5R format contract");
        check(provider.reads(link) == 1 && backend.bufferCreateCalls.get() == 2,
            "original and spatial buffers share one asset decode");

        int listenerSource = listenerRelative.getSource();
        SoundManager.VoiceRequest spatialDirect = SoundManager.VoiceRequest.spatial(
            link, 0.5F, 1F, 2F, 3F, 4F);
        manager.reconcile(owner, new IdentityHashMap<>(Map.of(direct, spatialDirect)), true);
        SoundPlayer directPlayer = manager.getOwnedVoice(owner, direct);
        FakeSoundBackend.Source directSource = backend.source(directPlayer);

        check(directPlayer.getSource() != listenerSource
                && backend.successfulSourceDeletes(listenerSource) == 1,
            "changing spatial mode recreates the source before selecting another buffer");
        check(directSource.buffer == spatialHandle && !directSource.relative,
            "direct spatial voice attaches the mono positional buffer");

        Object reflection = new Object();
        SoundManager.VoiceRequest spatialReflection = SoundManager.VoiceRequest.spatial(
            link, 0.25F, 0.4F, -6F, 1F, 8F);
        IdentityHashMap<Object, SoundManager.VoiceRequest> spatialVoices = new IdentityHashMap<>();
        spatialVoices.put(direct, spatialDirect);
        spatialVoices.put(reflection, spatialReflection);
        manager.reconcile(owner, spatialVoices, true);
        FakeSoundBackend.Source reflectedSource = backend.source(manager.getOwnedVoice(owner, reflection));

        check(reflectedSource.buffer == spatialHandle && !reflectedSource.relative,
            "reflected voice shares the mono positional buffer");
        check(Math.abs(directSource.x - reflectedSource.x) > 0.001F
                || Math.abs(directSource.y - reflectedSource.y) > 0.001F
                || Math.abs(directSource.z - reflectedSource.z) > 0.001F,
            "direct and reflected voices retain independent world positions");
        manager.deleteSounds();
        backend.assertAllReleasedExactlyOnce("stereo spatial playback ownership");

        FakeSoundBackend cleanupBackend = new FakeSoundBackend();
        SoundManager cleanupManager = new SoundManager(provider, cleanupBackend, Runnable::run, () -> true);
        SoundBuffer cleanupBuffer = cleanupManager.load(link, false);
        int cleanupOriginal = cleanupBuffer.getBuffer(false);
        int cleanupSpatial = cleanupBuffer.getBuffer(true);
        cleanupBackend.failNextDeleteBuffer = true;
        cleanupManager.deleteSound(link);

        check(cleanupBuffer.getBuffer(false) == cleanupOriginal
                && cleanupBuffer.getBuffer(true) < 0
                && cleanupBackend.successfulBufferDeletes(cleanupSpatial) == 1,
            "partial stereo-buffer cleanup retains only the failed handle for retry");
        cleanupManager.deleteSounds();
        check(cleanupBuffer.isCleanupComplete()
                && cleanupBackend.successfulBufferDeletes(cleanupOriginal) == 1,
            "later cleanup retries the remaining stereo-buffer handle");
        cleanupBackend.assertAllReleasedExactlyOnce("stereo spatial cleanup retry");
    }

    private static void playbackCleanupRetriesAndRetiredOwners() throws Exception
    {
        Link link = Link.assets("audio/retry.wav");
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(wave));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);
        Object owner = new Object();
        Object clip = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();
        desired.put(clip, new SoundManager.VoiceRequest(link, 0.1F, 1F));
        manager.reconcile(owner, desired, true);
        SoundPlayer player = manager.getOwnedVoice(owner, clip);
        int source = player.getSource();
        int buffer = player.getBuffer().getBuffer();

        backend.failNextDetach = true;
        backend.failNextDeleteSource = true;
        manager.deleteSound(link);
        check(manager.getOwnedVoiceCount(owner) == 1, "failed source cleanup retains owner mapping");
        check(player.getSource() == source && player.getBuffer() != null, "failed detach retains source handle");
        check(!backend.deletedBufferHandles.contains(buffer), "attached buffer is not deleted on failed detach");

        manager.releaseOwner(owner);
        check(manager.getOwnedVoiceCount(owner) == 0 && player.isDeleted(), "owner retry deletes retained source");
        check(backend.deletedBufferHandles.contains(buffer), "buffer deletes after source retry");

        SoundManager bufferManager = new SoundManager(provider, backend, Runnable::run, () -> true);
        SoundBuffer cached = bufferManager.load(link, false);
        int cachedHandle = cached.getBuffer();
        backend.failNextDeleteBuffer = true;
        bufferManager.deleteSound(link);
        check(cached.getBuffer() == cachedHandle, "failed buffer deletion retains handle");
        bufferManager.deleteSounds();
        check(cached.isDeleted(), "later reset retries buffer deletion");
        backend.assertAllReleasedExactlyOnce("retry playback ownership");

        AtomicBoolean context = new AtomicBoolean(false);
        FakeDispatcher queued = new FakeDispatcher();
        FakeSoundBackend fencedBackend = new FakeSoundBackend();
        SoundManager fenced = new SoundManager(provider, fencedBackend, queued, context::get);
        Object fencedOwner = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> fencedDesired = new IdentityHashMap<>();
        fencedDesired.put(new Object(), new SoundManager.VoiceRequest(link, 0.1F, 1F));
        fenced.reconcile(fencedOwner, fencedDesired, true);
        check(queued.submitCalls.get() == 1 && fencedBackend.totalOperationCalls.get() == 0,
            "off-context reconcile queues without touching playback backend");
        fenced.releaseOwner(fencedOwner);
        check(queued.submitCalls.get() == 2 && fencedBackend.totalOperationCalls.get() == 0,
            "off-context owner retirement still performs zero backend calls");
        context.set(true);
        queued.runAll();
        check(fenced.getOwnedVoiceCount(fencedOwner) == 0 && fencedBackend.sources.isEmpty(),
            "retired owner fences queued reconcile");
        check(queued.runCalls.get() == 2 && fencedBackend.totalOperationCalls.get() == 0,
            "retired queued work executes once without acquiring backend resources");

        /* Retired-owner fencing is identity based, not equals based. */
        FakeSoundBackend identityBackend = new FakeSoundBackend();
        SoundManager identityManager = new SoundManager(provider, identityBackend, Runnable::run, () -> true);
        EqualOwner retired = new EqualOwner("same");
        EqualOwner replacement = new EqualOwner("same");
        Object identityClip = new Object();
        identityManager.reconcile(retired,
            new IdentityHashMap<>(Map.of(identityClip, new SoundManager.VoiceRequest(link, 0.1F, 1F))), true);
        identityManager.releaseOwner(retired);
        identityManager.reconcile(replacement,
            new IdentityHashMap<>(Map.of(identityClip, new SoundManager.VoiceRequest(link, 0.1F, 1F))), true);
        check(identityManager.getOwnedVoiceCount(replacement) == 1,
            "equal but distinct playback owners remain independent");
        identityManager.releaseOwner(replacement);
        identityManager.deleteSounds();
        identityBackend.assertAllReleasedExactlyOnce("identity-fenced playback ownership");

        /* A one-shot source deletion failure is retried before global buffer teardown. */
        FakeSoundBackend globalRetryBackend = new FakeSoundBackend();
        SoundManager globalRetry = new SoundManager(provider, globalRetryBackend, Runnable::run, () -> true);
        Object globalOwner = new Object();
        Object globalClip = new Object();
        globalRetry.reconcile(globalOwner,
            new IdentityHashMap<>(Map.of(globalClip, new SoundManager.VoiceRequest(link, 0.1F, 1F))), true);
        globalRetryBackend.failNextDeleteSource = true;
        globalRetry.deleteSounds();
        check(globalRetry.getPlayers().isEmpty() && globalRetryBackend.sources.isEmpty(),
            "global teardown retries a failed source delete");
        check(globalRetryBackend.deletedBufferHandles.size() == 1,
            "global teardown deletes buffer only after source retry");

        SoundBuffer nullIdBuffer = new SoundBuffer(null, wave, null, globalRetryBackend);
        check(nullIdBuffer.getId() == null, "legacy null sound-buffer identity remains supported");
        SoundPlayer nullIdPlayer = new SoundPlayer(nullIdBuffer);
        nullIdPlayer.delete();
        nullIdBuffer.delete();
        int operationsAfterNullCleanup = globalRetryBackend.totalOperationCalls.get();
        nullIdPlayer.delete();
        nullIdBuffer.delete();
        check(globalRetryBackend.totalOperationCalls.get() == operationsAfterNullCleanup,
            "repeated player/buffer cleanup never reaches released fake handles");
        globalRetryBackend.assertAllReleasedExactlyOnce("global retry playback ownership");
    }

    private static void previewRestartReplacesUniqueOnly() throws Exception
    {
        Link link = Link.assets("audio/restart-preview.wav");
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(wave));
        FakeSoundBackend backend = new FakeSoundBackend();
        SoundManager manager = new SoundManager(provider, backend, Runnable::run, () -> true);

        /* Waveform generation is GPU-backed and intentionally excluded from
         * this headless lifecycle test; replacement ownership is identical. */
        SoundPlayer firstPreview = manager.restartUnique(link, false);
        int firstPreviewSource = firstPreview.getSource();
        Object owner = new Object();
        Object clip = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();
        desired.put(clip, new SoundManager.VoiceRequest(link, 0.25F, 0.75F));
        manager.reconcile(owner, desired, true);
        SoundPlayer ownedVoice = manager.getOwnedVoice(owner, clip);
        int ownedSource = ownedVoice.getSource();

        SoundPlayer secondPreview = manager.restartUnique(link, false);

        check(secondPreview != null && secondPreview != firstPreview
                && secondPreview.getSource() != firstPreviewSource,
            "preview restart creates a fresh source from the beginning");
        check(backend.successfulSourceDeletes(firstPreviewSource) == 1,
            "preview restart releases the previous unique source exactly once");
        check(manager.getOwnedVoice(owner, clip) == ownedVoice
                && ownedVoice.getSource() == ownedSource
                && backend.sources.containsKey(ownedSource),
            "preview restart leaves an owner-scoped voice using the same link untouched");
        check(manager.getPlayers().stream().filter(SoundPlayer::isUnique).count() == 1,
            "preview restart retains exactly one unique preview source");

        int secondPreviewSource = secondPreview.getSource();
        SoundPlayer thirdPreview = manager.restartUnique(link, false);

        check(thirdPreview != null && thirdPreview.getSource() != secondPreviewSource,
            "every preview click replaces the prior source");
        check(backend.successfulSourceDeletes(secondPreviewSource) == 1,
            "second preview source is released on the next click");
        check(manager.getOwnedVoice(owner, clip) == ownedVoice,
            "repeated preview restarts never stop the owner-scoped voice");

        manager.releaseOwner(owner);
        manager.deleteSounds();
        backend.assertAllReleasedExactlyOnce("preview restart ownership");
    }

    private static void playbackContextDispatchDefersBackend() throws Exception
    {
        Link link = Link.assets("audio/context-dispatch.wav");
        Wave wave = new Wave(new PcmFormat(PcmEncoding.PCM_S16_LE, ChannelLayout.MONO, 10), new byte[200]);
        MemoryAssetProvider provider = new MemoryAssetProvider();
        provider.put(link, wav(wave));
        FakeSoundBackend backend = new FakeSoundBackend();
        FakeDispatcher dispatcher = new FakeDispatcher();
        AtomicBoolean context = new AtomicBoolean(false);
        SoundManager manager = new SoundManager(provider, backend, dispatcher, context::get);
        Object owner = new Object();
        Object clip = new Object();
        IdentityHashMap<Object, SoundManager.VoiceRequest> desired = new IdentityHashMap<>();
        desired.put(clip, new SoundManager.VoiceRequest(link, .25F, .5F));

        manager.reconcile(owner, desired, true);
        check(dispatcher.submitCalls.get() == 1 && dispatcher.runCalls.get() == 0,
            "context switch queues one reconcile operation");
        check(backend.totalOperationCalls.get() == 0 && backend.sources.isEmpty() && backend.buffers.isEmpty(),
            "playback backend has zero calls before context switch");

        context.set(true);
        dispatcher.runNext();
        SoundPlayer player = manager.getOwnedVoice(owner, clip);
        check(player != null && backend.bufferCreateCalls.get() == 1 && backend.sourceCreateCalls.get() == 1,
            "context dispatcher acquires one buffer and one source");
        int source = player.getSource();
        int buffer = player.getBuffer().getBuffer();
        int operationsBeforeReleaseDispatch = backend.totalOperationCalls.get();

        context.set(false);
        manager.releaseOwner(owner);
        check(dispatcher.submitCalls.get() == 2 && dispatcher.runCalls.get() == 1,
            "off-context release queues one dispatcher operation");
        check(backend.totalOperationCalls.get() == operationsBeforeReleaseDispatch,
            "off-context release performs zero backend calls before dispatch");

        context.set(true);
        dispatcher.runNext();
        check(backend.successfulSourceDeletes(source) == 1,
            "dispatched owner release deletes source exactly once");
        manager.deleteSound(link);
        check(backend.successfulBufferDeletes(buffer) == 1,
            "buffer releases exactly once after its dispatched source");
        int operationsAfterCleanup = backend.totalOperationCalls.get();
        manager.releaseOwner(owner);
        manager.deleteSound(link);
        manager.deleteSounds();
        check(backend.totalOperationCalls.get() == operationsAfterCleanup,
            "repeated playback cleanup never calls released source or buffer handles");
        check(dispatcher.submitCalls.get() == 2 && dispatcher.runCalls.get() == 2
                && dispatcher.queuedTasks() == 0,
            "context dispatcher submits and runs each ownership task exactly once");
        backend.assertAllReleasedExactlyOnce("context-dispatched playback ownership");
    }

    private static void cameraControllerRemovalShutsDown()
    {
        CameraController controllers = new CameraController();
        TestCameraController first = new TestCameraController();
        TestCameraController second = new TestCameraController();

        controllers.add(first);
        controllers.add(second);
        check(controllers.remove(first) == first, "camera controller removed by identity");
        check(first.shutdownCalls.get() == 1, "identity removal shuts controller down once");
        check(controllers.remove(first) == null && first.shutdownCalls.get() == 1,
            "repeated identity removal is idempotent");
        controllers.reset();
        check(second.shutdownCalls.get() == 1, "camera reset shuts remaining controllers down");

        TestCameraController third = new TestCameraController();
        TestCameraController fourth = new TestCameraController();
        controllers.add(third);
        controllers.add(fourth);
        check(controllers.removeAll(TestCameraController.class).size() == 2,
            "class removal returns every removed controller");
        check(third.shutdownCalls.get() == 1 && fourth.shutdownCalls.get() == 1,
            "class removal shuts every controller down");
    }

    private static byte[] wav(Wave wave) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WaveWriter.write(output, wave);

        return output.toByteArray();
    }

    private static byte[] monoFrames(int... samples)
    {
        byte[] output = new byte[samples.length * 2];

        for (int i = 0; i < samples.length; i++)
        {
            putShort(output, i * 2, samples[i]);
        }

        return output;
    }

    private static byte[] stereoFrames(int[][] samples)
    {
        byte[] output = new byte[samples.length * 4];

        for (int i = 0; i < samples.length; i++)
        {
            putShort(output, i * 4, samples[i][0]);
            putShort(output, i * 4 + 2, samples[i][1]);
        }

        return output;
    }

    private static byte[] stereoTestFrames(int frames)
    {
        int[][] samples = new int[frames][2];

        for (int i = 0; i < frames; i++)
        {
            samples[i][0] = 1000 + i;
            samples[i][1] = -1000 - i;
        }

        return stereoFrames(samples);
    }

    private static void putShort(byte[] output, int offset, int value)
    {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
    }

    private static byte[] concat(byte[] first, byte[] second)
    {
        byte[] output = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, output, first.length, second.length);

        return output;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TempOwnershipTracker
    {
        private final Map<Path, Integer> createCalls = new HashMap<>();
        private final Map<Path, Integer> releaseCalls = new HashMap<>();

        private void claimOnlyFile(Path directory) throws IOException
        {
            List<Path> files;

            try (var paths = Files.list(directory))
            {
                files = paths.filter(Files::isRegularFile).toList();
            }

            check(files.size() == 1, "capture owns exactly one temporary file");
            this.claim(files.get(0));
        }

        private synchronized void claim(Path path)
        {
            Path key = normalize(path);
            check(Files.exists(key), "claimed capture temp exists");

            if (this.createCalls.putIfAbsent(key, 1) != null)
            {
                throw new AssertionError("capture temp identity was claimed twice: " + key);
            }
        }

        private synchronized boolean owns(Path path)
        {
            return path != null && this.createCalls.containsKey(normalize(path));
        }

        private synchronized Path singlePath()
        {
            check(this.createCalls.size() == 1, "one capture temp identity was created");

            return this.createCalls.keySet().iterator().next();
        }

        private synchronized void observeReleased(Path path)
        {
            Path key = this.beginRelease(path);
            check(!Files.exists(key), "production released tracked capture temp");
            this.releaseCalls.put(key, 1);
        }

        private synchronized void deleteOwned(Path path) throws IOException
        {
            Path key = this.beginRelease(path);
            check(Files.deleteIfExists(key), "test owner deleted existing capture temp");
            this.releaseCalls.put(key, 1);
        }

        private Path beginRelease(Path path)
        {
            Path key = normalize(path);
            check(this.createCalls.containsKey(key), "only a claimed capture temp may be released");

            if (this.releaseCalls.containsKey(key))
            {
                throw new AssertionError("capture temp identity was released twice: " + key);
            }

            return key;
        }

        private synchronized void assertFullyReleased(String message)
        {
            check(this.createCalls.size() == 1 && this.releaseCalls.size() == 1, message + " counts");
            Path path = this.singlePath();
            check(this.createCalls.get(path) == 1 && this.releaseCalls.get(path) == 1, message + " exactly once");
            check(!Files.exists(path), message + " no file remains");
        }

        private static Path normalize(Path path)
        {
            if (path == null)
            {
                throw new AssertionError("capture temp identity is null");
            }

            return path.toAbsolutePath().normalize();
        }
    }

    private static final class FakeDispatcher implements java.util.function.Consumer<Runnable>
    {
        private final Deque<OwnedTask> tasks = new ArrayDeque<>();
        private final AtomicInteger submitCalls = new AtomicInteger();
        private final AtomicInteger runCalls = new AtomicInteger();
        private volatile Thread dispatchThread;

        @Override
        public synchronized void accept(Runnable task)
        {
            if (task == null)
            {
                throw new AssertionError("dispatcher cannot own a null task");
            }

            this.submitCalls.incrementAndGet();
            this.tasks.addLast(new OwnedTask(task));
        }

        private void runNext()
        {
            OwnedTask task;

            synchronized (this)
            {
                task = this.tasks.pollFirst();
            }

            if (task == null)
            {
                throw new AssertionError("dispatcher has no owned task to run");
            }

            task.run();
        }

        private void runAll()
        {
            while (this.queuedTasks() > 0)
            {
                this.runNext();
            }
        }

        private synchronized int queuedTasks()
        {
            return this.tasks.size();
        }

        private boolean isDispatching()
        {
            return this.dispatchThread == Thread.currentThread();
        }

        private final class OwnedTask implements Runnable
        {
            private final Runnable task;
            private boolean released;

            private OwnedTask(Runnable task)
            {
                this.task = task;
            }

            @Override
            public void run()
            {
                synchronized (FakeDispatcher.this)
                {
                    if (this.released)
                    {
                        throw new AssertionError("dispatcher task was released twice");
                    }

                    if (FakeDispatcher.this.dispatchThread != null)
                    {
                        throw new AssertionError("dispatcher cannot run two owned tasks concurrently");
                    }

                    this.released = true;
                    FakeDispatcher.this.runCalls.incrementAndGet();
                    FakeDispatcher.this.dispatchThread = Thread.currentThread();
                }

                try
                {
                    this.task.run();
                }
                finally
                {
                    synchronized (FakeDispatcher.this)
                    {
                        FakeDispatcher.this.dispatchThread = null;
                    }
                }
            }
        }
    }

    private static final class FakeCaptureBackend implements CaptureBackend
    {
        private enum DeviceState { NEW, OPEN, STARTED, STOPPED, CLOSED }

        private final String deviceName;
        private final Deque<byte[]> queue = new ArrayDeque<>();
        private final List<byte[]> tail = new ArrayList<>();
        private final AtomicReference<DeviceState> deviceState = new AtomicReference<>(DeviceState.NEW);
        private final AtomicInteger defaultDeviceCalls = new AtomicInteger();
        private final AtomicInteger openCalls = new AtomicInteger();
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger availableCalls = new AtomicInteger();
        private final AtomicInteger liveAvailableCalls = new AtomicInteger();
        private final AtomicInteger finalDrainAvailableCalls = new AtomicInteger();
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger liveReadCalls = new AtomicInteger();
        private final AtomicInteger finalDrainReadCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch firstRead = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private long openHandle = 7L;
        private volatile boolean blockClose;
        private volatile boolean enumerationFailure;
        private volatile boolean readFailure;
        private volatile boolean pollFailure;
        private volatile boolean finalDrainPollFailure;
        private volatile boolean finalDrainReadFailure;
        private volatile boolean stopFailure;
        private volatile boolean closeFailure;
        private volatile boolean unsupportedMode;
        private volatile boolean openFailure;
        private volatile boolean startFailure;
        private volatile int forcedAvailable = -1;
        private volatile int availableAfterFirstRead = -1;
        private volatile int bytesPerFrame = 2;

        private FakeCaptureBackend(String deviceName)
        {
            this.deviceName = deviceName;
        }

        private void enqueueLive(byte[] bytes, int channels)
        {
            validateChunk(bytes, channels);
            this.queue.add(bytes);
        }

        private void enqueueTail(byte[] bytes, int channels)
        {
            validateChunk(bytes, channels);
            this.tail.add(bytes);
        }

        private static void validateChunk(byte[] bytes, int channels)
        {
            if (bytes == null || (channels != 1 && channels != 2) || bytes.length % (channels * 2) != 0)
            {
                throw new AssertionError("fake capture chunk must contain complete PCM16 frames");
            }
        }

        @Override
        public String defaultDevice() throws Exception
        {
            this.defaultDeviceCalls.incrementAndGet();

            if (this.enumerationFailure)
            {
                throw new IOException("injected device enumeration failure");
            }

            return this.deviceName;
        }

        @Override
        public long open(String deviceName, CaptureSpec spec)
            throws Exception
        {
            this.openCalls.incrementAndGet();
            this.bytesPerFrame = spec.bytesPerFrame();

            if (this.deviceName == null || !this.deviceName.equals(deviceName))
            {
                throw new AssertionError("fake capture opened an unenumerated device identity");
            }

            if (this.unsupportedMode)
            {
                throw new CaptureBackend.UnsupportedModeException("injected unsupported mode");
            }

            if (this.openFailure)
            {
                throw new IOException("injected open failure");
            }

            if (this.openHandle != 0L
                && !this.deviceState.compareAndSet(DeviceState.NEW, DeviceState.OPEN))
            {
                throw new AssertionError("capture device identity opened twice: " + this.openHandle);
            }

            return this.openHandle;
        }

        @Override
        public void start(long device) throws Exception
        {
            this.startCalls.incrementAndGet();
            this.requireDevice(device, "start", DeviceState.OPEN);

            if (this.startFailure)
            {
                throw new IOException("injected start failure");
            }

            if (!this.deviceState.compareAndSet(DeviceState.OPEN, DeviceState.STARTED))
            {
                throw new AssertionError("capture device start raced for identity " + device);
            }
        }

        @Override
        public int availableFrames(long device) throws Exception
        {
            this.availableCalls.incrementAndGet();
            DeviceState phase = this.requireDevice(device, "poll", DeviceState.STARTED, DeviceState.STOPPED);

            if (phase == DeviceState.STARTED)
            {
                this.liveAvailableCalls.incrementAndGet();

                if (this.pollFailure)
                {
                    throw new IOException("injected live poll failure");
                }
            }
            else
            {
                this.finalDrainAvailableCalls.incrementAndGet();

                if (this.finalDrainPollFailure)
                {
                    throw new IOException("injected final-drain poll failure");
                }
            }

            if (this.forcedAvailable >= 0)
            {
                return this.forcedAvailable;
            }

            if (this.availableAfterFirstRead >= 0 && this.readCalls.get() > 0)
            {
                return this.availableAfterFirstRead;
            }

            int bytes = this.queue.stream().mapToInt(value -> value.length).sum();

            return bytes / this.bytesPerFrame;
        }

        @Override
        public void read(long device, ByteBuffer destination, int frames) throws Exception
        {
            this.firstRead.countDown();
            this.readCalls.incrementAndGet();
            DeviceState phase = this.requireDevice(device, "read", DeviceState.STARTED, DeviceState.STOPPED);

            if (phase == DeviceState.STARTED)
            {
                this.liveReadCalls.incrementAndGet();
            }
            else
            {
                this.finalDrainReadCalls.incrementAndGet();
            }

            if (this.readFailure || (phase == DeviceState.STOPPED && this.finalDrainReadFailure))
            {
                throw new IOException(phase == DeviceState.STOPPED
                    ? "injected final-drain read failure" : "injected live read failure");
            }

            int bytes = frames * this.bytesPerFrame;

            if (frames <= 0 || destination == null || destination.remaining() != bytes)
            {
                throw new AssertionError("capture read must own one exact destination chunk");
            }

            int copied = 0;

            while (copied < bytes && !this.queue.isEmpty())
            {
                byte[] chunk = this.queue.removeFirst();
                int amount = Math.min(chunk.length, bytes - copied);
                destination.put(chunk, 0, amount);
                copied += amount;

                if (amount < chunk.length)
                {
                    this.queue.addFirst(Arrays.copyOfRange(chunk, amount, chunk.length));
                }
            }

            if (copied != bytes)
            {
                throw new IOException("fake capture underrun");
            }
        }

        @Override
        public void stop(long device) throws Exception
        {
            this.stopCalls.incrementAndGet();
            DeviceState current = this.requireDevice(device, "stop", DeviceState.OPEN, DeviceState.STARTED);

            if (!this.deviceState.compareAndSet(current, DeviceState.STOPPED))
            {
                throw new AssertionError("capture device stop raced for identity " + device);
            }

            if (this.stopFailure)
            {
                throw new IOException("injected stop failure");
            }

            this.queue.addAll(this.tail);
            this.tail.clear();
        }

        @Override
        public void close(long device) throws Exception
        {
            this.closeCalls.incrementAndGet();
            this.closeEntered.countDown();
            DeviceState current = this.requireDevice(device, "close",
                DeviceState.OPEN, DeviceState.STARTED, DeviceState.STOPPED);

            if (!this.deviceState.compareAndSet(current, DeviceState.CLOSED))
            {
                throw new AssertionError("capture device close raced for identity " + device);
            }

            if (this.blockClose)
            {
                while (this.allowClose.getCount() != 0L)
                {
                    try
                    {
                        this.allowClose.await(10, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException ignored)
                    {}
                }
            }

            if (this.closeFailure)
            {
                throw new IOException("injected close failure");
            }
        }

        private DeviceState requireDevice(long device, String operation, DeviceState... allowed)
        {
            if (device == 0L || device != this.openHandle)
            {
                throw new AssertionError(operation + " used an unknown capture device identity " + device);
            }

            DeviceState current = this.deviceState.get();

            for (DeviceState state : allowed)
            {
                if (current == state)
                {
                    return current;
                }
            }

            throw new AssertionError(operation + " reused released capture device " + device
                + " in state " + current);
        }
    }

    private static final class MemoryAssetProvider extends AssetProvider
    {
        private final Map<Link, byte[]> assets = new HashMap<>();
        private final Map<Link, Integer> readCounts = new HashMap<>();

        private void put(Link link, byte[] bytes)
        {
            this.assets.put(link, bytes);
        }

        @Override
        public java.io.InputStream getAsset(Link link) throws IOException
        {
            this.readCounts.merge(link, 1, Integer::sum);
            byte[] bytes = this.assets.get(link);

            if (bytes == null)
            {
                throw new IOException("missing " + link);
            }

            return new ByteArrayInputStream(bytes);
        }

        private int reads(Link link)
        {
            return this.readCounts.getOrDefault(link, 0);
        }
    }

    private static final class EqualOwner
    {
        private final String value;

        private EqualOwner(String value)
        {
            this.value = value;
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof EqualOwner owner && this.value.equals(owner.value);
        }

        @Override
        public int hashCode()
        {
            return this.value.hashCode();
        }
    }

    private static final class TestCameraController implements ICameraController
    {
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        @Override
        public void setup(Camera camera, float transition)
        {}

        @Override
        public void shutdown()
        {
            this.shutdownCalls.incrementAndGet();
        }
    }

    private static final class FakeSoundBackend implements SoundBackend
    {
        private enum State { INITIAL, PLAYING, PAUSED, STOPPED }

        private static final class Source
        {
            private final int handle;
            private int buffer;
            private float gain;
            private float offset;
            private float pitch = 1F;
            private float maxDistance;
            private boolean relative;
            private boolean looping;
            private float x;
            private float y;
            private float z;
            private State state = State.INITIAL;

            private Source(int handle)
            {
                this.handle = handle;
            }
        }

        private final AtomicInteger nextBuffer = new AtomicInteger(100);
        private final AtomicInteger nextSource = new AtomicInteger(1);
        private final AtomicInteger totalOperationCalls = new AtomicInteger();
        private final AtomicInteger bufferCreateCalls = new AtomicInteger();
        private final AtomicInteger sourceCreateCalls = new AtomicInteger();
        private final Map<String, Integer> operationCalls = new HashMap<>();
        private final Map<Integer, Wave> buffers = new HashMap<>();
        private final Map<Integer, Source> sources = new HashMap<>();
        private final Map<Integer, Integer> createdBufferHandles = new HashMap<>();
        private final Map<Integer, Integer> createdSourceHandles = new HashMap<>();
        private final Map<Integer, Integer> bufferDeleteAttempts = new HashMap<>();
        private final Map<Integer, Integer> sourceDeleteAttempts = new HashMap<>();
        private final Map<Integer, Integer> bufferDeleteSuccesses = new HashMap<>();
        private final Map<Integer, Integer> sourceDeleteSuccesses = new HashMap<>();
        private final List<Integer> deletedBufferHandles = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean failNextStop;
        private boolean failNextDetach;
        private boolean failNextDeleteSource;
        private boolean failNextDeleteBuffer;

        @Override
        public int createBuffer(Wave wave)
        {
            this.record("createBuffer");
            int handle = this.nextBuffer.getAndIncrement();
            check(this.buffers.put(handle, wave) == null, "fake buffer handle is unique");
            check(this.createdBufferHandles.put(handle, 1) == null, "fake buffer identity is created once");
            this.bufferCreateCalls.incrementAndGet();
            this.events.add("createBuffer:" + handle);

            return handle;
        }

        @Override
        public void deleteBuffer(int buffer)
        {
            this.record("deleteBuffer");
            this.bufferDeleteAttempts.merge(buffer, 1, Integer::sum);

            if (!this.buffers.containsKey(buffer))
            {
                throw new AssertionError("buffer identity released twice or was never created: " + buffer);
            }

            if (this.failNextDeleteBuffer)
            {
                this.failNextDeleteBuffer = false;
                throw new IllegalStateException("injected buffer deletion failure");
            }

            for (Source source : this.sources.values())
            {
                if (source.buffer == buffer)
                {
                    throw new AssertionError("buffer released while source remains attached: " + buffer);
                }
            }

            this.events.add("deleteBuffer:" + buffer);
            this.deletedBufferHandles.add(buffer);
            this.buffers.remove(buffer);
            int successes = this.bufferDeleteSuccesses.merge(buffer, 1, Integer::sum);
            check(successes == 1, "buffer identity release succeeds exactly once");
        }

        @Override
        public int createSource()
        {
            this.record("createSource");
            int handle = this.nextSource.getAndIncrement();
            check(this.sources.put(handle, new Source(handle)) == null, "fake source handle is unique");
            check(this.createdSourceHandles.put(handle, 1) == null, "fake source identity is created once");
            this.sourceCreateCalls.incrementAndGet();
            this.events.add("createSource:" + handle);

            return handle;
        }

        private Source source(int handle)
        {
            Source source = this.sources.get(handle);
            check(source != null, "source identity is still live: " + handle);

            return source;
        }

        private Source source(SoundPlayer player)
        {
            return this.sources.get(player.getSource());
        }

        private Wave buffer(int handle)
        {
            Wave wave = this.buffers.get(handle);
            check(wave != null, "buffer identity is still live: " + handle);

            return wave;
        }

        @Override
        public void setSourceBuffer(int source, int buffer)
        {
            this.record("setSourceBuffer");
            Source owned = this.source(source);

            if (buffer == 0 && this.failNextDetach)
            {
                this.failNextDetach = false;
                throw new IllegalStateException("injected detach failure");
            }

            if (buffer != 0 && !this.buffers.containsKey(buffer))
            {
                throw new AssertionError("source attached unknown or released buffer " + buffer);
            }

            owned.buffer = buffer;
            this.events.add((buffer == 0 ? "detach:" : "attach:") + source + ":" + buffer);
        }

        @Override
        public void setSourceMaxDistance(int source, float distance)
        {
            this.record("setSourceMaxDistance");
            this.source(source).maxDistance = distance;
        }

        @Override
        public void setSourceVolume(int source, float volume)
        {
            this.record("setSourceVolume");
            this.source(source).gain = volume;
        }

        @Override
        public void setSourcePitch(int source, float pitch)
        {
            this.record("setSourcePitch");
            this.source(source).pitch = pitch;
        }

        @Override
        public void setSourceRelative(int source, boolean relative)
        {
            this.record("setSourceRelative");
            this.source(source).relative = relative;
        }

        @Override
        public void setSourceLooping(int source, boolean looping)
        {
            this.record("setSourceLooping");
            this.source(source).looping = looping;
        }

        @Override
        public void setSourcePosition(int source, float x, float y, float z)
        {
            this.record("setSourcePosition");
            Source owned = this.source(source);

            owned.x = x;
            owned.y = y;
            owned.z = z;
        }

        @Override
        public void setSourceVelocity(int source, float x, float y, float z)
        {
            this.record("setSourceVelocity");
            this.source(source);
        }

        @Override
        public void playSource(int source)
        {
            this.record("playSource");
            this.source(source).state = State.PLAYING;
            this.events.add("play:" + source);
        }

        @Override
        public void pauseSource(int source)
        {
            this.record("pauseSource");
            this.source(source).state = State.PAUSED;
            this.events.add("pause:" + source);
        }

        @Override
        public void stopSource(int source)
        {
            this.record("stopSource");
            Source owned = this.source(source);

            if (this.failNextStop)
            {
                this.failNextStop = false;
                throw new IllegalStateException("injected stop");
            }

            owned.state = State.STOPPED;
            this.events.add("stop:" + source);
        }

        @Override
        public boolean isSourcePlaying(int source)
        {
            this.record("isSourcePlaying");

            return this.source(source).state == State.PLAYING;
        }

        @Override
        public boolean isSourcePaused(int source)
        {
            this.record("isSourcePaused");

            return this.source(source).state == State.PAUSED;
        }

        @Override
        public boolean isSourceStopped(int source)
        {
            this.record("isSourceStopped");

            return this.source(source).state == State.STOPPED;
        }

        @Override
        public float getSourcePosition(int source)
        {
            this.record("getSourcePosition");

            return this.source(source).offset;
        }

        @Override
        public void setSourcePositionSeconds(int source, float seconds)
        {
            this.record("setSourcePositionSeconds");
            this.source(source).offset = seconds;
            this.events.add("seek:" + source);
        }

        @Override
        public void deleteSource(int source)
        {
            this.record("deleteSource");
            this.sourceDeleteAttempts.merge(source, 1, Integer::sum);
            Source owned = this.sources.get(source);

            if (owned == null)
            {
                throw new AssertionError("source identity released twice or was never created: " + source);
            }

            if (this.failNextDeleteSource)
            {
                this.failNextDeleteSource = false;
                throw new IllegalStateException("injected source deletion failure");
            }

            if (owned.buffer != 0)
            {
                throw new AssertionError("source released before buffer detach: " + source);
            }

            this.events.add("deleteSource:" + source);
            this.sources.remove(source);
            int successes = this.sourceDeleteSuccesses.merge(source, 1, Integer::sum);
            check(successes == 1, "source identity release succeeds exactly once");
        }

        private synchronized void record(String operation)
        {
            this.totalOperationCalls.incrementAndGet();
            this.operationCalls.merge(operation, 1, Integer::sum);
        }

        private int successfulSourceDeletes(int source)
        {
            return this.sourceDeleteSuccesses.getOrDefault(source, 0);
        }

        private int successfulBufferDeletes(int buffer)
        {
            return this.bufferDeleteSuccesses.getOrDefault(buffer, 0);
        }

        private void assertAllReleasedExactlyOnce(String message)
        {
            check(this.sources.isEmpty(), message + " has no live sources");
            check(this.buffers.isEmpty(), message + " has no live buffers");

            for (Map.Entry<Integer, Integer> entry : this.createdSourceHandles.entrySet())
            {
                check(entry.getValue() == 1, message + " source created exactly once: " + entry.getKey());
                check(this.sourceDeleteSuccesses.getOrDefault(entry.getKey(), 0) == 1,
                    message + " source released exactly once: " + entry.getKey());
                check(this.sourceDeleteAttempts.getOrDefault(entry.getKey(), 0) >= 1,
                    message + " source release attempted: " + entry.getKey());
            }

            for (Map.Entry<Integer, Integer> entry : this.createdBufferHandles.entrySet())
            {
                check(entry.getValue() == 1, message + " buffer created exactly once: " + entry.getKey());
                check(this.bufferDeleteSuccesses.getOrDefault(entry.getKey(), 0) == 1,
                    message + " buffer released exactly once: " + entry.getKey());
                check(this.bufferDeleteAttempts.getOrDefault(entry.getKey(), 0) >= 1,
                    message + " buffer release attempted: " + entry.getKey());
            }
        }
    }
}
