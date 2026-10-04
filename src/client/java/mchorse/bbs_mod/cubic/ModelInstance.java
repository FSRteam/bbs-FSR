package mchorse.bbs_mod.cubic;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelMesh;
import mchorse.bbs_mod.cubic.jem.CemAnimation;
import mchorse.bbs_mod.cubic.model.ArmorSlot;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.cubic.model.View;
import mchorse.bbs_mod.cubic.model.ModelSetupQueue;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.cubic.render.CubicCubeRenderer;
import mchorse.bbs_mod.cubic.render.CubicGlintCubeRenderer;
import mchorse.bbs_mod.cubic.render.CubicMatrixRenderer;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.render.CubicVAOBuilderRenderer;
import mchorse.bbs_mod.cubic.render.CubicVAORenderer;
import mchorse.bbs_mod.cubic.render.GlintRenderState;
import mchorse.bbs_mod.cubic.render.WeldGeometryCache;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.cubic.weld.ModelWeld;
import mchorse.bbs_mod.cubic.weld.WeldBinding;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

public class ModelInstance implements IModelInstance
{
    private static final Quaternionf ROTATE_Y_180 = Axis.YP.rotationDegrees(180F);

    /** The frame the seams and the CPU bake are computed in: the model's root, camera-independent. */
    private static final PoseStack ROOT = new PoseStack();

    public final String id;
    public IModel model;
    public Animations animations;

    /** The live OptiFine CEM program of a .jem model, or null for every other kind. */
    public CemAnimation cemAnimation;

    /** The quirks the loader worked around while reading this model, printed against its name. */
    public final List<String> warnings = new ArrayList<>();

    /** The model's intrinsic texture from its loader; {@link ModelConfig#texture} overrides it when set. */
    public Link baseTexture;

    /**
     * Per-material default textures, loaded from the model's {@code textures/<material>/}
     * folders (or synthesized as a 1x1 swatch for flat-color materials). Keyed by material
     * name; the empty key is the model's default texture. Used as the static fallback for a
     * material when no animation track overrides it - see {@link #getMaterialTexture}.
     */
    public Map<String, Link> materialTextures = new HashMap<>();

    /** Ordered, distinct list of material names present on the model (for the editor and resolution). */
    public List<String> materials = new ArrayList<>();

    /** The model's {@code config.json} as an editable value tree; the instance reads every setting from here. */
    public final ModelConfig config;

    /** Welds resolved against the model (groups/cubes/corners). Built lazily on first render, kept across frames. */
    private List<WeldBinding> weldBindings;

    /** Every group that takes part in a weld, derived from the bindings once. */
    private Set<ModelGroup> weldedGroups;

    /** The baked CPU half of a welded model, keyed by pose — see {@link WeldGeometryCache}. */
    private final WeldGeometryCache weldCache = new WeldGeometryCache();

    /** Whether the VAO bake skipped some groups (shape-keyed meshes) — those render immediate via the hybrid path. */
    private boolean partialVaos;

    public transient ModelForm form;

    /** Per group, the geometry split into one VAO per material name (empty key = default texture). */
    private Map<ModelGroup, Map<String, ModelVAO>> vaos = new HashMap<>();
    private CubicMatrixRenderer matrixRenderer;
    private PoseStack matrixStack = new PoseStack();

    public ModelInstance(String id, IModel model, Animations animations, Link texture)
    {
        this.id = id;
        this.model = model;
        this.animations = animations;
        this.baseTexture = texture;
        this.config = new ModelConfig(id);
    }

    @Override
    public IModel getModel()
    {
        return this.model;
    }

    @Override
    public Pose getSneakingPose()
    {
        return this.config.getSneakingPose();
    }

    @Override
    public Animations getAnimations()
    {
        return this.animations;
    }

    public Map<ModelGroup, Map<String, ModelVAO>> getVaos()
    {
        return this.vaos;
    }

    /** Welds resolved against this model, built once. Empty when the model declares none or isn't cubic. */
    public List<WeldBinding> getWeldBindings()
    {
        if (this.weldBindings == null)
        {
            this.weldBindings = new ArrayList<>();
            this.weldedGroups = new HashSet<>();

            if (this.model instanceof Model model)
            {
                for (ModelWeld weld : this.config.getWelds())
                {
                    WeldBinding binding = WeldBinding.resolve(model, weld);

                    if (binding != null)
                    {
                        this.weldBindings.add(binding);
                        this.weldedGroups.add(binding.sourceGroup);
                        this.weldedGroups.add(binding.targetGroup);
                    }
                }
            }
        }

        return this.weldBindings;
    }

    /**
     * Re-resolve welds after the config's weld list was edited: drop the cached bindings (rebuilt on the
     * next render) and refresh the config's derived caches so the new welds take effect.
     */
    public void invalidateWelds()
    {
        this.weldBindings = null;
        this.weldedGroups = null;
        this.weldCache.invalidate();
        this.config.rebuild();
    }

    /**
     * Resolve a material's static default texture: the per-material texture loaded
     * from {@code textures/<material>/} if present, otherwise the supplied fallback
     * (the form/model default texture). Animation tracks layer on top of this at
     * render time (handled by the caller), so this only covers the non-animated default.
     */
    public Link getMaterialTexture(String material, Link fallback)
    {
        Link link = this.materialTextures.get(material);

        return link != null ? link : fallback;
    }

