package mchorse.bbs_mod.client.renderer.item;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.entity.model.EntityModelLoader;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

public class GunItemBEWLR extends BuiltinModelItemRenderer
{
    private final GunItemRenderer delegate;

    public GunItemBEWLR(BlockEntityRenderDispatcher dispatcher, EntityModelLoader modelLoader, GunItemRenderer delegate)
    {
        super(dispatcher, modelLoader);

        this.delegate = delegate;
    }

    @Override
    public void render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay)
    {
        this.delegate.render(stack, mode, matrices, vertexConsumers, light, overlay);
    }
}
