package mchorse.bbs_mod.forms.renderers.utils;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.utils.colors.Color;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import org.lwjgl.system.MemoryStack;

public class RecolorVertexSodiumConsumer extends RecolorVertexConsumer implements VertexBufferWriter
{
    public RecolorVertexSodiumConsumer(VertexConsumer consumer, Color color)
    {
        super(consumer, color);

        newColor = color;
    }

    @Override
    public void push(MemoryStack memoryStack, long pointer, int count, VertexFormat vertexFormat)
    {
        VertexBufferWriter writer = VertexBufferWriter.tryOf(this.consumer);

        if (writer != null)
        {
            writer.push(memoryStack, pointer, count, vertexFormat);
        }
    }
}