    public String getAnchor()
    {
        String anchor = this.model.getAnchor();
        String anchorGroup = this.config.anchor.get();

        if (anchorGroup.isEmpty() && !anchor.isEmpty())
        {
            return anchor;
        }

        return anchorGroup;
    }

    public void applyConfig(MapType data)
    {
        if (data == null)
        {
            return;
        }

        this.config.fromData(data);
    }

    /* Config accessors — the instance reads all of these from {@link #config}. */

    public Link getTexture()
    {
        Link texture = this.config.getTexture();

        return texture != null ? texture : this.baseTexture;
    }

    public Vector3f getScale()
    {
        return this.config.scale.get();
    }

    public float getUiScale()
    {
        return this.config.uiScale.get();
    }

    public boolean isProcedural()
    {
        return this.config.procedural.get();
    }

    public boolean isCulling()
    {
        return this.config.culling.get();
    }

    public String getPoseGroup()
    {
        String group = this.config.poseGroup.get();

        return group.isEmpty() ? this.id : group;
    }

    public View getView()
    {
        return this.config.getView();
    }

    public Set<String> getDisabledBones()
    {
        return this.config.disabledBones.get();
    }

    public Map<String, String> getFlippedParts()
    {
        return this.config.getFlippedParts();
    }

    public Map<ArmorType, ArmorSlot> getArmorSlots()
    {
        return this.config.getArmorSlots();
    }

    public List<ArmorSlot> getItemsMain()
    {
        return this.config.getItemsMain();
    }

    public List<ArmorSlot> getItemsOff()
    {
        return this.config.getItemsOff();
    }

    public ArmorSlot getFpMain()
    {
        return this.config.getFpMain();
    }

    public ArmorSlot getFpOffhand()
    {
        return this.config.getFpOffhand();
    }

    public void setup()
    {
        if (this.model instanceof BOBJModel model)
        {
            ModelSetupQueue.add(model::setup);
        }

        /* A welded or shape-keyed model still builds VAOs: only its welded bones and shape-keyed groups render
         * on the immediate (CPU) path, the rest ride their VAOs on the GPU (see {@link #renderHybrid}). */
        if (this.model instanceof Model model)
        {
            boolean bake = !this.config.onCpu.get();

            this.partialVaos = bake && this.hasShapeKeyedGroups(model);

            if (bake)
            {
                ModelSetupQueue.add(() ->
                {
                    CubicRenderer.processRenderModel(new CubicVAOBuilderRenderer(this.vaos), null, new PoseStack(), model);
                });
            }
        }
    }

