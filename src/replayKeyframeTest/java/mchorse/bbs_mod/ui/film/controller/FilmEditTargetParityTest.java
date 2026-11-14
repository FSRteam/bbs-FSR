package mchorse.bbs_mod.ui.film.controller;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.states.AnimationState;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.settings.values.ui.ValueTrackStyles;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.states.keyframes.UIAnimationStateEditor;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIClickable;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIChoiceButton;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformSpace;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseTransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UITransformKeyframeFactory;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.context.ContextAction;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Executing pose-owner dispatch, conversion history and film target readouts. */
public final class FilmEditTargetParityTest
{
    private FilmEditTargetParityTest()
    {}

    public static void runAll() throws ReflectiveOperationException
    {
        Field registry = BBSMod.class.getDeclaredField("forms");
        registry.setAccessible(true);
        Object previous = registry.get(null);
        FormArchitect architect = new FormArchitect();
        architect.register(Link.bbs("model"), ModelForm.class);
        architect.register(Link.bbs("mob"), MobForm.class);
        architect.register(Link.bbs("posed_fixture"), AddonPosedForm.class);
        registry.set(null, architect);

        try
        {
            poseSheetsResolveTheirOwnCapability();
            poseConversionPreservesRestKeysAndHistory();
            animationStateMenuUsesSharedPoseDispatch();
            targetSpacesAndLabelsFollowTheActualEditor();
            sharedActionTimelineFollowsModeAndDockVisibility();
            System.out.println("FilmEditTargetParityTest passed");
        }
        finally
        {
            registry.set(null, previous);
        }
    }

    private static void poseSheetsResolveTheirOwnCapability()
    {
        ModelForm model = new ModelForm();
        MobForm mob = new MobForm();
        AddonPosedForm addon = new AddonPosedForm();
        check(sheet("pose", model.pose).getPoseForm() == model, "a model's pose did not resolve its owning model");
        UIKeyframeSheet mobSheet = sheet("pose", mob.pose);
        check(mobSheet.getPosedForm() == mob && mobSheet.getPoseForm() == null,
            "a mob pose was narrowed to a model or excluded from pose operations");
        Form root = new Form() {};
        BodyPart part = new BodyPart("");
        part.setForm(addon);
        root.parts.add(part);
        String path = FormUtils.getPath(addon);
        UIKeyframeSheet nested = sheet(path + "/pose", addon.pose);
        check(!path.isEmpty() && nested.getPosedForm() == addon && nested.getPoseForm() == null,
            "a nested addon pose borrowed the replay root's model");
        check(sheet(path + "/pose_overlay", addon.overlay).getPosedForm() == null
                && sheet(path + "/pose_overlay0/pose", addon.pose).getPosedForm() == null,
            "an additive overlay was offered as the whole pose");
        check(sheet("pose", null).getPosedForm() == null,
            "a property-less pose seed was treated as a form's pose");
        KeyframeChannel<Transform> wrongFactory = new KeyframeChannel<>("pose", KeyframeFactories.TRANSFORM);
        check(new UIKeyframeSheet(0xffffff, false, wrongFactory, addon.pose).getPosedForm() == null,
            "pose dispatch depended only on a channel's name");

        int previous = BBSSettings.recordingPoseOverlays.get();
        try
        {
            BBSSettings.recordingPoseOverlays.set(0);
            ContextMenuManager menu = new ContextMenuManager();
            List<TrackId> refreshed = new ArrayList<>();
            UIReplaysEditorUtils.addOverlayTrackAction(menu, nested, refreshed::add);
            check(menu.actions.size() == 1, "an addon pose lost the shared Add pose track action");
            menu.actions.getFirst().runnable.run();
            check(addon.additionalOverlays.size() == 1
                    && refreshed.equals(List.of(TrackId.property(path, "pose"))),
                "the shared overlay action updated a different form or expanded a different track");
        }
        finally
        {
            BBSSettings.recordingPoseOverlays.set(previous);
        }
    }

