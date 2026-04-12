package mchorse.bbs_mod.client.renderer.item;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.lang.reflect.Method;

public class ModelBlockItemRenderer
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
            item.entity.getProperties().update(item.formEntity);
            item.formEntity.update();
        }
    }

    public void render(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        Item item = this.get(stack);

        if (item != null)
        {
            ModelProperties properties = item.entity.getProperties();
            Form form = properties.getForm(mode);

            if (form != null)
            {
                item.expiration = 20;

                Transform transform = properties.getTransform(mode);

                matrices.pushPose();
                matrices.translate(0.5F, 0F, 0.5F);
                MatrixStackUtils.applyTransform(matrices, transform);

                RenderSystem.enableDepthTest();
                FormUtilsClient.render(form, new FormRenderingContext()
                    .set(resolveRenderType(mode), item.formEntity, matrices, light, overlay, getTickDelta())
                    .camera(Minecraft.getInstance().gameRenderer.getMainCamera()));
                RenderSystem.disableDepthTest();

                matrices.popPose();
            }
        }
    }

    public Item get(ItemStack stack)
    {
        if (stack == null || stack.getItem() != BBSMod.MODEL_BLOCK_ITEM)
        {
            return null;
        }

        if (this.map.containsKey(stack))
        {
            return this.map.get(stack);
        }

        CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        ModelBlockEntity entity = new ModelBlockEntity(BlockPos.ORIGIN, BBSMod.MODEL_BLOCK.defaultBlockState());
        Item item = new Item(entity);

        this.map.put(stack, item);

        if (blockEntityData == null || blockEntityData.isEmpty())
        {
            return item;
        }

        applyBlockEntityTag(entity, blockEntityData.copyTag());

        return item;
    }

    public static class Item
    {
        public ModelBlockEntity entity;
        public IEntity formEntity;
        public int expiration = 20;

        public Item(ModelBlockEntity entity)
        {
            this.entity = entity;
            this.formEntity = new StubEntity(Minecraft.getInstance().level);
        }
    }

    private static FormRenderType resolveRenderType(ItemDisplayContext mode)
    {
        if (mode.firstPerson())
        {
            return FormRenderType.ITEM_FP;
        }
        else if (mode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || mode == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
        {
            return FormRenderType.ITEM_TP;
        }
        else if (mode == ItemDisplayContext.GROUND)
        {
            return FormRenderType.ITEM;
        }
        else if (mode == ItemDisplayContext.GUI)
        {
            return FormRenderType.ITEM_INVENTORY;
        }

        return FormRenderType.ENTITY;
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

    private static void applyBlockEntityTag(ModelBlockEntity entity, CompoundTag tag)
    {
        try
        {
            Object level = Minecraft.getInstance().level;
            Object registryAccess = level == null ? null : level.getClass().getMethod("registryAccess").invoke(level);

            if (registryAccess != null)
            {
                for (Method method : entity.getClass().getMethods())
                {
                    if (method.getName().equals("loadWithComponents")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == CompoundTag.class
                        && method.getParameterTypes()[1].isInstance(registryAccess))
                    {
                        method.invoke(entity, tag, registryAccess);
                        return;
                    }
                }
            }
        }
        catch (Exception ignored)
        {}

        try
        {
            entity.getClass().getMethod("load", CompoundTag.class).invoke(entity, tag);
            return;
        }
        catch (Exception ignored)
        {}

        try
        {
            entity.getClass().getMethod("readNbt", CompoundTag.class).invoke(entity, tag);
        }
        catch (Exception ignored)
        {}
    }
}
