package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

public class CubicAxisRenderer implements ICubicRenderer
{
    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        stack.pushPose();
        stack.translate(group.initial.translate.x / 16, group.initial.translate.y / 16, group.initial.translate.z / 16);

        Matrix4f matrix = stack.last().pose();
        float f = 0.1F;

        builder.addVertex(matrix, 0, 0, 0).setColor(1, 0, 0, 1);
        builder.addVertex(matrix, f, 0, 0).setColor(1, 0, 0, 1);

        builder.addVertex(matrix, 0, 0, 0).setColor(0, 1, 0, 1);
        builder.addVertex(matrix, 0, f, 0).setColor(0, 1, 0, 1);

        builder.addVertex(matrix, 0, 0, 0).setColor(0, 0, 1, 1);
        builder.addVertex(matrix, 0, 0, f).setColor(0, 0, 1, 1);

        stack.popPose();

        return false;
    }
}
