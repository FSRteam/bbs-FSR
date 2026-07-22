package mchorse.bbs_mod.plugin.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Closeable single-threaded WatchService adapter. It only emits intents and
 * never invokes plugin or lifecycle code.
 */
public final class PluginDirectoryWatcher implements AutoCloseable
{
    public static final Config DEFAULT_CONFIG = new Config(
        Duration.ofMillis(750),
        Duration.ofMillis(250),
        Duration.ofMinutes(5)
    );

    private final Path root;
    private final Config config;
    private final Consumer<PluginWatchIntent> sink;
    private final Consumer<Throwable> errorSink;
    private final AtomicBoolean autoApply;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();
    private final ConcurrentLinkedQueue<ReconciliationRequest> reconciliations = new ConcurrentLinkedQueue<>();

    private volatile WatchService watchService;
    private volatile Thread thread;

    public PluginDirectoryWatcher(
        Path root,
        boolean autoApply,
        Consumer<PluginWatchIntent> sink,
        Consumer<Throwable> errorSink
    )
    {
        this(root, autoApply, DEFAULT_CONFIG, sink, errorSink);
    }

    public PluginDirectoryWatcher(
        Path root,
        boolean autoApply,
        Config config,
        Consumer<PluginWatchIntent> sink,
        Consumer<Throwable> errorSink
    )
    {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.autoApply = new AtomicBoolean(autoApply);
        this.config = Objects.requireNonNull(config, "config");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
    }

    public void start() throws IOException
    {
        synchronized (this.lifecycleMonitor)
        {
            if (this.closed.get())
            {
                throw new IllegalStateException("Watcher is already closed");
            }

            if (!this.started.compareAndSet(false, true))
            {
                throw new IllegalStateException("Watcher is already started");
            }

            try
            {
                Files.createDirectories(this.root);
                this.watchService = FileSystems.getDefault().newWatchService();
                this.registerRoot(this.watchService);

                Thread thread = new Thread(this::run, "BBS plugin directory watcher");

                thread.setDaemon(true);
                this.thread = thread;
                thread.start();
            }
            catch (IOException | RuntimeException e)
            {
                this.closeWatchService();
                this.started.set(false);

                throw e;
            }
        }
    }

    public Path root()
    {
        return this.root;
    }

    public boolean autoApply()
    {
        return this.autoApply.get();
    }

    public void setAutoApply(boolean autoApply)
    {
        this.autoApply.set(autoApply);
    }

    public void requestRescan(boolean apply)
    {
        if (this.closed.get())
        {
            return;
        }

        this.reconciliations.add(new ReconciliationRequest(PluginWatchIntent.Trigger.MANUAL_RESCAN, apply));

        Thread thread = this.thread;

        if (thread != null)
        {
            thread.interrupt();
        }
    }

