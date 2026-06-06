package mchorse.bbs_mod.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.selectors.ISelectorOwnerProvider;
import mchorse.bbs_mod.selectors.SelectorOwner;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.morphing.UIMorphingPanel;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.LivingEntity;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

public class MorphRenderer
{
    public static boolean hidePlayer = false;

    public static boolean renderPlayer(AbstractClientPlayer player, float f, float g, PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, int i)
    {
        Form currentForm = FormUtilsClient.getCurrentForm();

        if (hidePlayer)
        {
            if (currentForm instanceof MobForm form && !form.isPlayer())
            {
                return true;
            }
        }

        if (currentForm != null)
        {
            return false;
        }

        Morph morph = Morph.getMorph(player);

        if (morph != null && morph.getForm() != null)
        {
            if (canRender())
            {
                RenderSystem.enableDepthTest();

                float bodyYaw = Lerps.lerp(player.yBodyRotO, player.yBodyRot, g);
                int overlay = LivingEntityRenderer.getOverlayCoords(player, 0F);

                matrixStack.pushPose();
                matrixStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));

                renderForm(morph.getForm(), new FormRenderingContext()
                    .set(FormRenderType.ENTITY, morph.entity, matrixStack, i, overlay, g)
                    .camera(Minecraft.getInstance().gameRenderer.getMainCamera()));

                matrixStack.popPose();

                RenderSystem.disableDepthTest();
            }

            return true;
        }

        return false;
    }

    private static boolean canRender()
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();
        
        if (menu instanceof UIDashboard dashboard)
        {
            UIDashboardPanel panel = dashboard.getPanels().panel;

            if (panel instanceof UIMorphingPanel morphingPanel)
            {
                return !morphingPanel.palette.editor.isEditing();
            }
        }

        return true;
    }

    public static boolean renderLivingEntity(LivingEntity livingEntity, float f, float g, PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, int i, int o)
    {
        if (FormUtilsClient.getCurrentForm() != null)
        {
            return false;
        }

        if (!(livingEntity instanceof ISelectorOwnerProvider))
        {
            return false;
        }

        SelectorOwner owner = ((ISelectorOwnerProvider) livingEntity).getOwner();

        owner.check();

        Form form = owner.getForm();

        if (form != null)
        {
            RenderSystem.enableDepthTest();

            float bodyYaw = Lerps.lerp(livingEntity.yBodyRotO, livingEntity.yBodyRot, g);

            matrixStack.pushPose();
            matrixStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));

            renderForm(form, new FormRenderingContext()
                .set(FormRenderType.ENTITY, owner.entity, matrixStack, i, o, g)
                .camera(Minecraft.getInstance().gameRenderer.getMainCamera()));

            matrixStack.popPose();

            RenderSystem.disableDepthTest();

            return true;
        }

        return false;
    }

    public static void renderForm(Form form, FormRenderingContext context)
    {
        if (!BBSRendering.isRenderingWorld())
        {
            FormUtilsClient.render(form, context);

            return;
        }

        PoseStack originalStack = context.stack;
        PoseStack bakedStack = new PoseStack();
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();

        bakedStack.setIdentity();
        bakedStack.last().pose().set(new Matrix4f(modelView).mul(originalStack.last().pose()));
        bakedStack.last().normal().set(new Matrix3f(modelView).mul(originalStack.last().normal()));

        modelViewStack.pushMatrix();
        modelViewStack.identity();
        RenderSystem.applyModelViewMatrix();
        context.stack = bakedStack;

        try
        {
            FormUtilsClient.render(form, context);
            FormUtilsClient.getProvider().endBatch();
        }
        finally
        {
            context.stack = originalStack;
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
