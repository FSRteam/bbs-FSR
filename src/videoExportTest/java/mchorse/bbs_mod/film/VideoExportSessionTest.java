package mchorse.bbs_mod.film;

import mchorse.bbs_mod.audio.ChannelLayout;
import mchorse.bbs_mod.utils.VideoExportAudioProfile;
import mchorse.bbs_mod.utils.VideoExportProcess;
import mchorse.bbs_mod.utils.VideoRecorder;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-light lifecycle regressions using an injectable recorder. */
public final class VideoExportSessionTest
{
    private VideoExportSessionTest()
    {}

    public static void runAll() throws Exception
    {
        assertRejectedWorldStartCleanupCannotTogglePlayback();
        assertWorldExportKeyOwnership();
        assertEarlyWorldExportToggleCancellationIsTyped();
        assertPrepareFailureNotifiesOnce();
        assertImmediateAndDelayedStartFailureMatch();
        assertRuntimeRecorderFailureWins();
        assertZeroDelayWaitsForReadiness();
        assertCancellationIsIdempotent();
        assertPendingReservationCanBeFenced();
        assertSharedWarmupReservation();
        assertPrepareReentryCannotReleaseOrOverwriteLease();
        assertTargetFailureTearsDown();
        assertTeardownFailureSuppressesSuccessEffects();
        assertCleanupStepsContinueAfterFailure();
        assertTeardownReentryCannotReleaseOrOverwriteLease();
        assertResetFailureCannotWedgeSession();
        assertSuccessNotifiesAfterTeardown();
        assertListenerFailureCannotWedgeSession();
        assertListenerCanReenterStableSession();
        assertImmediateFailureListenerCanReenter();
        assertQueuedOwnedCancellationDeliversOnce();
        assertRunningOwnedCancellationWaitsForWorker();
        assertOwnedTerminalFailuresAreIsolated();
    }

    private static void assertRejectedWorldStartCleanupCannotTogglePlayback() throws Exception
    {
        Path source = Path.of("src/client/java/mchorse/bbs_mod/film/WorldVideoExportSession.java");
        String session = Files.readString(source).replace("\r\n", "\n");
        String start = sourceSection(session, "private void startFilmPlayback()", "private void stopFilmPlayback()");
        String stop = sourceSection(session, "private void stopFilmPlayback()", "private void applyWindowSize(");

        assertTrue(start.contains("ClientNetwork.sendToggleFilm(this.filmId, false)"),
            "world export no longer sends its existing remote start request");
        assertTrue(stop.contains("ClientNetwork.sendActionState(this.filmId, ActionState.STOP, 0)"),
            "rejected or lost remote start cleanup does not use the explicit idempotent STOP action");
        assertFalse(stop.contains("sendToggleFilm"),
            "rejected or lost remote start cleanup can still toggle an absent film into playback");
    }

    private static void assertWorldExportKeyOwnership() throws Exception
    {
        Path source = Path.of("src/client/java/mchorse/bbs_mod/BBSModClient.java");
        String client = Files.readString(source).replace("\r\n", "\n");
        String key = sourceSection(client, "private static void keyRecordVideo(Minecraft mc)", "private static KeyMapping createKey");

        assertTrue(key.contains("VideoExportRequest activeRequest = worldExportSession.getActiveExportRequest()"),
            "F4 does not read the immutable active request fence");
        assertFalse(key.contains("worldExportSession.getFilmId()"),
            "F4 ownership still depends on mutable Film state");
        String f4Fence = sourceSection(key, "VideoExportRequest activeRequest", "if (worldExportSession.isRecording())");
        assertTrue(f4Fence.contains("activeRequest == null")
                && f4Fence.contains("!activeRequest.openEnd()")
                && f4Fence.contains("!activeRequest.sourceId().isEmpty()"),
            "F4 does not reject a closed or unidentified export before stop");
        assertTrue(key.contains("if (worldExportSession.isRecording())"),
            "F4 does not distinguish an active recording from warm-up");
        assertTrue(key.contains("worldExportSession.stop();"),
            "F4 active recording does not use the completing stop path");
        assertTrue(key.contains("worldExportSession.cancel();"),
            "F4 warm-up cancellation path was removed");

        int stop = key.indexOf("worldExportSession.stop();");
        int cancel = key.indexOf("worldExportSession.cancel();");

        assertTrue(stop >= 0 && cancel >= 0 && stop < cancel,
            "F4 completion branch is not ordered before warm-up cancellation");

        String f6 = sourceSection(client, "private static void keyPlayFilmAndRecord()",
            "private static void keyPauseFilm");
        assertTrue(f6.contains("VideoExportRequest activeRequest = worldExportSession.getActiveExportRequest()"),
            "F6 does not read the immutable active request fence");
        assertFalse(f6.contains("worldExportSession.getFilmId()"),
            "F6 ownership still depends on mutable Film state");
        assertTrue(f6.contains("activeRequest != null")
                && f6.contains("!activeRequest.openEnd()")
                && f6.contains("Objects.equals(filmId, activeRequest.sourceId())"),
            "F6 can cancel an unrelated or open-ended export");

        int f6Cancel = f6.indexOf("worldExportSession.cancel();");
        int f6Fence = f6.indexOf("if (activeRequest != null");
        assertTrue(f6Fence >= 0 && f6Cancel > f6Fence,
            "F6 cancellation is not behind its owner fence");
    }

