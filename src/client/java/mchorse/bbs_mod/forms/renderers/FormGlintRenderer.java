package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.cubic.render.vao.IModelVAO;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Draws the whole-form enchantment glint for forms without a skeleton.
 *
 * <p>Model forms place their glint per bone from the pose, which a skeleton makes possible;
 * everything else gets one setting for the entire form, applied by drawing its geometry a
 * second time through a shader-pack-compatible vanilla render type.</p>
 */
public class FormGlintRenderer
{
    /** Draws the glint over geometry already baked into a VAO. */
    public static void render(Form form, IModelVAO vao, Matrix4f modelView, Matrix3f normalMat, int light, int overlay)
    {
        int mode = form.glintMode.get();

        if (mode == Form.GLINT_OFF || vao == null)
        {
            return;
        }

        Color color = form.glintColor.get();

        FormTranslucentQueue.add(new FormTranslucentQueue.GlintVAOCommand(
            mode, form.glintSpeed.get(), color, form.glintTransform.get(),
            vao, modelView, normalMat, light, overlay));
    }

    /**
     * Draws the glint for a form whose shape is produced by a vanilla renderer.
     *
     * <p>Blocks and items have no geometry of their own to re-draw — vanilla builds it and
     * spreads it over several render types. The callback re-runs that renderer against a
     * buffer source that collects everything into one mesh in the glint shader's format,
     * which then gets drawn as the glint pass. Re-running costs a second geometry build,
     * which is why nothing happens unless the form actually has a glint.</p>
     */
    public static void renderCaptured(Form form, Consumer<MultiBufferSource> renderer)
    {
        int mode = form.glintMode.get();

        if (mode == Form.GLINT_OFF)
        {
            return;
        }

        Color color = form.glintColor.get();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        VertexConsumer tinted = new GlintTintVertexConsumer(builder, color, mode);

        renderer.accept(new GlintCaptureBufferSource(tinted));

        MeshData mesh = builder.build();

        if (mesh == null)
        {
            return;
        }

        /* Captured vanilla geometry already contains the local layer transform. */
        Vector3f origin = FormTranslucentQueue.getSortOrigin();

        renderMesh(form, mesh, origin == null ? null : new Vector3f(origin));
    }

    /** Submit already transformed glint vertices after their base surface. */
    public static void renderMesh(Form form, MeshData mesh, Vector3f origin)
    {
        if (mesh == null || form.glintMode.get() == Form.GLINT_OFF)
        {
            return;
        }

        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());

        if (origin == null)
        {
            origin = modelView.getTranslation(new Vector3f());
        }

        FormTranslucentQueue.add(new FormTranslucentQueue.GlintMeshCommand(
            form.glintMode.get(), form.glintSpeed.get(), form.glintColor.get(),
            !form.glintTransform.get().isDefault(), mesh, modelView, origin));
    }

}
