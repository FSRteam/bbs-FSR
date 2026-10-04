package mchorse.bbs_mod.client.render.multiview;

import mchorse.bbs_mod.utils.sodium.SodiumUtils;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Camera/terrain state shared by supported Sodium scheduling interfaces. */
public final class SodiumViewAdapter
{
    private static final Set<Object> EVENTS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> MAINTENANCE = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Object, ChunkJobCollector> MESHING = new IdentityHashMap<>();
    private static boolean multiViewFrame;
    private static SodiumUtils.CullingState frameCulling;

    private SodiumViewAdapter()
    {}

    public static void beginFrame()
    {
        multiViewFrame = true;
        EVENTS.clear();
        MAINTENANCE.clear();
        MESHING.clear();
        SodiumChunkScheduling.clearFrame();
    }

    public static boolean isMultiViewFrame()
    {
        return multiViewFrame;
    }

    public static boolean claimChunkEvents(Object renderer)
    {
        return !multiViewFrame || EVENTS.add(renderer);
    }

    public static boolean claimMaintenance(Object renderer)
    {
        return !multiViewFrame || MAINTENANCE.add(renderer);
    }

    public static ChunkJobCollector meshingCollector(Object renderer, int highBudget, int lowBudget,
                                                     Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results)
    {
        return MESHING.computeIfAbsent(renderer, ignored -> new ChunkJobCollector(highBudget, lowBudget, results));
    }

    public static ChunkJobCollector sortingCollector(Consumer<ChunkJobResult<? extends BuilderTaskOutput>> results)
    {
        return new ChunkJobCollector(result -> {})
        {
            @Override
            public void onJobFinished(ChunkJobResult<? extends BuilderTaskOutput> result)
            {
                try
                {
                    results.accept(result);
                }
                finally
                {
                    /* Sodium signals completion before invoking its consumer.
                     * A following view must see this sort result before it can draw. */
                    super.onJobFinished(result);
                }
            }
        };
    }

    public static void prepareForView()
    {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();

        if (renderer != null)
        {
            renderer.scheduleTerrainUpdate();
        }
    }

    public static void captureFrameCulling()
    {
        frameCulling = SodiumUtils.captureCameraCulling();
    }

    public static void restoreFrameCulling()
    {
        if (frameCulling != null)
        {
            frameCulling.apply(false);
            frameCulling = null;
        }
    }

    public static void applyCameraCulling(boolean orthographic)
    {
        if (frameCulling == null)
        {
            captureFrameCulling();
        }

        if (frameCulling != null)
        {
            frameCulling.apply(orthographic);
        }
    }

    public static CullingScope openCameraCulling(boolean orthographic)
    {
        CullingScope scope = new CullingScope(SodiumUtils.captureCameraCulling());
        applyCameraCulling(orthographic);
        return scope;
    }

    public static void endFrame()
    {
        multiViewFrame = false;
        EVENTS.clear();
        MAINTENANCE.clear();
        MESHING.clear();
        SodiumChunkScheduling.clearFrame();
    }

    public static final class CullingScope implements AutoCloseable
    {
        private final SodiumUtils.CullingState previous;
        private boolean closed;

        private CullingScope(SodiumUtils.CullingState previous)
        {
            this.previous = previous;
        }

        @Override
        public void close()
        {
            if (!this.closed)
            {
                this.closed = true;

                if (this.previous != null)
                {
                    this.previous.apply(false);
                }
            }
        }
    }
}
