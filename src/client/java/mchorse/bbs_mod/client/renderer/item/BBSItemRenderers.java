package mchorse.bbs_mod.client.renderer.item;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;

public final class BBSItemRenderers
{
    private static final ModelBlockItemRenderer MODEL_BLOCK_RENDERER = new ModelBlockItemRenderer();
    private static final GunItemRenderer GUN_RENDERER = new GunItemRenderer();

    private static BuiltinModelItemRenderer modelBlockBuiltinRenderer;
    private static BuiltinModelItemRenderer gunBuiltinRenderer;

    private BBSItemRenderers() {}

    public static ModelBlockItemRenderer getModelBlockRenderer()
    {
        return MODEL_BLOCK_RENDERER;
    }

    public static GunItemRenderer getGunRenderer()
    {
        return GUN_RENDERER;
    }

    public static BuiltinModelItemRenderer getModelBlockBuiltinRenderer()
    {
        if (modelBlockBuiltinRenderer == null)
        {
            MinecraftClient client = MinecraftClient.getInstance();

            modelBlockBuiltinRenderer = new ModelBlockItemBEWLR(
                client.getBlockEntityRenderDispatcher(),
                client.getEntityModelLoader(),
                MODEL_BLOCK_RENDERER
            );
        }

        return modelBlockBuiltinRenderer;
    }

    public static BuiltinModelItemRenderer getGunBuiltinRenderer()
    {
        if (gunBuiltinRenderer == null)
        {
            MinecraftClient client = MinecraftClient.getInstance();

            gunBuiltinRenderer = new GunItemBEWLR(
                client.getBlockEntityRenderDispatcher(),
                client.getEntityModelLoader(),
                GUN_RENDERER
            );
        }

        return gunBuiltinRenderer;
    }
}
