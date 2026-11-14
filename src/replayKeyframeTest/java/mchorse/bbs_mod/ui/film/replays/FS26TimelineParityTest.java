package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.utils.categories.CategoryPath;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import mchorse.bbs_mod.film.replays.tracks.ConstraintTracks;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeMotionShiftTest;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_mod.utils.keyframes.KeyframeStyle;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Pose;

/** Executes playback-head edits and upstream typed-film compatibility without a GL context. */
public final class FS26TimelineParityTest
{
    public static void runAll()
    {
        testNestedKeyframeOverview();
        testAutomaticEdits();
        testTypedTracks();
        testTypedFilm();
        testConstraintPlayback();
        testReplayFolders();
        testReplayFolderDragOwner();
        testSettingsAndGizmo();
        testPreviewSmoothing();
        testKeyframeAppearanceAndGesture();
        KeyframeMotionShiftTest.runAll();
    }

    private static void testNestedKeyframeOverview()
    {
        AutoTimeline view = new AutoTimeline();
        UIKeyframeSheet root = sheet(new KeyframeChannel<>("root", KeyframeFactories.FLOAT));
        UIKeyframeSheet child = sheet(new KeyframeChannel<>("child", KeyframeFactories.FLOAT));
        UIKeyframeSheet bone = sheet(new KeyframeChannel<>("bone", KeyframeFactories.FLOAT));
        UIKeyframeSheet removed = sheet(new KeyframeChannel<>("removed", KeyframeFactories.FLOAT));
        UIKeyframeSheet unrelated = sheet(new KeyframeChannel<>("unrelated", KeyframeFactories.FLOAT));
        child.setParent(root);
        bone.setParent(child);
        removed.setParent(root);
        bone.channel.insert(9F, 1F);
        view.addSheet(root);
        view.addSheet(child);
        view.addSheet(bone);
        view.addSheet(unrelated);
        root.folded = true;
        child.folded = true;
        check(view.getDopeSheet().isOverviewChild(root, bone), "Folded descendants disappeared from the parent preview");
        check(view.getDopeSheet().isOverviewChild(child, bone), "Intermediate parent omitted child keys");
        check(!view.getDopeSheet().isOverviewChild(root, removed), "Filtered-out tracks still contributed to the preview");
        check(!view.getDopeSheet().isOverviewChild(root, unrelated)
            && !view.getDopeSheet().isOverviewChild(root, root), "Preview included another tree or the parent's own channel");
        check(root.channel.getKeyframes().isEmpty() && root.selection.getSelected().isEmpty(),
            "Preview changed the parent channel or its interactive selection");
        view.getDopeSheet().configurePoseTabs(java.util.Map.of(root, List.of(unrelated)), java.util.Map.of(), java.util.Set.of());
        check(view.getDopeSheet().isOverviewChild(root, unrelated), "Legacy pose-tab preview lost addon compatibility");
    }

