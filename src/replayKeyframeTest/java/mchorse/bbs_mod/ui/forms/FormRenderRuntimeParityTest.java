package mchorse.bbs_mod.ui.forms;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.forms.forms.utils.ParticleSettings;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderSpace;
import mchorse.bbs_mod.forms.renderers.FramebufferFormRenderer;
import mchorse.bbs_mod.forms.renderers.MobRenderContext;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.VanillaBoneHierarchy;
import mchorse.bbs_mod.forms.renderers.VanillaParticleFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCache;
import mchorse.bbs_mod.forms.renderers.utils.MatrixCacheEntry;
import mchorse.bbs_mod.mob.MobPoseApplier;
import mchorse.bbs_mod.mob.MobRig;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform.RotationMode;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Exercises the real matrix walk, mob pose owners and particle parser without a world. */
public final class FormRenderRuntimeParityTest
{
    public static void runAll()
    {
        try
        {
            framebufferMatrices();
            reusedContextAndTracks();
            mobLayers();
            modelOverlayFix();
            particleSettings();
            particlePlacement();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Form render runtime parity failed", exception);
        }

        System.out.println("FormRenderRuntimeParityTest passed");
    }

    private static void modelOverlayFix()
    {
        ModelForm form = new ModelForm();
        PoseTransform base = form.pose.get().getOrCreate("head");
        PoseTransform overlay = form.poseOverlay.get().getOrCreate("head");
        ModelFormRenderer renderer = new ModelFormRenderer(form);

        base.translate.x = 10F;
        base.scale.set(2F, 2F, 2F);
        overlay.translate.x = 4F;
        overlay.scale.set(3F, 3F, 3F);
        overlay.fix = 1E-7F;

        PoseTransform tiny = renderer.getPose().get("head");

        check(tiny.translate.x == 14F && tiny.scale.x == 4F,
            "A near-zero model overlay fix erased its additive pose contribution");

        overlay.fix = 0.5F;
        PoseTransform weighted = renderer.getPose().get("head");

        check(weighted.translate.x == 7F && weighted.scale.x == 2.5F,
            "A regular model overlay fix stopped blending the authored poses");
        check(base.translate.x == 10F && base.scale.x == 2F,
            "Reading a model overlay mutated the saved base pose");
    }