    /** Whether some group carries shape-keyed meshes — the VAO builder skips those, so the render is hybrid. */
    private boolean hasShapeKeyedGroups(Model model)
    {
        for (ModelGroup group : model.getAllGroups())
        {
            for (ModelMesh mesh : group.meshes)
            {
                if (!mesh.data.isEmpty())
                {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isVAORendered()
    {
        /* A welded or shape-keyed model builds VAOs too, but renders through the hybrid path — external
         * callers (shader choice, etc.) must still treat it as non-VAO, so report false for those. */
        if (!this.getWeldBindings().isEmpty() || this.partialVaos)
        {
            return false;
        }

        return !this.vaos.isEmpty() || this.model instanceof BOBJModel;
    }

    public void delete()
    {
        for (Map<String, ModelVAO> groupVaos : this.vaos.values())
        {
            for (ModelVAO value : groupVaos.values())
            {
                value.delete();
            }
        }

        this.vaos.clear();
        this.weldCache.delete();
    }

    /* Rendering */

    public void fillStencilMap(StencilMap stencilMap, ModelForm form)
    {
        if (this.model instanceof Model model)
        {
            for (ModelGroup group : model.getOrderedGroups())
            {
                stencilMap.addPicking(form, this.getPickingBone(form, group.id));
            }
        }
        else if (this.model instanceof BOBJModel model)
        {
            for (BOBJBone orderedBone : model.getArmature().orderedBones)
            {
                stencilMap.addPicking(form, this.getPickingBone(form, orderedBone.name));
            }
        }
    }

    private String getPickingBone(ModelForm form, String bone)
    {
        if (form != null && form.pickingOverrides.get() instanceof MapType map)
        {
            String string = map.getString(bone);

            if (!string.trim().isEmpty())
            {
                return string;
            }
        }

        return this.config.getPickingOverrides().getOrDefault(bone, bone);
    }

    public void captureMatrices(MatrixCache bones)
    {
        if (this.model instanceof Model model)
        {
            if (this.matrixRenderer == null || this.matrixRenderer.matrices.size() != model.getAllGroupKeys().size())
            {
                this.matrixRenderer = new CubicMatrixRenderer(model);
            }

            CubicMatrixRenderer renderer = this.matrixRenderer;
            PoseStack stack = this.getMatrixStack();

            renderer.reset();
            CubicRenderer.processRenderModel(renderer, null, stack, model);

            for (ModelGroup group : model.getAllGroups())
            {
                Matrix4f matrix = new Matrix4f(renderer.matrices.get(group.index));
                Matrix4f origin = new Matrix4f(renderer.origins.get(group.index));

                matrix.translate(
                    group.initial.translate.x / 16,
                    group.initial.translate.y / 16,
                    group.initial.translate.z / 16
                );
                matrix.rotateY(MathUtils.PI);
                origin.translate(
                    group.initial.translate.x / 16,
                    group.initial.translate.y / 16,
                    group.initial.translate.z / 16
                );
                origin.rotateY(MathUtils.PI);
                bones.putEvaluated(group.id, matrix, origin, evaluatedChannelRotation(group.current, group.orient, true));
            }
        }
        else if (this.model instanceof BOBJModel model)
        {
            model.getArmature().setupMatrices();

            for (BOBJBone orderedBone : model.getArmature().orderedBones)
            {
                Matrix4f matrix = new Matrix4f();
                Matrix4f origin = new Matrix4f();

                matrix.rotateY(MathUtils.PI).mul(orderedBone.mat);
                origin.rotateY(MathUtils.PI).mul(orderedBone.originMat);
                bones.putEvaluated(orderedBone.name, matrix, origin, evaluatedChannelRotation(orderedBone.transform, orderedBone.orient, false));
            }
        }
    }

    private static Vector3f evaluatedChannelRotation(Transform current, Quaternionf orient, boolean degrees)
    {
        if (current.rotationMode == Transform.RotationMode.QUATERNION)
        {
            return null;
        }

        Vector3f radians = degrees
            ? new Vector3f(
                MathUtils.toRad(current.rotate.x),
                MathUtils.toRad(current.rotate.y),
                MathUtils.toRad(current.rotate.z)
            )
            : new Vector3f(current.rotate);

        if (orient != null)
        {
            Quaternionf channels = Matrices.toQuaternionZYXRadians(radians.x, radians.y, radians.z);

            if (Math.abs(channels.dot(orient)) < 0.9999F)
            {
                return null;
            }
        }

        return radians;
    }

    private PoseStack getMatrixStack()
    {
        if (!this.matrixStack.clear())
        {
            this.matrixStack = new PoseStack();
        }
        else
        {
            this.matrixStack.setIdentity();
        }

        return this.matrixStack;
    }

    public void render(PoseStack stack, Supplier<ShaderInstance> program, Color color, int light, int overlay, StencilMap stencilMap, ShapeKeys keys, Function<String, Link> textureResolver)
    {
        ShaderInstance shader = program.get();

        if (this.model instanceof Model model)
        {
            List<WeldBinding> bindings = this.getWeldBindings();

            /* Welds and partially-baked (shape-keyed) models mix VAO and immediate rendering; a partial
             * model whose VAOs aren't baked yet falls through to the plain CPU path below. */
            if (!bindings.isEmpty() || (this.partialVaos && !this.vaos.isEmpty()))
            {
                this.renderHybrid(stack, shader, color, light, overlay, stencilMap, keys, textureResolver, model, bindings);
            }
            else if (this.isVAORendered())
            {
                CubicVAORenderer renderProcessor = new CubicVAORenderer(shader, this, light, overlay, stencilMap, keys, textureResolver);

                renderProcessor.setColor(color.r, color.g, color.b, color.a);
                CubicRenderer.processRenderModel(renderProcessor, null, stack, model);
                renderProcessor.renderGlint();
            }
            else
            {
                CubicCubeRenderer renderProcessor = new CubicCubeRenderer(light, overlay, stencilMap, keys);

                renderProcessor.setColor(color.r, color.g, color.b, color.a);
                RenderSystem.setShader(() -> shader);

                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);

                CubicRenderer.processRenderModel(renderProcessor, builder, stack, model);

                /* The plain CPU path bakes in the caller's frame, so the global model-view applies as is. */
                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());

                this.drawImmediate(builder.buildOrThrow(), null, shader, modelView, null,
                    modelView.transformPosition(stack.last().pose().getTranslation(new Vector3f())),
                    stencilMap, BBSModClient.getTextures().getLastBound(), color.a, false);

                this.renderGlintImmediate(stack, model, light, overlay, stencilMap, keys, null);
            }
        }
        else if (this.model instanceof BOBJModel model)
        {
            List<BOBJModelVAO> vaos = model.getVaos();

            if (!vaos.isEmpty())
            {
                stack.pushPose();
                stack.mulPose(ROTATE_Y_180);

                model.getArmature().setupMatrices();

                /* All meshes of one BOBJ share a single armature, so judge the pose change once per
                 * draw instead of re-walking every bone for every material mesh. */
                Matrix4f[] armatureMatrices = model.getArmature().matrices;
                boolean armatureChanged = this.hasBOBJArmatureChanged(vaos, armatureMatrices);
                /* Lazy shared snapshot: copied once when the first deferred command needs it, then
                 * reused by every other command of this draw — the live matrices array keeps moving
                 * with the next animation frame. */
                Matrix4f[] deferredArmature = null;
                boolean deferredSnapshotTaken = false;
                /* Material -> resolved texture, resolved once per draw (a mesh name is its material). */
                Map<String, Texture> textures = new HashMap<>();
                List<BOBJModelVAO.BatchDraw> batch = new ArrayList<>();
                Texture batchTexture = null;

                for (BOBJModelVAO vao : vaos)
                {
                    Texture texture = this.resolveBOBJTexture(textures, textureResolver, vao.data.mesh.name);

                    vao.updateMesh(stencilMap, armatureMatrices, armatureChanged);
                    Matrix4f modelView = ModelVAORenderer.captureModelView(stack);
                    Matrix3f normalMat = new Matrix3f(stack.last().normal());

                    if (FormTranslucentQueue.needsSplit(shader, stencilMap, texture, color.a))
                    {
                        this.renderBOBJBatch(shader, batchTexture, batch, stencilMap);

                        FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_OPAQUE);
                        vao.render(shader, modelView, normalMat, color.r, color.g, color.b, color.a, stencilMap, light, overlay);
                        FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_SINGLE);

                        if (!deferredSnapshotTaken)
                        {
                            deferredArmature = vao.snapshotArmature();
                            deferredSnapshotTaken = true;
                        }

                        FormTranslucentQueue.add(new FormTranslucentQueue.BOBJCommand(vao,
                            deferredArmature, vao.getUploadCount(), texture, modelView, normalMat,
                            color.r, color.g, color.b, color.a, light, overlay, this.isCulling()));
                    }
                    else if (FormTranslucentQueue.needsWholeDefer(shader, stencilMap, texture, color.a))
                    {
                        this.renderBOBJBatch(shader, batchTexture, batch, stencilMap);

                        ShaderInstance capturedShader = shader;
<<<<<<< HEAD

                        if (!deferredSnapshotTaken)
                        {
                            deferredArmature = vao.snapshotArmature();
                            deferredSnapshotTaken = true;
                        }

=======
                        Matrix4f[] armature = vao.snapshotArmature();
>>>>>>> origin/master
                        int uploadCount = vao.getUploadCount();

                        if (texture != null && texture.hasTranslucency())
                        {
                            FormTranslucentQueue.add(new FormTranslucentQueue.BOBJCommand(vao,
                                () -> capturedShader, FormTranslucentQueue.PASS_TEX_OPAQUE, true,
<<<<<<< HEAD
                                deferredArmature, uploadCount, texture, modelView, normalMat,
                                color.r, color.g, color.b, color.a, light, overlay, this.isCulling()));
                            FormTranslucentQueue.add(new FormTranslucentQueue.BOBJCommand(vao,
                                () -> capturedShader, FormTranslucentQueue.PASS_TEX_TRANSLUCENT, true,
                                deferredArmature, uploadCount, texture, modelView, normalMat,
=======
                                armature, uploadCount, texture, modelView, normalMat,
                                color.r, color.g, color.b, color.a, light, overlay, this.isCulling()));
                            FormTranslucentQueue.add(new FormTranslucentQueue.BOBJCommand(vao,
                                () -> capturedShader, FormTranslucentQueue.PASS_TEX_TRANSLUCENT, true,
                                armature, uploadCount, texture, modelView, normalMat,
>>>>>>> origin/master
                                color.r, color.g, color.b, color.a, light, overlay, this.isCulling()));
                        }
                        else
                        {
                            FormTranslucentQueue.add(new FormTranslucentQueue.BOBJCommand(vao,
                                () -> capturedShader, FormTranslucentQueue.PASS_SINGLE, true,
<<<<<<< HEAD
                                deferredArmature, uploadCount, texture, modelView, normalMat,
=======
                                armature, uploadCount, texture, modelView, normalMat,
>>>>>>> origin/master
                                color.r, color.g, color.b, color.a, light, overlay, this.isCulling()));
                        }
                    }
                    else
                    {
                        /* Same-texture continuation only: an immediate mesh keeps the traversal
                         * order of translucent mixes, while opaque runs could regroup freely. */
                        if (!batch.isEmpty() && texture != batchTexture)
                        {
                            this.renderBOBJBatch(shader, batchTexture, batch, stencilMap);
                        }

                        batchTexture = texture;
                        batch.add(new BOBJModelVAO.BatchDraw(vao, modelView, normalMat,
                            color.r, color.g, color.b, color.a, light, overlay));
                    }
                }

                this.renderBOBJBatch(shader, batchTexture, batch, stencilMap);

                stack.popPose();
            }
        }
    }

