package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class CubicMatrixRenderer implements ICubicRenderer
{
    public List<Matrix4f> matrices;
    public List<Matrix4f> origins;

    public CubicMatrixRenderer(Model model)
    {
        this.matrices = new ArrayList<>();
        this.origins = new ArrayList<>();

        for (int i = 0; i < model.getAllGroupKeys().size(); i++)
        {
            this.matrices.add(new Matrix4f());
            this.origins.add(new Matrix4f());
        }
    }

    @Override
    public void applyGroupTransformations(PoseStack stack, ModelGroup group)
    {
        ICubicRenderer.translateGroup(stack, group);

        this.origins.get(group.index).set(stack.last().pose());

        ICubicRenderer.moveToGroupPivot(stack, group);
        ICubicRenderer.rotateGroup(stack, group);
        ICubicRenderer.scaleGroup(stack, group);
        ICubicRenderer.moveBackFromGroupPivot(stack, group);
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        this.matrices.get(group.index).set(stack.last().pose());

        return false;
    }
}