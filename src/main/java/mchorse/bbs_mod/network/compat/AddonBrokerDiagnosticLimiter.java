package mchorse.bbs_mod.network.compat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded fixed-window admission for hostile broker diagnostics. Both the
 * connection and shared budgets must admit an event before it reaches SLF4J.
 */
final class AddonBrokerDiagnosticLimiter
{
    private final int perConnectionBurst;
    private final int sharedBurst;
    private final int maxConnections;
    private final long windowNanos;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Window> connections = new LinkedHashMap<>(16, 0.75F, true);
    private final Window shared = new Window();

    AddonBrokerDiagnosticLimiter(
        int perConnectionBurst,
        int sharedBurst,
        int maxConnections,
        long windowNanos,
        LongSupplier clock
    )
    {
        if (perConnectionBurst <= 0 || sharedBurst <= 0 || maxConnections <= 0 || windowNanos <= 0L)
        {
            throw new IllegalArgumentException("diagnostic limiter bounds must be positive");
        }

        this.perConnectionBurst = perConnectionBurst;
        this.sharedBurst = sharedBurst;
        this.maxConnections = maxConnections;
        this.windowNanos = windowNanos;
        this.clock = clock == null ? System::nanoTime : clock;
    }

    synchronized Decision acquire(String connectionKey)
    {
        long now = this.clock.getAsLong();
        String key = normalizeConnectionKey(connectionKey);
        Window connection = this.connection(key);

        connection.rotate(now, this.windowNanos);
        this.shared.rotate(now, this.windowNanos);

        if (connection.emitted >= this.perConnectionBurst || this.shared.emitted >= this.sharedBurst)
        {
            connection.suppressed += 1L;
            this.shared.suppressed += 1L;

            return Decision.SUPPRESSED;
        }

        connection.emitted += 1;
        this.shared.emitted += 1;

        long connectionSuppressed = connection.takePendingSuppressed();
        long sharedSuppressed = this.shared.takePendingSuppressed();

        return new Decision(true, connectionSuppressed, sharedSuppressed);
    }

    synchronized int trackedConnections()
    {
        return this.connections.size();
    }

    synchronized int clearConnection(String connectionKey)
    {
        return this.connections.remove(normalizeConnectionKey(connectionKey)) == null ? 0 : 1;
    }

    synchronized void reset()
    {
        this.connections.clear();
        this.shared.reset();
    }

    private Window connection(String key)
    {
        Window window = this.connections.get(key);

        if (window != null)
        {
            return window;
        }

        if (this.connections.size() >= this.maxConnections)
        {
            Iterator<Map.Entry<String, Window>> iterator = this.connections.entrySet().iterator();

            if (iterator.hasNext())
            {
                iterator.next();
                iterator.remove();
            }
        }

        window = new Window();
        this.connections.put(key, window);

        return window;
    }

    private static String normalizeConnectionKey(String connectionKey)
    {
        return connectionKey == null || connectionKey.isBlank() ? "<unknown>" : connectionKey;
    }

    record Decision(boolean allowed, long connectionSuppressed, long sharedSuppressed)
    {
        private static final Decision SUPPRESSED = new Decision(false, 0L, 0L);
    }

    private static final class Window
    {
        private boolean initialized;
        private long startedAt;
        private int emitted;
        private long suppressed;
        private long pendingSuppressed;

        private void rotate(long now, long windowNanos)
        {
            if (!this.initialized)
            {
                this.initialized = true;
                this.startedAt = now;

                return;
            }

            if (now - this.startedAt < windowNanos)
            {
                return;
            }

            this.pendingSuppressed += this.suppressed;
            this.startedAt = now;
            this.emitted = 0;
            this.suppressed = 0L;
        }

        private long takePendingSuppressed()
        {
            long suppressed = this.pendingSuppressed;

            this.pendingSuppressed = 0L;

            return suppressed;
        }

        private void reset()
        {
            this.initialized = false;
            this.startedAt = 0L;
            this.emitted = 0;
            this.suppressed = 0L;
            this.pendingSuppressed = 0L;
        }
    }
}
