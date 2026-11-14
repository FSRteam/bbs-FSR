package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.animation.IAnimator;
import mchorse.bbs_mod.cubic.animation.ProceduralAnimator;
import mchorse.bbs_mod.cubic.animation.ProceduralBone;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelUV;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.ModelManagerLoadingTest;
import mchorse.bbs_mod.cubic.jem.CemVanillaStage;
import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.cubic.model.config.ArmorSlotValue;
import mchorse.bbs_mod.cubic.model.config.WeldValue;
import mchorse.bbs_mod.cubic.data.model.CubeFace;
import mchorse.bbs_mod.cubic.weld.WeldBinding;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.mob.MobStandIn;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import mchorse.bbs_mod.ui.framework.elements.utils.UITabStrip;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Vector3f;
import org.joml.Vector2f;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exercises model data and real editor controls without a rendering context. */
public final class ModelEditorMigrationTest
{
    private ModelEditorMigrationTest()
    {}

    public static void runAll()
    {
        try
        {
            testFaceContracts();
            testSnapshotAndHiddenUV();
            testCubeMultiEdit();
            testBranchScaling();
            testConfigBoneRename();
            testSavePreservesMetadata();
            testCreateModel();
            ModelManagerLoadingTest.runAll();
            testTreeGestures();
            testUVGestureTransaction();
            testTransformGestureTransaction();
            testRestPreviewTransition();
            ModelChannelsCacheTest.runAll();
            testDefaultPoseAndProceduralRoles();
            testEntryIdentityAndReloadSelection();
            testConfigEditorListsAndSlotGesture();
            testStructureVerbsAndIK();
            testBabyCemVanillaStage();
        }
        catch (Exception exception)
        {
            throw new AssertionError("Model editor migration regression failed", exception);
        }
        System.out.println("Model editor migration tests passed");
    }

    @SuppressWarnings("deprecation")
    private static void testFaceContracts()
    {
        check(ModelFaces.FACES.equals(List.of(CubeFace.values())), "editor faces do not use the geometry-owned enum");

        for (CubeFace face : ModelFaces.FACES)
        {
            mchorse.bbs_mod.cubic.weld.CubeFace legacy = mchorse.bbs_mod.cubic.weld.CubeFace.fromCanonical(face);

            check(ModelFaces.ALL.get(face.ordinal()) == legacy, "legacy face list changed its element type or order");
            check(ModelFaces.icon(face) == ModelFaces.icon(legacy), "legacy face icon disagrees with geometry");
            check(ModelFaces.label(face) == ModelFaces.label(legacy), "legacy face label disagrees with geometry");
        }
    }

    private static Model model()
    {
        Model model = new Model(new MolangParser());
        model.textureWidth = model.textureHeight = 32;
        ModelGroup root = new ModelGroup("root");
        ModelGroup child = new ModelGroup("child");
        root.children.add(child);
        root.cubes.add(cube(2F));
        child.cubes.add(cube(5F));
        model.topGroups.add(root);
        model.topGroups.add(new ModelGroup("other"));
        model.initialize();
        return model;
    }

    private static ModelCube cube(float width)
    {
        ModelCube cube = new ModelCube();
        cube.name = "named cube";
        cube.material = "fabric";
        cube.origin.set(2F, 3F, 4F);
        cube.size.set(width, 4F, 6F);
        cube.pivot.set(1F, 2F, 3F);
        cube.front = new ModelUV();
        cube.front.from(12F, 10F, 4F, 2F);
        cube.front.rotation = 90;
        return cube;
    }

    private static void testSnapshotAndHiddenUV()
    {
        Model model = model();
        ModelCube cube = model.getGroup("root").cubes.get(0);
        cube.hideUV(CubeFace.FRONT);
        MapType snapshot = model.toData();
        model.topGroups.clear();
        model.reload(snapshot);
        cube = model.getGroup("root").cubes.get(0);
        check(cube.front == null, "hidden face became visible after reload");
        cube.showUV(CubeFace.FRONT);
        check(cube.front.sx() == 12F && cube.front.ex() == 4F && cube.front.rotation == 90,
            "hidden mirrored UV lost its corners or rotation");
        check("fabric".equals(cube.material) && "named cube".equals(cube.name), "snapshot lost cube metadata");
        cube.fromData(new MapType());
        check(cube.front == null && cube.name.isEmpty() && cube.material.isEmpty(), "load retained omitted fields");
        check(model.getGroup("child").parent == model.getGroup("root"), "snapshot lost group hierarchy");
    }