    private static void poseConversionPreservesRestKeysAndHistory()
    {
        AddonPosedForm addon = new AddonPosedForm();
        addon.setRenderer(new SkeletonRenderer(addon));
        Form root = new Form() {};
        BodyPart part = new BodyPart("");
        part.setForm(addon);
        root.parts.add(part);
        String path = FormUtils.getPath(addon);
        ValueGroup document = new ValueGroup("document");
        FormProperties properties = new FormProperties("properties");
        document.add(properties);
        TrackId poseId = TrackId.property(path, "pose");
        KeyframeChannel<Pose> channel = properties.register(poseId, KeyframeFactories.POSE);
        Pose moved = new Pose();
        moved.getOrCreate("arm").translate.x = 3F;
        Keyframe<Pose> first = channel.get(channel.insert(0F, moved));
        first.setDuration(2F);
        first.setShape(KeyframeShape.CIRCLE);
        channel.insert(10F, new Pose());
        UIKeyframeSheet pose = sheet(poseId.toKey(), addon.pose, channel);
        pose.selection.all();
        BaseType before = properties.toData();
        UIBaseMenu menu = new UIBaseMenu() {};
        UIFormUndoHandler history = new UIFormUndoHandler(menu.main);
        document.preCallback(history::handlePreValues);

        check(UIReplaysEditorUtils.posesToLimbTracks(properties, pose), "a nested addon pose failed to convert");
        KeyframeChannel<PoseTransform> arm = properties.get(TrackId.bone(path, "arm"));
        check(arm != null && arm.getKeyframes().size() == 2 && arm.get(0).getValue() != moved.get("arm")
                && arm.get(0).getValue().translate.x == 3F && arm.get(1).getValue().isDefault(),
            "conversion shared source values or omitted an animated bone's rest key");
        check(arm.get(0).getDuration() == 2F && arm.get(0).getShape() == KeyframeShape.CIRCLE,
            "conversion lost the source keyframe's shaping metadata");
        check(properties.get(TrackId.bone(path, "unused")) == null && channel.isEmpty() && !pose.selection.hasAny(),
            "conversion created untouched-bone tracks or left the converted source selected");
        BaseType after = properties.toData();
        history.submitUndo(true);
        check(history.getUndoManager().getTotalUndos() == 1
                && history.getUndoManager().undo(document) && before.equals(properties.toData()),
            "conversion undo did not restore pose keys and remove the newly created bone channel together");
        check(history.getUndoManager().redo(document) && after.equals(properties.toData()),
            "conversion redo lost its rest key, metadata or channel namespace");

        KeyframeChannel<Pose> untouched = new KeyframeChannel<>("pose", KeyframeFactories.POSE);
        untouched.insert(0F, new Pose());
        UIKeyframeSheet allDefault = sheet("pose", addon.pose, untouched);
        allDefault.selection.all();
        check(!UIReplaysEditorUtils.posesToLimbTracks(properties, allDefault)
                && untouched.getKeyframes().size() == 1 && allDefault.selection.hasAny()
                && after.equals(properties.toData()),
            "a conversion with no edited bones removed its source or changed the track collection");
    }

    private static void animationStateMenuUsesSharedPoseDispatch() throws ReflectiveOperationException
    {
        MobForm mob = new MobForm();
        mob.getBoneTracks().set(true);
        mob.setRenderer(new SkeletonRenderer(mob));
        UIFormEditor host = allocate(UIFormEditor.class);
        host.form = mob;
        UIAnimationStateEditor states = new UIAnimationStateEditor(host);
        UIBaseMenu menu = new UIBaseMenu() {};
        menu.main.add(states);
        AnimationState state = new AnimationState("conversion");
        KeyframeChannel<Pose> channel = state.properties.register(TrackId.property("", "pose"), KeyframeFactories.POSE);
        Pose pose = new Pose();
        pose.getOrCreate("arm").rotate.x = 0.5F;
        channel.insert(0F, pose);
        states.setState(state);
        Method category = UIAnimationStateEditor.class.getDeclaredMethod("setCategory", TrackCategory.class);
        category.setAccessible(true);
        category.invoke(states, TrackCategories.POSE);
        UIKeyframes view = states.keyframeEditor.view;
        UIKeyframeSheet poseSheet = view.getGraph().getSheets().stream()
            .filter(sheet -> sheet.id.equals("pose")).findFirst().orElseThrow();
        poseSheet.selection.all();
        menu.context.setMouse(10, view.getDopeSheet().getDopeSheetY(poseSheet) + 1, 1);
        UISimpleContextMenu popup = (UISimpleContextMenu) view.createContextMenu(menu.context);
        ContextAction conversion = popup.actions.getList().stream()
            .filter(action -> action.label == UIKeys.FILM_REPLAY_CONTEXT_POSES_TO_LIMBS).findFirst().orElseThrow();
        conversion.runnable.run();
        check(channel.isEmpty() && state.properties.get(TrackId.bone("", "arm")) != null
                && states.keyframeEditor.view.getGraph().getSheets().stream().anyMatch(sheet -> sheet.isBoneTrack)
                && states.keyframeEditor.view.getDopeSheet().getExpandedPoseTabIds().contains("pose"),
            "the animation-state menu did not convert the shared pose, expand its bone rows and rebuild: source="
                + channel.getKeyframes().size() + ", bone=" + state.properties.get(TrackId.bone("", "arm"))
                + ", rows=" + states.keyframeEditor.view.getGraph().getSheets().stream().map(sheet -> sheet.id).toList());
    }

