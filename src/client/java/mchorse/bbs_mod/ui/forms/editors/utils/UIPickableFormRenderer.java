package mchorse.bbs_mod.ui.forms.editors.utils;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;

import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Colors;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.function.Supplier;

public class UIPickableFormRenderer extends UIFormRenderer implements GizmoViewport
{
    public UIFormEditor formEditor;

    private boolean update;

    private StencilFormFramebuffer stencil = new StencilFormFramebuffer();
    private StencilMap stencilMap = new StencilMap();
    private final GizmoInteraction gizmoInteraction = new GizmoInteraction(this);

    private IEntity target;
    private Supplier<Boolean> renderForm;

    public UIPickableFormRenderer(UIFormEditor formEditor)
    {
        this.formEditor = formEditor;
    }

    public void updatable()
    {
        this.update = true;
    }

    public StencilFormFramebuffer getStencil()
    {
        return this.stencil;
    }

    public GizmoDrag createGizmoDrag()
    {
        return GizmoDrag.fromRenderedGizmo(this.camera, this.area);
    }

    @Override
    public StencilFormFramebuffer getGizmoStencil()
    {
        return this.stencil;
    }

    @Override
    public Matrix4f getGizmoProjection()
    {
        return this.camera.projection;
    }

    @Override
    public Area getGizmoArea()
    {
        return this.area;
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        return this.formEditor.startGizmo(context, stencilIndex);
    }

    @Override
    public void pickGizmoForm(UIContext context, Form form, String bone)
    {
        this.formEditor.pickGizmoFormFromRenderer(form, bone);
    }

    public void setRenderForm(Supplier<Boolean> renderForm)
    {
        this.renderForm = renderForm;
    }

    public IEntity getTargetEntity()
    {
        return this.target == null ? this.entity : this.target;
    }

    public void setTarget(IEntity target)
    {
        this.target = target;
    }

    private void ensureFramebuffer()
    {
        this.stencil.setup(Link.bbs("stencil_form"));
        this.stencil.resizeGUI(this.area.w, this.area.h);
    }

    @Override
    public void resize()
    {
        super.resize();

        this.ensureFramebuffer();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.gizmoInteraction.mouseClicked(context))
        {
            return true;
        }

        if (this.formEditor.clickViewport(context, this.stencil))
        {
            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        boolean handled = this.gizmoInteraction.mouseReleased(context);

        this.gizmoInteraction.stop();

        return super.subMouseReleased(context) || handled;
    }

    @Override
    protected void renderUserModel(UIContext context)
    {
        if (this.form == null)
        {
            return;
        }

        this.formEditor.preFormRender(context, this.form);

        FormRenderingContext formContext = new FormRenderingContext()
            .set(FormRenderType.PREVIEW, this.target == null ? this.entity : this.target, context.batcher.getContext().pose(), LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY, context.getTransition())
            .camera(this.camera)
            .modelRenderer();

        if (this.renderForm == null || this.renderForm.get())
        {
            FormUtilsClient.render(this.form, formContext);

            if (this.form.hitbox.get())
            {
                this.renderFormHitbox(context);
            }
        }

        /* Keep the gizmo the same on-screen size as in the film preview (see
         * Gizmo#setViewportScale); set before both the visual (renderAxes) and the
         * stencil pass below so the drawn handles and their pick hitbox match. */
        Gizmo.INSTANCE.setViewportScale(context.menu.height / (float) this.area.h);

        this.renderAxes(context);

        if (this.area.isInside(context))
        {
            context.batcher.flush();

            GlStateManager._disableScissorTest();

            boolean applied = false;

            try
            {
                this.stencilMap.setup();
                this.stencil.apply();
                applied = true;

                FormUtilsClient.render(this.form, formContext.stencilMap(this.stencilMap));

                Matrix4f matrix = this.formEditor.getOrigin(context.getTransition());
                PoseStack stack = context.render.batcher.getContext().pose();

                stack.pushPose();

                try
                {
                    if (matrix != null)
                    {
                        MatrixStackUtils.multiply(stack, MatrixStackUtils.stripScale(matrix));
                    }

                    Gizmo.INSTANCE.setViewport(this.area);

                    /* Skip the gizmo's pick stencil while the hide-gizmo key is held, so its handles can't be
                     * clicked when hidden. Form-part picking (the stencil rendered above) is left intact, and
                     * the F8 axes toggle is untouched here on purpose. */
                    if (!UIBaseMenu.isHideGizmoHeld())
                    {
                        Gizmo.INSTANCE.renderStencil(context.batcher.getContext().pose(), this.stencilMap);
                    }
                }
                finally
                {
                    stack.popPose();
                }

                this.stencil.pickGUI(context, this.area, BBSSettings.gizmoHoverTolerance.get(), Gizmo.STENCIL_MAX);
            }
            finally
            {
                if (applied)
                {
                    this.stencil.unbind(this.stencilMap);
                }

                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

                GlStateManager._enableScissorTest();
            }
        }
        else
        {
            this.stencil.clearPicking();
        }
    }

