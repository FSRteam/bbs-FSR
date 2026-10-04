package mchorse.bbs_mod.client.render.multiview;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Delayed GPU timestamps and independent CPU submission costs for a view. */
public final class ViewGpuTiming implements AutoCloseable
{
    private static final int QUERY_SLOTS = 8;
    private static final int MAXIMUM_SPECS = 64;
    private static final Spec DEFAULT_SPEC = new Spec(1, 1, false);

    private final LongSupplier clock;
    private final Queries queries;
    private final Slot[] slots = new Slot[QUERY_SLOTS];
    private final Map<Spec, Cost> costs = new LinkedHashMap<>(16, 0.75F, true);
    private final List<Sample> pendingGpuCosts = new ArrayList<>();
    private Slot activeSlot;
    private Spec activeSpec;
    private Sample activeSample;
    private Sample lastSample;
    private long startedNanos;
    private long lastCpuNanos;
    private long lastGpuNanos = -1L;
    private long lastPreparationCpuNanos;
    private long lastPreparationGpuNanos = -1L;

    public ViewGpuTiming()
    {
        this(System::nanoTime, new OpenGlQueries());
    }

    public ViewGpuTiming(LongSupplier clock, Queries queries)
    {
        this.clock = clock;
        this.queries = queries;
    }

    public void begin()
    {
        this.begin(DEFAULT_SPEC);
    }

    public void begin(Spec spec)
    {
        if (this.activeSpec != null)
        {
            throw new IllegalStateException("A view timing sample is already active");
        }

        this.poll();
        this.activeSpec = spec;
        this.activeSample = new Sample(spec);
        this.startedNanos = this.clock.getAsLong();

        if (!this.queries.supported())
        {
            return;
        }

        for (int i = 0; i < this.slots.length; i++)
        {
            Slot slot = this.slots[i];

            if (slot == null)
            {
                int start = this.queries.create();

                try
                {
                    slot = new Slot(start, this.queries.create());
                }
                catch (RuntimeException | Error failure)
                {
                    this.queries.delete(start);
                    throw failure;
                }

                this.slots[i] = slot;
            }

            if (!slot.pending)
            {
                slot.sample = this.activeSample;
                this.queries.timestamp(slot.start);
                this.activeSlot = slot;
                this.activeSample.gpuPending = true;
                break;
            }
        }
    }

    public long end()
    {
        if (this.activeSpec == null)
        {
            return 0L;
        }

        this.lastCpuNanos = Math.max(0L, this.clock.getAsLong() - this.startedNanos);
        this.activeSample.cpuNanos = this.lastCpuNanos;
        this.lastSample = this.activeSample;

        if (this.activeSample.preparation)
        {
            this.lastPreparationCpuNanos = this.lastCpuNanos;
        }
        else
        {
            this.cost(this.activeSpec).cpu.add(this.activeSample.cpuWithoutExcluded());
        }

        try
        {
            if (this.activeSlot != null)
            {
                this.queries.timestamp(this.activeSlot.end);
                this.activeSlot.pending = true;
            }
        }
        finally
        {
            if (this.activeSlot != null && !this.activeSlot.pending)
            {
                this.activeSample.gpuPending = false;
            }

            this.activeSlot = null;
            this.activeSpec = null;
            this.activeSample = null;
        }

        return this.lastCpuNanos;
    }

    /** Keep allocation/compilation latency observable without training steady-state cost on it. */
    public void markPreparation()
    {
        if (this.activeSample != null)
        {
            this.activeSample.preparation = true;
        }
    }

    /** Remove a completed auxiliary pass from its enclosing frame's CPU and GPU baseline. */
    public void exclude(Sample sample)
    {
        if (this.activeSample != null && sample != null && sample != this.activeSample)
        {
            this.activeSample.excluded.add(sample);
        }
    }

    public Sample getLastSample()
    {
        return this.lastSample;
    }

    public boolean wasLastSamplePreparation()
    {
        return this.lastSample != null && this.lastSample.preparation;
    }

    public long getLastPreparationCpuNanos()
    {
        return this.lastPreparationCpuNanos;
    }

    public long getLastPreparationGpuNanos()
    {
        return this.lastPreparationGpuNanos;
    }

    /** Read a result only when both timestamps are ready; never wait for the GPU. */
    public void poll()
    {
        for (Slot slot : this.slots)
        {
            if (slot != null && slot.pending
                && this.queries.ready(slot.end) && this.queries.ready(slot.start))
            {
                long elapsed = Math.max(0L, this.queries.result(slot.end) - this.queries.result(slot.start));
                Sample sample = slot.sample;
                sample.gpuNanos = elapsed;
                sample.gpuPending = false;

                if (sample.preparation)
                {
                    this.lastPreparationGpuNanos = elapsed;
                }
                else
                {
                    this.pendingGpuCosts.add(sample);
                }

                this.lastGpuNanos = elapsed;
                slot.pending = false;
            }
        }

        Iterator<Sample> iterator = this.pendingGpuCosts.iterator();

        while (iterator.hasNext())
        {
            Sample sample = iterator.next();

            if (sample.hasPendingExcludedGpu())
            {
                continue;
            }

            long cost = sample.gpuWithoutExcluded();

            if (cost >= 0L)
            {
                this.cost(sample.spec).gpu.add(cost);
            }

            iterator.remove();
        }

        /* A lost child measurement cannot grow an unbounded chain of frame snapshots. */
        while (this.pendingGpuCosts.size() > QUERY_SLOTS)
        {
            this.pendingGpuCosts.removeFirst();
        }
    }