    /**
     * Model-level armature change decision for BOBJ rendering. Every mesh of the model was last
     * uploaded from the same shared armature, so the first mesh whose remembered snapshot differs
     * marks the pose changed for all of them — no mesh then skips the skinning its neighbours do.
     */
    private boolean hasBOBJArmatureChanged(List<BOBJModelVAO> vaos, Matrix4f[] armatureMatrices)
    {
        for (BOBJModelVAO vao : vaos)
        {
            if (vao.hasBOBJArmatureChanged(armatureMatrices))
            {
                return true;
            }
        }

        return false;
    }

    private Texture resolveBOBJTexture(Map<String, Texture> textures, Function<String, Link> textureResolver, String material)
    {
        if (textureResolver == null)
        {
            return null;
        }

        if (textures.containsKey(material))
        {
            return textures.get(material);
        }

        Link link = textureResolver.apply(material);
        Texture texture = link == null ? null : BBSModClient.getTextures().getTexture(link);

        textures.put(material, texture);

        return texture;
    }

    /**
     * Flush the pending immediate meshes under one shared shader lifetime. A null texture keeps
     * whatever is bound — matching the old path, which fell back to the last bound texture.
     */
    private void renderBOBJBatch(ShaderInstance shader, Texture texture, List<BOBJModelVAO.BatchDraw> batch, StencilMap stencilMap)
    {
        if (batch.isEmpty())
        {
            return;
        }

        if (texture != null)
        {
            BBSModClient.getTextures().bindTexture(texture);
        }

        BOBJModelVAO.renderBatch(shader, stencilMap, batch);
        batch.clear();
    }

