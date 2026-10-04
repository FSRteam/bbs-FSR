package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import mchorse.bbs_mod.client.BBSRendering;
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
    /** Builder currently being ended; consumed by the RenderType mixin during layer.draw(). */
    private static final ThreadLocal<BufferBuilder> endingBuilder = new ThreadLocal<>();

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
        BufferBuilder source = endingBuilder.get();
        /* RenderType.draw() can also be called directly, outside this provider's
         * endBatch override. In that case preserve the actual Iris flag instead
         * of restoring a guessed false value after the upload. */
        boolean previousLayout = source == null
            ? BBSRendering.captureIrisVertexLayout()
            : BBSRendering.beginIrisBufferUpload(source);
        boolean extendedLayout = BBSRendering.captureIrisVertexLayout();

        try
        {
            buffer.bind();
            buffer.upload(meshData);
            VertexBuffer.unbind();
        }
        finally
        {
            BBSRendering.endIrisBufferUpload(previousLayout);
        }

        FormTranslucentQueue.add(new FormTranslucentQueue.RenderLayerCommand(
            layer,
            buffer,
            new Matrix4f(RenderSystem.getModelViewMatrix()),
            new Vector3f(origin),
            captureLayerPreparation(layer),
            extendedLayout
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

    /**
     * Mirrors BufferSource.endBatch(RenderType), retaining the original BufferBuilder so
     * Iris can distinguish a plain builder from an Iris-extended one at upload time.
     */
    @Override
    public void endBatch(RenderType layer)
    {
        BufferBuilder builder = this.startedBuilders.remove(layer);

        if (builder == null)
        {
            return;
        }

        MeshData meshData = builder.build();

        if (meshData != null)
        {
            if (layer.sortOnUpload())
            {
                ByteBufferBuilder allocator = this.fixedBuffers.getOrDefault(layer, this.sharedBuffer);
                meshData.sortQuads(allocator, RenderSystem.getVertexSorting());
            }

            endingBuilder.set(builder);

            try
            {
                layer.draw(meshData);
            }
            finally
            {
                endingBuilder.remove();
            }
        }

        if (layer.equals(this.lastSharedType))
        {
            this.lastSharedType = null;
        }
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
