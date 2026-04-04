package mchorse.bbs_mod.items;

import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public class ModelBlockItem extends BlockItem
{
    public ModelBlockItem(Block block, Settings settings)
    {
        super(block, settings);
    }

    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            @Override
            public net.minecraft.client.render.item.BuiltinModelItemRenderer getCustomRenderer()
            {
                return BBSItemRenderers.getModelBlockBuiltinRenderer();
            }
        });
    }
}
