package mchorse.bbs_mod.api.client.events;

import java.util.Objects;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;

/**
 * Client-thread pose extension points shared by drawing and attachment matrix walks.
 * Never advance a simulation here: a frame may evaluate the same form several times.
 * Matrices supplied to observers are borrowed read-only values. Transform and model pose
 * callbacks may modify their output, but must not edit the form's saved animation.
 * Listeners run in registration order.
 *
 * <p>The chain-claim event is the one member of this set that is not here yet, and it is absent
 * rather than declared. Upstream's {@code CLAIM_CHAIN} hands a {@code FormBone} to its listeners and
 * they answer whether they own that bone's chain — the whole event is addressed by bone. FSR has no
 * bone model to hand over: a bone's physics settings live in the form's {@code physics} blob, not in
 * a per-bone group, until the value layer grows one. Declaring the signature now would freeze a
 * shape that must change the day {@code ValueBones}/{@code FormBone} arrive (P1, upstream
 * {@code 4339b076a}), so an addon would compile against a contract that is known to be temporary.
 * It lands together with the bone model instead. In the meantime B9·B1b keeps a file's
 * {@code bones} data intact across load and save, so nothing is lost while it waits.</p>
 */
public final class FormPoseEvents
{
    public enum Pass { RENDER, MATRICES }

    /** Mutable local transform, after overlays, before either matrix-stack path uses it. */
    public static final FunctionalEvent<TransformPose> TRANSFORM = new FunctionalEvent<>(
        listeners -> (form, transform, transition) ->
        {
            for (TransformPose listener : listeners)
            {
                listener.apply(form, transform, transition);
            }
        });

    /** Parent frame before the form's own transform, for each matrix walk (including models). */
    public static final FunctionalEvent<ParentFrame> PARENT_FRAME = new FunctionalEvent<>(
        listeners -> (form, entity, parent, path, transition) ->
        {
            for (ParentFrame listener : listeners)
            {
                listener.capture(form, entity, parent, path, transition);
            }
        });

    /** After animation and IK, before built-in chain physics in render and bone capture in a walk. */
    public static final FunctionalEvent<ModelPose> MODEL_POSE = new FunctionalEvent<>(
        listeners -> (form, entity, model, transition, base, pass) ->
        {
            for (ModelPose listener : listeners)
            {
                listener.apply(form, entity, model, transition, base, pass);
            }
        });

    /** Include externally authored bone offsets when collecting default pivot frames. */
    public static final FunctionalEvent<PivotOffsets> PIVOT_OFFSETS = new FunctionalEvent<>(
        listeners -> model ->
        {
            for (PivotOffsets listener : listeners)
            {
                if (listener.include(model))
                {
                    return true;
                }
            }

            return false;
        });

    /** Resolve a temporary anchor without changing the stored one. Return a non-null anchor. */
    public static final FunctionalEvent<ResolveAnchor> ANCHOR = new FunctionalEvent<>(
        listeners -> anchor ->
        {
            for (ResolveAnchor listener : listeners)
            {
                anchor = Objects.requireNonNull(listener.resolve(anchor));
            }

            return anchor;
        });

    /** Actor context is established, before anchors and form transforms are resolved. */
    public static final FunctionalEvent<ActorPrepare> ACTOR_BEFORE = new FunctionalEvent<>(
        listeners -> context ->
        {
            for (ActorPrepare listener : listeners)
            {
                listener.prepare(context);
            }
        });

    public interface TransformPose { void apply(Form form, Transform transform, float transition); }
    public interface ParentFrame { void capture(Form form, IEntity entity, Matrix4f parent, String path, float transition); }
    public interface ModelPose { void apply(ModelForm form, IEntity entity, ModelInstance model, float transition, Matrix4f base, Pass pass); }
    public interface PivotOffsets { boolean include(IModel model); }
    public interface ResolveAnchor { Anchor resolve(Anchor anchor); }
    public interface ActorPrepare { void prepare(FilmControllerContext context); }

    private FormPoseEvents()
    {}
}
