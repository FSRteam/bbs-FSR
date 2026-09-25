package mchorse.bbs_mod.cubic.render;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.cubic.weld.WeldBinding;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Lerps;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class CubicVAORenderer extends CubicCubeRenderer
{
    private ShaderInstance program;
    private ModelInstance model;
    private Function<String, Link> textureResolver;
    private final List<FormTranslucentQueue.DrawCommand> glintCommands = new ArrayList<>();
    private final List<ImmediateDraw> immediateDraws = new ArrayList<>();
    private final Map<String, Texture> textureCache = new HashMap<>();

    /** Whether any base geometry of this model went into the deferred queue (see renderGlint). */
    private boolean deferredBase;

    /**
     * Non-null puts the renderer in hybrid mode (a welded model): these groups — and any group with no baked VAO —
     * fall through to the CPU immediate path so their welded cubes can deform against a live neighbour, while every
     * other group still rides its VAO on the GPU. Null keeps the plain all-VAO behaviour for unwelded models.
     */
    private Set<ModelGroup> weldedGroups;

    public CubicVAORenderer(ShaderInstance program, ModelInstance model, int light, int overlay, StencilMap stencilMap, ShapeKeys shapeKeys, Function<String, Link> textureResolver)
    {
        super(light, overlay, stencilMap, shapeKeys);

        this.program = program;
        this.model = model;
        this.textureResolver = textureResolver;
    }

    public void setWeldedGroups(Set<ModelGroup> weldedGroups)
    {
        this.weldedGroups = weldedGroups;
    }

    @Override
    public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
    {
        Map<String, ModelVAO> groupVaos = this.model.getVaos().get(group);

        if (this.weldedGroups != null)
        {
            /* A welded bone tessellates on the CPU only while its seam actually bends — at rest it rides
             * its VAO like everything else. Groups with no VAO (shape-keyed meshes) always render immediate. */
            boolean welded = this.weldedGroups.contains(group) && WeldBinding.hasActiveSeam(this.welds, group);

            if (welded || groupVaos == null || groupVaos.isEmpty())
            {
                return super.renderGroup(builder, stack, group, model);
            }
        }

        if (groupVaos == null || groupVaos.isEmpty() || !group.visible)
        {
            return false;
        }

        float r = this.r * group.color.r;
        float g = this.g * group.color.g;
        float b = this.b * group.color.b;
        float a = this.a * group.color.a;
        int light = this.light;

        if (this.stencilMap != null)
        {
            light = this.stencilMap.increment ? group.index : 0;
        }
        else
        {
            int u = (int) Lerps.lerp(light & '\uffff', LightTexture.FULL_BLOCK, MathUtils.clamp(group.lighting, 0F, 1F));
            int v = light >> 16 & '\uffff';

            light = u | v << 16;
        }

        /* Resolve each material once per model draw. The render pass may visit the same material
         * from many groups, especially in high-face-count multi-texture models. */
        for (Map.Entry<String, ModelVAO> entry : groupVaos.entrySet())
        {
            Texture texture = this.resolveTexture(entry.getKey());

            if (texture == null)
            {
                texture = BBSModClient.getTextures().getLastBound();
            }

            Matrix4f modelView = ModelVAORenderer.captureModelView(stack);
            Matrix3f normalMat = new Matrix3f(stack.last().normal());

            if (FormTranslucentQueue.needsSplit(this.program, this.stencilMap, texture, a))
            {
                this.deferredBase = true;

                if (texture != null)
                {
                    BBSModClient.getTextures().bindTexture(texture);
                }

                FormTranslucentQueue.setPassMode(this.program, FormTranslucentQueue.PASS_OPAQUE);
                ModelVAORenderer.render(this.program, entry.getValue(), modelView, normalMat, r, g, b, a, light, this.overlay);
                FormTranslucentQueue.setPassMode(this.program, FormTranslucentQueue.PASS_SINGLE);
                FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(entry.getValue(), texture,
                    modelView, normalMat, r, g, b, a, light, this.overlay, this.model.isCulling()));
            }
            else if (FormTranslucentQueue.needsWholeDefer(this.program, this.stencilMap, texture, a))
            {
                ShaderInstance program = this.program;

                this.deferredBase = true;

                if (texture != null && texture.hasTranslucency())
                {
                    FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(entry.getValue(),
                        () -> program, FormTranslucentQueue.PASS_TEX_OPAQUE, true, texture, modelView,
                        normalMat, r, g, b, a, light, this.overlay, this.model.isCulling()));
                    FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(entry.getValue(),
                        () -> program, FormTranslucentQueue.PASS_TEX_TRANSLUCENT, true, texture, modelView,
                        normalMat, r, g, b, a, light, this.overlay, this.model.isCulling()));
                }
                else
                {
                    FormTranslucentQueue.add(new FormTranslucentQueue.ModelVAOCommand(entry.getValue(),
                        () -> program, FormTranslucentQueue.PASS_SINGLE, true, texture, modelView,
                        normalMat, r, g, b, a, light, this.overlay, this.model.isCulling()));
                }
            }
            else
            {
                this.immediateDraws.add(new ImmediateDraw(texture,
                    new ModelVAORenderer.BatchDraw(entry.getValue(), modelView, normalMat, r, g, b, a, light, this.overlay),
                    a >= 1F && (texture == null || !texture.hasTranslucency())));
            }
        }

        this.collectGlint(stack, group, model, groupVaos, light);

        return false;
    }

    /**
     * Submit every collected glint only after the model's complete base pass. This keeps
     * RenderType state changes from affecting later bones and places world glint after any
     * deferred translucent base commands.
     *
     * <p>When the base pass drew immediately, the glint must draw immediately too. The queue is
     * only flushed again once the world pass has returned, and the overlay's {@code GL_EQUAL}
     * depth test rejects every fragment as soon as the projection differs from the one the base
     * geometry was drawn with — which reads as the glint vanishing entirely outside the editor
     * preview, where the queue is suspended and everything already draws immediately.</p>
     */
    public void renderGlint()
    {
        this.renderImmediateBatches();

        for (FormTranslucentQueue.DrawCommand command : this.glintCommands)
        {
            if (this.deferredBase)
            {
                FormTranslucentQueue.add(command);
            }
            else
            {
                command.draw();
                command.release();
            }
        }

        this.glintCommands.clear();
        this.deferredBase = false;
    }

    private Texture resolveTexture(String material)
    {
        if (this.textureResolver == null)
        {
            return null;
        }

        if (this.textureCache.containsKey(material))
        {
            return this.textureCache.get(material);
        }

        Link link = this.textureResolver.apply(material);
        Texture texture = link == null ? null : BBSModClient.getTextures().getTexture(link);

        this.textureCache.put(material, texture);

        return texture;
    }

    /**
     * Opaque model geometry can be reordered by texture without changing the visible result. If
     * a UI/immediate path contains translucent entries, retain traversal order and only merge
     * adjacent entries so alpha compositing keeps its historical order.
     */
    private void renderImmediateBatches()
    {
        if (this.immediateDraws.isEmpty())
        {
            return;
        }

        boolean opaque = true;

        for (ImmediateDraw draw : this.immediateDraws)
        {
            if (!draw.opaque())
            {
                opaque = false;
                break;
            }
        }

        if (opaque)
        {
            Map<Texture, List<ModelVAORenderer.BatchDraw>> batches = new LinkedHashMap<>();

            for (ImmediateDraw draw : this.immediateDraws)
            {
                batches.computeIfAbsent(draw.texture(), (key) -> new ArrayList<>()).add(draw.draw());
            }

            for (Map.Entry<Texture, List<ModelVAORenderer.BatchDraw>> entry : batches.entrySet())
            {
                if (entry.getKey() != null)
                {
                    BBSModClient.getTextures().bindTexture(entry.getKey());
                }

                ModelVAORenderer.renderBatch(this.program, entry.getValue());
            }
        }
        else
        {
            Texture currentTexture = null;
            List<ModelVAORenderer.BatchDraw> batch = new ArrayList<>();

            for (ImmediateDraw draw : this.immediateDraws)
            {
                if (!batch.isEmpty() && draw.texture() != currentTexture)
                {
                    this.renderImmediateBatch(currentTexture, batch);
                    batch = new ArrayList<>();
                }

                currentTexture = draw.texture();
                batch.add(draw.draw());
            }

            this.renderImmediateBatch(currentTexture, batch);
        }

        this.immediateDraws.clear();
    }

    private void renderImmediateBatch(Texture texture, List<ModelVAORenderer.BatchDraw> batch)
    {
        if (texture != null)
        {
            BBSModClient.getTextures().bindTexture(texture);
        }

        ModelVAORenderer.renderBatch(this.program, batch);
    }

    private static record ImmediateDraw(Texture texture, ModelVAORenderer.BatchDraw draw, boolean opaque)
    {}

    /**
     * Captures the bone's second pass without changing render state during base rendering.
     *
     * <p>Skipped while picking — the stencil pass encodes bone indices into the buffer,
     * and an extra draw would corrupt which bone a click resolves to.</p>
     */
    private void collectGlint(PoseStack stack, ModelGroup group, Model model, Map<String, ModelVAO> groupVaos, int light)
    {
        if (group.glintMode == 0 || this.stencilMap != null)
        {
            return;
        }

        if (group.glintMode == mchorse.bbs_mod.forms.forms.Form.GLINT_EDGE)
        {
            this.collectEdgeGlint(stack, group, model, light);

            return;
        }

        Matrix4f modelView = ModelVAORenderer.captureModelView(stack);
        Matrix3f normalMat = new Matrix3f(stack.last().normal());

        for (ModelVAO vao : groupVaos.values())
        {
            this.glintCommands.add(new FormTranslucentQueue.GlintVAOCommand(
                group.glintMode, group.glintSpeed, group.glintColor, group.glintTransform,
                vao, modelView, normalMat, light, this.overlay));
        }
    }

    /** Edge intensity is view-dependent, so bake that one cheap CPU pass per enabled bone. */
    private void collectEdgeGlint(PoseStack stack, ModelGroup group, Model model, int light)
    {
        Matrix4f modelView = ModelVAORenderer.captureModelView(stack);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        CubicGlintCubeRenderer renderer = new CubicGlintCubeRenderer(light, this.overlay, this.shapeKeys,
            group.glintMode, null, GlintRenderState.getViewOrigin(modelView));
        PoseStack localStack = new PoseStack();

        renderer.setWelds(this.welds);
        /* The base VAO stores cube/mesh vertices in group-local space and applies the bone
         * matrix in the shader. Generate the edge pass in that same space and draw it with
         * the captured base matrix. Baking the bone matrix on the CPU produced slightly
         * different depth bits, so GL_EQUAL passed only over a direction-dependent arc. */
        renderer.renderGroup(builder, localStack, group, model);

        MeshData mesh = builder.build();

        if (mesh != null)
        {
            Vector3f origin = modelView.getTranslation(new Vector3f());

            this.glintCommands.add(new FormTranslucentQueue.GlintMeshCommand(
                group.glintMode, group.glintSpeed, group.glintColor,
                !group.glintTransform.isDefault(), mesh, modelView, origin));
        }
    }

}
