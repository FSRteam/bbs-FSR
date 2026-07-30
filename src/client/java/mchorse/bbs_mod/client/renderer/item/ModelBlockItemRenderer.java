package mchorse.bbs_mod.client.renderer.item;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
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
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class ModelBlockItemRenderer
{
    /* Each physical stack owns its animation/simulation state. Equal copies must not
     * share a temporary entity, while changing this stack's components must still hit
     * the same entry so the BLOCK_ENTITY_DATA snapshot can invalidate it below. */
    private final Map<ItemStack, Item> map = new IdentityHashMap<>();

    public void update()
    {
        Level world = Minecraft.getInstance().level;
        Iterator<Item> it = this.map.values().iterator();

        while (it.hasNext())
        {
            Item item = it.next();

            if (item.expiration <= 0)
            {
                it.remove();
                item.release();

                continue;
            }

            item.expiration -= 1;
            item.syncWorld(world);
            item.entity.getProperties().update(item.formEntity);
            item.formEntity.update();
        }
    }

    public void render(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        Item item = this.get(stack);
        ModelProperties properties = item == null ? null : item.entity.getProperties();
        Form form = properties == null ? null : properties.getForm(mode);

        if (form == null)
        {
            /* The item model is builtin/entity, so nothing would be drawn at all
             * for a model block that has no form yet. Fall back to the block's
             * own model so the item stays visible in inventories and in hand. */
            renderBlockFallback(stack, mode, matrices, vertexConsumers, light, overlay);

            return;
        }

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

    private static void renderBlockFallback(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        Minecraft client = Minecraft.getInstance();

        /* Resolve through the block state rather than a hand-built ModelResourceLocation: the block
         * carries a WATERLOGGED property, so its baked variant key is "waterlogged=false", not "".
         * Looking it up by a guessed variant silently returns the missing model instead. */
        BakedModel baked = client.getBlockRenderer().getBlockModelShaper()
            .getBlockModel(BBSMod.MODEL_BLOCK.get().defaultBlockState());

        if (baked == null)
        {
            return;
        }

        matrices.pushPose();
        try
        {
            /* ItemRenderer#render re-applies the display transform and the same
             * -0.5 pivot offset our caller already applied, so undo it first. */
            matrices.translate(0.5F, 0.5F, 0.5F);

            client.getItemRenderer().render(stack, mode, false, matrices, vertexConsumers, light, overlay, baked);
        }
        finally
        {
            matrices.popPose();
        }
    }

    public Item get(ItemStack stack)
    {
        if (stack == null || stack.getItem() != BBSMod.MODEL_BLOCK_ITEM.get())
        {
            return null;
        }

        CustomData blockEntityData = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY);
        Item cached = this.map.get(stack);

        if (cached != null && cached.matches(blockEntityData))
        {
            cached.expiration = 20;
            cached.syncWorld(Minecraft.getInstance().level);

            return cached;
        }

        /* FormUtils.fromData needs the client FormArchitect. Do not cache a
         * partially hydrated entity while that registry is still bootstrapping;
         * the next lookup will retry the saved component instead. */
        if (!blockEntityData.isEmpty() && BBSMod.getForms() == null)
        {
            return null;
        }

        ModelBlockEntity entity = new ModelBlockEntity(BlockPos.ZERO, BBSMod.MODEL_BLOCK.get().defaultBlockState());

        applyBlockEntityData(entity.getProperties(), blockEntityData);

        /* A model block without stored properties is still a valid item: it is
         * what the player gets from the creative tab, and the editor has to be
         * able to open on it to assign a form in the first place. */
        Item item = new Item(entity, blockEntityData);

        item.syncWorld(Minecraft.getInstance().level);

        Item replaced = this.map.put(stack, item);

        if (replaced != null && replaced != item)
        {
            replaced.release();
        }

        return item;
    }

    public static class Item
    {
        public ModelBlockEntity entity;
        public IEntity formEntity;
        public int expiration = 20;
        private final CustomData blockEntityData;
        private final Map<ItemDisplayContext, Object> simulationOwners = new EnumMap<>(ItemDisplayContext.class);

        public Item(ModelBlockEntity entity)
        {
            this(entity, CustomData.EMPTY);
        }

        public Item(ModelBlockEntity entity, CustomData blockEntityData)
        {
            this.entity = entity;
            this.formEntity = new StubEntity();
            this.blockEntityData = blockEntityData == null ? CustomData.EMPTY : blockEntityData;
        }

        public boolean matches(CustomData blockEntityData)
        {
            return this.blockEntityData.equals(blockEntityData == null ? CustomData.EMPTY : blockEntityData);
        }

        public Object getSimulationOwner(ItemDisplayContext mode)
        {
            ItemDisplayContext key = mode == null ? ItemDisplayContext.NONE : mode;

            return this.simulationOwners.computeIfAbsent(key, (ignored) -> new Object());
        }

        public void syncWorld(Level world)
        {
            this.formEntity.setWorld(world);

            if (world != null && this.entity.getLevel() != world)
            {
                this.entity.setLevel(world);
            }
        }

        public void release()
        {
            Set<Form> released = Collections.newSetFromMap(new IdentityHashMap<>());
            ModelProperties properties = this.entity.getProperties();

            release(released, properties.getForm());
            release(released, properties.getFormThirdPerson());
            release(released, properties.getFormInventory());
            release(released, properties.getFormFirstPerson());
            this.simulationOwners.clear();
        }

        private static void release(Set<Form> released, Form form)
        {
            if (form != null && released.add(form))
            {
                FormUtilsClient.release(form);
            }
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

    private static void applyBlockEntityData(ModelProperties properties, CustomData blockEntityData)
    {
        if (blockEntityData == null || blockEntityData.isEmpty())
        {
            return;
        }

        BaseType data = DataStorageUtils.readFromNbtCompound(blockEntityData.copyTag(), "Properties");

        if (data instanceof MapType mapType)
        {
            properties.fromData(mapType);
        }
    }
}
