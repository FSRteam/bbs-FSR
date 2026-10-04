package mchorse.bbs_mod.ui.film;

import com.google.common.collect.ImmutableList;
import mchorse.bbs_mod.client.render.view.IrisViewBackend;
import mchorse.bbs_mod.mixin.client.iris.IrisRenderingPipelineViewMixin;
import mchorse.bbs_mod.utils.iris.IrisPipelineResources;
import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.utils.iris.ViewSampleClock;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Exercises production invalidation decisions without constructing shaders or allocating GPU resources. */
public final class IrisViewHistoryResetTest
{
    private IrisViewHistoryResetTest()
    {}

    public static void runAll() throws IOException
    {
        try
        {
            testRepeatedSeeksRetainPipelines();
            testResourceChangesStillRetirePipelines();
            testFailedPassStillRetiresResources();
            testHistoryClearIsLocalAndConsumedOnce();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IOException("Could not verify temporal history reuse", exception);
        }

        System.out.println("IrisViewHistoryResetTest: repeated seeks retain pipelines; all checks passed");
    }

    private static void testRepeatedSeeksRetainPipelines() throws ReflectiveOperationException
    {
        for (boolean primary : new boolean[] {false, true})
        {
            for (boolean shaders : new boolean[] {false, true})
            {
                Fixture view = new Fixture(primary, shaders);
                Fixture other = new Fixture(false, shaders);
                ViewSampleClock otherClock = other.clock();

                for (int i = 0; i < 100; i++)
                {
                    view.prepare(i + 2L, shaders, 320, 180);
                    check(view.get("pipeline") == view.pipeline, "seeking replaced a compiled view pipeline");
                    check(view.destroyed == 0 && view.released == 0, "seeking destroyed view resources");
                    check(!((Boolean) view.get("resetPrimary")), "seeking scheduled a native pipeline replacement");

                    ViewSampleClock clock = view.clock();
                    ViewSampleClock.Sample sample = clock.begin(i + 1L, 0F);

                    check(sample.frameCounter() == 0, "a new timeline sample must not reuse old temporal accumulation");
                    clock.publish(sample);
                    view.prepare(i + 2L, shaders, 320, 180);
                    check(view.clock() == clock, "ordinary playback reset the sample clock");
                }

                check(view.resets == (shaders ? 100 : 0), "history reset was lost or repeated during playback");
                check(other.resets == 0 && other.clock() == otherClock && other.get("pipeline") == other.pipeline,
                    "one view's reset changed another view's history or pipeline");
            }
        }
    }

    private static void testResourceChangesStillRetirePipelines() throws ReflectiveOperationException
    {
        Fixture resized = new Fixture(false, true);

        resized.prepare(2L, true, 640, 360);
        check(resized.get("pipeline") == null && resized.destroyed == 1 && resized.released == 1,
            "a real target size change must still retire incompatible owned resources");

        Fixture modeChanged = new Fixture(false, true);

        modeChanged.prepare(2L, false, 320, 180);
        check(modeChanged.get("pipeline") == null && modeChanged.destroyed == 1 && modeChanged.resets == 0,
            "a shader-mode change was mistaken for a temporal-only reset");

        Fixture primary = new Fixture(true, true);

        primary.prepare(2L, true, 640, 360);
        check(primary.get("pipeline") == null && (Boolean) primary.get("resetPrimary")
                && primary.destroyed == 0 && primary.released == 0,
            "native primary replacement must retain the old pipeline until construction succeeds");
    }

    private static void testFailedPassStillRetiresResources() throws ReflectiveOperationException
    {
        Fixture view = new Fixture(false, true);
        IrisViewState.Scope abandoned = IrisViewState.enter("abandoned-test", true, 1L);

        abandoned.close();
        view.set("lastScope", abandoned);
        view.prepare(1L, true, 320, 180);
        check(view.get("pipeline") == null && view.destroyed == 1 && view.released == 1,
            "a failed or unpublished pass reused potentially corrupted resources");
    }