    private static void framebufferMatrices()
    {
        FramebufferForm framebuffer = new FramebufferForm();
        FramebufferFormRenderer renderer = new FramebufferFormRenderer(framebuffer);
        framebuffer.setRenderer(renderer);
        framebuffer.width.set(400);
        framebuffer.height.set(200);
        framebuffer.scale.set(2F);
        framebuffer.transform.get().translate.set(10F, 20F, 30F);
        framebuffer.transform.get().rotate.z = (float) Math.PI / 2F;
        StatefulForm child = new StatefulForm();
        ProbeRenderer probe = new ProbeRenderer(child);
        child.setRenderer(probe);
        BodyPart part = attach(framebuffer, child);
        part.transform.get().translate.set(3F, 6F, 9F);
        part.useTarget.set(true);
        IEntity entity = new StubEntity();
        Object owner = new Object();
        Matrix4f semantic = new Matrix4f().translation(100F, 200F, 300F);
        PoseStack stack = new PoseStack();
        stack.translate(7F, 11F, 13F);
        Matrix4f before = new Matrix4f(stack.last().pose());
        MatrixCache result = new MatrixCache();
        renderer.collectMatrices(entity, owner, semantic, true, true, stack, result, "", 0.25F);
        MatrixCacheEntry entry = result.get(FormUtils.getPath(child));
        check(entry.matrix() != null && !result.has("0"), "Framebuffer child lost its stable form address");
        Matrix4f parent = new Matrix4f(before).mul(framebuffer.transform.get().createMatrix());
        Matrix4f expected = new Matrix4f(parent).translate(8F, 8F, 0F).rotateY(0.3F).scale(2F, 3F, 4F);
        check(expected.equals(entry.matrix(), 1E-5F), "Framebuffer projection changed the child basis or missed aspect/scale");
        check(new Matrix4f(parent).translate(8F, 8F, 0F).equals(entry.origin(), 1E-5F), "Framebuffer origin was not projected onto its plane");
        check(entry.rotationOffset().equals(1F, 2F, 3F) && entry.evaluatedRotation().equals(4F, 5F, 6F),
            "Framebuffer projection discarded rotation sampling metadata");
        Matrix4f expectedSemantic = new Matrix4f(semantic).mul(framebuffer.transform.get().createMatrix())
            .mul(part.transform.get().createMatrix());
        check(probe.owner == owner && probe.entity == entity && probe.targets && probe.collisions
            && expectedSemantic.equals(probe.semantic, 1E-5F), "Framebuffer did not pass through the simulation placement");
        check(child.applies == 1 && child.unapplies == 1 && before.equals(stack.last().pose()),
            "Framebuffer matrix collection leaked state or its parent frame");

        probe.fail = true;
        try
        {
            renderer.collectMatrices(entity, stack, new MatrixCache(), "", 0F);
            throw new AssertionError("Expected child matrix failure");
        }
        catch (IllegalStateException expectedFailure)
        {
            check(child.applies == 2 && child.unapplies == 2 && before.equals(stack.last().pose()),
                "A failing child left applied states or a matrix frame behind");
        }

        Form ordinary = new StatefulForm();
        ordinary.setRenderer(new PlainRenderer(ordinary));
        FramebufferForm nested = new FramebufferForm();
        nested.setRenderer(new FramebufferFormRenderer(nested));
        nested.width.set(100);
        nested.height.set(200);
        nested.scale.set(0.5F);
        BodyPart outer = attach(ordinary, nested);
        Form leaf = new StatefulForm();
        leaf.setRenderer(new PlainRenderer(leaf));
        BodyPart inner = attach(nested, leaf);
        inner.transform.get().translate.set(4F, 8F, 12F);
        MatrixCache nestedResult = new PlainRenderer(ordinary).collectMatrices(entity, 0F);
        check(nestedResult.get(FormUtils.getPath(leaf)).matrix().getTranslation(new Vector3f()).equals(1F, 4F, 0F),
            "Nested framebuffer or the ordinary parent's stable matrix path diverged");
        attach(ordinary, new StatefulForm());
        ordinary.parts.moveBodyPart(outer, 1);
        check(new PlainRenderer(ordinary).collectMatrices(entity, 0F).has(FormUtils.getPath(leaf)),
            "Reordering a parent body part changed a nested matrix address");
    }

    private static void reusedContextAndTracks()
    {
        FormRenderingContext context = new FormRenderingContext().set(FormRenderType.ENTITY, null, new PoseStack(), 1, 2, 0F);
        PoseStack owned = context.world;
        owned.translate(1F, 2F, 3F);
        owned.pushPose();
        owned.scale(2F, 2F, 2F);
        context.set(FormRenderType.ENTITY, null, new PoseStack(), 3, 4, 0F);
        check(context.world == owned && owned.clear() && owned.last().pose().equals(new Matrix4f()),
            "Render context did not reuse and reset its world stack");
        PoseStack external = new PoseStack();
        external.translate(5F, 6F, 7F);
        context.semanticWorld(external).set(FormRenderType.ENTITY, null, new PoseStack(), 3, 4, 0F);
        check(context.world == owned && external.last().pose().getTranslation(new Vector3f()).equals(5F, 6F, 7F),
            "Context reset modified an externally supplied semantic frame");

        int overlays = BBSSettings.recordingTransformOverlays.get();
        try
        {
            BBSSettings.recordingTransformOverlays.set(0);
            StatefulForm form = new StatefulForm();
            PlainRenderer renderer = new PlainRenderer(form);
            BBSSettings.recordingTransformOverlays.set(1);
            renderer.createEvaluatedTransform(0F);
            check(form.additionalTransforms.size() == 1, "Transform evaluation did not synchronize added overlay tracks");
            form.additionalTransforms.getFirst().get().translate.x = 3F;
            check(renderer.createTransform().translate.x == 3F, "A newly added transform track did not affect evaluation");
            BBSSettings.recordingTransformOverlays.set(0);
            check(renderer.createTransform().translate.x == 0F && form.additionalTransforms.isEmpty(),
                "Transform evaluation retained a removed overlay track");
        }
        finally
        {
            BBSSettings.recordingTransformOverlays.set(overlays);
        }
    }