    private void renderAxes(UIContext context)
    {
        Matrix4f matrix = this.formEditor.getOrigin(context.getTransition());
        PoseStack stack = context.render.batcher.getContext().pose();

        stack.pushPose();

        if (matrix != null)
        {
            MatrixStackUtils.multiply(stack, MatrixStackUtils.stripScale(matrix));
        }

        /* Draw axes */
        if (UIBaseMenu.shouldRenderAxes())
        {
            RenderSystem.disableDepthTest();
            Gizmo.INSTANCE.setViewport(this.area);
            Gizmo.INSTANCE.render(stack);
            RenderSystem.enableDepthTest();
        }

        stack.popPose();
    }

    private void renderFormHitbox(UIContext context)
    {
        float hitboxW = this.form.hitboxWidth.get();
        float hitboxH = this.form.hitboxHeight.get();
        float eyeHeight = hitboxH * this.form.hitboxEyeHeight.get();

        /* Draw look vector */
        final float thickness = 0.01F;
        Draw.renderBox(context.batcher.getContext().pose(), -thickness, -thickness + eyeHeight, -thickness, thickness, thickness, 2F, 1F, 0F, 0F);

        /* Draw hitbox */
        Draw.renderBox(context.batcher.getContext().pose(), -hitboxW / 2, 0, -hitboxW / 2, hitboxW, hitboxH, hitboxW);
    }

    @Override
    protected void update()
    {
        super.update();

        if (this.update && this.form != null)
        {
            this.form.update(this.entity);
        }
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);
        this.gizmoInteraction.update(context);
        this.gizmoInteraction.renderSphereHighlight(context);
        this.gizmoInteraction.renderReadout(context);

        if (!this.stencil.hasPicked())
        {
            return;
        }

        int index = this.stencil.getIndex();
        Texture texture = this.stencil.getFramebuffer().getMainTexture();
        Pair<Form, String> pair = this.stencil.getPicked();
        int w = texture.width;
        int h = texture.height;

        ShaderInstance previewProgram = BBSShaders.getPickerPreviewProgram();
        Uniform target = previewProgram.getUniform("Target");

        if (target != null)
        {
            target.set(index);
        }

        Uniform highlight = previewProgram.getUniform("HighlightColor");

        if (highlight != null)
        {
            int color = BBSSettings.stencilHighlightColor.get();

            highlight.set(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
        }

        RenderSystem.enableBlend();
        context.batcher.texturedBox(BBSShaders::getPickerPreviewProgram, texture.id, Colors.WHITE, this.area.x, this.area.y, this.area.w, this.area.h, 0, h, w, 0, w, h);

        if (pair != null && pair.a != null)
        {
            String label = pair.a.getFormIdOrName();

            if (!pair.b.isEmpty())
            {
                label += " - " + pair.b;
            }

            context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
        }
    }

    @Override
    protected void renderGrid(UIContext context)
    {
        if (this.renderForm == null || this.renderForm.get())
        {
            super.renderGrid(context);
        }
    }
}
