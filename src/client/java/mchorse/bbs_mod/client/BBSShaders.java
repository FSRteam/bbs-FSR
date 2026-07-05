package mchorse.bbs_mod.client;

import mchorse.bbs_mod.BBSMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.Optional;

public class BBSShaders
{
    private static ShaderInstance model;
    private static ShaderInstance multiLink;
    private static ShaderInstance subtitles;
    private static ShaderInstance selection;

    private static ShaderInstance pickerPreview;
    private static ShaderInstance pickerBillboard;
    private static ShaderInstance pickerBillboardNoShading;
    private static ShaderInstance pickerParticles;
    private static ShaderInstance pickerModels;

    static
    {
        setup();
    }

    public static void setup()
    {
        if (model != null) model.close();
        if (multiLink != null) multiLink.close();
        if (subtitles != null) subtitles.close();
        if (selection != null) selection.close();

        if (pickerPreview != null) pickerPreview.close();
        if (pickerBillboard != null) pickerBillboard.close();
        if (pickerBillboardNoShading != null) pickerBillboardNoShading.close();
        if (pickerParticles != null) pickerParticles.close();
        if (pickerModels != null) pickerModels.close();

        try
        {
            ResourceProvider factory = new ProxyResourceFactory(Minecraft.getInstance().getResourceManager());

            model = new ShaderInstance(factory, "model", DefaultVertexFormat.NEW_ENTITY);
            multiLink = new ShaderInstance(factory, "multilink", DefaultVertexFormat.POSITION_TEX_COLOR);
            subtitles = new ShaderInstance(factory, "subtitles", DefaultVertexFormat.POSITION_TEX_COLOR);
            selection = new ShaderInstance(factory, "selection", DefaultVertexFormat.POSITION_TEX_COLOR);

            pickerPreview = new ShaderInstance(factory, "picker_preview", DefaultVertexFormat.POSITION_TEX_COLOR);
            pickerBillboard = new ShaderInstance(factory, "picker_billboard", DefaultVertexFormat.NEW_ENTITY);
            pickerBillboardNoShading = new ShaderInstance(factory, "picker_billboard_no_shading", DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
            pickerParticles = new ShaderInstance(factory, "picker_particles", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            pickerModels = new ShaderInstance(factory, "picker_models", DefaultVertexFormat.NEW_ENTITY);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public static ShaderInstance getModel()
    {
        return model;
    }

    public static ShaderInstance getMultilinkProgram()
    {
        return multiLink;
    }

    public static ShaderInstance getSubtitlesProgram()
    {
        return subtitles;
    }

    public static ShaderInstance getSelectionProgram()
    {
        return selection;
    }

    public static ShaderInstance getPickerPreviewProgram()
    {
        return pickerPreview;
    }

    public static ShaderInstance getPickerBillboardProgram()
    {
        return pickerBillboard;
    }

    public static ShaderInstance getPickerBillboardNoShadingProgram()
    {
        return pickerBillboardNoShading;
    }

    public static ShaderInstance getPickerParticlesProgram()
    {
        return pickerParticles;
    }

    public static ShaderInstance getPickerModelsProgram()
    {
        return pickerModels;
    }

    private static class ProxyResourceFactory implements ResourceProvider
    {
        private ResourceManager manager;

        public ProxyResourceFactory(ResourceManager manager)
        {
            this.manager = manager;
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation id)
        {
            if (id.getPath().contains("/core/"))
            {
                return this.manager.getResource(ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, id.getPath()));
            }

            return this.manager.getResource(id);
        }
    }
}
