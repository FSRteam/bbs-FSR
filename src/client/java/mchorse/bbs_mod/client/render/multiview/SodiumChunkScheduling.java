package mchorse.bbs_mod.client.render.multiview;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/** Keeps version-specific Sodium task descriptors out of unconditional mixin shadows. */
public final class SodiumChunkScheduling
{
    private static final Map<Object, ModernFrame> FRAMES = new IdentityHashMap<>();
    private static final Map<Object, long[]> TIMES = new IdentityHashMap<>();
    private static final ClassValue<Scheduler> SCHEDULERS = new ClassValue<>()
    {
        @Override
        protected Scheduler computeValue(Class<?> type)
        {
            try
            {
                Method legacy = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getName().equals("submitSectionTasks") && method.getParameterCount() == 3
                        && method.getParameterTypes()[1].getName().endsWith(".ChunkUpdateType"))
                    .findFirst().orElse(null);

                return legacy == null ? new ModernScheduler(type) : new LegacyScheduler(legacy);
            }
            catch (ReflectiveOperationException failure)
            {
                throw propagate(failure);
            }
        }
    };

    private SodiumChunkScheduling()
    {}

    public static void clearFrame()
    {
        FRAMES.clear();
        TIMES.clear();
    }

    public static void framePrepared(Object manager)
    {
        try
        {
            SCHEDULERS.get(manager.getClass()).framePrepared(manager);
        }
        catch (ReflectiveOperationException failure)
        {
            throw propagate(failure);
        }
    }

    public static void updateChunks(Object manager, ChunkBuilder builder,
        Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results)
    {
        try
        {
            SCHEDULERS.get(manager.getClass()).update(manager, builder, results);
        }
        catch (ReflectiveOperationException failure)
        {
            throw propagate(failure);
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);

        return field;
    }

    private static RuntimeException propagate(ReflectiveOperationException failure)
    {
        Throwable cause = failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;

        if (cause instanceof Error error)
        {
            throw error;
        }

        return cause instanceof RuntimeException exception ? exception
            : new IllegalStateException("Unsupported Sodium terrain scheduling interface", cause);
    }

    private interface Scheduler
    {
        default void framePrepared(Object manager) throws ReflectiveOperationException
        {}

        void update(Object manager, ChunkBuilder builder,
            Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results) throws ReflectiveOperationException;
    }

    private static final class LegacyScheduler implements Scheduler
    {
        private final Method submit;

        private LegacyScheduler(Method submit)
        {
            this.submit = submit;
            this.submit.setAccessible(true);
        }

        @Override
        public void update(Object manager, ChunkBuilder builder,
            Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results) throws ReflectiveOperationException
        {
            ChunkJobCollector sorting = SodiumViewAdapter.sortingCollector(results);
            ChunkJobCollector meshing = SodiumViewAdapter.meshingCollector(manager,
                builder.getHighEffortSchedulingBudget(), builder.getLowEffortSchedulingBudget(), results);

            this.submit.invoke(manager, sorting, ChunkUpdateType.IMPORTANT_SORT, true);
            this.submit.invoke(manager, sorting, ChunkUpdateType.SORT, true);
            this.submit.invoke(manager, meshing, ChunkUpdateType.IMPORTANT_REBUILD, false);
            this.submit.invoke(manager, meshing, ChunkUpdateType.REBUILD, false);
            this.submit.invoke(manager, meshing, ChunkUpdateType.INITIAL_BUILD, false);
            sorting.awaitCompletion(builder);
        }
    }

    private static final class ModernScheduler implements Scheduler
    {
        private final Field taskLists;
        private final Field regions;
        private final Field[] timing;
        private final Field currentTasks;
        private final Field nextTasks;
        private final Field deferredTasks;
        private final Method submit;
        private final Method pending;
        private final Method isRebuild;
        private final Method isInitialBuild;
        private final Method remainingDuration;
        private final Method stagingBuffer;
        private final Method uploadSizeLimit;
        private final Method collectorBudget;
        private final Method submittedCount;
        private final Method uploadAvailable;
        private final Constructor<?> collector;
        private final Constructor<?> uploadBudget;
        private final Object unlimitedUpload;

        private ModernScheduler(Class<?> manager) throws ReflectiveOperationException
        {
            ClassLoader loader = manager.getClassLoader();
            Class<?> section = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false, loader);
            Class<?> updates = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes", false, loader);
            Class<?> budget = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget", false, loader);
            Class<?> limited = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.LimitedResourceBudget", false, loader);
            Class<?> unlimited = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UnlimitedResourceBudget", false, loader);

            this.taskLists = field(manager, "taskLists");
            this.regions = field(manager, "regions");
            this.timing = new Field[] {field(manager, "averageFrameDuration"), field(manager, "lastFrameDuration"),
                field(manager, "lastFrameAtTime")};
            this.currentTasks = field(manager, "thisFrameBlockingTasks");
            this.nextTasks = field(manager, "nextFrameBlockingTasks");
            this.deferredTasks = field(manager, "deferredTasks");
            this.submit = manager.getDeclaredMethod("submitSectionTask", ChunkJobCollector.class, section, int.class, budget, boolean.class);
            this.submit.setAccessible(true);
            this.pending = section.getMethod("getPendingUpdate");
            this.isRebuild = updates.getMethod("isRebuild", int.class);
            this.isInitialBuild = updates.getMethod("isInitialBuild", int.class);
            this.remainingDuration = ChunkBuilder.class.getMethod("getTotalRemainingDuration", long.class);
            this.stagingBuffer = this.regions.getType().getMethod("getStagingBuffer");
            this.uploadSizeLimit = this.stagingBuffer.getReturnType().getMethod("getUploadSizeLimit", long.class);
            this.collectorBudget = ChunkJobCollector.class.getMethod("hasBudgetRemaining");
            this.submittedCount = ChunkJobCollector.class.getMethod("getSubmittedTaskCount");
            this.uploadAvailable = budget.getMethod("isAvailable");
            this.collector = ChunkJobCollector.class.getConstructor(long.class, Consumer.class);
            this.uploadBudget = limited.getConstructor(long.class, long.class);
            this.unlimitedUpload = unlimited.getField("INSTANCE").get(null);
        }

        @Override
        public void framePrepared(Object manager) throws IllegalAccessException
        {
            long[] saved = TIMES.get(manager);

            if (saved == null)
            {
                saved = new long[this.timing.length];

                for (int i = 0; i < saved.length; i++)
                {
                    saved[i] = this.timing[i].getLong(manager);
                }

                TIMES.put(manager, saved);
            }
            else
            {
                /* Additional cameras change visibility, not the native frame-duration estimate. */
                for (int i = 0; i < saved.length; i++)
                {
                    this.timing[i].setLong(manager, saved[i]);
                }
            }
        }

        private ModernFrame frame(Object manager, ChunkBuilder builder,
            Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results) throws ReflectiveOperationException
        {
            ModernFrame frame = FRAMES.get(manager);

            if (frame == null)
            {
                long duration = this.timing[0].getLong(manager);
                long remaining = (Long) this.remainingDuration.invoke(builder, duration);
                Object staging = this.stagingBuffer.invoke(this.regions.get(manager));
                long sizeLimit = (Long) this.uploadSizeLimit.invoke(staging, duration);
                ChunkJobCollector meshing = (ChunkJobCollector) this.collector.newInstance(remaining, results);
                Object uploads = this.uploadBudget.newInstance(Math.max((long) (duration * 0.1F), 2_000_000L), sizeLimit);
                frame = new ModernFrame(meshing, uploads);
                FRAMES.put(manager, frame);
            }

            return frame;
        }

        @Override
        public void update(Object manager, ChunkBuilder builder,
            Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results) throws ReflectiveOperationException
        {
            ModernFrame frame = this.frame(manager, builder, results);
            ChunkJobCollector sorting = SodiumViewAdapter.sortingCollector(results);
            Map<?, ?> lists = (Map<?, ?>) this.taskLists.get(manager);

            for (Object queue : lists.values())
            {
                Iterator<?> sections = ((Deque<?>) queue).iterator();

                while (sections.hasNext())
                {
                    Object section = sections.next();
                    int type = (Integer) this.pending.invoke(section);

                    if (type == 0)
                    {
                        sections.remove();
                        continue;
                    }

                    boolean mesh = (Boolean) this.isRebuild.invoke(null, type) || (Boolean) this.isInitialBuild.invoke(null, type);

                    if (mesh && (!(Boolean) this.collectorBudget.invoke(frame.meshing)
                        || !(Boolean) this.uploadAvailable.invoke(frame.uploads)))
                    {
                        /* Leave deferred meshes pending, but continue to sort this camera's translucent sections. */
                        continue;
                    }

                    sections.remove();
                    this.submit.invoke(manager, mesh ? frame.meshing : sorting, section, type,
                        mesh ? frame.uploads : this.unlimitedUpload, !mesh);
                }
            }

            sorting.awaitCompletion(builder);
            this.currentTasks.setInt(manager, (Integer) this.submittedCount.invoke(sorting));
            this.nextTasks.setInt(manager, 0);
            this.deferredTasks.setInt(manager, (Integer) this.submittedCount.invoke(frame.meshing));
        }
    }

    private record ModernFrame(ChunkJobCollector meshing, Object uploads)
    {}
}