    private static void testCubeMultiEdit()
    {
        Model model = model();
        ModelGroup root = model.getGroup("root");
        ModelGroup child = model.getGroup("child");
        ModelCube first = root.cubes.get(0);
        ModelCube second = child.cubes.get(0);
        Transform from = new Transform();
        from.translate.set(first.origin);
        from.scale.set(first.size);
        Transform to = new Transform();
        to.copy(from);
        to.scale.x = 4F;
        ModelCubeEdit drag = new ModelCubeEdit(from).add(root, first, true, true, true).add(child, second, true, true, false);
        drag.carry(to, true, ModelCubeEdit.Source.ROW);
        check(first.size.x == 4F && second.size.x == 7F, "size drag must carry a delta to every cube");
        drag.carry(from, true, ModelCubeEdit.Source.ROW);
        check(first.size.x == 2F && second.size.x == 5F, "reverting a drag accumulated size changes");
        drag.carry(to, true, ModelCubeEdit.Source.TYPED);
        check(first.size.x == 4F && second.size.x == 4F, "typed size must set every selected cube");
    }

    private static void testBranchScaling()
    {
        Model model = model();
        ModelGroup root = model.getGroup("root");
        ModelGroup child = model.getGroup("child");
        root.initial.translate.set(1F, 1F, 1F);
        child.initial.translate.set(3F, 3F, 3F);
        child.current.copy(child.initial);
        ModelBranchScale scale = new ModelBranchScale(root);
        scale.scale(new Vector3f(2F));
        check(child.initial.translate.equals(new Vector3f(5F)), "branch scale did not move child pivot about root");
        check(child.cubes.get(0).size.x == 10F, "branch scale omitted child cube");
        check(root.initial.translate.equals(new Vector3f(1F)), "branch scale moved its center");
        scale.scale(new Vector3f(1F));
        check(child.initial.translate.equals(new Vector3f(3F)) && child.cubes.get(0).size.x == 5F,
            "branch scale did not restore its initial numbers");
    }

    private static void testConfigBoneRename()
    {
        ModelConfig config = new ModelConfig("fixture");
        config.cemParents.get().put("root", "child");
        config.cemParents.get().put("child", "root");
        config.flippedParts.get().put("root", "child");
        config.renameBone("root", "renamed");
        check("renamed".equals(config.cemParents.get().get("child")), "rename lost CEM parent value");
        check(config.cemParents.get().containsKey("renamed") && !config.cemParents.get().containsKey("root"),
            "rename lost CEM parent key");
        check(config.flippedParts.get().containsKey("renamed"), "rename omitted mirror references");
    }

    private static void testSavePreservesMetadata() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-model-migration-");
        Path file = directory.resolve("model.bbs.json");
        try
        {
            Model model = model();
            MapType source = new MapType();
            source.put("model", model.toData());
            source.putString("author", "keep me");
            MapType groups = new MapType();
            MapType track = new MapType();
            track.putString("expression", "query.life_time");
            groups.put("root", track);
            MapType animation = new MapType();
            animation.put("groups", groups);
            animation.putFloat("duration", 12F);
            MapType animations = new MapType();
            animations.put("walk", animation);
            source.put("animations", animations);
            DataToString.writeSilently(file.toFile(), source, true);

            AssetProvider provider = new AssetProvider();
            provider.register(new ExternalAssetsSourcePack("assets", directory.toFile()).providesFiles());
            ModelManager manager = new ModelManager(provider);
            ModelInstance instance = new ModelInstance("fixture", model, new Animations(model.parser), null);
            instance.setModelFile(Link.assets("model.bbs.json"));
            model.getGroup("root").id = "renamed";
            model.initialize();
            check(manager.saveModel(instance, List.<String[]>of(new String[] {"root", "renamed"})), "model save failed");
            MapType saved = DataToString.mapFromString(Files.readString(file));
            check("keep me".equals(saved.getString("author")), "geometry save erased root metadata");
            MapType savedGroups = saved.getMap("animations").getMap("walk").getMap("groups");
            check(savedGroups.has("renamed") && !savedGroups.has("root") && track.equals(savedGroups.get("renamed")),
                "geometry save lost animation channels while renaming bone");
            Files.writeString(file, "{broken");
            check(!manager.saveModel(instance, List.of()), "unreadable source should reject saving");
            check("{broken".equals(Files.readString(file)), "failed save overwrote original content");
        }
        finally
        {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static void testCreateModel() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-new-model-");
        Path folder = directory.resolve("models/blank");
        Path modelFile = folder.resolve("model.bbs.json");
        Path texture = folder.resolve("model.png");
        try
        {
            AssetProvider provider = new AssetProvider();
            provider.register(new ExternalAssetsSourcePack("assets", directory.toFile()).providesFiles());
            ModelManager manager = new ModelManager(provider);
            check(manager.createModel("blank"), "creating a model failed");
            String original = Files.readString(modelFile);
            Model model = new Model(manager.parser);
            model.fromData(DataToString.mapFromString(original).getMap("model"));
            model.initialize();
            check(model.getGroup("group") != null && model.getGroup("group").cubes.size() == 1,
                "new model does not contain an editable starter cube");
            check(model.textureWidth == 64 && model.textureHeight == 64 && Files.size(texture) > 0,
                "new model texture or its dimensions are missing");
            check(!manager.createModel("blank") && original.equals(Files.readString(modelFile)),
                "creating a duplicate model overwrote an asset folder");
        }
        finally
        {
            Files.deleteIfExists(texture);
            Files.deleteIfExists(modelFile);
            Files.deleteIfExists(folder);
            Files.deleteIfExists(directory.resolve("models"));
            Files.deleteIfExists(directory);
        }
    }

