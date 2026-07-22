package mchorse.bbs_mod.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-light checks for bounded FFmpeg mux teardown. */
public final class VideoMuxerTeardownContractTest
{
    private VideoMuxerTeardownContractTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
    }

    public static void runAll() throws Exception
    {
        assertPersistentProbeFailureCannotBlockTerminalCompletion();
    }

    private static void assertPersistentProbeFailureCannotBlockTerminalCompletion() throws Exception
    {
        PersistentProbeFailureProcess process = new PersistentProbeFailureProcess();
        IOException primaryFailure = new IOException("primary mux failure");
        List<Throwable> failures = new ArrayList<>();
        failures.add(primaryFailure);
        AtomicInteger terminalCompletions = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() ->
        {
            try
            {
                VideoMuxer.terminate(process, failures, Duration.ofMillis(250L));
                terminalCompletions.incrementAndGet();
            }
            catch (Throwable error)
            {
                workerFailure.set(error);
            }
        }, "bbs-mux-teardown-contract");
        worker.setDaemon(true);
        worker.start();
        worker.join(1_500L);

        check(!worker.isAlive(), "persistent FFmpeg liveness failures blocked the postprocess worker");
        if (workerFailure.get() != null)
        {
            throw new AssertionError("bounded mux teardown threw instead of reporting cleanup diagnostics",
                workerFailure.get());
        }
        check(terminalCompletions.get() == 1, "mux teardown did not release exactly one terminal completion");
        check(failures.get(0) == primaryFailure, "mux teardown replaced the first failure");
        check(process.destroyCalls == 1 && process.forceDestroyCalls == 1,
            "mux teardown retried destroy operations without a finite bound");
        check(process.livenessProbes >= 1 && process.livenessProbes <= 2,
            "mux teardown retried an exceptional liveness probe without a finite bound");
        check(failures.stream().anyMatch(VideoMuxerTeardownContractTest::isDeadlineDiagnostic),
            "mux teardown did not append an unconfirmed-termination deadline diagnostic");
    }

    private static boolean isDeadlineDiagnostic(Throwable failure)
    {
        return failure instanceof IOException && failure.getMessage() != null
            && failure.getMessage().contains("teardown deadline");
    }

    private static void check(boolean value, String message)
    {
        if (!value) throw new AssertionError(message);
    }

    private static final class PersistentProbeFailureProcess extends Process
    {
        private final OutputStream output = new ByteArrayOutputStream();
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final InputStream error = new ByteArrayInputStream(new byte[0]);
        private int livenessProbes;
        private int destroyCalls;
        private int forceDestroyCalls;

        @Override
        public OutputStream getOutputStream()
        {
            return this.output;
        }

        @Override
        public InputStream getInputStream()
        {
            return this.input;
        }

        @Override
        public InputStream getErrorStream()
        {
            return this.error;
        }

        @Override
        public int waitFor()
        {
            throw new IllegalThreadStateException("fake process never exits");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit)
        {
            return false;
        }

        @Override
        public int exitValue()
        {
            throw new IllegalThreadStateException("fake process is still alive");
        }

        @Override
        public void destroy()
        {
            this.destroyCalls += 1;
            throw new IllegalStateException("graceful destroy failed");
        }

        @Override
        public Process destroyForcibly()
        {
            this.forceDestroyCalls += 1;
            throw new IllegalStateException("forced destroy failed");
        }

        @Override
        public boolean isAlive()
        {
            this.livenessProbes += 1;
            throw new IllegalStateException("persistent liveness probe failure");
        }
    }
}
