package mchorse.bbs_mod.utils;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import mchorse.bbs_mod.utils.joml.Vectors;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

public class MatrixStackUtils
{
    private static Matrix3f normal = new Matrix3f();

    private static Matrix4f oldProjection = new Matrix4f();
    private static Matrix4f oldMV = new Matrix4f();

    public static void scaleStack(PoseStack stack, float x, float y, float z)
    {
        stack.last().pose().scale(x, y, z);
        stack.last().normal().scale(x < 0F ? -1F : 1F, y < 0F ? -1F : 1F, z < 0F ? -1F : 1F);
    }

    public static void cacheMatrices()
    {
        /* Cache the global stuff */
        oldProjection.set(RenderSystem.getProjectionMatrix());
        oldMV.set(RenderSystem.getModelViewMatrix());

        Matrix4fStack renderStack = RenderSystem.getModelViewStack();

        renderStack.pushMatrix();
        renderStack.identity();
        RenderSystem.applyModelViewMatrix();
        renderStack.popMatrix();
    }

    public static void restoreMatrices()
    {
        /* Return back to orthographic projection */
        RenderSystem.setProjectionMatrix(oldProjection, VertexSorting.ORTHOGRAPHIC_Z);

        Matrix4fStack renderStack = RenderSystem.getModelViewStack();

        renderStack.pushMatrix();
        renderStack.identity();
        renderStack.mul(oldMV);
        RenderSystem.applyModelViewMatrix();
        renderStack.popMatrix();
    }

    public static void applyTransform(PoseStack stack, Transform transform)
    {
        if (transform.translate.x != 0F || transform.translate.y != 0F || transform.translate.z != 0F)
        {
            stack.translate(transform.translate.x, transform.translate.y, transform.translate.z);
        }

        if (transform.rotate.z != 0F) stack.mulPose(Axis.ZP.rotation(transform.rotate.z));
        if (transform.rotate.y != 0F) stack.mulPose(Axis.YP.rotation(transform.rotate.y));
        if (transform.rotate.x != 0F) stack.mulPose(Axis.XP.rotation(transform.rotate.x));
        if (transform.rotate2.z != 0F) stack.mulPose(Axis.ZP.rotation(transform.rotate2.z));
        if (transform.rotate2.y != 0F) stack.mulPose(Axis.YP.rotation(transform.rotate2.y));
        if (transform.rotate2.x != 0F) stack.mulPose(Axis.XP.rotation(transform.rotate2.x));

        if (transform.scale.x != 1F || transform.scale.y != 1F || transform.scale.z != 1F)
        {
            scaleStack(stack, transform.scale.x, transform.scale.y, transform.scale.z);
        }
    }

    public static void multiply(PoseStack stack, Matrix4f matrix)
    {
        normal.set(matrix);
        normal.getScale(Vectors.TEMP_3F);

        Vectors.TEMP_3F.x = Vectors.TEMP_3F.x == 0F ? 0F : 1F / Vectors.TEMP_3F.x;
        Vectors.TEMP_3F.y = Vectors.TEMP_3F.y == 0F ? 0F : 1F / Vectors.TEMP_3F.y;
        Vectors.TEMP_3F.z = Vectors.TEMP_3F.z == 0F ? 0F : 1F / Vectors.TEMP_3F.z;

        normal.scale(Vectors.TEMP_3F);

        stack.last().pose().mul(matrix);
        stack.last().normal().mul(normal);
    }

    public static void scaleBack(PoseStack matrices)
    {
        Matrix4f position = matrices.last().pose();

        float scaleX = (float) Math.sqrt(position.m00() * position.m00() + position.m10() * position.m10() + position.m20() * position.m20());
        float scaleY = (float) Math.sqrt(position.m01() * position.m01() + position.m11() * position.m11() + position.m21() * position.m21());
        float scaleZ = (float) Math.sqrt(position.m02() * position.m02() + position.m12() * position.m12() + position.m22() * position.m22());

        float max = Math.max(scaleX, Math.max(scaleY, scaleZ));

        position.m00(position.m00() / max);
        position.m10(position.m10() / max);
        position.m20(position.m20() / max);

        position.m01(position.m01() / max);
        position.m11(position.m11() / max);
        position.m21(position.m21() / max);

        position.m02(position.m02() / max);
        position.m12(position.m12() / max);
        position.m22(position.m22() / max);
    }

    public static Matrix4f stripScale(Matrix4f matrix)
    {
        Matrix4f m = new Matrix4f(matrix);

        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
        float sz = (float) Math.sqrt(m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22());

        if (sx != 0F)
        {
            m.m00(m.m00() / sx);
            m.m01(m.m01() / sx);
            m.m02(m.m02() / sx);
        }

        if (sy != 0F)
        {
            m.m10(m.m10() / sy);
            m.m11(m.m11() / sy);
            m.m12(m.m12() / sy);
        }

        if (sz != 0F)
        {
            m.m20(m.m20() / sz);
            m.m21(m.m21() / sz);
            m.m22(m.m22() / sz);
        }

        return m;
    }
}
