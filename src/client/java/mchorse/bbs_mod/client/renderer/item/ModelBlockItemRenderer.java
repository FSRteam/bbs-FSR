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
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.lang.reflect.Method;

public class ModelBlockItemRenderer
{
    private Map<ItemStack, Item> map = new HashMap<>();
    private static Method loadWithComponentsMethod;
    private static Method loadMethod;
    private static Method readNbtMethod;
    private static boolean loadWithComponentsResolved;
    private static boolean loadResolved;
    private static boolean readNbtResolved;

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
        if (stack == null || stack.getItem() != BBSMod.MODEL_BLOCK_ITEM.get())
        {
            return null;
        }

        Item cached = this.map.get(stack);

        if (cached != null)
        {
            return cached;
        }

        CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        ModelBlockEntity entity = new ModelBlockEntity(BlockPos.ZERO, BBSMod.MODEL_BLOCK.get().defaultBlockState());
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
        private final Map<ItemDisplayContext, Object> simulationOwners = new EnumMap<>(ItemDisplayContext.class);

        public Item(ModelBlockEntity entity)
        {
            this.entity = entity;
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

    private static void applyBlockEntityTag(ModelBlockEntity entity, CompoundTag tag)
    {
        try
        {
            HolderLookup.Provider registryAccess = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.registryAccess();

            if (registryAccess != null)
            {
                Method method = getLoadWithComponentsMethod(entity.getClass(), registryAccess);

                if (method != null)
                {
                    method.invoke(entity, tag, registryAccess);
                    return;
                }
            }
        }
        catch (Exception ignored)
        {}

        try
        {
            Method method = getLoadMethod(entity.getClass());

            if (method != null)
            {
                method.invoke(entity, tag);
                return;
            }
        }
        catch (Exception ignored)
        {}

        try
        {
            Method method = getReadNbtMethod(entity.getClass());

            if (method != null)
            {
                method.invoke(entity, tag);
            }
        }
        catch (Exception ignored)
        {}
    }

    private static Method getLoadWithComponentsMethod(Class<?> clazz, HolderLookup.Provider registryAccess)
    {
        if (!loadWithComponentsResolved)
        {
            for (Method method : clazz.getMethods())
            {
                if (method.getName().equals("loadWithComponents")
                    && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == CompoundTag.class
                    && method.getParameterTypes()[1].isInstance(registryAccess))
                {
                    loadWithComponentsMethod = method;
                    break;
                }
            }

            loadWithComponentsResolved = true;
        }

        return loadWithComponentsMethod;
    }

    private static Method getLoadMethod(Class<?> clazz)
    {
        if (!loadResolved)
        {
            try
            {
                loadMethod = clazz.getMethod("load", CompoundTag.class);
            }
            catch (Exception ignored)
            {}

            loadResolved = true;
        }

        return loadMethod;
    }

    private static Method getReadNbtMethod(Class<?> clazz)
    {
        if (!readNbtResolved)
        {
            try
            {
                readNbtMethod = clazz.getMethod("readNbt", CompoundTag.class);
            }
            catch (Exception ignored)
            {}

            readNbtResolved = true;
        }

        return readNbtMethod;
    }
}
