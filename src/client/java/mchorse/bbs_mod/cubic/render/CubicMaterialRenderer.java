package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.utils.FormMaterialLevels;
import mchorse.bbs_mod.forms.renderers.utils.FormOverlay;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A single CPU walk, with separate geometry for each bone/material appearance. */
public class CubicMaterialRenderer extends CubicCubeRenderer implements AutoCloseable
{
    public record Key(ModelGroup group, String material) {}

    public record Batch(Key key, WeldGeometryCache.Entry geometry, float alpha) {}

    private static class Builder implements AutoCloseable
    {
        private final ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
        private final BufferBuilder vertices = new BufferBuilder(this.bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        private final float alpha;

        private Builder(float alpha)
        {
            this.alpha = alpha;
        }

        @Override
        public void close()
        {
            this.bytes.close();
        }
    }

    private final ModelForm form;
    private final Set<ModelGroup> groups;
    private final Map<Key, Builder> builders = new LinkedHashMap<>();
    private final Color baseColor = new Color();
    private final int baseLight;
    private final int baseOverlay;

    public CubicMaterialRenderer(ModelForm form, Set<ModelGroup> groups, Color color, int light, int overlay, StencilMap stencilMap, ShapeKeys keys)
    {
        super(light, overlay, stencilMap, keys);
        this.form = form;
        this.groups = groups;
        this.baseColor.copy(color);
        this.baseLight = light;
        this.baseOverlay = overlay;
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        return this.groups.contains(group) && super.renderGroup(builder, stack, group, model);
    }

    private BufferBuilder builder(ModelGroup group, String material)
    {
        if (!FormMaterialLevels.materialVisible(this.form, material)) return null;

        this.setColor(this.baseColor.r, this.baseColor.g, this.baseColor.b, this.baseColor.a);
        this.light = this.baseLight;
        this.overlay = this.baseOverlay;

        if (this.stencilMap == null && this.form != null)
        {
            Color tint = FormMaterialLevels.materialColor(this.form, material);

            if (tint != null)
            {
                this.r *= tint.r;
                this.g *= tint.g;
                this.b *= tint.b;
                this.a *= tint.a;
            }

            int block = (int) Lerps.lerp(this.light & 0xffff, LightTexture.FULL_BLOCK,
                FormMaterialLevels.materialGlow(this.form, material));
            this.light = block | (this.light & 0xffff0000);

            if (this.overlay == OverlayTexture.NO_OVERLAY && FormOverlay.combine(this.form, material, group) != null)
            {
                this.overlay = 0;
            }
        }

        Key key = new Key(group, material);
        Builder builder = this.builders.get(key);

        if (builder == null)
        {
            builder = new Builder(this.a * group.color.a);
            this.builders.put(key, builder);
        }

        return builder.vertices;
    }

    @Override
    protected void renderCube(BufferBuilder ignored, PoseStack stack, ModelGroup group, ModelCube cube)
    {
        BufferBuilder builder = this.builder(group, cube.material);

        if (builder != null) super.renderCube(builder, stack, group, cube);
    }

    @Override
    protected void renderMesh(BufferBuilder ignored, PoseStack stack, Model model, ModelGroup group, ModelMesh mesh)
    {
        BufferBuilder builder = this.builder(group, mesh.material);

        if (builder != null) super.renderMesh(builder, stack, model, group, mesh);
    }

    public List<Batch> upload()
    {
        List<Batch> result = new ArrayList<>();

        try
        {
            for (Map.Entry<Key, Builder> item : this.builders.entrySet())
            {
                MeshData mesh = item.getValue().vertices.build();

                if (mesh == null) continue;

                WeldGeometryCache.Entry geometry = new WeldGeometryCache.Entry();
                VertexBuffer buffer = geometry.buffer();

                try
                {
                    buffer.bind();
                    buffer.upload(mesh);
                    geometry.hasGeometry = true;
                    result.add(new Batch(item.getKey(), geometry, item.getValue().alpha));
                }
                catch (RuntimeException | Error failure)
                {
                    geometry.close();
                    throw failure;
                }
                finally
                {
                    VertexBuffer.unbind();
                }
            }

            return result;
        }
        catch (RuntimeException | Error failure)
        {
            result.forEach((batch) -> batch.geometry().close());
            throw failure;
        }
    }

    @Override
    public void close()
    {
        this.builders.values().forEach(Builder::close);
        this.builders.clear();
    }
}
