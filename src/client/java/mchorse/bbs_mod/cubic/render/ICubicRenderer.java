package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.pose.Transform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;

public interface ICubicRenderer
{
    public static void offsetGroup(PoseStack stack, ModelGroup group)
    {
        Vector3f offset = group.offset;

        if (offset != null)
        {
            stack.translate(offset.x, offset.y, offset.z);
        }
    }

    public static void translateGroup(PoseStack stack, ModelGroup group)
    {
        Vector3f translate = group.current.translate;
        Vector3f pivot = group.initial.translate;

        stack.translate(-(translate.x - pivot.x) / 16F, (translate.y - pivot.y) / 16F, (translate.z - pivot.z) / 16F);
    }

    public static void moveToGroupPivot(PoseStack stack, ModelGroup group)
    {
        Vector3f pivot = group.initial.translate;

        stack.translate(pivot.x / 16F, pivot.y / 16F, pivot.z / 16F);
    }

    public static void rotateGroup(PoseStack stack, ModelGroup group)
    {
        if (group.orient != null)
        {
            stack.mulPose(group.orient);

            return;
        }

        if (group.current.rotationMode == Transform.RotationMode.QUATERNION)
        {
            stack.mulPose(group.current.quat);

            return;
        }

        Vector3f rotate = group.current.rotate;

        if (rotate.x != 0F || rotate.y != 0F || rotate.z != 0F)
        {
            stack.mulPose(Matrices.toLocalRotationZYXDegrees(rotate));
        }
    }

    public static void scaleGroup(PoseStack stack, ModelGroup group)
    {
        Vector3f scale = group.current.scale;

        MatrixStackUtils.scaleStack(stack, scale.x, scale.y, scale.z);
    }

    public static void moveBackFromGroupPivot(PoseStack stack, ModelGroup group)
    {
        Vector3f pivot = group.initial.translate;

        stack.translate(-pivot.x / 16F, -pivot.y / 16F, -pivot.z / 16F);
    }

    public default void applyGroupTransformations(PoseStack stack, ModelGroup group)
    {
        offsetGroup(stack, group);
        translateGroup(stack, group);
        moveToGroupPivot(stack, group);
        rotateGroup(stack, group);
        scaleGroup(stack, group);
        moveBackFromGroupPivot(stack, group);
    }

    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model);
}
