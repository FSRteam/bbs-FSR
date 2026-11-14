package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.FormPropertyAliases;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.cubic.ik.IKControls;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.tracks.TrackBehaviours;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.states.AnimationState;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.UIFormEditor;
import mchorse.bbs_mod.ui.forms.editors.states.keyframes.UIAnimationStateEditor;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIClickable;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.pose.Pose;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs the shared runtime and its actual timeline consumers without an OpenGL context. */
public final class TrackRuntimeParityTest
{
    private static final String CANONICAL = "tracktest:strength";
    private static final String LEGACY = "tracktest_legacy_strength";

    public static void runAll()
    {
        try
        {
            Field registry = BBSMod.class.getDeclaredField("forms");
            registry.setAccessible(true);
            Object previous = registry.get(null);
            FormArchitect architect = new FormArchitect();
            architect.register(Link.bbs("anchor"), AnchorForm.class, null);
            architect.register(Link.bbs("model"), ModelForm.class, null);
            architect.register(Link.create("tracktest:form"), AddonForm.class, null);
            registry.set(null, architect);
            try
            {
                testAliasMigration();
                testTypedRuntimePhases();
                testOverlayActions();
                testTimelineConsumers();
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

    private static void testAliasMigration()
    {
        FormPropertyAliases.register(LEGACY, CANONICAL);
        for (boolean canonicalFirst : List.of(false, true))
        {
            MapType data = new MapType();
            if (canonicalFirst) data.putFloat(CANONICAL, 7F);
            data.putFloat(LEGACY, 3F);
            if (!canonicalFirst) data.putFloat(CANONICAL, 7F);
            ListType disabled = new ListType();
            disabled.addString(LEGACY);
            data.put("disabled_tracks", disabled);
            AddonForm form = new AddonForm();
            form.fromData(data);
            check(form.strength.get() == 7F, "Canonical form value did not win an alias collision");
            check(form.disabledTracks.get().contains(CANONICAL), "Disabled form track was not migrated");
            check(!form.toData().asMap().has(LEGACY), "Legacy property leaked back into the form save");

            KeyframeChannel<Float> old = new KeyframeChannel<>(LEGACY, KeyframeFactories.FLOAT);
            old.insert(0F, 3F);
            KeyframeChannel<Float> current = new KeyframeChannel<>(CANONICAL, KeyframeFactories.FLOAT);
            current.insert(0F, 7F);
            MapType channels = new MapType();
            if (canonicalFirst) channels.put(CANONICAL, current.toData());
            channels.put(LEGACY, old.toData());
            if (!canonicalFirst) channels.put(CANONICAL, current.toData());
            FormProperties properties = new FormProperties("properties");
            properties.fromData(channels);
            properties.applyProperties(form, 0F);
            check(form.strength.get() == 7F && properties.tracks.size() == 1,
                "Legacy track alias displaced its canonical channel");

            ListType typed = new ListType();
            if (canonicalFirst) typed.add(record(CANONICAL, current.toData()));
            typed.add(record(LEGACY, old.toData()));
            if (!canonicalFirst) typed.add(record(CANONICAL, current.toData()));
            MapType input = new MapType();
            input.put("tracks", typed);
            properties.fromData(input);
            MapType saved = properties.toData().asMap().getList("tracks").get(0).asMap();
            check(saved.getString("subject").equals(CANONICAL) && saved.getString("addon_metadata").equals("retained"),
                "Typed alias lost its canonical id or record metadata");
            form.strength.set(0F);
            properties.applyProperties(form, 0F);
            check(form.strength.get() == 7F, "Typed alias collision changed playback");

            Map.Entry<String, KeyframeChannel> entry = properties.properties.entrySet().iterator().next();
            entry.setValue(old);
            check(properties.get(TrackId.property("", CANONICAL)) == old
                && properties.get(CANONICAL) == old, "Legacy map entry replacement did not write through");
        }
    }

    private static MapType record(String subject, BaseType channel)
    {
        MapType record = new MapType();
        record.putString("kind", "property");
        record.putString("form", "");
        record.putString("subject", subject);
        record.putString("addon_metadata", "retained");
        record.put("channel", channel);
        return record;
    }

    private static void testTypedRuntimePhases()
    {
        ModelForm root = new ModelForm();
        ModelForm child = new ModelForm();
        BodyPart part = new BodyPart("");
        part.setForm(child);
        root.parts.add(part);
        String path = FormUtils.getPath(child);
        FormProperties properties = new FormProperties("properties");
        TrackId controlsId = TrackId.ikControls(path);
        KeyframeChannel<IKControls> controls = properties.getOrCreate(controlsId);
        IKControls value = new IKControls();
        value.get("hand").weight = 0.35F;
        controls.insert(0F, value);
        TrackId targetId = TrackId.ikTarget(path, "hand");
        KeyframeChannel<Anchor> targets = properties.getOrCreate(targetId);
        Anchor anchor = new Anchor();
        anchor.replay = "1234abcd";
        targets.insert(0F, anchor);
        int[] resolved = {0};
        TrackContext frame = TrackContext.frame(root, 0.25F, (bound, transition) ->
        {
            resolved[0]++;
            check(transition == 0.25F, "Target resolver lost frame transition");
            return new Vector3f(4F, 5F, 6F);
        });
        properties.apply(TrackContext.of(root), 0F, 1F);
        check(child.ikControlOverrides.isEmpty(), "Animation-state playback wrote solver overrides");
        properties.apply(frame, 0F, 1F, id -> id.kind() != TrackKind.IK_TARGET);
        check(resolved[0] == 0 && child.ikTargetOverrides.isEmpty(), "Target ran before the spatial phase");
        check(child.bones.getBone("hand").ik.get().weight == 0.35F
            && child.ikControlOverrides.get("hand").weight == 0.35F, "Typed solver scalar and compatibility adapter disagree");
        properties.apply(frame, 0F, 1F, id -> id.kind() == TrackKind.IK_TARGET);
        check(resolved[0] == 1 && child.ikTargetOverrides.get("hand").equals(new Vector3f(4F, 5F, 6F)),
            "Spatial track did not reach the nested form");
        properties.remove(controlsId);
        properties.remove(targetId);
        TrackBehaviours.clearOverrides(root);
        check(child.ikControlOverrides.isEmpty() && child.ikTargetOverrides.isEmpty()
            && child.bones.getBone("hand").ik.get().weight == 1F, "Deleting a solver track left a stale runtime override");
    }

    private static void testOverlayActions()
    {
        int poses = BBSSettings.recordingPoseOverlays.get();
        int transforms = BBSSettings.recordingTransformOverlays.get();
        try
        {
            BBSSettings.recordingPoseOverlays.set(0);
            BBSSettings.recordingTransformOverlays.set(0);
            ModelForm form = new ModelForm();
            FormProperties properties = new FormProperties("properties");
            KeyframeChannel<Pose> poseChannel = properties.create(form.pose);
            UIKeyframeSheet pose = new UIKeyframeSheet(0xFFFFFF, false, poseChannel, form.pose);
            ContextMenuManager menu = new ContextMenuManager();
            TrackId[] refreshed = {null};
            UIReplaysEditorUtils.addOverlayTrackAction(menu, pose, parent -> refreshed[0] = parent);
            check(menu.actions.size() == 1, "Pose row does not offer overlay creation");
            menu.actions.getFirst().runnable.run();
            check(form.additionalOverlays.size() == 1 && BBSSettings.recordingTransformOverlays.get() == 0
                && refreshed[0].equals(TrackId.property("", "pose")), "Pose overlay action changed the wrong global count");
            Pose value = new Pose();
            value.getOrCreate("hand").translate.x = 2F;
            KeyframeChannel<Pose> overlay = properties.create(form.additionalOverlays.getFirst());
            overlay.insert(2F, value);
            MapType saved = (MapType) properties.toData().copy();
            overlay.insert(5F, new Pose());
            properties.fromData(saved);
            check(properties.get(TrackId.property("", "pose_overlay0")).getKeyframes().size() == 1,
                "Overlay channel snapshot failed to restore the authored keys");
            ModelForm restored = new ModelForm();
            restored.fromData(form.toData());
            FormProperties reloaded = new FormProperties("properties");
            reloaded.fromData(properties.toData());
            reloaded.applyProperties(restored, 2F);
            check(restored.additionalOverlays.getFirst().get().get("hand").translate.x == 2F,
                "Authored pose overlay failed save/reload/playback");

            AnchorForm plain = new AnchorForm();
            FormProperties plainProperties = new FormProperties("properties");
            UIKeyframeSheet transform = new UIKeyframeSheet(0xFFFFFF, false, plainProperties.create(plain.transform), plain.transform);
            menu = new ContextMenuManager();
            UIReplaysEditorUtils.addOverlayTrackAction(menu, transform, parent -> {});
            menu.actions.getFirst().runnable.run();
            TrackDescriptor child = TrackCatalog.forPart(plain, plainProperties, "").stream()
                .filter(track -> track.id().subject().equals("transform_overlay0")).findFirst().orElseThrow();
            check(child.parent().equals(TrackId.property("", "transform")) && BBSSettings.recordingPoseOverlays.get() == 1,
                "Transform overlay is missing from the shared hierarchy or changed pose overlays");
            List<UIKeyframeSheet> sheets = new ArrayList<>();
            UIReplaysEditorUtils.buildSheets(TrackCatalog.forPart(plain, plainProperties, ""), sheets);
            sheets.removeIf(sheet -> sheet.id.equals("transform"));
            UIReplaysEditorUtils.pruneTree(sheets);
            check(sheets.stream().filter(sheet -> sheet.id.equals("transform_overlay0")).allMatch(sheet -> sheet.parent == null),
                "Filtering an overlay parent made its authored child inaccessible");
        }
        finally
        {
            BBSSettings.recordingPoseOverlays.set(poses);
            BBSSettings.recordingTransformOverlays.set(transforms);
        }
    }

    private static void testTimelineConsumers() throws ReflectiveOperationException
    {
        TrackCategory addon = new TrackCategory("tracktest:category", Icons.KEY, IKey.constant("Addon"), IKey.constant("Addon tracks"));
        TrackCategories.register(addon, (track, owned) -> owned && track.subject().equals(CANONICAL));
        AddonForm root = new AddonForm();
        BodyPart selected = new BodyPart("");
        selected.setForm(new AddonForm());
        root.parts.add(selected);
        BodyPart other = new BodyPart("");
        other.setForm(new AddonForm());
        root.parts.add(other);
        String path = selected.getId();

        UIFormEditor host = allocate(UIFormEditor.class);
        host.form = root;
        UIAnimationStateEditor states = new UIAnimationStateEditor(host);
        AnimationState state = new AnimationState("state");
        states.setState(state);
        states.selectPart(path);
        check(states.getSelectedPart().equals(path) && states.keyframeEditor.view.getDopeSheet().getSheets().stream()
            .allMatch(sheet -> TrackId.parse(sheet.id).formPath().equals(path)), "State timeline mixed body-part owners");
        MapType undo = new MapType();
        states.collectUndoData(undo);
        root.parts.moveBodyPart(selected, 1);
        states.setState(state);
        check(states.getSelectedPart().equals(path), "Reordering body parts changed the state selection");
        states.selectPart("");
        states.applyUndoData(undo);
        check(states.getSelectedPart().equals(path), "State undo did not restore the stable body part");
        clickCategory(states, "categoryButtons", addon);
        check(states.keyframeEditor.view.getDopeSheet().getSheets().size() == 1
            && states.keyframeEditor.view.getDopeSheet().getSheets().getFirst().id.equals(path + "/" + CANONICAL),
            "Registered addon category button did not filter the state timeline");

        HeadlessFilmPanel panel = allocate(HeadlessFilmPanel.class);
        panel.film = new Film();
        panel.editArea = new UIElement();
        UIReplaysEditor replays = new UIReplaysEditor(panel);
        Replay replay = new Replay("1234abcd");
        replay.form.set(root);
        panel.film.replays.add(replay);
        set(replays, "film", panel.film);
        set(replays, "replay", replay);
        replays.selectBodyPart(path);
        clickCategory(replays, "tabButtons", addon);
        check(replays.keyframeEditor.view.getDopeSheet().getSheets().size() == 1
            && replays.keyframeEditor.view.getDopeSheet().getSheets().getFirst().id.equals(path + "/" + CANONICAL),
            "Registered addon category button did not filter the replay timeline");
        MapType replayUndo = new MapType();
        replays.collectUndoData(replayUndo);
        check(replayUndo.getString("replay_id").equals(replay.getId()) && replayUndo.getString("body_part").equals(path),
            "Replay undo snapshot lost stable replay/body-part ownership");
        root.parts.removeBodyPart(selected);
        states.setState(state);
        replays.selectBodyPart(path + "/removed");
        check(states.getSelectedPart().isEmpty() && replays.getSelectedPart().isEmpty(), "Removed body part did not fall back to the root");
    }

    @SuppressWarnings("unchecked")
    private static void clickCategory(Object editor, String field, TrackCategory category) throws ReflectiveOperationException
    {
        Field buttons = editor.getClass().getDeclaredField(field);
        buttons.setAccessible(true);
        UIIcon button = ((Map<TrackCategory, UIIcon>) buttons.get(editor)).get(category);
        check(button != null && button.getParent() != null, "Registered addon category has no visible button");
        /* Exercise the real category action without the unrelated client sound service. */
        Method click = UIClickable.class.getDeclaredMethod("click", int.class);
        click.setAccessible(true);
        click.invoke(button, 0);
    }

    private static void set(Object object, String field, Object value) throws ReflectiveOperationException
    {
        Field target = object.getClass().getDeclaredField(field);
        target.setAccessible(true);
        target.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException
    {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    public static class AddonForm extends AnchorForm
    {
        public final ValueFloat strength = new ValueFloat(CANONICAL, 1F);

        public AddonForm()
        {
            this.add(this.strength);
        }
    }

    private static class HeadlessFilmPanel extends UIFilmPanel
    {
        private Film film;

        private HeadlessFilmPanel()
        {
            super(null);
        }

        @Override
        public Film getData() { return this.film; }

        @Override
        public int getEditPanelTopOffsetPx() { return 0; }
    }

    private TrackRuntimeParityTest() {}
}