    /**
     * Draws the glint of every bone that has one, as a pass over the model following the
     * model's own render.
     *
     * <p>Runs once per glint mode: this path packs the whole model into one buffer and one
     * draw, so a mode can only be communicated to the shader for the batch as a whole.
     * Modes with no bones cost nothing. Skipped entirely while picking, where an extra
     * draw would corrupt the bone the stencil buffer reports.</p>
     *
     * <p>Scroll speed is likewise per batch rather than per bone here — the first bone of
     * each mode sets it. The VAO path, which is what nearly every model takes, keeps speed
     * per bone; this fallback only runs for models that can't be baked into VAOs.</p>
     *
     * @param restrictTo bones to consider, or {@code null} for the whole model. The hybrid
     *                   path passes the bones that rendered on the CPU, since the rest
     *                   already drew their glint with their VAO.
     */
    private void renderGlintImmediate(PoseStack stack, Model model, int light, int overlay, StencilMap stencilMap, ShapeKeys keys, Set<ModelGroup> restrictTo)
    {
        if (stencilMap != null)
        {
            return;
        }

        this.renderGlintPasses(stack, model, light, overlay, keys, restrictTo);
    }

    private void renderGlintPasses(PoseStack stack, Model model, int light, int overlay, ShapeKeys keys, Set<ModelGroup> restrictTo)
    {
        for (int mode = 1; mode <= 3; mode++)
        {
            for (int transformMode = 0; transformMode < 2; transformMode++)
            {
                boolean transformed = transformMode == 1;
                Set<ModelGroup> batchGroups = new HashSet<>();
                float speed = 0F;
                Color color = null;

                for (ModelGroup group : model.getAllGroups())
                {
                    if (group.visible && group.glintMode == mode
                        && (restrictTo == null || restrictTo.contains(group))
                        && group.glintTransform.isDefault() != transformed)
                    {
                        if (batchGroups.isEmpty())
                        {
                            speed = group.glintSpeed;
                            color = group.glintColor;
                        }

                        batchGroups.add(group);
                    }
                }

                if (batchGroups.isEmpty())
                {
                    continue;
                }

                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
                CubicGlintCubeRenderer processor = new CubicGlintCubeRenderer(light, overlay, keys,
                    mode, batchGroups, GlintRenderState.getViewOrigin(modelView));

                BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);

                CubicRenderer.processRenderModel(processor, builder, stack, model);

                MeshData mesh = builder.build();

                if (mesh != null)
                {
                    Vector3f origin = modelView.transformPosition(stack.last().pose().getTranslation(new Vector3f()));

                    FormTranslucentQueue.add(new FormTranslucentQueue.GlintMeshCommand(
                        mode, speed, color, transformed, mesh, modelView, origin));
                }
            }
        }
    }

    /**
     * Draw the CPU bake. Two-pass translucency when needed: the opaque texels draw now and write
     * depth, the semi-transparent ones replay from a retained vertex buffer when the frame's
     * translucent queue flushes.
     *
     * <p>{@code cached} is the welded-geometry cache entry holding the bake, or null for the owned
     * path. The distinction is the buffer's lifetime: a cached buffer is only BORROWED by the queue —
     * it must stay untouched until the flush, which its owner guarantees by never rebuilding a lent
     * entry within the frame (see {@link WeldGeometryCache}) and by not closing it here. An owned
     * buffer (a throwaway upload of {@code mesh}) is handed over and freed by the flush.</p>
     *
     * <p>{@code mesh} is only read when {@code cached} is null. {@code rootFrame} says which frame
     * that mesh is in: the hybrid bake is in {@link #ROOT}, so an owned fallback buffer must be drawn
     * with the explicit model-view passed in, while the plain CPU path baked straight into the caller's
     * frame — which is exactly what the global model-view already describes — and keeps drawing the
     * mesh directly, as it did before this cache existed.</p>
     */
    private void drawImmediate(MeshData mesh, WeldGeometryCache.Entry cached, ShaderInstance shader,
        Matrix4f modelView, Matrix3f normalMat, Vector3f origin, StencilMap stencilMap, Texture texture,
        float alpha, boolean rootFrame)
    {
        boolean bbsModelShader = shader != null && shader.getUniform("PassMode") != null;
        boolean split = FormTranslucentQueue.needsSplit(shader, stencilMap, texture, alpha);
        boolean whole = !split && FormTranslucentQueue.needsWholeDefer(shader, stencilMap, texture, alpha);
<<<<<<< HEAD
        boolean owned = cached == null;

        /* The plain CPU path baked straight into the caller's frame, which is exactly what the global
         * model-view already describes — so a single direct draw stays exactly as it was, with no GL
         * buffer to create and free. Only the ROOT-frame hybrid bake needs one. */
        if (owned && !rootFrame && !split && !whole)
        {
            drawWithStableModelColor(mesh, shader, bbsModelShader);

=======

        if (!split && !whole)
        {
            drawWithStableModelColor(mesh, shader, bbsModelShader);
>>>>>>> origin/master
            return;
        }

        VertexBuffer buffer;

<<<<<<< HEAD
        if (owned)
=======
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());

        if (split && normalMat != null && shader.getUniform("NormalMat") != null)
>>>>>>> origin/master
        {
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
        }
        else
        {
            buffer = cached.buffer();
            buffer.bind();
        }

        if (!split && !whole)
        {
            /* The ring was full, so this ROOT-frame bake is a throwaway: drawn here, freed here. */
            drawWithStableModelColor(buffer, shader, modelView, bbsModelShader);
            VertexBuffer.unbind();
            buffer.close();

            return;
        }

        if (split)
        {
            if (normalMat != null && shader.getUniform("NormalMat") != null)
            {
                shader.getUniform("NormalMat").set(normalMat);
            }

            FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_OPAQUE);
            drawWithStableModelColor(buffer, shader, modelView, bbsModelShader);
            FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_SINGLE);
        }

