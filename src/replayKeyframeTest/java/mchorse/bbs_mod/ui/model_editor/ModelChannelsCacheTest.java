package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.animation.IAnimator;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.glint.GlintControl;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Runs the actual renderer channels phase and both skeleton snapshot implementations. */
public final class ModelChannelsCacheTest
{
    public static void runAll() throws Exception
    {
        ValueBoolean previousCache = BBSSettings.framePoseCache;
        boolean previousProfiler = BBSProfiler.enabled;

        BBSSettings.framePoseCache = new ValueBoolean("channels_test", true);
        BBSProfiler.enabled = true;
        BBSProfiler.frame();

        try
        {
            testRendererCacheAndInvalidation();
            testBobjSnapshots();
            testSparsePoseReads();
            testBobjVisibilityAndDeferredSnapshot();
            testCubicVisibilityAndPreviewSnapshot();
            RuntimeAnimationParityTest.runAll();
            mchorse.bbs_mod.utils.pose.PoseVisibilityTest.main(new String[0]);
        }
        finally
        {
            BBSSettings.framePoseCache = previousCache;
            BBSProfiler.enabled = previousProfiler;
            RenderFrame.invalidate();
        }
    }

    private static void testRendererCacheAndInvalidation() throws Exception
    {
        Model model = new Model(new MolangParser());
        ModelGroup group = new ModelGroup("root");
        model.topGroups.add(group);
        model.initialize();
        ModelInstance instance = new ModelInstance("channels", model, null, null);
        ModelForm firstForm = new ModelForm();
        firstForm.pose.get().getOrCreate("root").translate.x = 3F;
        firstForm.pose.get().getOrCreate("root").glintMode = 2F;
        instance.config.defaultPose.get().getOrCreate("root").translate.x = 2F;
        CountingAnimator firstAnimator = new CountingAnimator(group);
        FixtureRenderer first = new FixtureRenderer(firstForm, instance, firstAnimator);
        StubEntity entity = new StubEntity();
        Method evaluate = ModelFormRenderer.class.getDeclaredMethod("evaluateChannels", IEntity.class, ModelInstance.class, float.class);
        evaluate.setAccessible(true);

        evaluate.invoke(first, entity, instance, 0F);
        require(firstAnimator.calls == 1 && group.current.translate.x == 15F,
            "cache miss did not compose actions/default/form pose");
        Quaternionf fkOrient = new Quaternionf(group.orient);
        Vector3f fkOffset = new Vector3f(group.offset);
        group.orient.rotateX(1F);
        group.offset.add(9F, 8F, 7F);
        evaluate.invoke(first, entity, instance, 0F);
        require(firstAnimator.calls == 1 && group.orient.equals(fkOrient, 1E-6F) && group.offset.equals(fkOffset, 1E-6F),
            "cache hit stacked procedural output instead of restoring FK overlays");
        require(group.glintMode == 2 && group.current.translate.x == 15F,
            "cache hit discarded FSR glint or FK channels");
        group.orient.rotateY(0.8F);
        group.offset.zero();
        evaluate.invoke(first, entity, instance, 0F);
        require(firstAnimator.calls == 1 && group.orient.equals(fkOrient, 1E-6F) && group.offset.equals(fkOffset, 1E-6F),
            "procedural stages mutated the saved FK overlays");

        StubEntity otherEntity = new StubEntity();
        evaluate.invoke(first, otherEntity, instance, 0F);
        require(firstAnimator.calls == 2, "entity identity did not invalidate shared channels");
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 3, "transition did not invalidate shared channels");
        RenderFrame.invalidate();
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 4, "frame epoch did not invalidate shared channels");
        firstForm.bumpPoseVersion();
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 5, "pose version did not invalidate shared channels");

        CountingAnimator secondAnimator = new CountingAnimator(group);
        FixtureRenderer second = new FixtureRenderer(new ModelForm(), instance, secondAnimator);
        evaluate.invoke(second, otherEntity, instance, 0.5F);
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(secondAnimator.calls == 1 && firstAnimator.calls == 6 && group.current.translate.x == 15F,
            "a second form's shared model pose was reused by the first form");

        BBSSettings.framePoseCache.set(false);
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 8, "disabled frame caching still skipped the channels phase");
        BBSSettings.framePoseCache.set(true);
        first.setRest(true);
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 8 && group.current.translate.x == 0F,
            "rest preview reused a deformed channel snapshot");
        first.setRest(false);
        evaluate.invoke(first, otherEntity, instance, 0.5F);
        require(firstAnimator.calls == 9 && group.current.translate.x == 15F,
            "leaving rest did not rebuild posed channels");

        first.evaluateChannels(otherEntity, 0.5F);
        otherEntity.setAge(2);
        first.evaluateChannels(otherEntity, 0.5F);
        require(firstAnimator.calls == 11, "reader-facing samples reused unversioned entity inputs");
        BBSProfiler.frame();
        require(BBSProfiler.get(BBSProfiler.Section.EVALUATE_CHANNELS) == 12L,
            "evaluate-channel profiler counts did not represent real cache misses");
        require(BBSProfiler.get(BBSProfiler.Section.CHANNELS_SKIPPED) == 2L,
            "cache-hit profiler did not count the skipped evaluations");

        group.orient = null;
        group.offset = null;
        model.snapshotChannels();
        group.orient = new Quaternionf();
        group.offset = new Vector3f(1F);
        model.restoreChannels();
        require(group.orient == null && group.offset == null, "absent cubic FK overlays became present after restore");
    }

    private static void testBobjSnapshots()
    {
        BOBJArmature armature = new BOBJArmature("channels");
        BOBJBone bone = new BOBJBone(0, "root", "", new Matrix4f());
        armature.addBone(bone);
        armature.initArmature();
        BOBJModel model = new BOBJModel(armature, List.of(), false);
        bone.transform.translate.set(3F, 4F, 5F);
        bone.orient = new Quaternionf().rotationZ(0.3F);
        bone.offset = new Vector3f(1F, 2F, 3F);
        Quaternionf fkOrient = new Quaternionf(bone.orient);
        Vector3f fkOffset = new Vector3f(bone.offset);
        model.snapshotChannels();

        for (int pass = 0; pass < 2; pass++)
        {
            bone.orient.rotateX(1F);
            bone.offset.zero();
            model.restoreChannels();
            require(bone.orient.equals(fkOrient, 1E-6F) && bone.offset.equals(fkOffset, 1E-6F),
                "BOBJ procedural output was retained or aliased its FK snapshot");
            require(bone.transform.translate.equals(new Vector3f(3F, 4F, 5F)), "BOBJ snapshot rewrote FK channels");
        }

        bone.orient = null;
        bone.offset = null;
        model.snapshotChannels();
        bone.orient = new Quaternionf();
        bone.offset = new Vector3f();
        model.restoreChannels();
        require(bone.orient == null && bone.offset == null, "absent BOBJ FK overlays became present after restore");
    }

    private static void testSparsePoseReads()
    {
        Pose pose = new Pose();
        require(pose.get("absent") == null && pose.isEmpty(), "reading a missing bone inserted it into a sparse pose");
        PoseTransform written = pose.getOrCreate("arm");
        written.translate.x = 2F;
        require(pose.get("arm") == written && pose.getOrCreate("arm") == written,
            "writer lookup replaced an authored bone transform");
        GlintControl glint = new GlintControl();
        glint.mode = PoseTransform.GLINT_EDGE;
        glint.copy(pose.get("absent"));
        require(glint.equals(GlintControl.DEFAULT) && pose.transforms.size() == 1,
            "reading sparse glint did not use the neutral value without inserting a bone");
    }

    private static void testBobjVisibilityAndDeferredSnapshot() throws Exception
    {
        BOBJArmature armature = new BOBJArmature("visible");
        BOBJBone root = new BOBJBone(0, "root", "", new Matrix4f());
        BOBJBone child = new BOBJBone(1, "child", "root", new Matrix4f());
        armature.addBone(root);
        armature.addBone(child);
        armature.initArmature();
        BOBJModel model = new BOBJModel(armature, List.of(), false);
        Pose hidden = new Pose();
        hidden.getOrCreate("child").visible = false;
        model.applyPose(hidden);
        require(!child.visible && root.visible, "BOBJ pose did not hide the authored bone");
        Pose shown = new Pose();
        shown.getOrCreate("child").visible = true;
        model.applyPose(shown);
        require(!child.visible, "a later pose overlay re-enabled an earlier hidden bone");

        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        BOBJModelVAO vao = (BOBJModelVAO) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), BOBJModelVAO.class);
        vao.armature = armature;
        vao.data = (BOBJLoader.CompiledData) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), BOBJLoader.CompiledData.class);
        vao.data.weightData = new float[36];
        vao.data.boneIndexData = new int[36];

        for (int vertex = 0; vertex < 9; vertex++)
        {
            vao.data.weightData[vertex * 4] = 1F;
            vao.data.boneIndexData[vertex * 4] = vertex >= 3 && vertex < 6 ? 1 : 0;
            vao.data.boneIndexData[vertex * 4 + 1] = 1;
        }

        Field count = BOBJModelVAO.class.getDeclaredField("count");
        count.setAccessible(true);
        count.setInt(vao, 9);
        Matrix4f[] matrices = {new Matrix4f(), new Matrix4f()};
        FormTranslucentQueue.BOBJCommand command = new FormTranslucentQueue.BOBJCommand(vao,
            () -> null, FormTranslucentQueue.PASS_TRANSLUCENT, true, matrices, 0,
            null, new Matrix4f(), new Matrix3f(), 1F, 1F, 1F, 0.5F, 0, 0, false);
        Field snapshotField = FormTranslucentQueue.BOBJCommand.class.getDeclaredField("visibilitySnapshot");
        snapshotField.setAccessible(true);
        boolean[] snapshot = (boolean[]) snapshotField.get(command);
        require(snapshot[0] && !snapshot[1], "deferred command did not capture the hidden bone");
        root.visible = false;
        child.visible = true;
        require(snapshot[0] && !snapshot[1], "shared armature edits changed a submitted draw's visibility");

        Method updateRanges = BOBJModelVAO.class.getDeclaredMethod("updateVisibleRanges", boolean[].class);
        updateRanges.setAccessible(true);
        Field rangesField = BOBJModelVAO.class.getDeclaredField("visibleRanges");
        rangesField.setAccessible(true);
        updateRanges.invoke(vao, (Object) snapshot);
        List<int[]> ranges = (List<int[]>) rangesField.get(vao);
        require(ranges.size() == 2 && ranges.get(0)[0] == 0 && ranges.get(0)[1] == 3
            && ranges.get(1)[0] == 6 && ranges.get(1)[1] == 3,
            "hidden influences did not omit their whole triangle or zero-weight influences hid visible triangles");
        updateRanges.invoke(vao, (Object) new boolean[] {false, false});
        require(((List<?>) rangesField.get(vao)).isEmpty(), "fully hidden BOBJ still emitted triangle ranges");
        updateRanges.invoke(vao, new Object[] {null});
        require(rangesField.get(vao) == null, "all-visible BOBJ did not return to its full-mesh draw");
        model.resetPose();
        require(root.visible && child.visible && vao.snapshotVisibility() == null,
            "resetting a BOBJ pose retained stale hidden bones");
    }

    private static void testCubicVisibilityAndPreviewSnapshot() throws Exception
    {
        Model model = new Model(new MolangParser());
        ModelGroup root = new ModelGroup("root");
        ModelGroup child = new ModelGroup("child");
        root.children.add(child);
        model.topGroups.add(root);
        model.initialize();
        require(List.copyOf(model.getRigBones()).equals(List.of(root, child)), "rig iteration did not preserve parent-before-child order");
        Pose hidden = new Pose();
        hidden.getOrCreate("root").visible = false;
        model.applyPose(hidden);
        require(!root.isVisible() && child.isVisible() && root.visible, "pose visibility overwrote authored visibility or hid children");
        Pose shown = new Pose();
        shown.getOrCreate("root").visible = true;
        model.applyPose(shown);
        require(!root.isVisible(), "a neutral overlay re-enabled a hidden cubic bone");
        root.glintMode = Math.round(PoseTransform.GLINT_EDGE);
        root.glintColor.set(0.2F, 0.4F, 0.6F, 0.8F);
        root.glintSpeed = 2F;
        root.glintTransform.translate.set(3F, 4F, 5F);
        Class<?> snapshotType = Class.forName("mchorse.bbs_mod.forms.renderers.ModelFormRenderer$ModelGroupPose");
        java.lang.reflect.Constructor<?> constructor = snapshotType.getDeclaredConstructor(ModelGroup.class);
        constructor.setAccessible(true);
        Object snapshot = constructor.newInstance(root);
        Method capture = snapshotType.getDeclaredMethod("capture");
        Method restore = snapshotType.getDeclaredMethod("restore");
        capture.setAccessible(true);
        restore.setAccessible(true);
        capture.invoke(snapshot);
        root.visible = false;
        root.poseVisible = true;
        root.glintMode = 0;
        root.glintColor.set(1F, 1F, 1F, 1F);
        root.glintSpeed = 1F;
        root.glintTransform.identity();
        restore.invoke(snapshot);
        require(root.visible && !root.poseVisible && !root.isVisible(), "preview snapshot lost separate authored/pose visibility");
        require(root.glintMode == PoseTransform.GLINT_EDGE && root.glintSpeed == 2F
            && root.glintTransform.translate.equals(new Vector3f(3F, 4F, 5F)) && root.glintColor.a == 0.8F,
            "preview reuse lost FSR glint state");
        root.visible = false;
        model.resetPose();
        require(root.poseVisible && !root.isVisible(), "resetPose restored authored/CEM-hidden geometry");
        root.visible = true;
        require(root.isVisible(), "pose visibility leaked into the next frame");
    }

    private static final class FixtureRenderer extends ModelFormRenderer
    {
        private final ModelInstance instance;

        private FixtureRenderer(ModelForm form, ModelInstance instance, IAnimator animator) throws Exception
        {
            super(form);
            this.instance = instance;
            Field field = ModelFormRenderer.class.getDeclaredField("animator");
            field.setAccessible(true);
            field.set(this, animator);
        }

        @Override
        public ModelInstance getModel()
        {
            return this.instance;
        }

        @Override
        public void ensureAnimator(float transition)
        {}
    }

    private static final class CountingAnimator implements IAnimator
    {
        private final ModelGroup group;
        private int calls;

        private CountingAnimator(ModelGroup group)
        {
            this.group = group;
        }

        public List<String> getActions() { return List.of(); }
        public void setup(IModelInstance model, ActionsConfig config, boolean fade) {}
        public void playAnimation(String name) {}
        public void update(IEntity entity) {}

        public void applyActions(IEntity entity, IModelInstance model, float transition)
        {
            this.calls++;
            this.group.current.translate.x = 10F;
            this.group.orient = new Quaternionf().rotationZ(0.2F);
            this.group.offset = new Vector3f(1F, 2F, 3F);
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private ModelChannelsCacheTest()
    {}
}
