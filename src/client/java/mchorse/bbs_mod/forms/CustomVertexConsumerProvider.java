package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import mchorse.bbs_mod.forms.renderers.utils.RecolorVertexConsumer;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.MeshData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class CustomVertexConsumerProvider extends MultiBufferSource.BufferSource
{
    private static Consumer<RenderType> runnables;
    private static Function<RenderType, Runnable> layerPreparations;

    private Function<VertexConsumer, VertexConsumer> substitute;
    private boolean ui;

    public static boolean drawLayer(RenderType layer, MeshData meshData)
    {
        Vector3f origin = FormTranslucentQueue.getSortOrigin();

        /* Text layers defer only inside a recorded group (labels), where the group preserves
         * the text-over-background order. */
        boolean textLayer = FormTranslucentQueue.isGroupOpen()
            && layer.format() == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;

        if (origin == null || !FormTranslucentQueue.isActive() || !(textLayer || isDeferrableTranslucent(layer)))
        {
            return false;
        }

        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(meshData);
        VertexBuffer.unbind();
        FormTranslucentQueue.add(new FormTranslucentQueue.RenderLayerCommand(
            layer,
            buffer,
            new Matrix4f(RenderSystem.getModelViewMatrix()),
            new Vector3f(origin),
            captureLayerPreparation(layer)
        ));
        return true;
    }

    public static void prepareLayer(RenderType layer)
    {
        Runnable preparation = captureLayerPreparation(layer);

        if (preparation != null)
        {
            preparation.run();
        }
    }

    public static void hijackVertexFormat(Consumer<RenderType> runnable)
    {
        runnables = runnable;
        layerPreparations = null;
    }

    public static void hijackLayerPreparation(Function<RenderType, Runnable> factory)
    {
        runnables = null;
        layerPreparations = factory;
    }

    public static void clearRunnables()
    {
        runnables = null;
        layerPreparations = null;
    }

    private static Runnable captureLayerPreparation(RenderType layer)
    {
        if (layerPreparations != null)
        {
            return layerPreparations.apply(layer);
        }

        Consumer<RenderType> preparation = runnables;

        return preparation == null ? null : () -> preparation.accept(layer);
    }

    public CustomVertexConsumerProvider(ByteBufferBuilder fallback, SequencedMap<RenderType, ByteBufferBuilder> layers)
    {
        super(fallback, layers);
    }

    public void setSubstitute(Function<VertexConsumer, VertexConsumer> substitute)
    {
        this.substitute = substitute;

        if (this.substitute == null)
        {
            RecolorVertexConsumer.newColor = null;
        }
    }

    public void setUI(boolean ui)
    {
        this.ui = ui;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderLayer)
    {
        VertexConsumer buffer = super.getBuffer(renderLayer);

        if (this.substitute != null)
        {
            VertexConsumer apply = this.substitute.apply(buffer);

            if (apply != null)
            {
                return apply;
            }
        }

        return buffer;
    }

    private static boolean isDeferrableTranslucent(RenderType layer)
    {
        String name = layer.toString();
        return name.contains("translucent") && !name.contains("glint");
    }

    public void draw()
    {
        this.endBatch();

        if (this.ui)
        {
            /* Force back the depth func because it seems like stuff rendered by a vertex
             * consumer is resetting the depth func to GL_LESS, and since this vertex consumer
             * is designed  */
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
        }
    }
}