    private static void targetSpacesAndLabelsFollowTheActualEditor() throws ReflectiveOperationException
    {
        ValueTrackStyles previous = BBSSettings.trackStyles;
        BBSSettings.trackStyles = new ValueTrackStyles("target-styles");

        try
        {
            UIKeyframeEditor editor = new UIKeyframeEditor(UIKeyframes::new);
            UIFilmPanel panel = allocate(UIFilmPanel.class);
            panel.replayEditor = allocate(UIReplaysEditor.class);
            panel.replayEditor.keyframeEditor = editor;
            TargetController controller = allocate(TargetController.class);
            set(UIFilmController.class, controller, "panel", panel);
            set(UIFilmController.class, controller, "addonTools", new ArrayList<>());
            controller.replay = new Replay("target-owner");
            controller.entity = new StubEntity();
            FilmControllerHud hud = new FilmControllerHud(controller);
            Form form = new Form() {};
            KeyframeChannel<Anchor> anchors = new KeyframeChannel<>("anchor", KeyframeFactories.ANCHOR);
            Keyframe<Anchor> anchor = anchors.get(anchors.insert(0F, new Anchor()));
            UIKeyframeSheet anchorSheet = new UIKeyframeSheet(0xffffff, false, anchors, form.anchor);
            editor.view.addSheet(anchorSheet);
            UIAnchorKeyframeFactory anchorEditor = new UIAnchorKeyframeFactory(anchor, editor.view);
            editor.editor = anchorEditor;
            BBSSettings.trackStyles.setName("anchor", "Attached prop");
            anchorSheet.applyStyle();

            for (TransformSpace space : TransformSpace.values())
            {
                selectSpace(anchorEditor.transform, space);
                FilmTarget target = controller.getEditTarget();
                check(editor.isFormAnchorTrack() && editor.getAnchorSpace() == space
                        && target.is(FilmTarget.Kind.ANCHOR) && target.space() == space,
                    "the anchor target collapsed " + space + " to a local/global flag");
                check(hud.getTargetLabel(target).equals("Attached prop"), "the HUD ignored a renamed anchor track");
            }

            editor.view.removeAllSheets();
            KeyframeChannel<Transform> transforms = new KeyframeChannel<>("0/transform", KeyframeFactories.TRANSFORM);
            Keyframe<Transform> transform = transforms.get(transforms.insert(0F, new Transform()));
            UIKeyframeSheet transformSheet = new UIKeyframeSheet("0/transform", IKey.raw("Transform"), 0xffffff, false, transforms, null);
            editor.view.addSheet(transformSheet);
            UITransformKeyframeFactory transformEditor = new UITransformKeyframeFactory(transform, editor.view);
            editor.editor = transformEditor;
            BBSSettings.trackStyles.setName("transform", "Held item transform");
            transformSheet.applyStyle();
            for (TransformSpace space : TransformSpace.values())
            {
                selectSpace(transformEditor.transform, space);
                FilmTarget target = controller.getEditTarget();
                check(target.is(FilmTarget.Kind.BONE) && target.bone().equals("0") && target.space() == space,
                    "a nested form transform lost its path or full transform space");
                check(hud.getTargetLabel(target).equals("Held item transform"),
                    "the HUD displayed a form path instead of the renamed transform track");
            }

            editor.view.removeAllSheets();
            AddonPosedForm addon = new AddonPosedForm();
            KeyframeChannel<Pose> poses = new KeyframeChannel<>("0/pose", KeyframeFactories.POSE);
            Keyframe<Pose> pose = poses.get(poses.insert(0F, new Pose()));
            UIKeyframeSheet poseSheet = sheet("0/pose", addon.pose, poses);
            editor.view.addSheet(poseSheet);
            UIPoseKeyframeFactory poseEditor = new UIPoseKeyframeFactory(pose, editor.view);
            poseEditor.poseEditor.groups.list.setList(List.of("arm", "leg"));
            poseEditor.poseEditor.groups.list.setCurrent("arm");
            editor.editor = poseEditor;
            for (TransformSpace space : TransformSpace.values())
            {
                selectSpace(poseEditor.poseEditor.transform, space);
                FilmTarget target = controller.getEditTarget();
                check(target.bone().equals("0/arm") && target.space() == space && hud.getTargetLabel(target).equals("arm"),
                    "a whole-pose target lost the selected bone, path or reference frame");
            }

            editor.view.removeAllSheets();
            String boneId = TrackId.bone("0", "arm").toKey();
            KeyframeChannel<PoseTransform> bones = new KeyframeChannel<>(boneId, KeyframeFactories.POSE_TRANSFORM);
            Keyframe<PoseTransform> bone = bones.get(bones.insert(0F, new PoseTransform()));
            UIKeyframeSheet boneSheet = new UIKeyframeSheet(boneId, IKey.raw("arm"), 0xffffff, false, bones, null, true);
            editor.view.addSheet(boneSheet);
            UIPoseTransformKeyframeFactory boneEditor = new UIPoseTransformKeyframeFactory(bone, editor.view);
            editor.editor = boneEditor;
            BBSSettings.trackStyles.setName(boneSheet.getFilterKey(), "Left hand");
            boneSheet.applyStyle();
            for (TransformSpace space : TransformSpace.values())
            {
                selectSpace(boneEditor.transform, space);
                FilmTarget target = controller.getEditTarget();
                check(target.bone().equals("0/arm") && target.space() == space && hud.getTargetLabel(target).equals("Left hand"),
                    "a bone track lost its full reference frame or renamed timeline title");
            }

            controller.recording = true;
            check(controller.getEditTarget().isNone(), "recording exposed an editable target");
            controller.recording = false;
            editor.view.removeAllSheets();
            editor.view.addSheet(new UIKeyframeSheet("ik_targets.arm", IKey.raw("IK target"), 0xffffff, false, anchors, null));
            editor.editor = anchorEditor;
            check(!editor.isFormAnchorTrack() && controller.getEditTarget().is(FilmTarget.Kind.ROOT),
                "a property-less IK anchor was treated as the form anchor");
            panel.replayEditor.keyframeEditor = null;
            check(hud.getTargetLabel(FilmTarget.root(TransformSpace.WORLD)).equals(TrackCategories.REPLAY.label.get())
                    && hud.getTargetLabel(FilmTarget.bone("0/arm", TransformSpace.PARENT)).equals("0/arm"),
                "HUD target fallback lost the replay category or stable bone path");
        }
        finally
        {
            BBSSettings.trackStyles = previous;
        }
    }