    private static void testTreeGestures()
    {
        Model model = model();
        TestTree tree = new TestTree();
        tree.area.set(0, 0, 180, 160);
        tree.fill(model);
        List<String> drops = new ArrayList<>();
        tree.onDrop((node, target) -> drops.add(node.key() + ":" + target));
        UIContext context = new UIContext(null);
        press(tree, context, 0, 40, 8);
        context.mouseY = 72;
        context.mouseButton = 1;
        tree.subMouseReleased(context);
        check(drops.isEmpty(), "wrong-button release committed a tree drop");
        context.mouseButton = 0;
        tree.subMouseReleased(context);
        check(drops.size() == 1 && drops.get(0).endsWith(":other"), "tree drag never reached its group drop callback");
        press(tree, context, 0, 40, 8);
        context.mouseY = 72;
        tree.cancel(context);
        tree.subMouseReleased(context);
        check(drops.size() == 1, "canceled tree drag committed on later release");
        tree.onDrop((node, target) -> { throw new IllegalStateException("fixture callback"); });
        press(tree, context, 0, 40, 8);
        context.mouseY = 72;
        try
        {
            tree.subMouseReleased(context);
            throw new AssertionError("expected callback failure");
        }
        catch (IllegalStateException expected)
        {}
        tree.onDrop((node, target) -> drops.add(target));
        press(tree, context, 0, 40, 8);
        context.mouseY = 72;
        tree.subMouseReleased(context);
        check(drops.size() == 2, "callback failure left tree drag ownership stuck");
    }

    private static void press(UIModelTree tree, UIContext context, int button, int x, int y)
    {
        context.mouseButton = button;
        context.mouseX = x;
        context.mouseY = y;
        tree.subMouseClicked(context);
    }

    private static void testUVGestureTransaction() throws Exception
    {
        TestPanel panel = allocate(TestPanel.class);
        panel.edits = new ArrayList<>();
        Model model = model();
        panel.instance = new ModelInstance("fixture", model, new Animations(model.parser), null);
        UIModelGeometryEditor geometry = new UIModelGeometryEditor(panel);
        geometry.fill(panel.instance);
        check(geometry.selectPick("root", 0), "cannot select cube for UV edit");
        UIModelCubeUV host = (UIModelCubeUV) geometry.uvPanel();
        host.pickFace(CubeFace.FRONT);
        Field canvasField = UIModelCubeUV.class.getDeclaredField("canvas");
        canvasField.setAccessible(true);
        UIModelUVEditor canvas = (UIModelUVEditor) canvasField.get(host);
        canvas.area.set(0, 0, 200, 200);
        canvas.fill(panel.instance, model, model.getGroup("root").cubes.get(0));
        canvas.scaleX.setZoom(4D);
        canvas.scaleY.setZoom(4D);
        MapType before = model.toData();
        UIContext context = new UIContext(null);
        context.mouseX = canvas.toX(12 - 16);
        context.mouseY = canvas.toY(10 - 16);
        context.mouseButton = 0;
        canvas.subMouseClicked(context);
        context.mouseX += 16;
        context.mouseButton = 1;
        canvas.subMouseReleased(context);
        check(canvas.dragging && panel.edits.isEmpty(), "UV wrong-button release terminated or committed drag");
        context.mouseButton = 0;
        canvas.mouseCanceled(context);
        check(!canvas.dragging && before.equals(model.toData()) && panel.edits.isEmpty(),
            "UV cancellation did not restore model without writing undo");

        canvas.fill(panel.instance, model, model.getGroup("root").cubes.get(0));
        context.mouseX = canvas.toX(12 - 16);
        context.mouseY = canvas.toY(10 - 16);
        canvas.subMouseClicked(context);
        context.mouseX += 16;
        canvas.subMouseReleased(context);
        check(panel.edits.size() == 1 && model.getGroup("root").cubes.get(0).front.sx() == 16F,
            "UV release did not commit one edit at the final pointer position");
        panel.edits.get(0).undo(null);
        check(before.equals(model.toData()), "UV undo did not restore geometry and mirrored mapping");
        panel.edits.get(0).redo(null);
        check(model.getGroup("root").cubes.get(0).front.sx() == 16F, "UV redo lost edited corner");
    }

