package mchorse.bbs_mod.forms.renderers;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.surface.BBSFormPreviewCapture;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.keys.KeyCodes;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.Transform;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public abstract class FormRenderer <T extends Form>
{
    protected T form;
    private final Transform combinedTransform = new Transform();
    private final Matrix4f transformMatrix = new Matrix4f();

    public FormRenderer(T form)
    {
        this.form = form;
    }

    public T getForm()
    {
        return this.form;
    }

    public List<String> getBones()
    {
        return Collections.emptyList();
    }

    public final void renderUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.renderInUI(context, x1, y1, x2, y2);
        BBSFormPreviewCapture.include(context, x1, y1, x2, y2);

        FontRenderer font = context.batcher.getFont();
        String name = this.form.name.get();

        if (!name.isEmpty())
        {
            name = font.limitToWidth(name, x2 - x1 - 3);

            int w = font.getWidth(name);

            context.batcher.textCard(name, (x2 + x1 - w) / 2, y1 + 6, Colors.WHITE, Colors.ACTIVE | Colors.A50);
        }

        int keybind = this.form.hotkey.get();

        if (keybind > 0)
        {
            name = KeyCodes.getName(keybind);
            name = font.limitToWidth(name, x2 - x1 - 3);

            int w = font.getWidth(name);

            context.batcher.textCard(name, (x2 + x1 - w) / 2, y2 - 6 - font.getHeight(), Colors.WHITE, Colors.A50);
        }
    }

    protected abstract void renderInUI(UIContext context, int x1, int y1, int x2, int y2);

    public boolean renderArm(PoseStack matrices, int light, AbstractClientPlayer player, InteractionHand hand)
    {
        return false;
    }

    public final void render(FormRenderingContext context)
    {
        if (!this.form.shaderShadow.get() && BBSRendering.isIrisShadowPass())
        {
            return;
        }

        int light = context.light;
        PoseStack world = context.world;
        boolean stackPushed = false;
        boolean worldPushed = false;
        boolean statesApplied = false;

        try
        {
            statesApplied = true;
            this.form.applyStates(context.transition);

            if (!this.form.visible.get())
            {
                return;
            }

            boolean isPicking = context.stencilMap != null;

            context.stack.pushPose();
            stackPushed = true;

            if (world != null)
            {
                world.pushPose();
                worldPushed = true;
            }

            this.applyTransforms(context.stack, false, context.getTransition());

            if (world != null)
            {
                this.applyTransforms(world, false, context.getTransition());
            }

            float lf = 1F - MathUtils.clamp(this.form.lighting.get(), 0F, 1F);
            int u = context.light & '\uffff';
            int v = context.light >> 16 & '\uffff';

            u = (int) Lerps.lerp(u, LightTexture.FULL_BRIGHT, lf);
            context.light = u | v << 16;

            this.render3D(context);

            if (isPicking)
            {
                this.updateStencilMap(context);
            }

            this.renderBodyParts(context);
        }
        finally
        {
            try
            {
                if (stackPushed)
                {
                    context.stack.popPose();
                }
            }
            finally
            {
                try
                {
                    if (worldPushed)
                    {
                        world.popPose();
                    }
                }
                finally
                {
                    context.light = light;

                    if (statesApplied)
                    {
                        this.form.unapplyStates();
                    }
                }
            }
        }
    }

    protected void applyTransforms(PoseStack stack, boolean origin, float transition)
    {
        Transform transform = this.setupTransform(this.combinedTransform);

        if (origin)
        {
            stack.translate(transform.translate.x, transform.translate.y, transform.translate.z);
        }
        else
        {
            MatrixStackUtils.applyTransform(stack, transform);
        }
    }

    protected void applyTransforms(Matrix4f matrix, float transition)
    {
        matrix.mul(this.setupTransform(this.combinedTransform).setupMatrix(this.transformMatrix.identity()));
    }

    protected Transform createTransform()
    {
        return this.setupTransform(new Transform());
    }

    protected Transform setupTransform(Transform transform)
    {
        transform.copy(this.form.transform.get());
        this.applyTransform(transform, this.form.transformOverlay.get());

        for (ValueTransform t : this.form.additionalTransforms)
        {
            this.applyTransform(transform, t.get());
        }

        return transform;
    }

    private void applyTransform(Transform transform, Transform overlay)
    {
        transform.translate.add(overlay.translate);
        transform.scale.add(overlay.scale).sub(1, 1, 1);
        transform.rotate.add(overlay.rotate);
        transform.rotate2.add(overlay.rotate2);
    }

    /**
     * Form-local displacement of the form's origin from its rest pose this frame, used to drag the
     * entity's shadow under the form's perceived position. The base form simply reports its own
     * transform's translation (so transform keyframes shift the shadow); subclasses can override to
     * add their own motion (e.g. {@link ModelFormRenderer} folds in anchor-bone root motion). The
     * caller maps the result to world axes with the render target.
     */
    public Vector3f getShadowDisplacement(IEntity entity, float transition)
    {
        PoseStack stack = new PoseStack();

        stack.pushPose();
        this.applyTransforms(stack, false, transition);

        Vector3f displacement = stack.last().pose().getTranslation(new Vector3f());

        stack.popPose();

        return displacement;
    }

    public Vector3f getShadowDisplacement(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, float transition)
    {
        return this.getShadowDisplacement(entity, transition);
    }

    protected Supplier<ShaderInstance> getShader(FormRenderingContext context, Supplier<ShaderInstance> normal, Supplier<ShaderInstance> picking)
    {
        if (context.isPicking())
        {
            this.setupTarget(context, picking.get());

            return picking;
        }

        return normal;
    }

    protected void setupTarget(FormRenderingContext context, ShaderInstance program)
    {
        Uniform target = program.getUniform("Target");

        if (target != null)
        {
            int pickingIndex = context.getPickingIndex();

            target.set(pickingIndex);
        }
    }

    protected void updateStencilMap(FormRenderingContext context)
    {
        context.stencilMap.addPicking(this.form);
    }

    protected void render3D(FormRenderingContext context)
    {}

    public void renderBodyParts(FormRenderingContext context)
    {
        for (BodyPart part : this.form.parts.getAllTyped())
        {
            this.renderBodyPart(part, context);
        }
    }

    protected void renderBodyPart(BodyPart part, FormRenderingContext context)
    {
        IEntity oldEntity = context.entity;
        Object oldSimulationOwner = context.simulationOwner;
        PoseStack world = context.world;
        boolean stackPushed = false;
        boolean worldPushed = false;

        try
        {
            context.entity = part.useTarget.get() ? oldEntity : part.getEntity();

            /* Entity selection controls animation/world inputs, not history ownership.
             * Keep the caller's placement owner so rendering this same nested form in
             * a world entity and in an editor preview cannot share Verlet/IK history.
             * The nested form has its own renderer, so it remains isolated from the
             * parent even when both deliberately use the same placement owner. */

            if (part.getForm() != null)
            {
                context.stack.pushPose();
                stackPushed = true;

                if (world != null)
                {
                    world.pushPose();
                    worldPushed = true;
                }

                MatrixStackUtils.applyTransform(context.stack, part.transform.get());

                if (world != null)
                {
                    MatrixStackUtils.applyTransform(world, part.transform.get());
                }

                FormUtilsClient.render(part.getForm(), context);
            }
        }
        finally
        {
            try
            {
                if (stackPushed)
                {
                    context.stack.popPose();
                }
            }
            finally
            {
                try
                {
                    if (worldPushed)
                    {
                        world.popPose();
                    }
                }
                finally
                {
                    context.entity = oldEntity;
                    context.simulationOwner = oldSimulationOwner;
                }
            }
        }
    }

    public MatrixCache collectMatrices(IEntity entity, float transition)
    {
        MatrixCache map = new MatrixCache();
        PoseStack stack = new PoseStack();

        this.collectMatrices(entity, stack, map, "", transition);

        return map;
    }

    /**
     * Collect matrices for a concrete simulation placement. Film anchors use
     * this path so procedural bones are sampled with the same owner, absolute
     * semantic base and world-input policy as the form that is rendered.
     */
    public MatrixCache collectMatrices(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, float transition)
    {
        MatrixCache map = new MatrixCache();
        PoseStack stack = new PoseStack();

        this.collectMatrices(entity, simulationOwner, semanticBase, allowWorldTargetOverrides, allowWorldCollisions, stack, map, "", transition);

        return map;
    }

    public void collectMatrices(IEntity entity, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        this.collectMatrices(entity, null, null, false, false, stack, matrices, prefix, transition);
    }

    public final void collectMatrices(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        boolean statesApplied = false;

        try
        {
            statesApplied = true;
            this.form.applyStates(transition);
            this.collectMatricesWithAppliedStates(entity, simulationOwner, semanticBase, allowWorldTargetOverrides, allowWorldCollisions, stack, matrices, prefix, transition);
        }
        finally
        {
            if (statesApplied)
            {
                this.form.unapplyStates();
            }
        }
    }

    protected void collectMatricesWithAppliedStates(IEntity entity, Object simulationOwner, Matrix4f semanticBase, boolean allowWorldTargetOverrides, boolean allowWorldCollisions, PoseStack stack, MatrixCache matrices, String prefix, float transition)
    {
        Matrix4f mm = new Matrix4f();
        Matrix4f oo = new Matrix4f();

        stack.pushPose();
        try
        {
            this.applyTransforms(stack, true, transition);
            oo.set(stack.last().pose());
        }
        finally
        {
            stack.popPose();
        }

        stack.pushPose();
        try
        {
            this.applyTransforms(stack, false, transition);
            mm.set(stack.last().pose());

            matrices.put(prefix, mm, oo);

            Matrix4f formSemanticBase = null;

            if (semanticBase != null)
            {
                formSemanticBase = new Matrix4f(semanticBase);
                this.applyTransforms(formSemanticBase, transition);
            }

            int i = 0;

            for (BodyPart part : this.form.parts.getAllTyped())
            {
                Form form = part.getForm();

                if (form != null)
                {
                    stack.pushPose();
                    try
                    {
                        MatrixStackUtils.applyTransform(stack, part.transform.get());

                        Matrix4f childSemanticBase = formSemanticBase == null
                            ? null
                            : new Matrix4f(formSemanticBase).mul(part.transform.get().setupMatrix(new Matrix4f()));
                        IEntity childEntity = part.useTarget.get() ? entity : part.getEntity();

                        FormUtilsClient.getRenderer(form).collectMatrices(
                            childEntity,
                            simulationOwner,
                            childSemanticBase,
                            allowWorldTargetOverrides,
                            allowWorldCollisions,
                            stack,
                            matrices,
                            StringUtils.combinePaths(prefix, String.valueOf(i)),
                            transition
                        );
                    }
                    finally
                    {
                        stack.popPose();
                    }
                }

                i += 1;
            }
        }
        finally
        {
            stack.popPose();
        }
    }
}
