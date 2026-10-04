package mchorse.bbs_mod.client.render.view;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.RenderStateRestorer;
import mchorse.bbs_mod.client.render.multiview.ViewPassContext;
import mchorse.bbs_mod.mixin.client.iris.IrisRenderingPipelineAccessor;
import mchorse.bbs_mod.utils.VideoRecorder;
import mchorse.bbs_mod.utils.iris.IrisPipelineManagerAccess;
import mchorse.bbs_mod.utils.iris.IrisPipelineResources;
import mchorse.bbs_mod.utils.iris.IrisStateSnapshot;
import mchorse.bbs_mod.utils.iris.IrisViewState;
import mchorse.bbs_mod.utils.iris.ViewResourceOwner;
import mchorse.bbs_mod.utils.iris.ViewSampleClock;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owns stable view pipelines; Iris retains its normal per-dimension primary pipeline. */
public final class IrisViewBackend
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, ViewRecord> VIEWS = new HashMap<>();

    private IrisViewBackend()
    {}

    public static boolean isAvailable()
    {
        try
        {
            return Iris.getPipelineManager() instanceof IrisPipelineManagerAccess;
        }
        catch (LinkageError exception)
        {
            return false;
        }
    }

    public static boolean isShaderPackAvailable()
    {
        return isAvailable() && Iris.getCurrentPack().isPresent();
    }

    /** Runs before any auxiliary target swap, so chunk materials come from the real dimension. */
    public static void prepareMain()
    {
        RenderSystem.assertOnRenderThread();

        if (IrisViewState.current() != null)
        {
            throw new IllegalStateException("Cannot prepare the primary pipeline inside a view pass");
        }

        WorldRenderingPipeline pipeline = Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());
        ShaderPack pack = Iris.getCurrentPack().orElse(null);

        if (pipeline instanceof IrisRenderingPipelineAccessor access && pack != null && !access.bbs$isBlockIdsInitialized())
        {
            /* Initialize shared chunk materials before any auxiliary can request a rebuild. */
            WorldRenderingSettings.INSTANCE.setBlockStateIds(BlockMaterialMapping.createBlockStateIdMap(
                pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries()));
            WorldRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(
                pack.getIdMap().getBlockRenderTypeMap()));
            Minecraft.getInstance().levelRenderer.allChanged();
            access.bbs$setInitializedBlockIds(true);
        }
    }

    public static IrisViewState.Scope open(String viewId, boolean shadersEnabled, long historyEpoch)
    {
        return open(viewId, shadersEnabled, historyEpoch, false);
    }

    public static IrisViewState.Scope openPrimary(String viewId, boolean shadersEnabled, long historyEpoch)
    {
        return open(viewId, shadersEnabled, historyEpoch, true);
    }

    private static IrisViewState.Scope open(String viewId, boolean shadersEnabled, long historyEpoch, boolean primary)
    {
        RenderSystem.assertOnRenderThread();

        String key = normalize(viewId);
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        NamespacedId dimension = Iris.getCurrentDimension();
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        IrisViewSnapshot snapshot = primary ? null : new IrisViewSnapshot();
        ViewRecord record = VIEWS.computeIfAbsent(key, ignored -> new ViewRecord());

        try
        {
            prepareRecord(record, shadersEnabled, historyEpoch, primary, pack, dimension, target.width, target.height);

            VideoRecorder recorder = BBSModClient.getVideoRecorder();
            long now = System.nanoTime();
            ViewSampleClock.Sample sample;

            if (recorder != null && recorder.isRecording())
            {
                double frameRate = recorder.getCaptureFrameRate();

                if (!Double.isFinite(frameRate) || frameRate <= 0D)
                {
                    frameRate = BBSRendering.getVideoFrameRate();
                }

                sample = record.clock.beginFixedStep(now, ViewSampleClock.exportFrameTime(frameRate, BBSRendering.canRender));
            }
            else
            {
                sample = record.clock.begin(now, SystemTimeUniforms.TIMER.getLastFrameTime());
            }
            IrisViewState.Scope scope = IrisViewState.enter(key, shadersEnabled, historyEpoch, primary, sample,
                snapshot == null ? null : snapshot::restore, () -> record.clock.publish(sample));

            record.lastScope = scope;

            return scope;
        }
        catch (RuntimeException | Error exception)
        {
            record.failure = exception;

            if (snapshot != null)
            {
                try
                {
                    snapshot.restore();
                }
                catch (RuntimeException | Error restoreFailure)
                {
                    rethrowFatal(appendFailure(exception, restoreFailure));
                }
            }

            throw exception;
        }
    }

    private static void prepareRecord(ViewRecord record, boolean shadersEnabled, long historyEpoch,
        boolean primary, ShaderPack pack, NamespacedId dimension, int width, int height)
    {
        boolean abandoned = record.lastScope != null && !record.lastScope.isCommitted();
        boolean changed = record.initialized && (record.shadersEnabled != shadersEnabled || record.pack != pack
            || !Objects.equals(record.dimension, dimension) || record.width != width || record.height != height);

        if (abandoned || changed)
        {
            invalidate(record, primary);
        }
        else if (record.initialized && record.historyEpoch != historyEpoch)
        {
            /* A timeline seek invalidates old pixels, not the compiled shader programs. */
            resetHistory(record);
        }

        if (!record.initialized && primary && historyEpoch != 0L)
        {
            /* A released view may reappear while Iris still owns its old dimension history. */
            record.resetPrimary = true;
        }

        record.initialized = true;
        record.primary = primary;
        record.shadersEnabled = shadersEnabled;
        record.historyEpoch = historyEpoch;
        record.dimension = dimension;
        record.pack = pack;
        record.width = width;
        record.height = height;
        record.failure = null;
    }

    /** Called by preparePipeline only while a view scope is active. */
    public static WorldRenderingPipeline selectPipeline(NamespacedId dimension)
    {
        String viewId = IrisViewState.activeViewId();

        if (viewId == null)
        {
            return null;
        }

        ViewRecord record = VIEWS.get(viewId);

        if (record == null)
        {
            throw new IllegalStateException("No resources for active view " + viewId);
        }

        try
        {
            if (record.pipeline == null)
            {
                record.pipeline = createPipeline(record, dimension);
            }

            record.pipeline.setIsMainBound(true);

            return record.pipeline;
        }
        catch (RuntimeException | Error exception)
        {
            record.failure = exception;
            IrisViewState.reportFailure(viewId, exception);
            LOGGER.error("Could not prepare Iris pipeline for view {}", viewId, exception);

            throw exception;
        }
    }

    private static WorldRenderingPipeline createPipeline(ViewRecord record, NamespacedId dimension)
    {
        if (!record.shadersEnabled || record.pack == null)
        {
            record.nativePrimary = false;
            markPreparation();

            return UnshadedViewPipeline.create();
        }

        IrisPipelineManagerAccess manager = (IrisPipelineManagerAccess) Iris.getPipelineManager();
        WorldRenderingPipeline primary = manager.bbs$getDimensionPipelines().get(dimension);

        if (record.primary && primary != null && !record.resetPrimary)
        {
            record.nativePrimary = true;

            return primary;
        }

        if (!record.primary && !(primary instanceof IrisRenderingPipeline))
        {
            throw new IllegalStateException("The selected shader pack has no working primary pipeline");
        }

        markPreparation();
        long preparationStart = System.nanoTime();
        Runnable restoreSettings = ((IrisStateSnapshot) WorldRenderingSettings.INSTANCE).bbs$captureState();
        ViewResourceOwner resources = ViewResourceOwner.begin();
        IrisRenderingPipeline pipeline = null;
        Throwable failure = null;

        try
        {
            pipeline = new IrisRenderingPipeline(record.pack.getProgramSet(dimension));
            ((IrisRenderingPipelineAccessor) pipeline).bbs$setInitializedBlockIds(WorldRenderingSettings.INSTANCE.getBlockStateIds() != null);
            ((IrisPipelineResources) pipeline).bbs$setResourceOwner(resources);
            resources.finishConstruction();
        }
        catch (RuntimeException | Error exception)
        {
            failure = exception;
        }

        try
        {
            restoreSettings.run();
        }
        catch (RuntimeException | Error restoreFailure)
        {
            failure = appendFailure(failure, restoreFailure);
        }

        if (failure != null)
        {
            try
            {
                resources.close();
            }
            catch (RuntimeException | Error cleanupFailure)
            {
                failure = appendFailure(failure, cleanupFailure);
            }

            rethrowFatal(failure);
            throw (RuntimeException) failure;
        }

        record.nativePrimary = record.primary;
        record.resetPrimary = false;

        if (record.primary)
        {
            WorldRenderingPipeline previous = manager.bbs$getDimensionPipelines().put(dimension, pipeline);

            if (previous != null && previous != pipeline)
            {
                destroy(previous);
            }
        }

        LOGGER.info("[FilmView] pipeline-created view={} size={}x{} elapsedMs={}", IrisViewState.activeViewId(),
            record.width, record.height, (System.nanoTime() - preparationStart) / 1_000_000L);

        return pipeline;
    }

    private static void markPreparation()
    {
        ViewPassContext context = ViewPassContext.current();

        if (context != null)
        {
            context.view().getTiming().markPreparation();
        }
    }

    private static void invalidate(ViewRecord record, boolean primary)
    {
        if (record.nativePrimary)
        {
            /* Replace only this dimension after construction succeeds, without resetting global time. */
            record.resetPrimary = primary;
        }
        else
        {
            destroy(record.pipeline);
        }

        record.pipeline = null;
        record.clock = new ViewSampleClock();
        record.lastScope = null;
    }

    private static void resetHistory(ViewRecord record)
    {
        if (record.pipeline instanceof IrisPipelineResources resources)
        {
            resources.bbs$resetTemporalHistory();
        }

        record.clock = new ViewSampleClock();
        record.lastScope = null;
    }

    public static long historyEpoch(String viewId)
    {
        ViewRecord record = VIEWS.get(normalize(viewId));

        return record == null ? 0L : record.historyEpoch;
    }

    public static Throwable failure(String viewId)
    {
        ViewRecord record = VIEWS.get(normalize(viewId));

        return record == null ? null : record.failure;
    }

    public static boolean shadersActive(String viewId)
    {
        ViewRecord record = VIEWS.get(normalize(viewId));

        return record != null && record.pipeline instanceof IrisRenderingPipeline;
    }

    public static void markFailure(String viewId, Throwable failure)
    {
        ViewRecord record = VIEWS.computeIfAbsent(normalize(viewId), ignored -> new ViewRecord());

        record.failure = failure;
        IrisViewState.reportFailure(viewId, failure);
    }

    public static void clearFailure(String viewId)
    {
        ViewRecord record = VIEWS.get(normalize(viewId));

        if (record != null)
        {
            record.failure = null;
        }
    }

    public static void release(String viewId)
    {
        ViewRecord record = VIEWS.remove(normalize(viewId));

        if (record != null && !record.nativePrimary)
        {
            detachPipeline(record.pipeline);
            destroy(record.pipeline);
        }
    }

    public static void releaseAll()
    {
        Throwable failure = null;

        for (ViewRecord record : VIEWS.values())
        {
            try
            {
                if (!record.nativePrimary)
                {
                    detachPipeline(record.pipeline);
                    destroy(record.pipeline);
                }
            }
            catch (RuntimeException | Error exception)
            {
                failure = appendFailure(failure, exception);
            }
        }

        VIEWS.clear();

        if (failure instanceof Error error)
        {
            throw error;
        }

        if (failure != null)
        {
            LOGGER.error("Could not release every Iris view pipeline", failure);
        }
    }

    private static void destroy(WorldRenderingPipeline pipeline)
    {
        if (pipeline == null)
        {
            return;
        }

        try (RenderStateRestorer releases = new RenderStateRestorer())
        {
            if (pipeline instanceof IrisPipelineResources resources)
            {
                releases.add(resources::bbs$releaseResources);
            }

            releases.add(pipeline::destroy);
        }
    }

    /** Retiring a selected unshaded view must not leave Iris pointing at its released backend. */
    private static void detachPipeline(WorldRenderingPipeline retiring)
    {
        if (retiring != null && Iris.getPipelineManager().getPipelineNullable() == retiring)
        {
            IrisPipelineManagerAccess manager = (IrisPipelineManagerAccess) Iris.getPipelineManager();
            WorldRenderingPipeline nativePipeline = manager.bbs$getDimensionPipelines().get(Iris.getCurrentDimension());

            manager.bbs$setPipeline(nativePipeline == retiring ? null : nativePipeline);
        }
    }

    private static String normalize(String viewId)
    {
        return viewId == null || viewId.isBlank() ? "main" : viewId;
    }

    private static Throwable appendFailure(Throwable first, Throwable next)
    {
        if (first == null || first == next)
        {
            return next;
        }

        if ((next instanceof VirtualMachineError && !(first instanceof VirtualMachineError))
            || (next instanceof Error && !(first instanceof Error)))
        {
            next.addSuppressed(first);
            return next;
        }

        first.addSuppressed(next);
        return first;
    }

    private static void rethrowFatal(Throwable failure)
    {
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static final class ViewRecord
    {
        private WorldRenderingPipeline pipeline;
        private ViewSampleClock clock = new ViewSampleClock();
        private IrisViewState.Scope lastScope;
        private ShaderPack pack;
        private NamespacedId dimension;
        private Throwable failure;
        private long historyEpoch;
        private int width;
        private int height;
        private boolean initialized;
        private boolean primary;
        private boolean nativePrimary;
        private boolean resetPrimary;
        private boolean shadersEnabled;
    }
}