    private static void sharedActionTimelineFollowsModeAndDockVisibility() throws ReflectiveOperationException
    {
        TimelineHost panel = allocate(TimelineHost.class);
        panel.editArea = new UIElement();
        UIReplaysEditor editor = new UIReplaysEditor(panel);
        panel.replayEditor = editor;
        panel.cameraEditor = new UIClipsPanel(panel, BBSMod.getFactoryCameraClips());
        panel.cameraEditor.setVisible(false);
        ActionTimeline actions = new ActionTimeline(panel);
        actions.target(panel.editArea);
        actions.setClips(new Clips("actions", BBSMod.getFactoryActionClips()));
        panel.actionEditor = actions;
        KeyframeTimeline keys = new KeyframeTimeline();
        keys.target(panel.editArea);
        panel.editArea.add(keys.inspector, actions.inspector);
        editor.keyframeEditor = keys;
        editor.add(keys);
        editor.attachActionTimeline(actions);
        set(UIFilmPanel.class, panel, "panels", List.of(panel.cameraEditor, editor, actions));
        set(UIFilmPanel.class, panel, "selectedMainEditorPanel", editor);

        check(actions.getParent() == editor, "the action timeline kept a separate main-panel owner");
        for (boolean timelineVisible : new boolean[] {false, true})
        {
            for (boolean propertiesVisible : new boolean[] {false, true})
            {
                editor.setTimelineVisible(timelineVisible);
                editor.setPropertiesVisible(propertiesVisible);
                editor.setActionsMode(false);
                check(keys.view.isVisible() == timelineVisible && keys.inspector.isVisible() == propertiesVisible
                        && !actions.clips.isVisible() && !actions.inspector.isVisible(),
                    "keyframe mode did not respect independently docked timeline/properties visibility");
                editor.setActionsMode(true);
                check(!keys.view.isVisible() && !keys.inspector.isVisible()
                        && actions.clips.isVisible() == timelineVisible && actions.inspector.isVisible() == propertiesVisible
                        && actions.isVisible() == (timelineVisible || propertiesVisible),
                    "action mode leaked an inspector or tied its visibility to the timeline dock");
            }
        }

        editor.setTimelineVisible(true);
        editor.setPropertiesVisible(true);
        editor.setActionsMode(false);
        keys.view.getXAxis().area.set(0, 0, 1000, 200);
        actions.clips.scale.area.set(0, 0, 1000, 200);
        keys.view.getXAxis().view(12D, 88D);
        Field toggle = UIReplaysEditor.class.getDeclaredField("actionsToggle");
        toggle.setAccessible(true);
        /* Execute the real control callback without requiring a headless sound engine. */
        Method click = UIClickable.class.getDeclaredMethod("click", int.class);
        click.setAccessible(true);
        click.invoke((UIIcon) toggle.get(editor), 0);
        check(editor.isActionsMode() && actions.clips.scale.getMinValue() == 12D && actions.clips.scale.getMaxValue() == 88D,
            "the visible action control failed to switch modes or copy the keyframe viewport");
        actions.clips.scale.view(25D, 125D);
        panel.showPanel(1);
        check(!editor.isActionsMode() && keys.view.getXAxis().getMinValue() == 25D
                && keys.view.getXAxis().getMaxValue() == 125D && panel.getPanelIndex() == 1,
            "the replay adapter lost the action viewport or persisted panel identity: actions="
                + editor.isActionsMode() + ", range=" + keys.view.getXAxis().getMinValue() + ","
                + keys.view.getXAxis().getMaxValue() + ", index=" + panel.getPanelIndex());
        panel.showPanel(actions);
        check(editor.isActionsMode() && panel.getPanelIndex() == 2 && actions.getParent() == editor,
            "the public action-panel adapter bypassed the shared replay owner");
        Method toggleActions = UIFilmPanel.class.getDeclaredMethod("toggleActionTimeline");
        toggleActions.setAccessible(true);
        actions.clips.scale.view(30D, 140D);
        toggleActions.invoke(panel);
        check(!editor.isActionsMode() && panel.getPanelIndex() == 1
                && keys.view.getXAxis().getMinValue() == 30D && keys.view.getXAxis().getMaxValue() == 140D,
            "the action shortcut did not toggle back to keyframes with its viewport");
        keys.view.getXAxis().view(15D, 90D);
        toggleActions.invoke(panel);
        check(editor.isActionsMode() && panel.getPanelIndex() == 2
                && actions.clips.scale.getMinValue() == 15D && actions.clips.scale.getMaxValue() == 90D,
            "the repeated action shortcut did not restore actions with the keyframe viewport");
        int notifications = panel.modeChanges;
        panel.showPanel(-1);
        panel.showPanel(3);
        check(panel.modeChanges == notifications && editor.isActionsMode(),
            "an invalid persisted panel index changed the active timeline");

        CycleHost cycleHost = allocate(CycleHost.class);
        cycleHost.cameraEditor = panel.cameraEditor;
        cycleHost.replayEditor = editor;
        set(UIFilmPanel.class, cycleHost, "selectedMainEditorPanel", editor);
        Method cycle = UIFilmPanel.class.getDeclaredMethod("cycleMainEditor");
        cycle.setAccessible(true);
        cycle.invoke(cycleHost);
        check(cycleHost.requested == panel.cameraEditor, "cycling from actions visited a third main editor");
        set(UIFilmPanel.class, cycleHost, "selectedMainEditorPanel", panel.cameraEditor);
        cycle.invoke(cycleHost);
        check(cycleHost.requested == editor, "cycling from the camera skipped the replay owner");

        KeyframeTimeline replacement = new KeyframeTimeline();
        replacement.target(panel.editArea);
        panel.editArea.add(replacement.inspector);
        editor.keyframeEditor = replacement;
        Field generation = UIReplaysEditor.class.getDeclaredField("keyframeEditorGeneration");
        generation.setAccessible(true);
        Method replace = UIReplaysEditor.class.getDeclaredMethod("replaceKeyframeEditor", UIKeyframeEditor.class,
            UIKeyframeEditor.class, long.class, boolean.class);
        replace.setAccessible(true);
        replace.invoke(editor, keys, replacement, generation.getLong(editor), false);
        check(replacement.getParent() == editor && !replacement.view.isVisible() && !replacement.inspector.isVisible()
                && actions.clips.isVisible() && actions.inspector.isVisible(),
            "rebuilding the keyframe editor exposed it over the active action timeline");
        editor.showReplayTracks();
        check(!editor.isActionsMode(), "picking replay tracks left the action timeline active");
    }

