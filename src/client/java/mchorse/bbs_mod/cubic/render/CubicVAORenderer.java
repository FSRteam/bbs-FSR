package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.Map;
import java.util.function.Function;

public class CubicVAORenderer extends CubicCubeRenderer
{
    private ShaderInstance program;
    private ModelInstance model;
    private Function<String, Link> textureResolver;

    public CubicVAORenderer(ShaderInstance program, ModelInstance model, int light, int overlay, StencilMap stencilMap, ShapeKeys shapeKeys, Function<String, Link> textureResolver)
    {
        super(light, overlay, stencilMap, shapeKeys);

        this.program = program;
        this.model = model;
        this.textureResolver = textureResolver;
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        Map<String, ModelVAO> groupVaos = this.model.getVaos().get(group);

        if (groupVaos == null || groupVaos.isEmpty() || !group.visible)
        {
            return false;
        }

        float r = this.r * group.color.r;
        float g = this.g * group.color.g;
        float b = this.b * group.color.b;
        float a = this.a * group.color.a;
        int light = this.light;

        if (this.stencilMap != null)
        {
            light = this.stencilMap.increment ? group.index : 0;
        }
        else
        {
            int u = (int) Lerps.lerp(light & '\uffff', LightTexture.FULL_BLOCK, MathUtils.clamp(group.lighting, 0F, 1F));
            int v = light >> 16 & '\uffff';

            light = u | v << 16;
        }

        /* One draw per material; bind that material's resolved texture before each. */
        for (Map.Entry<String, ModelVAO> entry : groupVaos.entrySet())
        {
            if (this.textureResolver != null)
            {
                Link link = this.textureResolver.apply(entry.getKey());

                if (link != null)
                {
                    BBSModClient.getTextures().bindTexture(link);
                }
            }

            ModelVAORenderer.render(this.program, entry.getValue(), stack, r, g, b, a, light, this.overlay);
        }

        return false;
    }
}