package mchorse.bbs_mod.cubic.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.Set;
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
    private final Vector3f viewOrigin = new Vector3f();
    private final Vector3f viewPosition = new Vector3f();
    private final Vector3f transformedNormal = new Vector3f();

    public CubicGlintCubeRenderer(int light, int overlay, ShapeKeys shapeKeys, int mode,
        Set<ModelGroup> restrictTo, Vector3f viewOrigin)
    {
        super(light, overlay, null, shapeKeys);

        this.mode = mode;
        this.restrictTo = restrictTo;

        if (viewOrigin != null)
        {
            this.viewOrigin.set(viewOrigin);
        }
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        if (group.glintMode != this.mode || (this.restrictTo != null && !this.restrictTo.contains(group)))
        {
            return false;
        }

        /* Additive energySwirl blending ignores the vertex alpha channel, so fold the
         * glint's opacity into RGB. The alpha channel is then left to the geometric edge
         * mask below instead of the user's slider. */
        this.setColor(
            group.glintColor.r * group.glintColor.a,
            group.glintColor.g * group.glintColor.a,
            group.glintColor.b * group.glintColor.a,
            1F);

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

        /* Position, normal, and view origin are expressed in the same emitted-vertex
         * coordinate space. This remains correct whether that space is group-local (VAO
         * edge pass) or PoseStack-baked (immediate path). */
        this.viewPosition.set(this.viewOrigin).sub(x, y, z);
        this.transformedNormal.set(normal);

        if (this.viewPosition.lengthSquared() <= 0.00001F || this.transformedNormal.lengthSquared() <= 0.00001F)
        {
            return 1F;
        }

        this.viewPosition.normalize();
        this.transformedNormal.normalize();

        float facing = Math.abs(this.transformedNormal.dot(this.viewPosition));

        return (float) Math.pow(1F - facing, 2.5F);
    }
}