    @Override
    public void close()
    {
        Thread thread;

        synchronized (this.lifecycleMonitor)
        {
            if (!this.closed.compareAndSet(false, true))
            {
                return;
            }

            this.closeWatchService();
            thread = this.thread;
        }

        if (thread != null && thread != Thread.currentThread())
        {
            thread.interrupt();

            try
            {
                thread.join(5_000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run()
    {
        PluginWatchIntentCollector collector = new PluginWatchIntentCollector(
            this.root,
            this.config.debounce(),
            this.config.stabilityInterval(),
            this.autoApply::get,
            this::emit,
            this::reportError
        );
        long periodicNanos = this.config.periodicReconciliation().toNanos();
        long nextPeriodic = add(System.nanoTime(), periodicNanos);
        boolean startupPending = true;

        while (!this.closed.get())
        {
            long now = System.nanoTime();

            try
            {
                if (startupPending)
                {
                    collector.requestReconciliation(PluginWatchIntent.Trigger.STARTUP, false);
                    startupPending = false;
                }

                this.drainReconciliations(collector);
                collector.poll(now);

                if (now - nextPeriodic >= 0)
                {
                    collector.requestReconciliation(PluginWatchIntent.Trigger.PERIODIC_RECONCILE, false);
                    nextPeriodic = add(now, periodicNanos);
                }
            }
            catch (Throwable t)
            {
                this.reportError(t);
                LockSupport.parkNanos(Math.min(
                    this.config.stabilityInterval().toNanos(),
                    TimeUnit.MILLISECONDS.toNanos(100L)
                ));

                continue;
            }

            try
            {
                WatchKey key = this.poll(this.watchService, collector.nextDeadlineNanos(), nextPeriodic, now);

                if (key != null)
                {
                    this.processKey(key, collector);
                }
            }
            catch (InterruptedException e)
            {
                if (this.closed.get())
                {
                    return;
                }
            }
            catch (ClosedWatchServiceException e)
            {
                return;
            }
            catch (Throwable t)
            {
                this.reportError(t);
                this.reconciliations.add(new ReconciliationRequest(
                    PluginWatchIntent.Trigger.WATCHER_FAILURE,
                    false
                ));
            }
        }
    }

    private WatchKey poll(
        WatchService service,
        OptionalLong collectorDeadline,
        long periodicDeadline,
        long nowNanos
    ) throws InterruptedException
    {
        long deadline = collectorDeadline.isPresent()
            ? Math.min(collectorDeadline.getAsLong(), periodicDeadline)
            : periodicDeadline;
        long waitNanos = Math.max(0L, deadline - nowNanos);
        long maximumWait = TimeUnit.MILLISECONDS.toNanos(250L);

        return service.poll(Math.min(waitNanos, maximumWait), TimeUnit.NANOSECONDS);
    }

    private void processKey(WatchKey key, PluginWatchIntentCollector collector)
    {
        long now = System.nanoTime();

        for (WatchEvent<?> event : key.pollEvents())
        {
            WatchEvent.Kind<?> kind = event.kind();

            if (kind == StandardWatchEventKinds.OVERFLOW)
            {
                collector.requestReconciliation(PluginWatchIntent.Trigger.OVERFLOW, false);

                continue;
            }

            Object context = event.context();

            if (!(context instanceof Path relative))
            {
                collector.requestReconciliation(PluginWatchIntent.Trigger.WATCHER_FAILURE, false);

                continue;
            }

            PluginWatchIntentCollector.FileEvent fileEvent;

            if (kind == StandardWatchEventKinds.ENTRY_CREATE)
            {
                fileEvent = PluginWatchIntentCollector.FileEvent.CREATE;
            }
            else if (kind == StandardWatchEventKinds.ENTRY_MODIFY)
            {
                fileEvent = PluginWatchIntentCollector.FileEvent.MODIFY;
            }
            else if (kind == StandardWatchEventKinds.ENTRY_DELETE)
            {
                fileEvent = PluginWatchIntentCollector.FileEvent.DELETE;
            }
            else
            {
                continue;
            }

            collector.onFileEvent(this.root.resolve(relative), fileEvent, now);
        }

        if (!key.reset())
        {
            collector.requestReconciliation(PluginWatchIntent.Trigger.INVALID_WATCH_KEY, false);

            if (!this.closed.get())
            {
                try
                {
                    Files.createDirectories(this.root);
                    this.registerRoot(this.watchService);
                }
                catch (IOException e)
                {
                    this.reportError(e);
                }
            }
        }
    }

    private void drainReconciliations(PluginWatchIntentCollector collector)
    {
        ReconciliationRequest request;

        while ((request = this.reconciliations.peek()) != null)
        {
            collector.requestReconciliation(request.trigger, request.forceApply);
            this.reconciliations.poll();
        }
    }

    private void registerRoot(WatchService service) throws IOException
    {
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(this.root))
        {
            throw new IOException("Plugin directory must be a non-symlink directory: " + this.root);
        }

        this.root.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        );
    }

    private void emit(PluginWatchIntent intent)
    {
        this.sink.accept(intent);
    }

    private void reportError(Throwable throwable)
    {
        try
        {
            this.errorSink.accept(throwable);
        }
        catch (Throwable ignored)
        {}
    }

    private void closeWatchService()
    {
        WatchService service = this.watchService;

        if (service != null)
        {
            try
            {
                service.close();
            }
            catch (IOException e)
            {
                this.reportError(e);
            }
        }
    }

    private static long add(long value, long increment)
    {
        long result = value + increment;

        if (((value ^ result) & (increment ^ result)) < 0)
        {
            return increment > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }

        return result;
    }

    public record Config(Duration debounce, Duration stabilityInterval, Duration periodicReconciliation)
    {
        public Config
        {
            requirePositive(debounce, "debounce");
            requirePositive(stabilityInterval, "stabilityInterval");
            requirePositive(periodicReconciliation, "periodicReconciliation");
        }

        private static void requirePositive(Duration duration, String name)
        {
            Objects.requireNonNull(duration, name);

            if (duration.isZero() || duration.isNegative())
            {
                throw new IllegalArgumentException(name + " must be positive");
            }

            try
            {
                duration.toNanos();
            }
            catch (ArithmeticException e)
            {
                throw new IllegalArgumentException(name + " is too large", e);
            }
        }
    }

    private record ReconciliationRequest(PluginWatchIntent.Trigger trigger, boolean forceApply)
    {}
}
