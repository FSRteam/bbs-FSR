package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.client.render.multiview.SodiumChunkScheduling;
import mchorse.bbs_mod.utils.sodium.SodiumUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.Consumer;

/** Exercises the installed dependency's real settings and scheduling descriptors. */
public final class SodiumCompatibilityTest
{
    private SodiumCompatibilityTest()
    {}

    public static void main(String[] args) throws Exception
    {
        runAll();
    }

    public static void runAll() throws ReflectiveOperationException
    {
        ClassLoader loader = SodiumCompatibilityTest.class.getClassLoader();
        Class<?> owner = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod", false, loader);
        Class<?> performance = owner.getMethod("options").getReturnType().getField("performance").getType();
        Object options = performance.getConstructor().newInstance();
        Field faces = performance.getField("useBlockFaceCulling");
        Field fog = performance.getField("useFogOcclusion");

        for (boolean originalFaces : new boolean[] {false, true})
        {
            for (boolean originalFog : new boolean[] {false, true})
            {
                faces.setBoolean(options, originalFaces);
                fog.setBoolean(options, originalFog);
                SodiumUtils.CullingState saved = SodiumUtils.captureCameraCulling(options);

                saved.apply(true);
                check(!faces.getBoolean(options) && !fog.getBoolean(options), "orthographic view did not disable culling");
                SodiumUtils.CullingState nested = SodiumUtils.captureCameraCulling(options);
                nested.apply(false);
                check(!faces.getBoolean(options) && !fog.getBoolean(options), "nested view changed its parent's culling");
                saved.apply(false);
                check(faces.getBoolean(options) == originalFaces && fog.getBoolean(options) == originalFog,
                    "camera scope failed to restore the installed Sodium options");
            }
        }

        Field schedulers = SodiumChunkScheduling.class.getDeclaredField("SCHEDULERS");
        schedulers.setAccessible(true);
        ClassValue<?> cache = (ClassValue<?>) schedulers.get(null);
        Class<?> manager = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", false, loader);
        Object scheduler = cache.get(manager);
        boolean legacy = false;

        for (Method method : manager.getDeclaredMethods())
        {
            legacy |= method.getName().equals("submitSectionTasks") && method.getParameterCount() == 3
                && method.getParameterTypes()[1].getName().endsWith(".ChunkUpdateType");
        }

        check(scheduler.getClass().getSimpleName().equals(legacy ? "LegacyScheduler" : "ModernScheduler"),
            "the installed Sodium task interface selected the wrong scheduler");

        if (!legacy)
        {
            testModernQueueAndFrameTiming(manager, loader);
        }

        System.out.println("SodiumCompatibilityTest: " + performance.getName() + "; "
            + scheduler.getClass().getSimpleName() + "; all checks passed");
    }

    private static void testModernQueueAndFrameTiming(Class<?> managerType, ClassLoader loader) throws ReflectiveOperationException
    {
        Class<?> sectionType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false, loader);
        Class<?> updates = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes", false, loader);
        Class<?> collectorType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector", false, loader);
        Class<?> builderType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder", false, loader);
        Class<?> budgetType = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.LimitedResourceBudget", false, loader);
        Class<?> frameType = Class.forName(SodiumChunkScheduling.class.getName() + "$ModernFrame", false, loader);
        Constructor<?> frameConstructor = frameType.getDeclaredConstructor(collectorType, Object.class);
        Method updateChunks = SodiumChunkScheduling.class.getMethod("updateChunks", Object.class, builderType, Consumer.class);
        frameConstructor.setAccessible(true);
        Object manager = allocate(managerType);
        Object mesh = allocate(sectionType);
        Object sort = allocate(sectionType);
        Object stale = allocate(sectionType);
        Method pending = sectionType.getMethod("getPendingUpdate");
        Method setPending = sectionType.getMethod("setPendingUpdate", int.class, long.class);
        int rebuildType = updates.getField("REBUILD").getInt(null);
        int sortType = updates.getField("SORT").getInt(null);
        ArrayDeque<Object> queue = new ArrayDeque<>();
        Consumer<Object> results = result -> {};
        Object collector = collectorType.getConstructor(long.class, Consumer.class).newInstance(0L, results);
        Object budget = budgetType.getConstructor(long.class, long.class).newInstance(0L, 0L);
        Object frame = frameConstructor.newInstance(collector, budget);
        Map<?, ?> frames = (Map<?, ?>) field(SodiumChunkScheduling.class, "FRAMES").get(null);

        SodiumChunkScheduling.clearFrame();

        try
        {
            setPending.invoke(mesh, rebuildType, 0L);
            setPending.invoke(sort, sortType, 0L);
            queue.add(mesh);
            queue.add(stale);
            queue.add(sort);
            field(managerType, "taskLists").set(manager, Map.of("fixture", queue));
            Map.class.getMethod("put", Object.class, Object.class).invoke(frames, manager, frame);

            /* Native sections with no translucent geometry complete sorting without allocating a mesh or GL object. */
            updateChunks.invoke(null, manager, null, results);
            check(queue.size() == 1 && queue.peek() == mesh && (Integer) pending.invoke(mesh) == rebuildType,
                "exhausted mesh/upload budgets must leave rebuilding sections pending");
            check((Integer) pending.invoke(sort) == 0, "an earlier deferred rebuild starved the camera's native sorting path");
            setPending.invoke(sort, sortType, 0L);
            queue.add(sort);
            updateChunks.invoke(null, manager, null, results);
            check(frames.get(manager) == frame && queue.size() == 1 && (Integer) pending.invoke(sort) == 0,
                "a second camera must share the exhausted mesh budget while completing its own sort");

            Field average = field(managerType, "averageFrameDuration");
            Field duration = field(managerType, "lastFrameDuration");
            Field time = field(managerType, "lastFrameAtTime");
            average.setLong(manager, 20_000_000L);
            duration.setLong(manager, 18_000_000L);
            time.setLong(manager, 200_000_000L);
            SodiumChunkScheduling.framePrepared(manager);
            average.setLong(manager, 1L);
            duration.setLong(manager, 2L);
            time.setLong(manager, 3L);
            SodiumChunkScheduling.framePrepared(manager);
            check(average.getLong(manager) == 20_000_000L && duration.getLong(manager) == 18_000_000L
                && time.getLong(manager) == 200_000_000L, "additional views distorted Sodium's frame-duration estimate");
            SodiumChunkScheduling.clearFrame();
            average.setLong(manager, 10_000_000L);
            SodiumChunkScheduling.framePrepared(manager);
            check(frames.isEmpty() && average.getLong(manager) == 10_000_000L,
                "the next outer frame retained the previous frame's budget or timing");
        }
        finally
        {
            SodiumChunkScheduling.clearFrame();
        }
    }

    private static Object allocate(Class<?> type) throws ReflectiveOperationException
    {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field instance = field(unsafeType, "theUnsafe");

        return unsafeType.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException
    {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);

        return field;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
