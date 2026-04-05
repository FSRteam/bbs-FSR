package mchorse.bbs_mod.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class ModelBlockItemBEWLR extends BlockEntityWithoutLevelRenderer
{
    private final ModelBlockItemRenderer delegate;

    public ModelBlockItemBEWLR(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet, ModelBlockItemRenderer delegate)
    {
        super(dispatcher, modelSet);

        this.delegate = delegate;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext mode, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay)
    {
        this.delegate.render(stack, mode, matrices, vertexConsumers, light, overlay);
    }
}