    private static void mobLayers() throws ReflectiveOperationException
    {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition parentDefinition = mesh.getRoot().addOrReplaceChild("head",
            CubeListBuilder.create().addBox(0F, 0F, 0F, 1F, 1F, 1F), PartPose.ZERO);
        parentDefinition.addOrReplaceChild("child", CubeListBuilder.create().addBox(2F, 0F, 0F, 1F, 1F, 1F), PartPose.ZERO);
        ModelPart part = LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("head");
        String canonical = "bbs:parity#main/head";
        registerFixtureBone(part, canonical);
        Pose base = new Pose();
        base.getOrCreate(canonical).translate.x = 10F;
        Pose primary = new Pose();
        PoseTransform primaryBone = primary.getOrCreate("head");
        primaryBone.translate.x = 20F;
        primaryBone.fix = 0.5F;
        primaryBone.visible = false;
        primaryBone.rotationMode = RotationMode.QUATERNION;
        primaryBone.quat.rotationY((float) Math.PI / 2F);
        ValuePose fixed = new ValuePose("pose_overlay_1", new Pose());
        fixed.get().getOrCreate("head").translate.x = 2F;
        fixed.get().get("head").fix = 0.5F;
        ValuePose additive = new ValuePose("pose_overlay_2", new Pose());
        additive.get().getOrCreate(canonical).translate.x = 3F;
        Object renderer = new BoneOwner(part);

        try (MobRenderContext context = MobRenderContext.push(renderer, base, primary, List.of(fixed, additive), Color.white()))
        {
            PoseTransform actual = MobRenderContext.getTransform(part);
            check(actual != null && Math.abs(actual.translate.x - 11.5F) < 1E-6F && !actual.visible,
                "Actual mob context did not merge base, primary and additional aliases in order");
            check(Math.abs(actual.getEulerRotation(new Vector3f()).y - (float) Math.PI / 8F) < 1E-5F,
                "Mob overlay merge discarded quaternion rotation");
            try (MobRenderContext nested = MobRenderContext.push(null, new Pose(), new Pose(), Color.white()))
            {
                check(MobRenderContext.getTransform(part) == null, "Nested context reused the caller's bone transforms");
            }
            check(MobRenderContext.getTransform(part) == actual, "Closing a nested context did not restore its caller");

            MobRig rig = new MobRig(new EmptyModel());
            rig.ordered().add(part);
            Field names = MobRig.class.getDeclaredField("names");
            names.setAccessible(true);
            ((Map<ModelPart, String>) names.get(rig)).put(part, "head");
            Pose bridgePose = new Pose();
            bridgePose.getOrCreate("head").copy(actual);
            Map<ModelPart, Transform> saved = new IdentityHashMap<>();
            part.x = 2F;
            MobPoseApplier.apply(rig, bridgePose, saved);
            check(part.skipDraw && part.visible && part.getChild("child").visible && part.x == 13.5F,
                "Mob bridge hid the child subtree or failed to apply the merged pose");
            CountingConsumer consumer = new CountingConsumer();
            part.render(new PoseStack(), consumer, 0, 0);
            check(consumer.vertices == 24, "Hiding the parent's boxes also hid its visible child");
            MobPoseApplier.restore(saved);
            check(!part.skipDraw && part.x == 2F && part.yRot == 0F && saved.isEmpty(), "Mob bridge did not restore the shared model");
            consumer.vertices = 0;
            part.render(new PoseStack(), consumer, 0, 0);
            check(consumer.vertices == 48, "Restored mob model did not draw both bones");
            part.skipDraw = true;
            bridgePose.get("head").visible = true;
            MobPoseApplier.apply(rig, bridgePose, saved);
            MobPoseApplier.restore(saved);
            check(part.skipDraw, "A neutral pose re-enabled a previously hidden model part");
        }
        finally
        {
            Field nodes = VanillaBoneHierarchy.class.getDeclaredField("NODES");
            nodes.setAccessible(true);
            ((Map<?, ?>) nodes.get(null)).keySet().removeIf(key -> ((Reference<?>) key).get() == part);
        }

        check(!MobRenderContext.isActive() && base.get(canonical).translate.x == 10F && primaryBone.translate.x == 20F,
            "Mob context leaked or mutated authored pose layers");
        Pose ordered = MobPoseApplier.merge(base, new Pose(), List.of(additive));
        check(ordered.get(canonical).translate.x == 13F, "The bridge's shared overlay merger diverged");
    }

