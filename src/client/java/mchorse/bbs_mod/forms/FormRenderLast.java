package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderSpace;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * "Render last": a form with {@link Form#renderLast} set skips its turn in the draw order and
 * draws after every other form of the same pass. Forms draw immediately, in list order, with
 * depth writes on — so a semi-transparent pixel drawn early hides whatever draws later behind
 * it, and the cure used to be reordering the replay list by hand. This is that reorder as a
 * per-form switch, the Blockbuster "render last".
 *
 * <p>Unlike {@link FormTranslucentQueue} it never moves a form into another phase of the frame:
 * the postponed forms go through the ordinary {@link FormUtilsClient#render} at the end of the
 * very pass that skipped them — same programs, same frame state, only later than their
 * neighbours. That is what keeps it sound under shader packs, where the queue's end-of-frame
 * replay lands past a deferred pack's lighting composite. What it cannot do is order surfaces
 * within one object, or two "last" forms against each other — those keep the list order.</p>
 *
 * <p>A pass wraps its form drawing in {@link #open()} / {@link #close(boolean)}; close draws
 * the postponed forms in the order they were skipped. Scopes nest: opening inside an open
 * scope opens nothing, and the forms wait for the outer close. What is captured is the form's
 * frame — matrices, light, camera, render space and timeline state — not its geometry: the
 * replay runs the renderer again, so animation, IK and physics evaluate at replay time, still
 * within the same frame. A postponed form takes its body parts with it, they draw inside its
 * renderer.</p>
 */
public class FormRenderLast
{
    private static final List<Postponed> postponed = new ArrayList<>();
    private static boolean active;

    /**
     * Whether forms skip their turn right now: inside an open scope, and never in the Iris
     * shadow pass — the shadow map wants every form where it stands, order means nothing there.
     */
    public static boolean isActive()
    {
        return active && !BBSRendering.isIrisShadowPass();
    }

    /**
     * Open a scope for a pass about to draw forms. Returns whether this call opened it — hand
     * that to {@link #close(boolean)}; a call inside an already open scope opens nothing.
     */
    public static boolean open()
    {
        if (active)
        {
            return false;
        }

        active = true;

        return true;
    }

    /** Close the scope this {@link #open()} call opened and draw the forms it postponed. */
    public static void close(boolean opened)
    {
        if (!opened)
        {
            return;
        }

        active = false;

        flush();
    }

    /**
     * Deactivate for a nested render that must draw where it stands — a framebuffer form's
     * parts go into its own buffer, postponing them would move them into the world. Returns the
     * previous state for {@link #restore(boolean)}.
     */
    public static boolean suspend()
    {
        boolean wasActive = active;

        active = false;

        return wasActive;
    }

    public static void restore(boolean wasActive)
    {
        active = wasActive;
    }

    /**
     * Drop whatever a failed pass left behind without drawing it. Unlike {@link #close(boolean)}
     * this draws nothing, so the next frame starts empty — the same role
     * {@link FormTranslucentQueue#abort()} plays for the translucent queue, which is aborted on
     * exactly the same paths.
     */
    public static void release()
    {
        active = false;
        postponed.clear();
    }

    /**
     * Skip the form's turn if it asks to render last: captures its frame for the replay and
     * returns true, and the caller draws nothing now. Picking draws right away — the stencil
     * needs every form in place and order is irrelevant to it. UI and model-renderer previews
     * also draw right away: the world pass renders the in-world UI between its own draws, and a
     * preview postponed out of it would replay against the world's matrices — the same two
     * exclusions {@link FormRenderingContext#canDeferWorldTranslucency()} makes, for the same
     * reason.
     */
    public static boolean postpone(Form form, FormRenderingContext context)
    {
        if (form == null || context == null || !form.renderLast.get() || !isActive() || context.isPicking()
            || context.ui || context.modelRenderer)
        {
            return false;
        }

        postponed.add(new Postponed(form, context));

        return true;
    }

    private static void flush()
    {
        if (postponed.isEmpty())
        {
            return;
        }

        /* Drained before drawing: the scope is closed, so the replay draws straight away and
         * nothing postpones again mid-flush — the list must simply be empty for the next pass. */
        List<Postponed> forms = new ArrayList<>(postponed);

        postponed.clear();

        /* Read raw, like BBSRendering#renderCoolStuff does: a shader pack sets blend modes with
         * indexed GL calls the cache never sees, and the cache is what the tracked calls write to.
         * The state is put back rather than forced off, because this flush now lands mid-frame —
         * the entity pass closes its scope at AFTER_ENTITIES, with vanilla's block-entity pass
         * still to come — so leaving depth test disabled would leak into the rest of the frame. */
        boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND);

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();

        try
        {
            for (Postponed form : forms)
            {
                form.render();
            }
        }
        finally
        {
            if (oldBlend)
            {
                RenderSystem.enableBlend();
            }
            else
            {
                RenderSystem.disableBlend();
            }

            if (oldDepthTest)
            {
                RenderSystem.enableDepthTest();
            }
            else
            {
                RenderSystem.disableDepthTest();
            }
        }
    }

    /** A form's frame at the moment it was skipped — enough to run its renderer later. */
    private static class Postponed
    {
        private final Form form;
        private final FormRenderType type;
        private final IEntity entity;
        private final FormRenderSpace renderSpace;
        private final Object simulationOwner;
        private final boolean allowWorldTargetOverrides;
        private final boolean allowWorldCollisions;
        private final Matrix4f position;
        private final Matrix3f normal;
        private final Matrix4f worldPosition;
        private final Matrix3f worldNormal;
        private final int light;
        private final int overlay;
        private final int color;
        private final float transition;
        private final boolean ui;
        private final boolean modelRenderer;
        private final long modelRendererTick;
        private final FormProperties timelineProperties;
        private final float timelineTick;
        private final boolean timelinePlaying;
        private final Camera camera = new Camera();

        private Postponed(Form form, FormRenderingContext context)
        {
            PoseStack.Pose entry = context.stack.last();
            PoseStack.Pose world = context.world == null ? null : context.world.last();

            this.form = form;
            this.type = context.type;
            this.entity = context.entity;
            this.renderSpace = context.renderSpace;
            this.simulationOwner = context.simulationOwner;
            this.allowWorldTargetOverrides = context.allowWorldTargetOverrides;
            this.allowWorldCollisions = context.allowWorldCollisions;
            this.position = new Matrix4f(entry.pose());
            this.normal = new Matrix3f(entry.normal());
            this.worldPosition = world == null ? null : new Matrix4f(world.pose());
            this.worldNormal = world == null ? null : new Matrix3f(world.normal());
            this.light = context.light;
            this.overlay = context.overlay;
            this.color = context.color;
            this.transition = context.transition;
            this.ui = context.ui;
            this.modelRenderer = context.modelRenderer;
            this.modelRendererTick = context.modelRendererTick;
            this.timelineProperties = context.timelineProperties;
            this.timelineTick = context.timelineTick;
            this.timelinePlaying = context.timelinePlaying;
            this.camera.copy(context.camera);
        }

        private void render()
        {
            PoseStack stack = new PoseStack();

            stack.last().pose().set(this.position);
            stack.last().normal().set(this.normal);

            FormRenderingContext context = new FormRenderingContext()
                .set(this.type, this.entity, stack, this.light, this.overlay, this.transition)
                .camera(this.camera)
                .color(this.color);

            /* set() derives the render space and the world-host flags from the type alone, and
             * resets the preview/timeline state; the capture holds what the pass really had.
             * A field left out here would not fail, it would draw the same form a different
             * way — the timeline tick it was sampled at, the simulation it resolved against. */
            context.renderSpace = this.renderSpace;
            context.simulationOwner = this.simulationOwner;
            context.allowWorldTargetOverrides = this.allowWorldTargetOverrides;
            context.allowWorldCollisions = this.allowWorldCollisions;
            context.ui = this.ui;
            context.modelRenderer = this.modelRenderer;
            context.modelRendererTick = this.modelRendererTick;
            context.timelineProperties = this.timelineProperties;
            context.timelineTick = this.timelineTick;
            context.timelinePlaying = this.timelinePlaying;

            /* set() rebuilt the world stack from the entity alone; the capture holds what the
             * pass really had there — a body part's slot on its parent, a film's anchor. */
            if (this.worldPosition != null)
            {
                context.world.last().pose().set(this.worldPosition);
                context.world.last().normal().set(this.worldNormal);
            }

            FormUtilsClient.render(this.form, context);
        }
    }
}