<<<<<<< HEAD
        VertexBuffer.unbind();

        if (!owned)
        {
            /* Handed to the queue: the cache must not rebuild this entry again this frame. */
            cached.lentEpoch = BBSRendering.getSceneFrameId();
        }

=======
        if (split)
        {
            FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_OPAQUE);
            drawWithStableModelColor(buffer, shader, modelView, bbsModelShader);
            FormTranslucentQueue.setPassMode(shader, FormTranslucentQueue.PASS_SINGLE);
        }
        VertexBuffer.unbind();

        Vector3f origin = modelView.transformPosition(stack.last().pose().getTranslation(new Vector3f()));
>>>>>>> origin/master
        if (split)
        {
            /* Depth stays on: this is solid geometry, so its semi-transparent texels must occlude
             * the ones behind them inside the same model. */
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer, () -> shader,
                FormTranslucentQueue.PASS_TRANSLUCENT, true, texture, modelView, normalMat,
<<<<<<< HEAD
                origin, this.isCulling(), null, null, owned));
        }
        else if (texture != null && texture.hasTranslucency())
        {
            /* Keep texture-opaque texels as the depth/blend base for faded overlays. Both commands
             * share one buffer, so only the second may free it at the flush. */
=======
                origin, this.isCulling(), null, null, true));
        }
        else if (texture != null && texture.hasTranslucency())
        {
            /* Keep texture-opaque texels as the depth/blend base for faded overlays. */
>>>>>>> origin/master
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer, () -> shader,
                FormTranslucentQueue.PASS_TEX_OPAQUE, true, texture, modelView, normalMat,
                origin, this.isCulling(), null, null, false));
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer, () -> shader,
                FormTranslucentQueue.PASS_TEX_TRANSLUCENT, true, texture, modelView, normalMat,
<<<<<<< HEAD
                origin, this.isCulling(), null, null, owned));
=======
                origin, this.isCulling(), null, null, true));
>>>>>>> origin/master
        }
        else
        {
            FormTranslucentQueue.add(new FormTranslucentQueue.VertexBufferCommand(buffer, () -> shader,
                FormTranslucentQueue.PASS_SINGLE, true, texture, modelView, normalMat,
<<<<<<< HEAD
                origin, this.isCulling(), null, null, owned));
=======
                origin, this.isCulling(), null, null, true));