    private static void testKeyframeAppearanceAndGesture()
    {
        KeyframeStyle previous = BBSSettings.keyframeDefaultStyle.get().copy();
        try
        {
            KeyframeStyle style = new KeyframeStyle();
            style.setShape(KeyframeShape.values()[1]);
            style.setFilled(true);
            style.setColor(Color.rgb(0x123456));
            BBSSettings.keyframeDefaultStyle.set(style);
            Keyframe<Float> fresh = new Keyframe<>("", KeyframeFactories.FLOAT, 0F, 1F);
            check(fresh.getStyle().equals(style), "New keyframe ignored default appearance");
            fresh.getColor().r = 1F;
            check(!fresh.getStyle().equals(BBSSettings.keyframeDefaultStyle.get()), "Keyframe style aliases settings");
            fresh.setStyle(style);
            Keyframe<Float> copy = new Keyframe<>("", KeyframeFactories.FLOAT);
            copy.copy(fresh);
            check(copy.equals(fresh), "Keyframe copy lost style");
            copy.getStyle().setFilled(false);
            check(!copy.equals(fresh), "Appearance is absent from keyframe equality/undo");
            copy.fromData(fresh.toData());
            check(copy.equals(fresh), "Filled appearance did not survive round trip");
            MapType legacy = new MapType();
            legacy.putFloat("value", 3F);
            copy.fromData(legacy);
            check(copy.getStyle().isDefault(), "Old keyframes inherited new user defaults on load");

            AutoTimeline view = new AutoTimeline();
            KeyframeChannel<Float> channel = new KeyframeChannel<>("numeric", KeyframeFactories.FLOAT);
            channel.insert(0F, 2F);
            channel.insert(10F, 8F);
            UIKeyframeSheet sheet = sheet(channel);
            view.addSheet(sheet);
            sheet.selection.add(0);
            int[] writes = {0};
            List<BaseType> before = new ArrayList<>();
            channel.preCallback((value, flag) -> before.add(channel.toData().copy()));
            channel.postCallback((value, flag) -> writes[0]++);
            BaseType original = channel.toData().copy();
            view.cacheKeyframes();
            view.getGraph().setValue(4F, false, true);
            view.getGraph().setValue(5F, false, true);
            check(writes[0] == 0, "Numeric gesture emits an undo entry per sample");
            view.cancelCachedKeyframes();
            check(channel.toData().equals(original) && writes[0] == 0, "Rejected numeric gesture changed data or history");
            view.cacheKeyframes();
            view.getGraph().setValue(6F, false, true);
            channel.get(0).getStyle().setFilled(false);
            view.submitKeyframes();
            check(writes[0] == 1 && channel.get(0).getValue() == 6F, "Accepted numeric gesture did not emit one complete edit");
            channel.fromData(before.getFirst());
            check(channel.toData().equals(original), "Undo failed to restore the numeric value and appearance");
        }
        finally
        {
            BBSSettings.keyframeDefaultStyle.set(previous);
        }
    }