    private static UIKeyframeSheet sheet(String id, ValuePose property)
    {
        return sheet(id, property, new KeyframeChannel<>(id, KeyframeFactories.POSE));
    }

    private static UIKeyframeSheet sheet(String id, ValuePose property, KeyframeChannel<Pose> channel)
    {
        return new UIKeyframeSheet(id, IKey.raw(id), 0xffffff, false, channel, property);
    }

    private static void selectSpace(UIPropTransform transform, TransformSpace space) throws ReflectiveOperationException
    {
        Field picker = UIPropTransform.class.getDeclaredField("spacePicker");
        picker.setAccessible(true);
        ((UIChoiceButton<TransformSpace>) picker.get(transform)).setValue(space);
    }

    private static void set(Class<?> owner, Object instance, String name, Object value) throws ReflectiveOperationException
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TimelineHost extends UIFilmPanel
    {
        private int modeChanges;

        private TimelineHost() { super(null); }
        @Override public int getEditPanelTopOffsetPx() { return 0; }
        @Override public boolean isFlying() { return false; }
        @Override public void onReplayTimelineModeChanged() { this.modeChanges++; }
    }

    private static final class ActionTimeline extends UIClipsPanel
    {
        private final UIElement inspector = new UIElement();

        private ActionTimeline(UIFilmPanel panel) { super(panel, BBSMod.getFactoryActionClips()); }
        @Override protected UIElement getPropertiesPanel() { return this.inspector; }
    }