    private static <T> T allocate(Class<T> type) throws Exception
    {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static void testTransformGestureTransaction() throws Exception
    {
        TestPanel panel = allocate(TestPanel.class);
        panel.edits = new ArrayList<>();
        Model model = model();
        panel.instance = new ModelInstance("fixture", model, new Animations(model.parser), null);
        UIModelGeometryEditor geometry = new UIModelGeometryEditor(panel);
        geometry.fill(panel.instance);
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.overlay.add(geometry);
        geometry.selectPick("root", 0);
        MapType before = model.toData();
        UIPropTransform editor = geometry.shownTarget().editor();
        editor.hotkeyDrag(() -> null);
        editor.enableMode(TransformOp.TRANSLATE, Axis.X);
        check(editor.isEditing(), "cube move could not start");
        editor.setT(Axis.X, 6D, 3D, 4D);
        check(model.getGroup("root").cubes.get(0).origin.x == 6F && panel.edits.isEmpty(),
            "model transform did not preview without writing undo");
        editor.rejectChanges();
        check(before.equals(model.toData()) && panel.edits.isEmpty(), "cancelled cube move left geometry or history changed");
        editor.enableMode(TransformOp.TRANSLATE, Axis.X);
        editor.setT(Axis.X, 9D, 3D, 4D);
        editor.acceptChanges();
        check(panel.edits.size() == 1 && model.getGroup("root").cubes.get(0).origin.x == 9F,
            "accepted cube move did not produce exactly one edit");
        panel.edits.get(0).undo(null);
        geometry.fill(panel.instance);
        geometry.selectPick("root", -1);
        editor = geometry.shownTarget().editor();
        editor.hotkeyDrag(() -> null);
        before = model.toData();
        editor.enableMode(TransformOp.SCALE, Axis.X);
        check(editor.isEditing(), "hidden group scale row disabled branch scaling");
        editor.setS(Axis.X, 2D, 2D, 2D);
        check(model.getGroup("child").cubes.get(0).size.x == 10F, "group gizmo did not scale descendants");
        editor.rejectChanges();
        check(before.equals(model.toData()) && panel.edits.size() == 1, "cancelled branch scale wrote history or changed model");
    }

    private static void testRestPreviewTransition() throws Exception
    {
        Model model = model();
        ModelInstance instance = new ModelInstance("fixture", model, new Animations(model.parser), null);
        ModelFormRenderer renderer = new ModelFormRenderer(new ModelForm());
        int[] actions = {0};
        Field animator = ModelFormRenderer.class.getDeclaredField("animator");
        animator.setAccessible(true);
        animator.set(renderer, new IAnimator()
        {
            public List<String> getActions() { return List.of(); }
            public void setup(IModelInstance ignored, ActionsConfig config, boolean fade) {}
            public void playAnimation(String name) {}
            public void update(IEntity entity) {}
            public void applyActions(IEntity entity, IModelInstance target, float transition)
            {
                actions[0]++;
                ((Model) target.getModel()).getGroup("root").current.translate.x = 10F;
            }
        });
        Method evaluate = ModelFormRenderer.class.getDeclaredMethod("evaluateChannels", IEntity.class, ModelInstance.class, float.class);
        evaluate.setAccessible(true);
        renderer.setRest(true);
        evaluate.invoke(renderer, new StubEntity(), instance, 0F);
        check(actions[0] == 0 && model.getGroup("root").current.translate.x == 0F, "geometry rest preview evaluated animation");
        renderer.setRest(false);
        evaluate.invoke(renderer, new StubEntity(), instance, 0F);
        check(actions[0] == 1 && model.getGroup("root").current.translate.x == 10F, "config preview did not resume animation");
        renderer.setRest(true);
        evaluate.invoke(renderer, new StubEntity(), instance, 0F);
        check(actions[0] == 1 && model.getGroup("root").current.translate.x == 0F, "returning to geometry retained stale pose");
    }

    private static void testDefaultPoseAndProceduralRoles() throws Exception
    {
        Model model = model();
        ModelInstance instance = new ModelInstance("fixture", model, new Animations(model.parser), null);
        instance.config.proceduralBones.get().put("head", "child");
        instance.config.proceduralBones.get().put("right_arm", "");
        instance.config.defaultPose.get().getOrCreate("root").translate.x = 2F;
        MapType data = instance.config.toData().asMap();
        ModelConfig restored = new ModelConfig("fixture");
        restored.fromData(data);
        check(restored.defaultPose.get().get("root").translate.x == 2F
            && restored.proceduralBones.get().containsKey("right_arm")
            && restored.proceduralBones.get().get("right_arm").isEmpty(), "Config round trip lost pose or disabled role");
        restored.fromData(new MapType());
        check(restored.defaultPose.get().isEmpty() && restored.proceduralBones.get().isEmpty(), "Reloading old config retained a newer model's pose/roles");

        StubEntity entity = new StubEntity();
        entity.setHeadYaw(30F);
        entity.update();
        new ProceduralAnimator().applyActions(entity, instance, 1F);
        check(model.getGroup("child").current.rotate.y == -30F, "Procedural head role did not reach its renamed bone");
        instance.config.proceduralBones.get().put("head", "");
        new ProceduralAnimator().applyActions(entity, instance, 1F);
        check(model.getGroup("child").current.rotate.y == 0F, "Empty procedural role still drives a bone");
        check(ProceduralBone.HEAD.resolve(model, java.util.Map.of()).equals("head"), "Missing role no longer uses legacy names");

        ModelForm form = new ModelForm();
        form.pose.get().getOrCreate("root").translate.x = 3F;
        ModelFormRenderer renderer = new ModelFormRenderer(form);
        Field animator = ModelFormRenderer.class.getDeclaredField("animator");
        animator.setAccessible(true);
        animator.set(renderer, new IAnimator()
        {
            public List<String> getActions() { return List.of(); }
            public void setup(IModelInstance ignored, ActionsConfig config, boolean fade) {}
            public void playAnimation(String name) {}
            public void update(IEntity ignored) {}
            public void applyActions(IEntity ignored, IModelInstance target, float transition)
            {
                ((Model) target.getModel()).getGroup("root").current.translate.x = 10F;
            }
        });
        Method evaluate = ModelFormRenderer.class.getDeclaredMethod("evaluateChannels", IEntity.class, ModelInstance.class, float.class);
        evaluate.setAccessible(true);
        evaluate.invoke(renderer, entity, instance, 0F);
        check(model.getGroup("root").current.translate.x == 15F, "Default pose is not composed between animation and form pose");
        renderer.setRest(true);
        evaluate.invoke(renderer, entity, instance, 0F);
        check(model.getGroup("root").current.translate.x == 0F, "Geometry preview is deformed by the config default pose");

        UIModelConfigPoseEditor editor = new UIModelConfigPoseEditor(instance.config.defaultPose);
        editor.poseOnly();
        editor.setPose(instance.config.defaultPose.get(), "fixture");
        editor.fillGroups(model, java.util.Map.of(), true);
        editor.selectBone("root");
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.overlay.add(editor);
        int[] edits = {0};
        List<MapType> undo = new ArrayList<>();
        instance.config.defaultPose.preCallback((value, flag) -> undo.add(instance.config.defaultPose.toData().asMap().copy().asMap()));
        instance.config.defaultPose.postCallback((value, flag) -> edits[0]++);
        editor.transform.hotkeyDrag(() -> null);
        editor.transform.enableMode(TransformOp.TRANSLATE, Axis.X);
        editor.transform.setT(Axis.X, 7D, 0D, 0D);
        editor.transform.rejectChanges();
        check(instance.config.defaultPose.get().get("root").translate.x == 2F && edits[0] == 0, "Cancelled config pose drag left history/data changed");
        editor.transform.enableMode(TransformOp.TRANSLATE, Axis.X);
        editor.transform.setT(Axis.X, 9D, 0D, 0D);
        editor.transform.acceptChanges();
        check(edits[0] == 1 && undo.size() == 1 && instance.config.defaultPose.get().get("root").translate.x == 9F,
            "Config pose gesture did not produce exactly one paired undo edit");
        instance.config.defaultPose.fromData(undo.getLast());
        check(instance.config.defaultPose.get().get("root").translate.x == 2F, "Config pose undo failed");
        editor.setPose(instance.config.defaultPose.get(), "fixture");
        editor.selectBone("root");
        editor.boneVisible.setValue(false);
        editor.boneVisible.callback.accept(editor.boneVisible);
        check(edits[0] == 2 && undo.size() == 2 && !instance.config.defaultPose.get().get("root").visible,
            "Config visibility did not reach the shared pose notifier exactly once");
        UIModelConfigPoseEditor rebound = new UIModelConfigPoseEditor();
        rebound.setValuePose(instance.config.sneakingPose);
        rebound.poseOnly();
        rebound.setPose(instance.config.sneakingPose.get(), "fixture");
        rebound.fillGroups(model, java.util.Map.of(), true);
        rebound.selectBone("root");
        int[] sneakEdits = {0};
        instance.config.sneakingPose.postCallback((value, flag) -> sneakEdits[0]++);
        rebound.boneVisible.setValue(false);
        rebound.boneVisible.callback.accept(rebound.boneVisible);
        check(sneakEdits[0] == 1 && edits[0] == 2 && !instance.config.sneakingPose.get().get("root").visible,
            "Rebinding a config pose left the shared notifier on the old value");
        check(!editor.fix.isVisible() && !editor.materialSection.isVisible(), "Model config pose editor exposes unrelated form controls");
    }

    private static void testEntryIdentityAndReloadSelection()
    {
        WeldValue first = new WeldValue("0");
        WeldValue second = new WeldValue("1");
        UIEntryList<WeldValue> list = new UIWeldList(null);

        check(first.equals(second), "fixture does not contain equal but distinct entries");
        list.refill(List.of(first, second));
        list.setCurrent(second);
        check(list.getCurrentFirst() == second, "identical welds selected the first equal entry");
        list.refill(List.of(second, first));
        check(list.getCurrentFirst() == second, "reordering lost the selected entry's identity");
        WeldValue replacement = new WeldValue("0");

        replacement.fromData(second.toData());
        list.refill(List.of(replacement, new WeldValue("1")));
        check(list.getCurrentFirst() == replacement, "reloading entries lost the old selected row");
    }

    private static void testConfigEditorListsAndSlotGesture() throws Exception
    {
        TestPanel panel = allocate(TestPanel.class);
        Model model = model();

        for (String bone : List.of("root", "child"))
        {
            ModelCube cube = model.getGroup(bone).cubes.get(0);
            cube.setupBoxUV(new Vector2f(), false);
            cube.generateQuads(64, 64);
        }

        panel.instance = new ModelInstance("config_fixture", model, new Animations(model.parser), null);
        ModelConfig config = panel.instance.config;
        WeldValue first = new WeldValue("0");
        WeldValue second = new WeldValue("1");

        first.sourceBone.set("root");
        first.targetBone.set("child");
        first.sourceFace.set("top");
        first.targetFace.set("bottom");
        second.fromData(first.toData());
        check(WeldBinding.diagnose(model, first.toWeld()) == null, "valid weld fixture was rejected");
        WeldValue self = new WeldValue("self");
        self.fromData(first.toData());
        self.targetBone.set("root");
        check(WeldBinding.diagnose(model, self.toWeld()) == WeldBinding.Issue.SAME_BONE,
            "self-weld must produce the dedicated editor diagnostic");
        config.welds.add(first);
        config.welds.add(second);
        config.welds.sync();
        ArmorSlotValue slot = new ArmorSlotValue("0");

        slot.group.set("root");
        config.itemsMain.add(slot);
        config.itemsMain.sync();
        UIModelConfigEditor editor = new UIModelConfigEditor(panel);
        UIBaseMenu menu = new UIBaseMenu() {};

        menu.overlay.add(editor);
        editor.fill(config);
        Field pagesField = UIModelConfigEditor.class.getDeclaredField("pages");
        pagesField.setAccessible(true);
        Object[] pages = (Object[]) pagesField.get(editor);
        check(pages.length == UIModelConfigEditor.Tab.values().length && pages.length == 8,
            "Configuration tabs did not create their eight production pages");
        editor.selectPage(UIModelConfigEditor.Tab.BONES);
        check(editor.selectBone("root"), "Configuration stencil pick did not select the bones page");
        invoke(editor, "setMirror", new Class<?>[] {String.class, String.class}, "root", "child");
        check("root".equals(invoke(editor, "mirrorOf", new Class<?>[] {String.class}, "child")),
            "Mirror assignment was not visible from both sides of the pair");
        invoke(editor, "setPickingOverride", new Class<?>[] {String.class, String.class}, "root", "child");
        Method picking = ModelInstance.class.getDeclaredMethod("getPickingBone", ModelForm.class, String.class);
        picking.setAccessible(true);
        check("child".equals(picking.invoke(panel.instance, new ModelForm(), "root")),
            "The config picking override did not reach the stencil's production bone resolver");
        invoke(editor, "setPickingOverride", new Class<?>[] {String.class, String.class}, "root", "root");
        check("root".equals(picking.invoke(panel.instance, new ModelForm(), "root")),
            "Clearing the picking override retained a stale stencil alias");
        Field weldsField = UIModelConfigEditor.class.getDeclaredField("weldList");

        weldsField.setAccessible(true);
        UIWeldList welds = (UIWeldList) weldsField.get(editor);

        check(welds.getList().get(0) == first && welds.getList().get(1) == second,
            "configuration editor did not bind the live weld entries");
        welds.setCurrent(second);
        Method duplicate = UIModelConfigEditor.class.getDeclaredMethod("duplicateWeld", WeldValue.class);

        duplicate.setAccessible(true);
        duplicate.invoke(editor, second);
        check(config.welds.getAllTyped().size() == 3 && config.welds.getAllTyped().get(1) == second
                && welds.getCurrentFirst() == config.welds.getAllTyped().get(2),
            "duplicating the second identical weld inserted or selected the wrong entry");
        Method remove = UIModelConfigEditor.class.getDeclaredMethod("removeWeld", WeldValue.class);

        remove.setAccessible(true);
        remove.invoke(editor, second);
        check(config.welds.getAllTyped().size() == 2 && config.welds.getAllTyped().get(0) == first,
            "removing the second identical weld removed its first equal neighbor");

        Field bonesField = UIModelConfigEditor.class.getDeclaredField("bones");

        bonesField.setAccessible(true);
        UIModelBoneList bones = (UIModelBoneList) bonesField.get(editor);
        Method branch = UIModelBoneList.class.getDeclaredMethod("setBranch", String.class, boolean.class);

        branch.setAccessible(true);
        branch.invoke(bones, "root", false);
        check(bones.isDisabled("root") && bones.isDisabled("child") && !bones.isDisabled("other"),
            "hiding a config bone did not follow its branch");
        branch.invoke(bones, "root", true);
        check(config.disabledBones.get().isEmpty(), "showing the branch did not restore its hidden children");

        editor.selectPage(UIModelConfigEditor.Tab.ITEMS);
        Field itemsField = UIModelConfigEditor.class.getDeclaredField("items");

        itemsField.setAccessible(true);
        Object items = itemsField.get(editor);
        Field listField = items.getClass().getField("list");

        listField.setAccessible(true);
        @SuppressWarnings("unchecked")
        UIEntryList<ArmorSlotValue> slots = (UIEntryList<ArmorSlotValue>) listField.get(items);

        slots.pick(0);
        ModelSlotTarget target = editor.shownTarget();

        check(target != null && target.kind() == ModelSlotKind.ITEM_MAIN && "root".equals(target.bone()),
            "the shared slot list did not own the preview gizmo target");
        UIPropTransform transform = target.editor();
        MapType before = slot.transform.toData().asMap().copy().asMap();
        int[] edits = {0};

        slot.transform.postCallback((value, flag) -> edits[0]++);
        transform.hotkeyDrag(() -> null);
        transform.enableMode(TransformOp.TRANSLATE, Axis.X);
        transform.setT(Axis.X, 5D, 0D, 0D);
        transform.rejectChanges();
        check(before.equals(slot.transform.toData()) && edits[0] == 0,
            "a canceled attachment gesture left config data or undo notifications changed");
        transform.enableMode(TransformOp.TRANSLATE, Axis.X);
        transform.setT(Axis.X, 8D, 0D, 0D);
        transform.acceptChanges();
        check(slot.transform.get().translate.x == 8F && edits[0] == 1,
            "an accepted attachment gesture did not write one undoable change");

        config.fpMain.group.set("root");
        config.fpOffhand.group.set("child");
        invoke(editor, "fillFirstPerson", new Class<?>[0]);
        editor.selectPage(UIModelConfigEditor.Tab.FIRST_PERSON);
        target = editor.shownTarget();
        check(target != null && target.kind() == ModelSlotKind.FIRST_PERSON_MAIN && "root".equals(target.bone()),
            "First-person main-hand tab did not expose its actual slot gizmo");
        Field fpTabsField = UIModelConfigEditor.class.getDeclaredField("fpTabs");
        fpTabsField.setAccessible(true);
        ((UITabStrip) fpTabsField.get(editor)).select(1);
        target = editor.shownTarget();
        check(target != null && target.kind() == ModelSlotKind.FIRST_PERSON_OFF && "child".equals(target.bone()),
            "First-person off-hand tab retained the main-hand slot");
        editor.selectPage(UIModelConfigEditor.Tab.POSES);
        check(editor.selectBone("root") && editor.shownTarget().kind() == ModelSlotKind.POSE,
            "Pose page stencil selection did not become the preview's pose target");
        MapType sneakingBefore = config.sneakingPose.toData().asMap().copy().asMap();
        Field poseTabsField = UIModelConfigEditor.class.getDeclaredField("poseTabs");
        Field poseEditorField = UIModelConfigEditor.class.getDeclaredField("poseEditor");
        poseTabsField.setAccessible(true);
        poseEditorField.setAccessible(true);
        ((UITabStrip) poseTabsField.get(editor)).select(1);
        UIModelConfigPoseEditor pose = (UIModelConfigPoseEditor) poseEditorField.get(editor);
        pose.transform.setT(Axis.X, 6D, 0D, 0D);
        check(config.defaultPose.get().get("root").translate.x == 6F && sneakingBefore.equals(config.sneakingPose.toData()),
            "Default pose tab wrote its transform into the sneaking pose");
    }

    private static void testStructureVerbsAndIK() throws Exception
    {
        TestPanel panel = allocate(TestPanel.class);
        Model model = model();
        panel.instance = new ModelInstance("structure_fixture", model, new Animations(model.parser), null);
        panel.edits = new ArrayList<>();
        panel.bind();
        UIModelGeometryEditor geometry = new UIModelGeometryEditor(panel);
        panel.geometry = geometry;
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.overlay.add(geometry);
        geometry.fill(panel.instance);
        geometry.selectPick("root", -1);
        MapType before = model.toData();
        invoke(geometry, "addIKBones", new Class<?>[] {String.class, String.class}, "root", "other");
        check(model.getGroup("root_end").parent == model.getGroup("root")
                && model.getGroup("controller_root").parent == model.getGroup("other")
                && model.getGroup("pole_root").parent == model.getGroup("controller_root"),
            "IK shortcut did not create its tip/controller/pole in their production parents");
        Field treeField = UIModelGeometryEditor.class.getDeclaredField("tree");
        treeField.setAccessible(true);
        UIModelTree tree = (UIModelTree) treeField.get(geometry);
        check(tree.getCurrent().equals(List.of(ModelNode.group("root_end"), ModelNode.group("controller_root")))
                && panel.edits.size() == 1, "IK shortcut did not select its two controls as one undo edit");
        panel.edits.getLast().undo(null);
        check(before.equals(model.toData()), "IK shortcut undo left generated bones behind");
        panel.edits.getLast().redo(null);
        check(model.getGroup("pole_root") != null, "IK shortcut redo lost the pole");

        ModelConfig config = panel.instance.config;
        config.defaultPose.get().getOrCreate("root").translate.x = 4F;
        config.proceduralBones.get().put("head", "root");
        config.pickingOverrides.get().put("child", "root");
        config.fpMain.group.set("root");
        Animation animation = new Animation("fixture", model.parser);
        AnimationPart part = new AnimationPart(model.parser);
        animation.parts.put("root", part);
        panel.instance.animations.add(animation);
        geometry.fill(panel.instance);
        geometry.selectPick("root", -1);
        invoke(geometry, "renameGroup", new Class<?>[] {String.class}, "renamed");
        check(model.getGroup("root") == null && model.getGroup("renamed") != null
                && config.defaultPose.get().get("renamed").translate.x == 4F
                && "renamed".equals(config.proceduralBones.get().get("head"))
                && "renamed".equals(config.pickingOverrides.get().get("child"))
                && "renamed".equals(config.fpMain.group.get()) && animation.parts.get("renamed") == part,
            "Production rename lost config references or animation ownership");
        ModelEditUndo rename = panel.edits.getLast();
        rename.undo(null);
        check(model.getGroup("root") != null && config.defaultPose.get().get("root").translate.x == 4F
                && animation.parts.get("root") == part, "Rename undo failed to restore model/config/animations together");
        rename.redo(null);
        check(model.getGroup("renamed") != null && animation.parts.get("renamed") == part,
            "Rename redo failed to follow the new bone name");

        geometry.fill(panel.instance);
        int destinationSize = model.getGroup("other").cubes.size();
        invoke(geometry, "dropNode", new Class<?>[] {ModelNode.class, String.class}, ModelNode.cube("renamed", 0), "other");
        check(model.getGroup("renamed").cubes.isEmpty() && model.getGroup("other").cubes.size() == destinationSize + 1,
            "Tree container drop did not move the cube into the production target");
        panel.edits.getLast().undo(null);
        check(model.getGroup("renamed").cubes.size() == 1 && model.getGroup("other").cubes.size() == destinationSize,
            "Cube reparent undo lost its previous owner");
        geometry.fill(panel.instance);
        invoke(geometry, "removeNodes", new Class<?>[] {List.class}, List.of(ModelNode.group("renamed")));
        check(model.getGroup("renamed") == null && model.getGroup("child") == null,
            "Deleting a group left its descendant in the live model");
        panel.edits.getLast().undo(null);
        check(model.getGroup("child").parent == model.getGroup("renamed"), "Deleting a branch could not be undone");
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception
    {
        Method method = target.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void testBabyCemVanillaStage() throws Exception
    {
        TestBabyZombie zombie = allocate(TestBabyZombie.class);
        CemVanillaStage stage = new CemVanillaStage("zombie_baby");
        Field standIn = CemVanillaStage.class.getDeclaredField("standIn");
        standIn.setAccessible(true);
        standIn.set(stage, new MobStandIn()
        {
            @Override
            public Entity ensure(String id, String nbt, boolean slim, boolean player)
            {
                return zombie;
            }
        });
        check(invoke(stage, "entity", new Class<?>[0]) == zombie && zombie.babyCalls == 1,
            "Baby CEM stage ignored a zombie because it is not an AgeableMob");
        invoke(stage, "entity", new Class<?>[0]);
        check(zombie.babyCalls == 1, "CEM stage reapplied the baby flag every frame");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static class TestTree extends UIModelTree
    {
        TestTree()
        {
            super(null);
        }

        void cancel(UIContext context)
        {
            this.subMouseCanceled(context);
        }
    }

    private static final class TestBabyZombie extends Zombie
    {
        private int babyCalls;

        private TestBabyZombie() { super(null, null); }

        @Override
        public void setBaby(boolean baby)
        {
            if (baby) this.babyCalls++;
        }
    }

    private static class TestPanel extends UIModelEditorPanel
    {
        private ModelInstance instance;
        private List<ModelEditUndo> edits;
        private UIModelGeometryEditor geometry;

        private TestPanel()
        {
            super(null);
        }

        private void bind() throws Exception
        {
            this.data = this.instance.config;
            Field bound = UIModelEditorPanel.class.getDeclaredField("bound");
            Field renames = UIModelEditorPanel.class.getDeclaredField("renames");
            bound.setAccessible(true);
            renames.setAccessible(true);
            bound.set(this, this.instance);
            renames.set(this, new ArrayList<String[]>());
        }

        @Override
        public void refresh()
        {
            if (this.instance != null)
            {
                this.instance.invalidateWelds();
            }
        }

        @Override
        public ModelInstance getInstance()
        {
            return this.instance;
        }

        @Override
        public void syncPreview()
        {}

        @Override
        public void refreshPreview()
        {}

        @Override
        public void closeModelEdit()
        {}

        @Override
        public void pushModelEdit(ModelEditUndo undo)
        {
            this.edits.add(undo);
        }

        @Override
        public void restoreModel(MapType data, MapType config)
        {
            ((Model) this.instance.getModel()).reload(data);
            if (config != null)
            {
                this.instance.config.fromData(config);
                this.instance.config.rebuild();
            }
        }

        @Override
        public void modelStructureChanged()
        {
            ((Model) this.instance.getModel()).initialize();
            if (this.geometry != null) this.geometry.fill(this.instance);
        }
    }
}