>>>>>>> origin/master
        }
    }

    /**
     * BBS model vertices already contain the complete form/bone tint. Keep the global shader colour
     * neutral while vanilla's buffer helpers copy it into {@code ColorModulator}; otherwise a UI or
     * glint draw that left a zero channel behind darkens the whole model when its alpha changes.
     */
    private static void drawWithStableModelColor(MeshData mesh, ShaderInstance shader, boolean bbsModelShader)
    {
        float[] previous = bbsModelShader ? RenderSystem.getShaderColor().clone() : null;

        if (bbsModelShader)
        {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }

        try
        {
            BufferUploader.drawWithShader(mesh);
        }
        finally
        {
            if (previous != null)
            {
                RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
            }
        }
    }

    private static void drawWithStableModelColor(VertexBuffer buffer, ShaderInstance shader,
        Matrix4f modelView, boolean bbsModelShader)
    {
        float[] previous = bbsModelShader ? RenderSystem.getShaderColor().clone() : null;

        if (bbsModelShader)
        {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }

        try
        {
            buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
        }
        finally
        {
            if (previous != null)
            {
                RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
            }
        }
    }

    /**
     * First weld pass: capture the rigid world corners of every welded face with no drawing, then build the seams.
     * Runs a dedicated capture-only renderer that only touches welded cubes (and only their welded face's corners),
     * so it's a light matrix walk over the tree rather than a full per-vertex pass.
     */
    private void captureWelds(List<WeldBinding> bindings, PoseStack stack, Model model, int light, int overlay, StencilMap stencilMap, ShapeKeys keys)
    {
        for (WeldBinding binding : bindings)
        {
            for (WeldBinding.Layer layer : binding.layers)
            {
                layer.resetCapture();
            }
        }

        CubicCubeRenderer capture = new CubicCubeRenderer(light, overlay, stencilMap, keys);

        capture.setWelds(bindings);
        capture.setCaptureOnly(true);
        CubicRenderer.processRenderModel(capture, null, stack, model);

        for (WeldBinding binding : bindings)
        {
            for (WeldBinding.Layer layer : binding.layers)
            {
                layer.computeSeam();
            }
        }
    }

    /**
     * Hybrid render: bones with baked VAOs ride the GPU; only actively-bending welded bones and groups
     * with no VAO (shape-keyed meshes, or none baked yet) go through the immediate CPU path, where their
     * cubes deform against the seam or morph. A light capture pass fills the seams first — for picking
     * too, so the stencil matches the deformed geometry.
     *
     * <p>The CPU half is BAKED ONCE and kept on the GPU keyed by everything it depends on ({@link
     * #weldKey}): a hit skips both the capture and the tessellation, so the same buffer serves every
     * pass of the frame and every frame in which nothing moved. The bake is computed in {@link #ROOT}
     * — the model's own frame, camera- and caller-independent — so the caller's stack is folded into
     * the model-view at draw time instead of being baked into the vertices.</p>
     */
    private void renderHybrid(PoseStack stack, ShaderInstance shader, Color color, int light, int overlay, StencilMap stencilMap, ShapeKeys keys, Function<String, Link> textureResolver, Model model, List<WeldBinding> bindings)
    {
        Set<ModelGroup> weldedGroups = this.weldedGroups;

        /* The welded cubes draw from a CPU bake, so — outside picking and the Iris pipeline, which run their own
         * shader state — they go through the BBS model shader; the VAO bones use the same shader so both halves of
         * the model match. */
        boolean explicitWeld = stencilMap == null && !(BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld());
        ShaderInstance drawShader = explicitWeld ? BBSShaders.getModel() : shader;

        /* The bake is a function of the pose and the draw's own inputs: a miss redoes what used to happen every
         * pass — capture the seams, decide which bones bend, tessellate them. */
        long epoch = BBSRendering.getSceneFrameId();
        long key = this.weldKey(model, keys, light, overlay, color, stencilMap);
        WeldGeometryCache.Entry entry = this.weldCache.find(key);
        Set<ModelGroup> cpuGroups;
        BufferBuilder builder = null;

        if (entry == null)
        {
            /* Capture the seams for the visible draw AND for picking: the stencil must match the deformed
             * geometry, or hovering a bent welded bone highlights its un-sealed rest silhouette at the joint. */
            if (!bindings.isEmpty())
            {
                this.captureWelds(bindings, ROOT, model, light, overlay, stencilMap, keys);
            }

            cpuGroups = this.collectCpuGroups(model, bindings, weldedGroups);

            if (!cpuGroups.isEmpty())
            {
                CubicVAORenderer bake = new CubicVAORenderer(drawShader, this, light, overlay, stencilMap, keys, textureResolver);

                bake.setColor(color.r, color.g, color.b, color.a);
                bake.setWelds(bindings);
                bake.setWeldedGroups(weldedGroups);
                bake.setCpuGroups(cpuGroups);
                bake.setHybridPasses(false, true);

                builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                CubicRenderer.processRenderModel(bake, builder, ROOT, model);
            }

            entry = this.weldCache.acquire(epoch);

            if (entry != null)
            {
                entry.key = key;
                entry.cpuGroups = cpuGroups;
                entry.hasGeometry = false;

                if (builder != null)
                {
                    /* Since 1.21.1 an empty buffer builds to null, so the built result decides
                     * whether this entry has any geometry at all. */
                    MeshData baked = builder.build();

                    if (baked != null)
                    {
                        entry.hasGeometry = true;

                        VertexBuffer buffer = entry.buffer();

                        buffer.bind();
                        buffer.upload(baked);
                        VertexBuffer.unbind();
                    }

                    builder = null;
                }

                /* Marked valid even with no geometry: it stops every later pass from re-walking the
                 * tree for a pose that bakes nothing. */
                entry.valid = true;
            }
        }
        else
        {
            cpuGroups = entry.cpuGroups;
        }

        boolean cpuGeometry = !cpuGroups.isEmpty();

        /* Past the ring's cap the caller owns its bake the old way. Built here (rather than with
         * buildOrThrow()) so a bake that emitted nothing is a miss, not an exception. */
        MeshData owned = null;

        if (entry == null && builder != null)
        {
            owned = builder.build();
        }

        /* The VAO bones ride the GPU every pass, in the caller's frame; the CPU set is the bake's, so the two
         * halves always agree on which bones are which — even when the cache serves a bake whose seams have
         * since been re-captured for another pose. */
        CubicVAORenderer renderProcessor = new CubicVAORenderer(drawShader, this, light, overlay, stencilMap, keys, textureResolver);

        renderProcessor.setColor(color.r, color.g, color.b, color.a);
        renderProcessor.setWelds(bindings);
        renderProcessor.setWeldedGroups(weldedGroups);
        renderProcessor.setCpuGroups(cpuGroups);
        renderProcessor.setHybridPasses(true, false);

        RenderSystem.setShader(() -> drawShader);

        /* The CPU path doesn't switch textures per material — it draws with whatever's bound. The VAO bones rebind
         * per material as they draw, so remember the caller's default texture and restore it for the CPU draw
         * (matches the old all-CPU path, which drew the welded cubes with that same default). */
        int defaultTexture = RenderSystem.getShaderTexture(0);

        CubicRenderer.processRenderModel(renderProcessor, null, stack, model);

        boolean hasGeometry = entry != null ? entry.hasGeometry : owned != null;

        if (hasGeometry)
        {
            RenderSystem.setShaderTexture(0, defaultTexture);

            /* Root-frame geometry drawn in the caller's frame: the caller's stack goes into the model-view, and
             * its normal matrix takes the baked normals into the same space the VAO path's NormalMat*Normal lands
             * in. The two products — (modelView * stack) * v here and modelView * (stack * v) before — are the
             * same transform. */
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose());
            Matrix3f normalMat = new Matrix3f(stack.last().normal());
            Uniform normalUniform = drawShader.getUniform("NormalMat");

            if (normalUniform != null)
            {
                normalUniform.set(normalMat);
            }

            this.drawImmediate(owned, entry, drawShader, modelView, normalMat,
                modelView.transformPosition(new Vector3f()), stencilMap,
                BBSModClient.getTextures().getLastBound(), color.a, true);
        }

        /* Both base paths must finish before either glint path changes RenderType state.
         * In the world, these commands are appended after any deferred base geometry. */
        renderProcessor.renderGlint();

        if (cpuGeometry)
        {
            this.renderGlintImmediate(stack, model, light, overlay, stencilMap, keys, cpuGroups);
        }
    }

    /**
     * What the CPU bake depends on: which bones ride a VAO (the rest of them are what the bake emits),
     * every bone's evaluated transform, colour and lighting (the pose the seams and the deformation come
     * from), the draw's light/overlay/colour, the shape keys, and the picking mode (which bakes stencil ids
     * into the light attribute). Anything here that a pass varies must be in the key, or a pass would be
     * served a bake made for another one.
     */
    private long weldKey(Model model, ShapeKeys keys, int light, int overlay, Color color, StencilMap stencilMap)
    {
        long hash = weldPoseKey(this.vaos.size(), model.getAllGroups());

        return weldDrawKey(hash, light, overlay, color.r, color.g, color.b, color.a,
            stencilMap == null ? 0 : (stencilMap.increment ? 2 : 1),
            keys == null ? 0 : keys.shapeKeys.hashCode());
    }

    /**
     * The pose half of the weld key: everything about the bones that the bake's vertices, seams and
     * normals are computed from. Kept apart from {@link #weldKey} — and free of any {@link ModelInstance}
     * state beyond the VAO count — so the weld-cache regression can build its own bones and prove that
     * dropping any single component changes the key. A key that lost one would keep serving geometry
     * baked for the previous pose, which is this cache's most dangerous silent failure.
     *
     * @param vaoCount how many bones ride a VAO. Which bones ride one decides which bones the CPU bake
     *                 must emit, and the VAO bake lands on a later tick than the first frames of a
     *                 spawn — so it is an input of the bake like any other. The map only ever grows, in
     *                 one atomic setup task, so its size is an exact generation counter.
     */
    static long weldPoseKey(int vaoCount, Collection<ModelGroup> groups)
    {
        long hash = 1125899906842597L;

        hash = hash * 31 + vaoCount;

        for (ModelGroup group : groups)
        {
            hash = hash * 31 + (group.visible ? 1 : 0);
            hash = hash * 31 + poseHash(group.current);
            hash = hash * 31 + poseHash(group.initial);
            hash = hash * 31 + (group.orient == null ? 0 : group.orient.hashCode());
            hash = hash * 31 + (group.offset == null ? 0 : group.offset.hashCode());
            hash = hash * 31 + group.color.getARGBColor();
            hash = hash * 31 + Float.floatToIntBits(group.lighting);
        }

        return hash;
    }

    /**
     * The draw-input half of the weld key, kept apart so the weld-cache regression can prove that
     * changing any single input changes the key — a key that dropped one component would silently
     * serve one pass the buffer baked for another.
     */
    static long weldDrawKey(long hash, int light, int overlay, float r, float g, float b, float a, int stencilMode, int shapeKeysHash)
    {
        hash = hash * 31 + light;
        hash = hash * 31 + overlay;
        hash = hash * 31 + Float.floatToIntBits(r);
        hash = hash * 31 + Float.floatToIntBits(g);
        hash = hash * 31 + Float.floatToIntBits(b);
        hash = hash * 31 + Float.floatToIntBits(a);
        hash = hash * 31 + stencilMode;
        hash = hash * 31 + shapeKeysHash;

        return hash;
    }

    /**
     * Pose hash of one bone: changes whenever any stored component changes, with no allocation. Upstream
     * calls {@code Transform.contentHash()} (added by an unrelated upstream commit, {@code 1826f4431});
     * FSR's {@link Transform} has no such method and the file lies outside this batch's write scope, so
     * the same field mixing is inlined here. {@code initial} is included on top of upstream's set: it is
     * the group's pivot and it enters the transform, and an included field can only cost a cache miss,
     * never serve a stale bake.
     */
    private static int poseHash(Transform transform)
    {
        int hash = transform.translate.hashCode();

        hash = 31 * hash + transform.scale.hashCode();
        hash = 31 * hash + transform.rotate.hashCode();
        hash = 31 * hash + transform.quat.hashCode();
        hash = 31 * hash + transform.rotationMode.ordinal();

        return hash;
    }

    /** Bones the immediate path will emit: visible bending welded bones, and visible bones with geometry but no VAO. */
    private Set<ModelGroup> collectCpuGroups(Model model, List<WeldBinding> bindings, Set<ModelGroup> weldedGroups)
    {
        Set<ModelGroup> groups = new HashSet<>();

        for (ModelGroup group : model.getAllGroups())
        {
            if (!group.visible || (group.cubes.isEmpty() && group.meshes.isEmpty()))
            {
                continue;
            }

            Map<String, ModelVAO> groupVaos = this.vaos.get(group);

            if ((weldedGroups.contains(group) && WeldBinding.hasActiveSeam(bindings, group)) || groupVaos == null || groupVaos.isEmpty())
            {
                groups.add(group);
            }
        }

        return groups;
    }
}
