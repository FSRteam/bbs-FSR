package mchorse.bbs_mod.client.renderer.item;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;

public final class BBSItemRenderers
{
    private static final ModelBlockItemRenderer MODEL_BLOCK_RENDERER = new ModelBlockItemRenderer();
    private static final GunItemRenderer GUN_RENDERER = new GunItemRenderer();

    private static BlockEntityWithoutLevelRenderer modelBlockCustomRenderer;
    private static BlockEntityWithoutLevelRenderer gunCustomRenderer;

    private BBSItemRenderers() {}

    public static ModelBlockItemRenderer getModelBlockRenderer()
    {
        return MODEL_BLOCK_RENDERER;
    }

    public static GunItemRenderer getGunRenderer()
    {
        return GUN_RENDERER;
    }

    public static BlockEntityWithoutLevelRenderer getModelBlockCustomRenderer()
    {
        if (modelBlockCustomRenderer == null)
        {
            Minecraft client = Minecraft.getInstance();

            modelBlockCustomRenderer = new ModelBlockItemBEWLR(
                client.getBlockEntityRenderDispatcher(),
                client.getEntityModels(),
                MODEL_BLOCK_RENDERER
            );
        }

        return modelBlockCustomRenderer;
    }

    public static BlockEntityWithoutLevelRenderer getGunCustomRenderer()
    {
        if (gunCustomRenderer == null)
        {
            Minecraft client = Minecraft.getInstance();

            gunCustomRenderer = new GunItemBEWLR(
                client.getBlockEntityRenderDispatcher(),
                client.getEntityModels(),
                GUN_RENDERER
            );
        }

        return gunCustomRenderer;
    }
}