    private static void assertEarlyWorldExportToggleCancellationIsTyped() throws Exception
    {
        assertEarlyOwnedCancellation("F4", true, "");
        assertEarlyOwnedCancellation("F6", false, "film-id");
    }

    private static void assertEarlyOwnedCancellation(String command, boolean openEnd,
                                                      String sourceId) throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-" + command.toLowerCase() + "-warmup-");
        OwnedTestSession session = new OwnedTestSession(root, true, false);
        AtomicInteger legacy = new AtomicInteger();
        AtomicInteger typed = new AtomicInteger();

        try
        {
            session.setFinishedListener(aborted ->
            {
                assertTrue(aborted, command + " warm-up toggle was reported as success");
                legacy.incrementAndGet();
            });
            session.setFinishedResultListener(result ->
            {
                assertEquals(VideoExportResult.Kind.CANCELLED, result.kind());
                assertEquals(VideoExportResult.Stage.CANCELLED, result.stage());
                typed.incrementAndGet();
            });

            assertTrue(session.startOwned(false, openEnd, sourceId),
                command + " did not enter its warm-up ownership window");
            assertTrue(session.isWarmingUp(), command + " warm-up was not observable");
            assertEquals(openEnd, session.getActiveExportRequest().openEnd());
            assertEquals(sourceId, session.getActiveExportRequest().sourceId());

            session.cancel();

            assertEquals(VideoExportResult.Kind.CANCELLED, session.getLastExportResult().kind());
            assertEquals(VideoExportSession.Result.CANCELLED, session.getLastResult());
            assertEquals(0, session.recorder.completeCount);
            assertEquals(1, session.recorder.cancelCount);
            assertEquals(0, session.recorder.announceCount);
            assertEquals(1, legacy.get());
            assertEquals(1, typed.get());
            assertEquals(1, session.teardownCount);
            assertFalse(session.isExporting(), command + " warm-up cancellation left the session active");
            assertFalse(Files.exists(session.artifacts.workDirectory()),
                command + " warm-up cancellation left owned artifacts behind");
        }
        finally
        {
            session.close();
            deleteTree(root);
        }
    }

    private static String sourceSection(String source, String start, String end)
    {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());

        if (from < 0 || to <= from)
        {
            throw new AssertionError("Could not locate source section " + start);
        }

        return source.substring(from, to);
    }

    private static void assertPrepareFailureNotifiesOnce()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();

        session.prepareResult = false;
        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "Preparation failure was reported as success");
            calls.incrementAndGet();
        });

        assertFalse(session.start(true), "Preparation failure left the session active");
        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(1, calls.get());
        assertEquals(0, session.teardownCount);

        TestSession next = new TestSession(recorder);

        assertTrue(next.start(false), "Preparation failure leaked the shared recorder reservation");
        next.cancel();
    }

    private static void assertImmediateAndDelayedStartFailureMatch()
    {
        assertStartFailure(true);
        assertStartFailure(false);
    }

    private static void assertStartFailure(boolean immediatelyReady)
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();

        recorder.startSucceeds = false;
        session.ready = immediatelyReady;
        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "FFmpeg startup failure was reported as success");
            calls.incrementAndGet();
        });

        boolean started = session.start(immediatelyReady);

        if (!immediatelyReady)
        {
            assertTrue(started, "Deferred startup did not enter warm-up");
            session.ready = true;
            session.update();
        }

        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(1, calls.get());
        assertEquals(1, session.teardownCount);
        assertTrue(session.lastTeardownAborted, "Startup failure used successful teardown semantics");
    }

    private static void assertRuntimeRecorderFailureWins()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("fake runtime encoder failure");

        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "Runtime encoder failure was reported as success");
            calls.incrementAndGet();
        });

        assertTrue(session.start(true), "Recording session did not start");
        recorder.outcome = VideoExportProcess.Outcome.FAILED;
        recorder.failure = failure;
        session.update();
        session.stop();
        session.cancel();

        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(failure, session.getLastFailure());
        assertEquals(1, session.teardownCount);
        assertEquals(1, calls.get());
        assertEquals(0, recorder.announceCount);

        TestSession next = new TestSession(recorder);

        assertTrue(next.start(true), "Runtime encoder failure left the recorder reservation wedged");
        next.cancel();
    }

    private static void assertCancellationIsIdempotent()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();

        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "Cancellation was reported as success");
            calls.incrementAndGet();
        });

        assertTrue(session.start(true), "Recording session did not start");
        session.cancel();
        session.cancel();

        assertEquals(VideoExportSession.Result.CANCELLED, session.getLastResult());
        assertEquals(1, recorder.cancelCount);
        assertEquals(1, session.teardownCount);
        assertEquals(1, calls.get());
        assertEquals(0, recorder.announceCount);
    }

    private static void assertZeroDelayWaitsForReadiness()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);

        assertTrue(session.start(false), "Zero-delay export did not remain in readiness warm-up");
        assertEquals(0, recorder.startCount);

        session.ready = true;
        session.update();

        assertEquals(1, recorder.startCount);
        assertTrue(session.isRecording(), "Ready zero-delay export did not enter recording");
        session.cancel();
    }

    private static void assertSharedWarmupReservation()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession first = new TestSession(recorder);
        TestSession second = new TestSession(recorder);

        first.ready = false;
        second.ready = false;

        assertTrue(first.start(false), "First warm-up did not reserve the recorder");
        assertFalse(second.start(false), "Second session entered warm-up under another owner");

        first.cancel();

        assertTrue(second.start(false), "Terminal cleanup did not release the recorder lease");
        second.cancel();
    }

    private static void assertPendingReservationCanBeFenced()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession pending = new TestSession(recorder);
        TestSession next = new TestSession(recorder);

        assertTrue(pending.reserveRecorder(), "Deferred export failed to reserve the recorder");
        assertFalse(next.reserveRecorder(), "Another owner bypassed a deferred reservation");

        pending.cancelPendingReservation();

        assertTrue(next.reserveRecorder(), "Pending-action fence did not release its reservation");
        next.cancelPendingReservation();
    }

    private static void assertPrepareReentryCannotReleaseOrOverwriteLease()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession first = new TestSession(recorder);
        TestSession competitor = new TestSession(recorder);
        AtomicBoolean leaseHeld = new AtomicBoolean();
        AtomicBoolean reentrantStart = new AtomicBoolean(true);
        AtomicBoolean fieldsPreserved = new AtomicBoolean();

        first.prepareAction = () ->
        {
            first.cancel();
            first.cancelPendingReservation();
            leaseHeld.set(!competitor.reserveRecorder());
            reentrantStart.set(first.start(32, 32));
            fieldsPreserved.set(first.width == 16 && first.height == 16);
        };

        assertTrue(first.start(16, 16), "Prepare reentry prevented the original recording from starting");
        assertTrue(leaseHeld.get(), "Prepare reentry released the original recorder lease");
        assertFalse(reentrantStart.get(), "Prepare reentry started a nested export");
        assertTrue(fieldsPreserved.get(), "Prepare reentry overwrote the original export dimensions");
        assertEquals(1, recorder.startCount);

        first.cancel();
        assertTrue(competitor.reserveRecorder(), "Prepare completion did not eventually release the recorder lease");
        competitor.cancelPendingReservation();
    }

    private static void assertTargetFailureTearsDown()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);

        session.targetThrows = true;

        assertFalse(session.start(true), "Target mutation failure left the session active");
        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(1, session.teardownCount);
        assertTrue(session.lastTeardownAborted, "Target failure did not unwind as an abort");
    }

    private static void assertTeardownFailureSuppressesSuccessEffects()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicBoolean aborted = new AtomicBoolean();

        session.teardownThrows = true;
        session.setFinishedListener(aborted::set);

        assertTrue(session.start(true), "Recording session did not start");
        session.stop();

        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertTrue(aborted.get(), "Teardown failure was reported as success");
        assertEquals(0, recorder.announceCount);
    }

    private static void assertCleanupStepsContinueAfterFailure()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();

        session.aggregateTeardownFailures = true;
        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "Aggregated teardown failure was reported as success");
            calls.incrementAndGet();
        });

        assertTrue(session.start(true), "Recording session did not start");
        session.stop();

        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(3, session.cleanupStepCount);
        assertEquals(1, calls.get());
        assertEquals("fake first cleanup failure", session.getLastFailure().getMessage());
        assertEquals(1, session.getLastFailure().getSuppressed().length);
        assertEquals("fake second cleanup failure", session.getLastFailure().getSuppressed()[0].getMessage());
        assertEquals(0, recorder.announceCount);

        TestSession next = new TestSession(recorder);

        assertTrue(next.start(true), "Aggregated teardown failure left the recorder reservation wedged");
        next.cancel();
    }

    private static void assertTeardownReentryCannotReleaseOrOverwriteLease()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession first = new TestSession(recorder);
        TestSession competitor = new TestSession(recorder);
        AtomicBoolean exposedAsActive = new AtomicBoolean(true);
        AtomicBoolean leaseHeld = new AtomicBoolean();
        AtomicBoolean reentrantStart = new AtomicBoolean(true);
        AtomicBoolean fieldsPreserved = new AtomicBoolean();

        first.teardownAction = () ->
        {
            exposedAsActive.set(first.isExporting());
            first.cancel();
            first.cancelPendingReservation();
            leaseHeld.set(!competitor.reserveRecorder());
            reentrantStart.set(first.start(32, 32));
            fieldsPreserved.set(first.width == 16 && first.height == 16);
        };

        assertTrue(first.start(16, 16), "Recording session did not start");
        first.stop();

        assertEquals(VideoExportSession.Result.SUCCESS, first.getLastResult());
        assertFalse(exposedAsActive.get(), "Teardown was exposed as active and would block UI restoration");
        assertTrue(leaseHeld.get(), "Teardown reentry released the original recorder lease");
        assertFalse(reentrantStart.get(), "Teardown reentry started a nested export");
        assertTrue(fieldsPreserved.get(), "Teardown reentry overwrote the original export dimensions");
        assertTrue(competitor.reserveRecorder(), "Teardown completion did not release the recorder lease");
        competitor.cancelPendingReservation();
    }

    private static void assertResetFailureCannotWedgeSession() throws Exception
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();
        Path directory = Files.createTempDirectory("bbs-export-reset-failure-");
        Path child = Files.writeString(directory.resolve("owned.tmp"), "keep directory non-empty");
        Field temporaryAudio = VideoExportSession.class.getDeclaredField("temporaryAudioFile");

        temporaryAudio.setAccessible(true);
        session.setFinishedListener((aborted) ->
        {
            assertTrue(aborted, "Reset failure was reported as success");
            calls.incrementAndGet();
        });

        assertTrue(session.start(true), "Recording session did not start");
        temporaryAudio.set(session, directory.toFile());
        session.stop();

        assertEquals(VideoExportSession.Result.FAILED, session.getLastResult());
        assertEquals(1, calls.get());
        assertFalse(session.isExporting(), "Reset failure left the session active");

        Files.delete(child);
        Files.delete(directory);

        TestSession next = new TestSession(recorder);

        assertTrue(next.start(true), "Reset failure left the recorder reservation wedged");
        next.cancel();
    }

    private static void assertSuccessNotifiesAfterTeardown()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicBoolean callbackSawTeardown = new AtomicBoolean();

        session.setFinishedListener((aborted) ->
        {
            assertFalse(aborted, "Successful export was reported as aborted");
            callbackSawTeardown.set(session.teardownCount == 1 && recorder.announceCount == 1);
        });

        assertTrue(session.start(true), "Recording session did not start");
        session.stop();

        assertEquals(VideoExportSession.Result.SUCCESS, session.getLastResult());
        assertEquals(1, recorder.completeCount);
        assertEquals(1, recorder.announceCount);
        assertTrue(callbackSawTeardown.get(), "Success listener ran before teardown/notification completed");
    }

    private static void assertListenerFailureCannotWedgeSession()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);

        session.setFinishedListener((aborted) ->
        {
            throw new IllegalStateException("fake listener failure");
        });

        assertTrue(session.start(true), "Recording session did not start");
        session.stop();

        assertEquals(VideoExportSession.Result.SUCCESS, session.getLastResult());
        assertFalse(session.isExporting(), "Listener failure left the session active");

        TestSession next = new TestSession(recorder);

        assertTrue(next.start(true), "Listener failure left the recorder reservation wedged");
        next.cancel();
    }

    private static void assertListenerCanReenterStableSession()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicBoolean reentered = new AtomicBoolean();

        session.setFinishedListener((aborted) -> reentered.set(session.start(true)));

        assertTrue(session.start(true), "Initial recording session did not start");
        session.stop();

        assertTrue(reentered.get(), "Completion listener observed a finishing/leased session");
        assertTrue(session.isExporting(), "Reentrant listener start was lost");
        session.cancel();
    }

    private static void assertImmediateFailureListenerCanReenter()
    {
        FakeVideoRecorder recorder = new FakeVideoRecorder();
        TestSession session = new TestSession(recorder);
        AtomicInteger calls = new AtomicInteger();

        recorder.startSucceeds = false;
        session.setFinishedListener((aborted) ->
        {
            calls.incrementAndGet();
            recorder.startSucceeds = true;
            assertTrue(session.start(true), "Synchronous-failure listener saw a partial session state");
        });

        assertFalse(session.start(true), "Failed outer start unexpectedly succeeded");
        assertEquals(1, calls.get());
        assertTrue(session.isExporting(), "Reentrant start from synchronous failure was lost");
        session.cancel();
    }

    private static void assertQueuedOwnedCancellationDeliversOnce() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-queued-cancel-");
        OwnedTestSession session = new OwnedTestSession(root, true, false);
        AtomicInteger legacy = new AtomicInteger();
        AtomicInteger typed = new AtomicInteger();
        AtomicInteger persistent = new AtomicInteger();

        try
        {
            session.setFinishedListener(aborted ->
            {
                assertTrue(aborted, "queued cancellation was reported as success");
                legacy.incrementAndGet();
            });
            session.setFinishedResultListener(result ->
            {
                assertEquals(VideoExportResult.Kind.CANCELLED, result.kind());
                typed.incrementAndGet();
            });
            session.addFinishedResultListener(result -> persistent.incrementAndGet());

            assertTrue(session.startOwned(), "owned session did not start");
            session.stop();
            assertTrue(session.isExporting(), "stop did not enter postprocess");
            session.cancel();
            session.drainUntilTerminal();
            session.cancel();

            assertEquals(1, legacy.get());
            assertEquals(1, typed.get());
            assertEquals(1, persistent.get());
            assertEquals(VideoExportResult.Kind.CANCELLED, session.getLastExportResult().kind());
            assertEquals(1, session.teardownCount);
            assertFalse(session.isExporting(), "queued cancellation left the session active");
            assertFalse(Files.exists(session.artifacts.workDirectory()),
                "queued cancellation left the owned work directory behind");
        }
        finally
        {
            session.close();
            deleteTree(root);
        }
    }

    private static void assertRunningOwnedCancellationWaitsForWorker() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-running-cancel-");
        OwnedTestSession session = new OwnedTestSession(root, false, true);
        AtomicInteger callbacks = new AtomicInteger();

        try
        {
            session.addFinishedResultListener(result -> callbacks.incrementAndGet());
            assertTrue(session.startOwned(), "running owned session did not start");
            session.stop();
            assertTrue(session.claimed.await(5L, TimeUnit.SECONDS),
                "postprocess worker never claimed execution");

            session.cancel();
            session.drainCallbacks();
            assertEquals(0, callbacks.get());
            assertTrue(session.isExporting(), "running cancellation completed before worker exit");

            session.release.countDown();
            session.drainUntilTerminal();
            assertEquals(1, callbacks.get());
            assertEquals(VideoExportResult.Kind.CANCELLED, session.getLastExportResult().kind());
            assertEquals(1, session.teardownCount);
        }
        finally
        {
            session.release.countDown();
            session.close();
            deleteTree(root);
        }
    }

    private static void assertOwnedTerminalFailuresAreIsolated() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-export-terminal-isolation-");
        OwnedTestSession session = new OwnedTestSession(root, false, false);
        AtomicInteger legacy = new AtomicInteger();
        AtomicInteger typed = new AtomicInteger();
        AtomicInteger persistent = new AtomicInteger();

        try
        {
            session.throwFromTerminalHook = true;
            session.recorder.throwFromAnnouncement = true;
            session.setFinishedListener(aborted ->
            {
                assertFalse(aborted, "success was changed by an observer exception");
                legacy.incrementAndGet();
            });
            session.setFinishedResultListener(result ->
            {
                assertTrue(result.isSuccess(), "typed callback lost successful result");
                typed.incrementAndGet();
            });
            session.addFinishedResultListener(result ->
            {
                throw new IllegalStateException("persistent observer failure");
            });
            session.addFinishedResultListener(result -> persistent.incrementAndGet());

            assertTrue(session.startOwned(), "owned terminal isolation session did not start");
            session.stop();
            session.drainUntilTerminal();

            assertEquals(1, legacy.get());
            assertEquals(1, typed.get());
            assertEquals(1, persistent.get());
            assertEquals(1, session.terminalHookCount);
            assertEquals(1, session.recorder.announceCount);
            assertEquals(VideoExportResult.Kind.SUCCESS, session.getLastExportResult().kind());
        }
        finally
        {
            session.close();
            deleteTree(root);
        }
    }

    private static class TestSession extends VideoExportSession
    {
        private final FakeVideoRecorder recorder;

        private boolean prepareResult = true;
        private boolean ready = true;
        private boolean targetThrows;
        private boolean teardownThrows;
        private boolean aggregateTeardownFailures;
        private Runnable prepareAction;
        private Runnable teardownAction;
        private int teardownCount;
        private int cleanupStepCount;
        private boolean lastTeardownAborted;

        private TestSession(FakeVideoRecorder recorder)
        {
            this.recorder = recorder;
        }

        private boolean start(boolean immediatelyReady)
        {
            this.ready = immediatelyReady;

            return this.start(16, 16);
        }

        private boolean start(int width, int height)
        {
            return this.begin(1, width, height, 0L);
        }

        @Override
        protected VideoRecorder getRecorder()
        {
            return this.recorder;
        }

        @Override
        protected boolean prepare()
        {
            Runnable action = this.prepareAction;

            this.prepareAction = null;

            if (action != null)
            {
                action.run();
            }

            return this.prepareResult;
        }

        @Override
        protected void applyExportTarget()
        {
            if (this.targetThrows)
            {
                throw new IllegalStateException("fake target failure");
            }
        }

        @Override
        protected boolean isWarmupReady()
        {
            return this.ready;
        }

        @Override
        protected void onRecordingStarted()
        {}

        @Override
        protected boolean isFinished()
        {
            return false;
        }

        @Override
        protected void teardown(boolean aborted)
        {
            this.teardownCount += 1;
            this.lastTeardownAborted = aborted;

            Runnable action = this.teardownAction;

            this.teardownAction = null;

            if (action != null)
            {
                action.run();
            }

            if (this.aggregateTeardownFailures)
            {
                this.runCleanupSteps(
                    () ->
                    {
                        this.cleanupStepCount += 1;
                        throw new IllegalStateException("fake first cleanup failure");
                    },
                    () ->
                    {
                        this.cleanupStepCount += 1;
                        throw new IllegalArgumentException("fake second cleanup failure");
                    },
                    () -> this.cleanupStepCount += 1
                );
            }

            if (this.teardownThrows)
            {
                throw new IllegalStateException("fake teardown failure");
            }
        }
    }

    /** Owned-pipeline fixture with deterministic worker ownership controls. */
    private static final class OwnedTestSession extends VideoExportSession implements AutoCloseable
    {
        private final Path root;
        private final boolean queueWorker;
        private final boolean blockWorker;
        private final OwnedVideoRecorder recorder = new OwnedVideoRecorder();
        private final ConcurrentLinkedQueue<Runnable> clientCallbacks = new ConcurrentLinkedQueue<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch claimed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private VideoExportArtifacts artifacts;
        private int teardownCount;
        private int terminalHookCount;
        private boolean throwFromTerminalHook;
        private boolean ready = true;
        private boolean openEnd;
        private String sourceId = "owned";

        private OwnedTestSession(Path root, boolean queueWorker, boolean blockWorker)
        {
            this.root = root;
            this.queueWorker = queueWorker;
            this.blockWorker = blockWorker;
        }

        private boolean startOwned()
        {
            return this.startOwned(true, false, "owned");
        }

        private boolean startOwned(boolean ready, boolean openEnd, String sourceId)
        {
            this.ready = ready;
            this.openEnd = openEnd;
            this.sourceId = sourceId;

            return this.begin(1, 16, 16, 0L);
        }

        @Override
        protected VideoRecorder getRecorder()
        {
            return this.recorder;
        }

        @Override
        protected VideoExportRequest createExportRequest(int width, int height) throws Exception
        {
            this.artifacts = VideoExportArtifacts.allocate(this.root, "owned");
            return new VideoExportRequest(this.artifacts.sessionId(), 1L, 0D, this.openEnd ? 0D : 1D, this.openEnd,
                24D, VideoExportAudioProfile.SAMPLE_RATE, 0, ChannelLayout.MONO,
                false, false, this.artifacts, this.sourceId, 24D, 1, false,
                width, height, VideoExportAudioProfile.DEFAULT_VIDEO_ARGUMENTS,
                VideoExportAudioProfile.DEFAULT_MUX_ARGUMENTS, false);
        }

        @Override
        protected boolean prepare()
        {
            return true;
        }

        @Override
        protected void applyExportTarget()
        {}

        @Override
        protected boolean isWarmupReady()
        {
            return this.ready;
        }

        @Override
        protected void onRecordingStarted()
        {}

        @Override
        protected boolean isFinished()
        {
            return false;
        }

        @Override
        protected void teardown(boolean aborted)
        {
            this.teardownCount += 1;
        }

        @Override
        protected java.util.concurrent.Future<?> submitPostprocess(Runnable runnable)
        {
            if (this.queueWorker)
            {
                return new FutureTask<>(runnable, null);
            }

            return this.executor.submit(runnable);
        }

        @Override
        protected void onPostprocessExecutionClaimed() throws Exception
        {
            if (this.blockWorker)
            {
                this.claimed.countDown();
                if (!this.release.await(5L, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("test worker release timed out");
                }
            }
        }

        @Override
        protected void postToClient(Runnable runnable)
        {
            this.clientCallbacks.add(runnable);
        }

        @Override
        protected void onTerminalResult(VideoExportResult result)
        {
            this.terminalHookCount += 1;
            if (this.throwFromTerminalHook)
            {
                throw new IllegalStateException("test terminal hook failure");
            }
        }

        private void drainCallbacks()
        {
            Runnable callback;
            while ((callback = this.clientCallbacks.poll()) != null)
            {
                callback.run();
            }
        }

        private void drainUntilTerminal() throws Exception
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (this.getLastExportResult() == null)
            {
                this.drainCallbacks();
                if (System.nanoTime() >= deadline)
                {
                    throw new AssertionError("owned export did not deliver a terminal result");
                }
                Thread.sleep(1L);
            }
            this.drainCallbacks();
        }

        @Override
        public void close()
        {
            this.release.countDown();
            this.executor.shutdownNow();
        }
    }

    private static final class OwnedVideoRecorder extends VideoRecorder
    {
        private boolean recording;
        private boolean outputProducerStarted;
        private File outputFile;
        private int counter;
        private VideoExportProcess.Outcome outcome = VideoExportProcess.Outcome.IDLE;
        private Throwable failure;
        private int completeCount;
        private int cancelCount;
        private int announceCount;
        private boolean throwFromAnnouncement;

        @Override
        public boolean isRecording()
        {
            return this.recording;
        }

        @Override
        public boolean didStartOutputProducer()
        {
            return this.outputProducerStarted;
        }

        @Override
        public File getOutputFile()
        {
            return this.outputFile;
        }

        @Override
        public int getCounter()
        {
            return this.counter;
        }

        @Override
        public VideoExportProcess.Outcome getOutcome()
        {
            return this.outcome;
        }

        @Override
        public Throwable getFailure()
        {
            return this.failure;
        }

        @Override
        public boolean tryStartRecording(String movieName, File audioFile, File outputFile, File logFile,
                                         ChannelLayout layout, double frameRate, int motionBlurPasses,
                                         int heldFrames, boolean limitFrameRate, String arguments,
                                         boolean logEnabled, int textureId, int width, int height)
        {
            try
            {
                Files.writeString(outputFile.toPath(), "owned-video");
            }
            catch (Exception e)
            {
                this.failure = e;
                this.outcome = VideoExportProcess.Outcome.FAILED;
                return false;
            }

            this.outputFile = outputFile;
            this.outputProducerStarted = true;
            this.recording = true;
            this.counter = 1;
            this.failure = null;
            this.outcome = VideoExportProcess.Outcome.RUNNING;
            return true;
        }

        @Override
        public boolean checkRecordingHealth()
        {
            return this.recording && this.outcome == VideoExportProcess.Outcome.RUNNING;
        }

        @Override
        public VideoExportProcess.Outcome completeRecording()
        {
            this.completeCount += 1;
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.SUCCEEDED;
            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome cancelRecording()
        {
            this.cancelCount += 1;
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.CANCELLED;
            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome failRecording(Throwable cause)
        {
            this.recording = false;
            this.failure = cause;
            this.outcome = VideoExportProcess.Outcome.FAILED;
            return this.outcome;
        }

        @Override
        public boolean acceptPublishedOutput(File expectedRaw, File published)
        {
            if (expectedRaw == null || published == null || !published.isFile())
            {
                return false;
            }

            if (this.outputFile == null || !this.outputFile.equals(expectedRaw))
            {
                return false;
            }

            this.outputFile = published;
            return true;
        }

        @Override
        public void announceSuccessfulCompletion()
        {
            this.announceCount += 1;
            if (this.throwFromAnnouncement)
            {
                throw new IllegalStateException("test recorder announcement failure");
            }
        }
    }

    private static class FakeVideoRecorder extends VideoRecorder
    {
        private boolean recording;
        private boolean startSucceeds = true;
        private VideoExportProcess.Outcome outcome = VideoExportProcess.Outcome.IDLE;
        private Throwable failure;
        private int completeCount;
        private int cancelCount;
        private int announceCount;
        private int startCount;

        @Override
        public boolean isRecording()
        {
            return this.recording;
        }

        @Override
        public VideoExportProcess.Outcome getOutcome()
        {
            return this.outcome;
        }

        @Override
        public Throwable getFailure()
        {
            return this.failure;
        }

        @Override
        public boolean tryStartRecording(String movieName, File audioFile, int textureId, int width, int height)
        {
            this.startCount += 1;

            if (!this.startSucceeds)
            {
                this.outcome = VideoExportProcess.Outcome.FAILED;
                this.failure = new IllegalStateException("fake FFmpeg startup failure");

                return false;
            }

            this.recording = true;
            this.outcome = VideoExportProcess.Outcome.RUNNING;
            this.failure = null;

            return true;
        }

        @Override
        public boolean checkRecordingHealth()
        {
            return this.recording && this.outcome == VideoExportProcess.Outcome.RUNNING;
        }

        @Override
        public VideoExportProcess.Outcome completeRecording()
        {
            this.completeCount += 1;
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.SUCCEEDED;

            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome cancelRecording()
        {
            this.cancelCount += 1;
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.CANCELLED;

            return this.outcome;
        }

        @Override
        public VideoExportProcess.Outcome failRecording(Throwable cause)
        {
            this.recording = false;
            this.outcome = VideoExportProcess.Outcome.FAILED;
            this.failure = cause;

            return this.outcome;
        }

        @Override
        public void announceSuccessfulCompletion()
        {
            if (this.outcome == VideoExportProcess.Outcome.SUCCEEDED)
            {
                this.announceCount += 1;
            }
        }
    }

    private static void assertTrue(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message)
    {
        assertTrue(!value, message);
    }

    private static void deleteTree(Path root) throws Exception
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            paths.sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
                .forEach(path ->
                {
                    try
                    {
                        Files.deleteIfExists(path);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                });
        }
        catch (RuntimeException e)
        {
            if (e.getCause() instanceof Exception exception)
            {
                throw exception;
            }
            throw e;
        }
    }

    private static void assertEquals(Object expected, Object actual)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
