package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
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

    private Function<VertexConsumer, VertexConsumer> substitute;
    private boolean ui;

    public static boolean drawLayer(RenderType layer, MeshData meshData)
    {
        if (runnables != null)
        {
            runnables.accept(layer);
        }

        Vector3f origin = FormTranslucentQueue.getSortOrigin();

        if (origin == null || !FormTranslucentQueue.isActive() || !isDeferrableTranslucent(layer))
        {
            return false;
        }

        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(meshData);
        VertexBuffer.unbind();
        FormTranslucentQueue.add(new FormTranslucentQueue.RenderLayerCommand(
            layer, buffer, new Matrix4f(RenderSystem.getModelViewMatrix()), new Vector3f(origin), false));
        return true;
    }

    public static void hijackVertexFormat(Consumer<RenderType> runnable)
    {
        runnables = runnable;
    }

    public static void clearRunnables()
    {
        runnables = null;
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