    /** Register the baked fixture directly because standalone tests do not run ModelPart accessors. */
    private static void registerFixtureBone(ModelPart part, String id) throws ReflectiveOperationException
    {
        Object descriptor = construct("Descriptor", id, "head", null, 0, "head");
        Object structure = construct("Structure", "bbs:parity#main", List.of(descriptor));
        Object bone = construct("Bone", descriptor, part);
        Object hierarchy = construct("Hierarchy", structure, List.of(bone), Map.of(id, bone),
            Map.of("head", bone), Map.of("head", bone));
        Object node = construct("RegisteredNode", hierarchy, bone);
        Method put = VanillaBoneHierarchy.class.getDeclaredMethod("put", ModelPart.class, node.getClass());
        put.setAccessible(true);
        put.invoke(null, part, node);
    }

    private static Object construct(String nestedClass, Object... arguments) throws ReflectiveOperationException
    {
        Constructor<?> constructor = Class.forName(VanillaBoneHierarchy.class.getName() + "$" + nestedClass).getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static void particleSettings() throws ReflectiveOperationException
    {
        Method parse = VanillaParticleFormRenderer.class.getDeclaredMethod("createEffect", ParticleSettings.class, Level.class);
        parse.setAccessible(true);
        ParticleSettings settings = new ParticleSettings();
        settings.particle = ResourceLocation.parse("minecraft:block");
        settings.arguments = " dirt ";
        check(parse.invoke(null, settings, null) instanceof BlockParticleOption block && block.getState().is(Blocks.DIRT),
            "Bare block particle settings failed outside a world");
        settings.particle = ResourceLocation.parse("minecraft:item");
        settings.arguments = "apple";
        check(parse.invoke(null, settings, null) instanceof ItemParticleOption item && item.getItem().is(Items.APPLE),
            "Bare item particle settings failed outside a world");
        settings.arguments = null;
        check(parse.invoke(null, settings, null) == ParticleTypes.FLAME, "Malformed particle settings did not fall back to flame");
        settings.arguments = "invalid::id";
        check(parse.invoke(null, settings, null) == ParticleTypes.FLAME, "Invalid particle identifiers escaped the fallback");
    }

    private static void particlePlacement() throws ReflectiveOperationException
    {
        VanillaParticleFormRenderer renderer = new VanillaParticleFormRenderer(new VanillaParticleForm());
        Method placement = VanillaParticleFormRenderer.class.getDeclaredMethod("updatePlacement", FormRenderingContext.class);
        Field positionField = VanillaParticleFormRenderer.class.getDeclaredField("pos");
        Field velocityField = VanillaParticleFormRenderer.class.getDeclaredField("vel");
        placement.setAccessible(true);
        positionField.setAccessible(true);
        velocityField.setAccessible(true);
        Matrix4f model = new Matrix4f().translation(21F, 30F, -15F).rotateY(0.4F);

        for (FormRenderSpace space : FormRenderSpace.values())
        {
            for (float cameraYaw : new float[] {0F, 0.7F, -1.3F})
            {
                FormRenderingContext context = new FormRenderingContext()
                    .set(FormRenderType.ENTITY, null, new PoseStack(), 0, 0, 0F)
                    .renderSpace(space);
                context.camera.position.set(100D, 50D, -80D);
                context.camera.view.rotationYXZ(cameraYaw, 0.3F, -0.2F);

                if (space == FormRenderSpace.ENTITY_LOCAL)
                {
                    context.world.last().pose().set(model);
                    context.stack.last().pose().rotationX(2F);
                }
                else
                {
                    Matrix4f cameraRelative = new Matrix4f().translation(-100F, -50F, 80F).mul(model);

                    context.stack.last().pose().set(space == FormRenderSpace.UI_LOCAL
                        ? new Matrix4f(context.camera.view).mul(cameraRelative)
                        : cameraRelative);
                }

                placement.invoke(renderer, context);
                Vector3d position = (Vector3d) positionField.get(renderer);
                Vector3f velocity = (Vector3f) velocityField.get(renderer);
                check(position.distance(21D, 30D, -15D) < 0.0001D,
                    "Vanilla particle world origin changed with camera rotation in " + space);
                check(velocity.distance((float) Math.sin(0.4F), 0F, (float) Math.cos(0.4F)) < 0.0001F,
                    "Vanilla particle direction inherited camera rotation in " + space);
            }
        }
    }

    private static BodyPart attach(Form parent, Form child)
    {
        BodyPart part = new BodyPart("");
        part.setForm(child);
        parent.parts.addBodyPart(part);
        return part;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class StatefulForm extends Form
    {
        private StatefulForm() { this.setRenderer(new PlainRenderer(this)); }
        private int applies;
        private int unapplies;
        @Override public void applyStates(float transition) { this.applies++; super.applyStates(transition); }
        @Override public void unapplyStates() { this.unapplies++; super.unapplyStates(); }
    }

    private static final class PlainRenderer extends FormRenderer<Form>
    {
        private PlainRenderer(Form form) { super(form); }
        @Override protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2) {}
    }

    private static final class ProbeRenderer extends FormRenderer<StatefulForm>
    {
        private Object owner;
        private IEntity entity;
        private Matrix4f semantic;
        private boolean targets;
        private boolean collisions;
        private boolean fail;

        private ProbeRenderer(StatefulForm form) { super(form); }
        @Override protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2) {}

        @Override
        protected void collectMatricesWithAppliedStates(IEntity entity, Object owner, Matrix4f semantic,
            boolean targets, boolean collisions, PoseStack stack, MatrixCache cache, String prefix, float transition)
        {
            this.owner = owner;
            this.entity = entity;
            this.semantic = semantic;
            this.targets = targets;
            this.collisions = collisions;
            if (this.fail) throw new IllegalStateException("Expected matrix collection failure");
            Matrix4f origin = new Matrix4f(stack.last().pose()).translate(1F, 2F, 3F);
            cache.put(prefix, new Matrix4f(origin).rotateY(0.3F).scale(2F, 3F, 4F), origin,
                new Vector3f(1F, 2F, 3F), new Vector3f(4F, 5F, 6F));
        }
    }

    private record BoneOwner(ModelPart part) {}

    private static final class EmptyModel extends EntityModel<LivingEntity>
    {
        @Override public void setupAnim(LivingEntity entity, float a, float b, float c, float d, float e) {}
        @Override public void renderToBuffer(PoseStack stack, VertexConsumer consumer, int light, int overlay, int color) {}
    }

    private static final class CountingConsumer implements VertexConsumer
    {
        private int vertices;
        @Override public VertexConsumer addVertex(float x, float y, float z) { this.vertices++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
