package mchorse.bbs_mod.items;

import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
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

    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return BBSItemRenderers.getModelBlockBuiltinRenderer();
            }
        });
    }
}
