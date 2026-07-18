package mchorse.bbs_mod.client.renderer.item;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockEditorMenu;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

public class GunItemRenderer
{
    private Map<ItemStack, Item> map = new HashMap<>();

    public void update()
    {
        Iterator<Item> it = this.map.values().iterator();

        while (it.hasNext())
        {
            Item item = it.next();

            if (item.expiration <= 0)
            {
                it.remove();
            }

            item.expiration -= 1;
            item.properties.update(item.formEntity);
            item.formEntity.update();
        }
    }

    public void render(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        Item item = this.get(stack);

        if (item != null)
        {
            GunProperties properties = item.properties;
            Form form = properties.getForm(mode);
            Transform transform = properties.getTransform(mode);
            boolean zoom = mode.firstPerson() && BBSModClient.getGunZoom() != null && properties.getZoomForm() != null;

            if (zoom)
            {
                form = properties.getZoomForm();
                transform = properties.zoomTransform;
            }

            /* Preview zoom form */
            if (UIScreen.getCurrentMenu() instanceof UIModelBlockEditorMenu editorMenu && editorMenu.currentSection == editorMenu.sectionZoom)
            {
                form = editorMenu.getGunProperties().getZoomForm();
                transform = editorMenu.getGunProperties().zoomTransform;
            }

            if (form != null)
            {
                item.expiration = 20;
                PoseStack semanticWorld = new PoseStack();

                MatrixStackUtils.applyTransform(semanticWorld, transform);

                matrices.pushPose();
                try
                {
                    matrices.translate(0.5F, 0F, 0.5F);
                    MatrixStackUtils.applyTransform(matrices, transform);

                    RenderSystem.enableDepthTest();
                    try
                    {
                        FormUtilsClient.render(form, new FormRenderingContext()
                            .set(FormRenderType.fromModelMode(mode), item.formEntity, matrices, light, overlay, getTickDelta())
                            .simulationOwner(item.getSimulationOwner(mode))
                            .semanticWorld(semanticWorld)
                            .localSimulation()
                            .camera(Minecraft.getInstance().gameRenderer.getMainCamera()));
                    }
                    finally
                    {
                        RenderSystem.disableDepthTest();
                    }
                }
                finally
                {
                    matrices.popPose();
                }
            }
        }
    }

    public Item get(ItemStack stack)
    {
        if (stack == null || stack.getItem() != BBSMod.GUN_ITEM.get())
        {
            return null;
        }

        if (this.map.containsKey(stack))
        {
            return this.map.get(stack);
        }

        Item item = new Item(GunProperties.get(stack));

        this.map.put(stack, item);

        return item;
    }

    public static class Item
    {
        public GunProperties properties;
        public IEntity formEntity;
        public int expiration = 20;
        private final Map<ItemDisplayContext, Object> simulationOwners = new EnumMap<>(ItemDisplayContext.class);

        public Item(GunProperties properties)
        {
            this.properties = properties;
            this.formEntity = new StubEntity();
        }

        public Object getSimulationOwner(ItemDisplayContext mode)
        {
            ItemDisplayContext key = mode == null ? ItemDisplayContext.NONE : mode;

            return this.simulationOwners.computeIfAbsent(key, (ignored) -> new Object());
        }
    }

    private static float getTickDelta()
    {
        try
        {
            return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        }
        catch (Exception ignored)
        {}

        return 0F;
    }
}
