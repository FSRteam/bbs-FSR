package mchorse.bbs_mod.forms.renderers.utils;

import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Matrix results for one render epoch. Entries include the form's pose version and FSR's
 * simulation owner, semantic placement and world-target permissions so independent views
 * cannot borrow each other's evaluated pose.
 *
 * <p>Explicit pass caches and the shared {@link RenderFrame} cache use the same epoch.
 * Temporary re-posing, such as onion skin and motion-path sampling, invalidates both.
 * Model channel snapshots separately restore the mutable channels on shared model assets.</p>
 */
public final class FormFrameCache
{
    private final Map<Form, Entry> entries = new IdentityHashMap<>();
    private long epoch = RenderFrame.getEpoch();

    /**
     * Evaluate {@code form} for {@code entity}, reusing {@code cache}'s result when it already holds one for
     * the same form, entity and transition. A {@code null} cache delegates to {@link RenderFrame}.
     */
    public static MatrixCache collect(FormFrameCache cache, Form form, IEntity entity, float transition)
    {
        return collect(cache, form, entity, null, null, false, false, transition);
    }

    public MatrixCache collect(Form form, IEntity entity, float transition)
    {
        return this.collect(form, entity, null, null, false, false, transition);
    }

    public static MatrixCache collect(
        FormFrameCache cache,
        Form form,
        IEntity entity,
        Object simulationOwner,
        Matrix4f semanticBase,
        boolean allowWorldTargetOverrides,
        boolean allowWorldCollisions,
        float transition
    )
    {
        if (cache == null)
        {
            return RenderFrame.collect(form, entity, simulationOwner, semanticBase,
                allowWorldTargetOverrides, allowWorldCollisions, transition);
        }

        return cache.collect(
            form,
            entity,
            simulationOwner,
            semanticBase,
            allowWorldTargetOverrides,
            allowWorldCollisions,
            transition
        );
    }

    public MatrixCache collect(
        Form form,
        IEntity entity,
        Object simulationOwner,
        Matrix4f semanticBase,
        boolean allowWorldTargetOverrides,
        boolean allowWorldCollisions,
        float transition
    )
    {
        if (this.epoch != RenderFrame.getEpoch())
        {
            this.clear();
        }

        Entry entry = this.entries.get(form);

        if (entry != null
            && entry.entity == entity
            && entry.simulationOwner == simulationOwner
            && entry.allowWorldTargetOverrides == allowWorldTargetOverrides
            && entry.allowWorldCollisions == allowWorldCollisions
            && entry.poseVersion == form.getPoseVersion()
            && Float.compare(entry.transition, transition) == 0
            && matricesEqual(entry.semanticBase, semanticBase))
        {
            BBSProfiler.count(BBSProfiler.Section.FRAME_CACHE_HIT);
            return entry.matrices;
        }

        BBSProfiler.count(BBSProfiler.Section.FRAME_CACHE_MISS);
        MatrixCache matrices = FormUtilsClient.getRenderer(form).collectMatrices(
            entity,
            simulationOwner,
            semanticBase,
            allowWorldTargetOverrides,
            allowWorldCollisions,
            transition
        );

        this.entries.put(form, new Entry(
            entity,
            simulationOwner,
            semanticBase == null ? null : new Matrix4f(semanticBase),
            allowWorldTargetOverrides,
            allowWorldCollisions,
            form.getPoseVersion(),
            transition,
            matrices
        ));

        return matrices;
    }

    public void clear()
    {
        this.entries.clear();
        this.epoch = RenderFrame.getEpoch();
    }

    private static boolean matricesEqual(Matrix4f a, Matrix4f b)
    {
        return a == b || (a != null && b != null && a.equals(b));
    }

    private record Entry(
        IEntity entity,
        Object simulationOwner,
        Matrix4f semanticBase,
        boolean allowWorldTargetOverrides,
        boolean allowWorldCollisions,
        int poseVersion,
        float transition,
        MatrixCache matrices
    )
    {}
}
