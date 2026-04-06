package mchorse.bbs_mod.items;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public class ModelBlockItem extends BlockItem
{
    public ModelBlockItem(Block block, Item.Properties settings)
    {
        super(block, settings);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return resolveRenderer("getModelBlockCustomRenderer");
            }
        });
    }

    private BlockEntityWithoutLevelRenderer resolveRenderer(String methodName)
    {
        try
        {
            Class<?> renderersClass = Class.forName("mchorse.bbs_mod.client.renderer.item.BBSItemRenderers");
            Object renderer = renderersClass.getMethod(methodName).invoke(null);

            if (renderer instanceof BlockEntityWithoutLevelRenderer typedRenderer)
            {
                return typedRenderer;
            }
        }
        catch (Exception e)
        {}

        return null;
    }
}