    public long getLastNanos()
    {
        return this.lastCpuNanos;
    }

    public long getLastGpuNanos()
    {
        return this.lastGpuNanos;
    }

    public boolean hasSample(Spec spec)
    {
        Cost cost = this.costs.get(spec);
        return cost != null && cost.cpu.count > 0;
    }

    public boolean hasGpuSample(Spec spec)
    {
        Cost cost = this.costs.get(spec);
        return cost != null && cost.gpu.count > 0;
    }

    public long getEstimatedNanos(Spec spec)
    {
        Cost cost = this.costs.get(spec);

        /* CPU submission and GPU execution overlap. Summing them would claim a
         * frame duration that has not been measured. */
        return cost == null ? 0L : Math.max(cost.cpu.conservative(), cost.gpu.conservative());
    }

    public long getEstimatedNanos()
    {
        return this.getEstimatedNanos(DEFAULT_SPEC);
    }

    public void publishGpuNanos(long gpuNanos)
    {
        this.lastGpuNanos = Math.max(0L, gpuNanos);
        this.cost(DEFAULT_SPEC).gpu.add(this.lastGpuNanos);
    }

    private Cost cost(Spec spec)
    {
        Cost cost = this.costs.get(spec);

        if (cost == null)
        {
            if (this.costs.size() >= MAXIMUM_SPECS)
            {
                this.costs.remove(this.costs.keySet().iterator().next());
            }

            cost = new Cost();
            this.costs.put(spec, cost);
        }

        return cost;
    }

    @Override
    public void close()
    {
        this.activeSpec = null;
        this.activeSlot = null;
        this.activeSample = null;
        this.lastSample = null;

        try (RenderStateRestorer releases = new RenderStateRestorer())
        {
            for (int i = 0; i < this.slots.length; i++)
            {
                Slot slot = this.slots[i];
                this.slots[i] = null;

                if (slot != null)
                {
                    if (slot.sample != null)
                    {
                        slot.sample.gpuPending = false;
                    }

                    releases.add(() -> this.queries.delete(slot.start));
                    releases.add(() -> this.queries.delete(slot.end));
                }
            }
        }
        finally
        {
            this.costs.clear();
            this.pendingGpuCosts.clear();
            this.lastCpuNanos = 0L;
            this.lastGpuNanos = -1L;
            this.lastPreparationCpuNanos = 0L;
            this.lastPreparationGpuNanos = -1L;
        }
    }

    public record Spec(int width, int height, boolean shaders)
    {}

    public static final class Sample
    {
        private final Spec spec;
        private final List<Sample> excluded = new ArrayList<>();
        private long cpuNanos;
        private long gpuNanos = -1L;
        private boolean gpuPending;
        private boolean preparation;

        private Sample(Spec spec)
        {
            this.spec = spec;
        }

        private long cpuWithoutExcluded()
        {
            long cost = this.cpuNanos;

            for (Sample sample : this.excluded)
            {
                cost = Math.max(0L, cost - sample.cpuNanos);
            }

            return cost;
        }

        private boolean hasPendingExcludedGpu()
        {
            for (Sample sample : this.excluded)
            {
                if (sample.gpuPending)
                {
                    return true;
                }
            }

            return false;
        }

        private long gpuWithoutExcluded()
        {
            long cost = this.gpuNanos;

            for (Sample sample : this.excluded)
            {
                if (sample.gpuNanos < 0L)
                {
                    return -1L;
                }

                cost = Math.max(0L, cost - sample.gpuNanos);
            }

            return cost;
        }
    }

    public interface Queries
    {
        boolean supported();
        int create();
        void timestamp(int query);
        boolean ready(int query);
        long result(int query);
        void delete(int query);
    }

    private static final class OpenGlQueries implements Queries
    {
        @Override
        public boolean supported()
        {
            return GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        }

        @Override
        public int create()
        {
            return GL15.glGenQueries();
        }

        @Override
        public void timestamp(int query)
        {
            GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
        }

        @Override
        public boolean ready(int query)
        {
            return GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
        }

        @Override
        public long result(int query)
        {
            return GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
        }

        @Override
        public void delete(int query)
        {
            GL15.glDeleteQueries(query);
        }
    }

    private static final class Slot
    {
        private final int start;
        private final int end;
        private Sample sample;
        private boolean pending;

        private Slot(int start, int end)
        {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Cost
    {
        private final Window cpu = new Window();
        private final Window gpu = new Window();
    }

    private static final class Window
    {
        private final long[] values = new long[24];
        private int count;
        private int cursor;
        private double average;
        private long conservative;

        private void add(long value)
        {
            this.average = this.count == 0 ? value : this.average * 0.8D + value * 0.2D;
            this.values[this.cursor] = value;
            this.cursor = (this.cursor + 1) % this.values.length;
            this.count = Math.min(this.count + 1, this.values.length);
            long[] sorted = Arrays.copyOf(this.values, this.count);
            Arrays.sort(sorted);
            this.conservative = Math.max(Math.round(this.average), sorted[(int) Math.ceil(this.count * 0.9D) - 1]);
        }

        private long conservative()
        {
            return this.conservative;
        }
    }
}