    private static final class CycleHost extends UIFilmPanel
    {
        private UIElement requested;

        private CycleHost() { super(null); }
        @Override public void showPanel(UIElement element) { this.requested = element; }
    }

    private static final class KeyframeTimeline extends UIKeyframeEditor
    {
        private final UIElement inspector = new UIElement();

        private KeyframeTimeline() { super(UIKeyframes::new); }
        @Override protected UIElement getPropertiesPanel() { return this.inspector; }
    }

    private static final class TargetController extends UIFilmController
    {
        private Replay replay;
        private IEntity entity;
        private boolean recording;

        private TargetController() { super(null); }
        @Override public boolean isRecording() { return this.recording; }
        @Override Replay getReplay() { return this.replay; }
        @Override public IEntity getCurrentEntity() { return this.entity; }
    }

    private static final class AddonPosedForm extends Form implements IPosedForm
    {
        private final ValuePose pose = new ValuePose("pose", new Pose());
        private final ValuePose overlay = new ValuePose("pose_overlay", new Pose());

        private AddonPosedForm()
        {
            this.add(this.pose);
            this.add(this.overlay);
        }

        public ValuePose getPose() { return this.pose; }
        public ValuePose getPoseOverlay() { return this.overlay; }
        public boolean hasBoneTracks() { return true; }
    }

    private static final class SkeletonRenderer extends FormRenderer<Form>
    {
        private SkeletonRenderer(Form form) { super(form); }

        @Override
        public BoneHierarchy getBoneHierarchy()
        {
            return new BoneHierarchy(List.of(new BoneHierarchy.Bone("arm", "arm", null, 0, "main"),
                new BoneHierarchy.Bone("unused", "unused", null, 0, "main")));
        }

        @Override
        protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
        {}
    }
}