    private static void testTypedFilm()
    {
        try
        {
            Field registry = BBSMod.class.getDeclaredField("forms");
            registry.setAccessible(true);
            Object previous = registry.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("model"), ModelForm.class, null);
            registry.set(null, architect);

            try
            {
                MapType child = new MapType();
                child.putString("id", "bbs:model");
                MapType leaf = new MapType();
                leaf.put("form", child.copy());
                ListType children = new ListType();
                children.add(leaf.copy());
                children.add(leaf.copy());
                child.put("parts", children);
                MapType parent = new MapType();
                parent.put("form", child);
                ListType parts = new ListType();
                parts.add(parent);
                MapType root = new MapType();
                root.putString("id", "bbs:model");
                root.put("parts", parts);

                KeyframeChannel<Float> value = new KeyframeChannel<>("source", KeyframeFactories.FLOAT);
                value.insert(2.5F, 0.8F);
                MapType record = new MapType();
                record.putString("kind", "material_prop");
                record.putString("form", "0/1");
                record.putString("subject", "Paint/detail");
                record.putString("prop", "metallic");
                record.put("channel", value.toData());
                ListType tracks = new ListType();
                tracks.add(record);
                MapType properties = new MapType();
                properties.put("tracks", tracks);
                MapType replayData = new MapType();
                replayData.put("form", root);
                replayData.put("properties", properties);
                ListType replays = new ListType();
                replays.add(replayData);
                MapType input = new MapType();
                input.put("replays", replays);
                Film film = new Film();
                film.fromData(input);
                Replay replay = film.replays.getList().getFirst();
                BodyPart first = replay.form.get().parts.getList().getFirst();
                BodyPart nested = first.getForm().parts.getList().get(1);
                String path = first.getId() + "/" + nested.getId();
                String key = path + "/materials.Paint/detail.metallic";
                check(replay.properties.properties.containsKey(key), "Full-film import did not migrate typed body-part addresses");
                replay.properties.applyProperties(replay.form.get(), 2.5F);
                check(((ModelForm) nested.getForm()).materialPbrOverrides.get("Paint/detail").get("metallic") == 0.8F,
                    "Imported nested material channel did not affect its form");
                check(((ModelForm) nested.getForm()).materials.getMaterial("Paint/detail") == null,
                    "Imported material animation changed the saved static appearance");
                Film restored = new Film();
                restored.fromData(film.toData());
                Replay again = restored.replays.getList().getFirst();
                check(again.getId().equals(replay.getId()) && again.properties.properties.containsKey(key), "Typed film reload changed stable addresses");
                check(again.properties.toData().asMap().getList("tracks").get(0).asMap().getString("form").equals(path), "Typed save returned legacy addresses");
            }
            finally
            {
                registry.set(null, previous);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void testSettingsAndGizmo()
    {
        MapType root = new MapType();
        MapType transformation = new MapType();
        transformation.putBool("axes_keep_screen_size", false);
        transformation.putBool("rotate_3d_sphere", false);
        transformation.putBool("rotate_hide_rings", true);
        root.put("transformation", transformation);
        BBSSettings.migrateLegacySettings(root);
        check(!transformation.getBool("gizmo_keep_screen_size") && !transformation.getBool("gizmo_show_sphere")
            && !transformation.getBool("gizmo_show_rotate"), "Old gizmo settings did not migrate");
        transformation.putBool("gizmo_show_rotate", true);
        BBSSettings.migrateLegacySettings(root);
        check(transformation.getBool("gizmo_show_rotate"), "Migrating again overwrote a new option");

        BBSSettings.gizmoShowRotate.set(false);
        BBSSettings.gizmoShowViewRotate.set(true);
        check(!Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.ROTATE_X) && Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.VIEW),
            "Screen rotation depends on axis-ring visibility");
        BBSSettings.rotateHideRings.set(false);
        check(BBSSettings.gizmoShowRotate.get(), "Legacy ring option no longer edits the new option");
        BBSSettings.gizmoShowTranslate.set(false);
        check(!Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.MOVE_X) && Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.SCALE_X),
            "Translation option also disabled scale");
        BBSSettings.gizmoShowTranslate.set(true);
        BBSSettings.gizmoShowViewRotate.set(false);
        check(!Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.VIEW), "Hidden view ring remains selectable");
        BBSSettings.gizmoShowViewRotate.set(true);
        BBSSettings.gizmoShowSphere.set(false);
        check(!Gizmo.INSTANCE.isHandleVisible(Gizmo.Handle.TRACKBALL), "Hidden sphere remains selectable");
        BBSSettings.gizmoShowSphere.set(true);
        check(BBSSettings.axesKeepScreenSize == BBSSettings.gizmoKeepScreenSize, "Fixed-size addon API lost its live setting");
    }

    private static void testPreviewSmoothing()
    {
        float previous = BBSSettings.editorCameraSmoothness.get();
        BBSSettings.editorCameraSmoothness.set(0.25F);
        try
        {
            HeadlessPreview first = new HeadlessPreview();
            HeadlessPreview second = new HeadlessPreview();
            first.focus(10F, 1F, 0F);
            second.focus(10F, 1F, 0F);
            first.setDistance(30F);
            second.setDistance(30F);
            first.advance(1F);
            second.advance(0.5F);
            second.advance(0.5F);
            check(Math.abs(first.pos.x - second.pos.x) < 0.0001F && first.pos.x > 0F && first.pos.x < 10F,
                "Preview smoothing depends on frame rate or snaps directly");
            first.setupPosition();
            second.setupPosition();
            check(first.camera.position.distance(second.camera.position) < 0.0001D, "Distance smoothing is quantized through Factor");
            first.pos.set(2F, 3F, 4F);
            first.camera.rotation.set(0.2F, 0.4F, 0F);
            first.advance(1F);
            check(first.pos.equals(2F, 3F, 4F) && first.camera.rotation.x == 0.2F, "Smoothing overwrote external camera fields");
            BBSSettings.editorCameraSmoothness.set(0F);
            first.focus(8F, 7F, 6F);
            first.advance(1F);
            check(first.pos.equals(8F, 7F, 6F), "Turning off smoothing did not follow the input immediately");
        }
        finally
        {
            BBSSettings.editorCameraSmoothness.set(previous);
        }
    }

    private static final class HeadlessPreview extends UIModelRenderer
    {
        void advance(float dt) { this.applySmoothing(dt); }

        @Override
        protected void renderUserModel(UIContext context) {}
    }

    private static void testAutomaticEdits()
    {
        AutoTimeline view = new AutoTimeline();
        KeyframeChannel<Float> a = new KeyframeChannel<>("a", KeyframeFactories.FLOAT);
        KeyframeChannel<Float> b = new KeyframeChannel<>("b", KeyframeFactories.FLOAT);
        a.insert(0F, 0F);
        a.insert(10F, 10F);
        b.insert(0F, 10F);
        b.insert(10F, 20F);
        UIKeyframeSheet first = sheet(a);
        UIKeyframeSheet second = sheet(b);
        view.addSheet(first);
        view.addSheet(second);
        first.selection.add(0);
        second.selection.add(0);
        view.tick = 2.5F;

        check((float) UIReplaysEditorUtils.sampleKeyframeValue(first, 2.5F) == 2.5F, "Display did not interpolate the playhead");
        check(a.getKeyframes().size() == 2, "Displaying the playhead inserted a keyframe");
        view.getGraph().setValue(4.5F, true, true);
        check(a.get(0).getValue() == 0F && b.get(0).getValue() == 10F, "Auto edit changed selected historical keys");
        check(a.get(1).getTick() == 2.5F && a.get(1).getValue() == 4.5F, "Auto edit lost fractional time/value");
        check(b.get(1).getTick() == 2.5F && b.get(1).getValue() == 14.5F, "Selected numeric tracks lost their relative values");
        view.getGraph().setValue(5.5F, true, true);
        check(a.getKeyframes().size() == 3 && a.get(1).getValue() == 5.5F, "Repeated edits duplicated the playhead key");

        KeyframeChannel<Pose> poses = new KeyframeChannel<>("pose", KeyframeFactories.POSE);
        Pose seed = new Pose();
        seed.getOrCreate("arm").translate.x = 3F;
        UIKeyframeSheet poseSheet = sheet(poses).seed(() -> seed);
        Keyframe<Pose> created = UIReplaysEditorUtils.ensureKeyframe(poseSheet, 0.5F);
        created.getValue().get("arm").translate.x = 8F;
        check(seed.get("arm").translate.x == 3F, "Auto keyframe aliases its static seed");
    }

    private static void testTypedTracks()
    {
        KeyframeChannel<Float> material = new KeyframeChannel<>("source", KeyframeFactories.FLOAT);
        material.insert(1.25F, 0.75F);
        MapType known = new MapType();
        known.putString("kind", "material_prop");
        known.putString("form", "0/1");
        known.putString("subject", "Material.001/detail");
        known.putString("prop", "metallic");
        known.putString("addon_note", "keep");
        known.put("channel", material.toData());
        MapType foreign = (MapType) known.copy();
        foreign.putString("kind", "addon:track");
        ListType tracks = new ListType();
        tracks.add(known);
        tracks.add(foreign);
        MapType input = new MapType();
        input.put("tracks", tracks);
        FormProperties properties = new FormProperties("properties");
        properties.fromData(input);
        String key = "0/1/materials.Material.001/detail.metallic";
        check(properties.properties.containsKey(key), "Typed material track did not become an editable channel");
        check((float) properties.properties.get(key).get(0).getValue() == 0.75F, "Typed track lost its value");
        ListType saved = properties.toData().asMap().getList("tracks");
        check(saved.size() == 2, "Unknown track kind was discarded");
        check(saved.get(0).asMap().getString("addon_note").equals("keep"), "Known typed track lost foreign metadata");
        check(saved.get(1).equals(foreign), "Unknown typed record was rewritten");
        FormProperties roundTrip = new FormProperties("properties");
        roundTrip.fromData(properties.toData());
        check(roundTrip.properties.get(key).get(0).getTick() == 1.25F, "Typed reload changed fractional keyframe time");
    }

    private static void testConstraintPlayback()
    {
        ModelForm form = new ModelForm();
        FormProperties properties = new FormProperties("properties");
        String key = ConstraintTracks.key("", "arm/ik_controls");
        BoneConstraint a = new BoneConstraint();
        a.limitX = true;
        a.minX = -30F;
        BoneConstraint b = a.copy();
        b.minX = -10F;
        KeyframeChannel channel = properties.getOrCreate(form, key);
        channel.insert(0F, a);
        channel.insert(10F, b);
        properties.applyProperties(form, 5F);
        check(form.bones.getBone("arm/ik_controls").constraints.get().minX == -20F, "Constraint track did not reach solver properties");
        check(form.bones.getBone("arm/ik_controls").constraints.getOriginalValue().minX == -180F, "Constraint playback changed static limits");
        properties.resetProperties(form);
        check(form.bones.getBone("arm/ik_controls").constraints.getRuntimeValue() == null, "Constraint reset retained runtime limits");
    }

    private static void testReplayFolders()
    {
        try
        {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafe.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            HeadlessFilmPanel panel = (HeadlessFilmPanel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), HeadlessFilmPanel.class);
            panel.film = new Film();
            Replay first = panel.film.replays.addReplay();
            Replay second = panel.film.replays.addReplay();
            Replay root = panel.film.replays.addReplay();
            first.label.set("Guard Alpha");
            second.label.set("Guard Beta");
            first.category.set("Crowd/Guards/Front");
            second.category.set("Crowd/Guards");
            String firstId = first.getId();
            String secondId = second.getId();
            String rootId = root.getId();
            panel.film.replayCategories.ensure("Crowd/Guards/Front");
            panel.film.replayCategories.getByPath("Crowd").color.set(0x123456);
            QuietReplayList list = new QuietReplayList(panel);
            list.refreshReplayList();
            check(list.getList().size() == 6, "Nested folder tree lost ancestors or replay rows");
            ReplayListEntry front = list.getList().stream().filter(e -> e.isFolder() && e.folderPath.endsWith("Front")).findFirst().orElseThrow();
            check(front.depth == 2 && front.count == 1 && front.color == 0x123456, "Folder depth/count/inherited color are wrong");
            panel.film.replayCategories.setExpanded("Crowd", false);
            list.refreshReplayList();
            check(list.getList().size() == 2, "Collapsing a parent left nested rows visible");
            list.filter("Alpha");
            check(list.matches() == 1, "Search did not reach a collapsed descendant");
            list.filter("");
            invoke(list, "selectCategory", new Class<?>[]{String.class}, "Crowd");
            check(list.getSelectedReplays().size() == 2, "Folder selection missed a nested replay");
            invoke(list, "setCategoryEnabled", new Class<?>[]{String.class, boolean.class}, "Crowd/Guards", false);
            check(!first.enabled.get() && !second.enabled.get() && root.enabled.get(), "Folder enable operation crossed its subtree boundary");
            String renamed = list.applyCategoryEdit("Crowd/Guards", "Actors", 0x654321);
            check(renamed.equals("Crowd/Actors") && first.category.get().equals("Crowd/Actors/Front"), "Folder rename failed to move descendants");
            list.pickReplays(List.of(first, second));
            list.reorderBefore(first, root);
            check(first.category.get().isEmpty() && second.category.get().isEmpty(), "Multi-drag did not move every selected replay");
            check(panel.film.replays.getList().equals(List.of(first, second, root)), "Multi-drag changed selection order");
            check(first.getId().equals(firstId) && second.getId().equals(secondId) && root.getId().equals(rootId), "Folder drag renumbered replay IDs");
            Film restored = new Film();
            restored.fromData(panel.film.toData());
            check(restored.replayCategories.getByPath("Crowd/Actors").color.get() == 0x654321, "Folder color/order metadata did not survive save/load");
            restored.replayCategoryNames.get().add("Legacy/Child");
            check(restored.replayCategories.getByPath("Legacy/Child") != null, "Legacy addon category-name field no longer updates metadata");
            check(CategoryPath.depth(Replay.normalizeCategory("1/2/3/4/5/6/7/8/9")) == 8, "Replay folder depth is not capped at eight");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void invoke(UIReplayList list, String method, Class<?>[] types, Object... arguments) throws ReflectiveOperationException
    {
        Method action = UIReplayList.class.getDeclaredMethod(method, types);
        action.setAccessible(true);
        action.invoke(list, arguments);
    }

    private static void testReplayFolderDragOwner()
    {
        try
        {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafe.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            HeadlessFilmPanel panel = (HeadlessFilmPanel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), HeadlessFilmPanel.class);
            panel.film = new Film();
            Replay first = panel.film.replays.addReplay();
            Replay second = panel.film.replays.addReplay();
            Replay target = panel.film.replays.addReplay();
            Replay root = panel.film.replays.addReplay();
            first.label.set("First");
            first.category.set("A/Sub");
            second.category.set("A");
            target.category.set("B");
            panel.film.replayCategories.ensure("A/Sub");
            panel.film.replayCategories.ensure("B");
            String categoryId = panel.film.replayCategories.getByPath("A").getId();
            panel.film.replayCategories.getByPath("A").color.set(0x123456);
            List<String> ids = List.of(first.getId(), second.getId(), target.getId(), root.getId());
            QuietReplayList list = new QuietReplayList(panel);
            UIBaseMenu menu = new UIBaseMenu() {};
            menu.main.add(list);
            list.area.set(0, 0, 220, 400);
            list.refreshReplayList();
            UIContext context = menu.context;

            list.press(context, list.folderRow("A"));
            check(list.drag.isPressed() && panel.film.replayCategories.isExpanded("A"),
                "Folder press folded immediately instead of arming the shared drag");
            context.mouseButton = 1;
            list.subMouseReleased(context);
            check(list.drag.isPressed() && panel.film.replayCategories.isExpanded("A"),
                "Foreign release ended or folded the shared folder gesture");
            context.mouseButton = 0;
            list.subMouseReleased(context);
            check(!list.drag.isPressed() && !panel.film.replayCategories.isExpanded("A"),
                "Owned folder click did not perform its deferred fold");
            list.press(context, list.folderRow("A"));
            list.subMouseReleased(context);
            check(panel.film.replayCategories.isExpanded("A"), "A second folder click did not reopen it");

            list.press(context, list.folderRow("A"));
            context.mouseY = list.rowY(list.folderRow("B"));
            context.mouseButton = 1;
            list.cancel(context);
            check(list.drag.isPressed(), "Foreign cancellation retired a folder's shared owner");
            context.mouseButton = 0;
            list.cancel(context);
            list.subMouseReleased(context);
            check(!list.drag.isPressed() && first.category.get().equals("A/Sub")
                    && panel.film.replayCategories.isExpanded("A"),
                "Canceled folder drag committed a move or its pending fold");

            list.press(context, list.folderRow("A"));
            context.mouseY = list.rowY(list.folderRow("B"));
            list.subMouseReleased(context);
            check(first.category.get().equals("B/A/Sub") && second.category.get().equals("B/A")
                    && categoryId.equals(panel.film.replayCategories.getByPath("B/A").getId())
                    && panel.film.replayCategories.getByPath("B/A").color.get() == 0x123456,
                "Shared folder container drop did not carry subtree paths and metadata together");
            MapType beforeCycle = panel.film.toData().asMap().copy().asMap();
            list.press(context, list.folderRow("B"));
            context.mouseY = list.rowY(list.folderRow("B/A/Sub"));
            list.subMouseReleased(context);
            check(beforeCycle.equals(panel.film.toData()), "A folder was dropped into its own descendant");

            list.press(context, list.folderRow("B/A"));
            context.mouseY = list.getList().size() * list.rowHeight() + 12;
            list.subMouseReleased(context);
            check(first.category.get().equals("A/Sub") && second.category.get().equals("A"),
                "Folder caret drop past the last row did not move the subtree to root");
            list.pickReplays(List.of(second, first));
            list.press(context, list.replayRow(first));
            check(list.drag.getItems().size() == 2, "Shared replay drag discarded the group selection");
            context.mouseY = list.getList().size() * list.rowHeight() + 12;
            context.mouseButton = 1;
            list.subMouseReleased(context);
            check(list.drag.getItems().size() == 2 && first.category.get().equals("A/Sub"),
                "Foreign replay release committed the selected group");
            context.mouseButton = 0;
            list.subMouseReleased(context);
            check(first.category.get().isEmpty() && second.category.get().isEmpty()
                    && panel.film.replays.getList().equals(List.of(target, root, first, second))
                    && list.getSelectedReplays().equals(List.of(first, second)),
                "Shared replay caret drop did not preserve visual group order or selection");
            check(ids.equals(List.of(first.getId(), second.getId(), target.getId(), root.getId())),
                "Shared replay/folder moves rewrote stable replay ids");

            list.callback = (entries) -> { throw new IllegalStateException("fixture selection failure"); };
            try
            {
                list.press(context, list.folderRow("A"));
                throw new AssertionError("Folder callback failure was swallowed");
            }
            catch (IllegalStateException expected)
            {}
            check(!list.drag.isPressed(), "Folder callback failure left the shared owner or payload active");
            list.callback = null;
            list.press(context, list.folderRow("A"));
            list.cancel(context);
            check(!list.drag.isPressed(), "Folder callback failure prevented the next shared gesture");
            System.out.println("Replay folder shared owner tests passed");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static final class HeadlessFilmPanel extends UIFilmPanel
    {
        Film film;

        private HeadlessFilmPanel() { super(null); }

        @Override
        public Film getData() { return this.film; }
    }

    private static final class QuietReplayList extends UIReplayList
    {
        QuietReplayList(UIFilmPanel panel) { super(null, null, panel); }

        @Override
        protected void updateFilmEditor() {}

        int matches() { return this.getFilteredSize(); }

        int rowY(int index) { return index * this.rowHeight() + this.rowHeight() / 2; }

        int folderRow(String path)
        {
            for (int i = 0; i < this.getList().size(); i++)
                if (this.getList().get(i).isFolder() && this.getList().get(i).folderPath.equals(path)) return i;
            throw new AssertionError("Folder row missing: " + path);
        }

        int replayRow(Replay replay)
        {
            for (int i = 0; i < this.getList().size(); i++)
                if (this.getList().get(i).replay == replay) return i;
            throw new AssertionError("Replay row missing");
        }

        void press(UIContext context, int index)
        {
            context.mouseX = 70;
            context.mouseY = this.rowY(index);
            context.mouseButton = 0;
            this.subMouseClicked(context);
        }

        void cancel(UIContext context) { this.subMouseCanceled(context); }

        void pickReplays(List<Replay> replays)
        {
            List<ReplayListEntry> entries = new ArrayList<>();
            for (Replay replay : replays)
                for (ReplayListEntry entry : this.getList())
                    if (entry.replay == replay) entries.add(entry);
            this.selection.setAll(entries);
        }

        void reorderBefore(Replay from, Replay to)
        {
            int fromIndex = -1;
            int toIndex = -1;
            for (int i = 0; i < this.getList().size(); i++)
            {
                ReplayListEntry entry = this.getList().get(i);
                if (entry.replay == from) fromIndex = i;
                if (entry.replay == to) toIndex = i;
            }
            this.handleSwap(fromIndex, toIndex);
        }
    }

    private static UIKeyframeSheet sheet(KeyframeChannel channel)
    {
        return new UIKeyframeSheet(channel.getId(), IKey.constant(channel.getId()), 0, false, channel, null);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class AutoTimeline extends UIKeyframes
    {
        Float tick;

        AutoTimeline()
        {
            super(null);
        }

        @Override
        public Float getAutoKeyframeTick()
        {
            return this.tick;
        }
    }
}
