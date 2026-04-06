package mchorse.bbs_mod.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
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

public class MorphRenderer
{
    public static boolean hidePlayer = false;

    public static boolean renderPlayer(AbstractClientPlayer player, float f, float g, PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, int i)
    {
        if (hidePlayer)
        {
            if (FormUtilsClient.getCurrentForm() instanceof MobForm form && !form.isPlayer())
            {
                return true;
            }
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

                FormUtilsClient.render(morph.getForm(), new FormRenderingContext()
                    .set(FormRenderType.ENTITY, morph.entity, matrixStack, i, overlay, g)
                    .camera(Minecraft.getInstance().gameRenderer.getCamera()));

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

            FormUtilsClient.render(form, new FormRenderingContext()
                .set(FormRenderType.ENTITY, owner.entity, matrixStack, i, o, g)
                .camera(Minecraft.getInstance().gameRenderer.getCamera()));

            matrixStack.popPose();

            RenderSystem.disableDepthTest();

            return true;
        }

        return false;
    }
}
