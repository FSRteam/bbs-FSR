package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Emits only the bones set to one glint mode, colored by their glint settings, so the CPU
 * render path can draw the effect as a second pass over the model.
 *
 * <p>The VAO path gets a draw call per bone and can simply set uniforms per bone, but this
 * path builds the whole model into a single buffer. Splitting by mode lets each pass carry
 * the one mode its shader run needs.</p>
 */
public class CubicGlintCubeRenderer extends CubicCubeRenderer
{
    private final int mode;
    private final Set<ModelGroup> restrictTo;
    private final Color previousColor = new Color();
    private final Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix());
    private final Matrix3f viewNormal;
    private final Vector3f viewPosition = new Vector3f();
    private final Vector3f transformedNormal = new Vector3f();

    public CubicGlintCubeRenderer(int light, int overlay, ShapeKeys shapeKeys, int mode, Set<ModelGroup> restrictTo)
    {
        super(light, overlay, null, shapeKeys);

        this.mode = mode;
        this.restrictTo = restrictTo;

        Matrix3f normal = new Matrix3f(this.view);

        this.viewNormal = Math.abs(normal.determinant()) > 0.00001F
            ? normal.invert().transpose()
            : new Matrix3f();
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        if (group.glintMode != this.mode || (this.restrictTo != null && !this.restrictTo.contains(group)))
        {
            return false;
        }

        this.setColor(group.glintColor.r, group.glintColor.g, group.glintColor.b, group.glintColor.a);

        /* Vertex color is built as this color times the bone's own tint, which would let a
         * tinted bone wash out its glint. Neutralize the tint for the duration of this
         * bone's vertices so the glint color stands on its own, then put it back — the
         * model renders serially, so nothing else observes the swap. */
        this.previousColor.copy(group.color);
        group.color.set(1F, 1F, 1F, 1F);
        stack.pushPose();

        try
        {
            /* Immediate vertices are baked through the current pose stack before they reach
             * the shader. Apply the glint layer here, while the stack is still in this
             * bone's local space, instead of applying it later to camera-relative vertices. */
            MatrixStackUtils.applyTransform(stack, group.glintTransform);

            return super.renderGroup(builder, stack, group, model);
        }
        finally
        {
            stack.popPose();
            group.color.copy(this.previousColor);
        }
    }

    @Override
    protected float getVertexAlpha(float x, float y, float z, Vector3f normal)
    {
        if (this.mode != Form.GLINT_EDGE)
        {
            return 1F;
        }

        this.view.transformPosition(x, y, z, this.viewPosition);
        this.viewNormal.transform(this.transformedNormal.set(normal));

        if (this.viewPosition.lengthSquared() <= 0.00001F || this.transformedNormal.lengthSquared() <= 0.00001F)
        {
            return 1F;
        }

        this.viewPosition.normalize().negate();
        this.transformedNormal.normalize();

        float facing = Math.abs(this.transformedNormal.dot(this.viewPosition));

        return (float) Math.pow(1F - facing, 2.5F);
    }
}