    private static void testHistoryClearIsLocalAndConsumedOnce() throws ReflectiveOperationException
    {
        IrisRenderingPipelineViewMixin view = new IrisRenderingPipelineViewMixin() {};
        IrisRenderingPipelineViewMixin other = new IrisRenderingPipelineViewMixin() {};
        Method colors = IrisRenderingPipelineViewMixin.class.getDeclaredMethod("bbs$clearViewHistory", boolean.class);
        Method shadows = IrisRenderingPipelineViewMixin.class.getDeclaredMethod("bbs$clearShadowHistory", ImmutableList.class);
        Method finished = IrisRenderingPipelineViewMixin.class.getDeclaredMethod("bbs$finishHistoryReset", CallbackInfo.class);
        Field shadowPasses = IrisRenderingPipelineViewMixin.class.getDeclaredField("shadowClearPassesFull");
        ImmutableList<String> ordinary = ImmutableList.of("ordinary clear");
        ImmutableList<String> full = ImmutableList.of("existing full clear");

        colors.setAccessible(true);
        shadows.setAccessible(true);
        finished.setAccessible(true);
        shadowPasses.setAccessible(true);
        shadowPasses.set(view, full);

        check(!(Boolean) colors.invoke(view, false), "normal rendering must retain Iris' ordinary clear policy");
        view.bbs$resetTemporalHistory();
        check((Boolean) colors.invoke(view, false), "a seek must clear old color history");
        check(shadows.invoke(view, ordinary) == full, "a seek must reuse the existing full shadow clear passes");
        check(!(Boolean) colors.invoke(other, false), "a history clear leaked into another pipeline");
        finished.invoke(view, new CallbackInfo("beginLevelRendering", false));
        check(!(Boolean) colors.invoke(view, false) && shadows.invoke(view, ordinary) == ordinary,
            "a completed reset must not clear temporal buffers again on the next frame");
        check((Boolean) colors.invoke(view, true), "native Iris full-clear requests must remain effective");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture
    {
        private final Object record;
        private final Object pipeline;
        private final Method prepare;
        private final boolean primary;
        private int resets;
        private int released;
        private int destroyed;

        private Fixture(boolean primary, boolean shaders) throws ReflectiveOperationException
        {
            this.primary = primary;
            Class<?> recordType = Class.forName(IrisViewBackend.class.getName() + "$ViewRecord");
            Class<?> pipelineType = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
            Class<?> packType = Class.forName("net.irisshaders.iris.shaderpack.ShaderPack");
            Class<?> dimensionType = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
            Constructor<?> constructor = recordType.getDeclaredConstructor();

            constructor.setAccessible(true);
            this.record = constructor.newInstance();
            this.prepare = IrisViewBackend.class.getDeclaredMethod("prepareRecord", recordType,
                boolean.class, long.class, boolean.class, packType, dimensionType, int.class, int.class);
            this.prepare.setAccessible(true);
            Class<?>[] interfaces = shaders ? new Class<?>[] {pipelineType, IrisPipelineResources.class}
                : new Class<?>[] {pipelineType};
            this.pipeline = Proxy.newProxyInstance(IrisViewHistoryResetTest.class.getClassLoader(), interfaces,
                (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "bbs$resetTemporalHistory" -> this.resets++;
                        case "bbs$releaseResources" -> this.released++;
                        case "destroy" -> this.destroyed++;
                    }

                    return null;
                });

            this.set("initialized", true);
            this.set("primary", primary);
            this.set("nativePrimary", primary && shaders);
            this.set("shadersEnabled", shaders);
            this.set("historyEpoch", 1L);
            this.set("width", 320);
            this.set("height", 180);
            this.set("pipeline", this.pipeline);
        }

        private void prepare(long epoch, boolean shaders, int width, int height) throws ReflectiveOperationException
        {
            this.prepare.invoke(null, this.record, shaders, epoch, this.primary, null, null, width, height);
        }

        private ViewSampleClock clock() throws ReflectiveOperationException
        {
            return (ViewSampleClock) this.get("clock");
        }

        private Object get(String name) throws ReflectiveOperationException
        {
            Field field = this.record.getClass().getDeclaredField(name);

            field.setAccessible(true);
            return field.get(this.record);
        }

        private void set(String name, Object value) throws ReflectiveOperationException
        {
            Field field = this.record.getClass().getDeclaredField(name);

            field.setAccessible(true);
            field.set(this.record, value);
        }
    }
}
