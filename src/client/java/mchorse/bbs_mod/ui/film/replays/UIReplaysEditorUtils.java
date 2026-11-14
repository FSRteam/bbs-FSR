package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.ik.ModelIKRuntime;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControls;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.ConstraintTracks;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.tracks.MaterialTracks;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.ui.film.ICursor;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseTransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UITransformKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.pose.PoseBones;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.keyframes.factories.SoundKeyframeFactory;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class UIReplaysEditorUtils
{
    private static final int BONE_TRACK_HUE_COUNT = 12;

    /** The overlay creation action is shared by Film and animation-state timelines. */
    public static void addOverlayTrackAction(ContextMenuManager menu, UIKeyframeSheet sheet, Consumer<TrackId> refresh)
    {
        if (sheet == null) return;
        TrackId track = TrackId.parse(sheet.id);
        Form owner = UIReplaysEditor.getSheetForm(sheet);
        if (track == null || track.kind() != TrackKind.PROPERTY || owner == null) return;
        boolean pose = track.subject().equals("pose") || track.subject().startsWith("pose_overlay");
        boolean transform = track.subject().equals("transform") || track.subject().startsWith("transform_overlay");
        if ((!pose && !transform) || (pose && !(owner instanceof IPosedForm))) return;
        ValueInt count = pose ? BBSSettings.recordingPoseOverlays : BBSSettings.recordingTransformOverlays;
        if (count == null || count.get() >= count.getMax()) return;
        menu.action(Icons.ADD, L10n.lang(pose ? "bbs.ui.keyframes.context.add_pose_track" : "bbs.ui.keyframes.context.add_transform_track"), () ->
        {
            if (count.get() >= count.getMax()) return;
            count.set(count.get() + 1);
            owner.syncOverlayTracks();
            refresh.accept(TrackId.property(track.formPath(), pose ? "pose" : "transform"));
        });
    }

    public static void buildSheets(List<TrackDescriptor> catalog, List<UIKeyframeSheet> sheets)
    {
        Map<TrackId, UIKeyframeSheet> rows = new HashMap<>();
        for (TrackDescriptor track : catalog)
        {
            UIKeyframeSheet sheet = new UIKeyframeSheet(track);
            rows.put(track.id(), sheet);
            sheets.add(sheet);
        }
        for (TrackDescriptor track : catalog)
            if (track.parent() != null) rows.get(track.id()).setParent(rows.get(track.parent()));
    }

    public static void pruneTree(List<UIKeyframeSheet> sheets)
    {
        Set<UIKeyframeSheet> present = new HashSet<>(sheets);
        for (UIKeyframeSheet sheet : sheets)
        {
            sheet.children.removeIf(child -> !present.contains(child));
            if (sheet.parent != null && !present.contains(sheet.parent)) sheet.setParent(null);
        }
    }

    public static void insertPoseKeyframesAtTick(Replay replay, float tick, Set<String> expandedPoseIds)
    {
        FoldState<String> expanded = new FoldState<>();

        if (expandedPoseIds != null)
        {
            expanded.expandAll(expandedPoseIds);
        }

        insertPoseKeyframesAtTick(replay, tick, expanded);
    }

    /** The active timeline supplies its shared folds, including branches absent from the current tab. */
    public static void insertPoseKeyframesAtTick(Replay replay, float tick, FoldState<String> expandedPoseIds)
    {
        if (replay == null)
        {
            return;
        }

        FoldState<String> expanded = expandedPoseIds == null ? new FoldState<>() : expandedPoseIds;
        Form form = replay.form.get();
        BaseValue.edit(replay.properties, (props) ->
        {
            for (Map.Entry<TrackId, KeyframeChannel> entry : props.tracks.entrySet())
            {
                TrackId track = entry.getKey();
                KeyframeChannel<?> channel = entry.getValue();
                String id = track.toKey();

                if (track.is(TrackKind.BONE))
                {
                    String parent = TrackId.property(track.formPath(), FormProperties.POSE_PROPERTY).toKey();

                    if (!expanded.isExpanded(parent))
                    {
                        continue;
                    }

                    KeyframeChannel<PoseTransform> poseChannel = (KeyframeChannel<PoseTransform>) channel;
                    KeyframeSegment<PoseTransform> segment = poseChannel.find(tick);
                    PoseTransform value = segment != null ? segment.createInterpolated() : new PoseTransform();

                    /* insertInheriting is this exact insert + left-neighbour inherit, so the value is
                     * still looked up here and the shaping is delegated. */
                    poseChannel.insertInheriting(tick, value);

                    continue;
                }

                if (track.is(TrackKind.PROPERTY) && track.subject().equals(FormProperties.POSE_PROPERTY)
                    && channel.getFactory() == KeyframeFactories.POSE && !expanded.isExpanded(id))
                {
                    KeyframeChannel<Pose> poseChannel = (KeyframeChannel<Pose>) channel;
                    KeyframeSegment<Pose> segment = poseChannel.find(tick);
                    Pose value;

                    if (segment != null)
                    {
                        value = segment.createInterpolated();
                    }
                    else
                    {
                        BaseValue property = form == null ? null : FormUtils.getProperty(form, id);
                        Object current = property instanceof BaseValueBasic basic ? basic.get() : null;
                        value = current instanceof Pose pose ? poseChannel.getFactory().copy(pose) : poseChannel.getFactory().createEmpty();
                    }

                    /* As above: the value is resolved here (from the neighbour, the live property or
                     * an empty pose), the shaping comes from insertInheriting. */
                    poseChannel.insertInheriting(tick, value);
                }
            }
        });
    }

    public static void addBoneTrackSheets(Form form, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addBoneTrackSheets(form, properties, out, null);
    }

    /** One per-form track whose value contains the four glint fields for every bone. */
    public static void addGlintControlSheet(Form form, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(form, properties, out, track -> FormControlKeys.isGlintControlChannel(track.key()), null);
    }

    private static GlintControls buildGlintControls(PoseForm form, BoneHierarchy hierarchy)
    {
        GlintControls controls = new GlintControls();

        for (BoneHierarchy.Bone bone : hierarchy.getBones())
        {
            controls.get(bone.id()).copy(form.getPose().get().get(bone.id()));
        }

        return controls;
    }

    public static void addBoneTrackSheets(Form form, FormProperties properties, List<UIKeyframeSheet> out, Map<String, Integer> depthBySheetId)
    {
        addCatalogSheets(form, properties, out,
            track -> track.kind() == TrackKind.BONE || track.kind() == TrackKind.BONE_CONSTRAINT, depthBySheetId);
    }

    private static int getBoneDepth(IModel model, String bone)
    {
        int depth = 0;
        String current = bone;

        while (current != null && !current.isEmpty())
        {
            current = model.getParentGroupKey(current);

            if (current != null && !current.isEmpty())
            {
                depth++;
            }
        }

        return Math.max(0, depth);
    }

    /**
     * One texture track per model material (OBJ material name / BOBJ mesh name), enumerated from
     * the loaded model. Each is a LINK channel layered over the material's static default at
     * playback - mirrors the bone tracks. Lives in the Model category beside the main texture track.
     */
    public static void addMaterialTextureSheets(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.MATERIAL_TEXTURE, null);
    }

    public static void addMaterialPropertySheets(ModelForm form, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(form, properties, out, track -> track.kind() == TrackKind.MATERIAL_PROP, null);
    }

    public static Object sampleKeyframeValue(UIKeyframeSheet sheet, float tick)
    {
        if (sheet == null) return null;
        KeyframeSegment segment = sheet.channel.find(tick);
        if (segment != null) return segment.createInterpolated();
        if (sheet.property != null) return sheet.channel.getFactory().copy(sheet.property.get());
        return sheet.seed == null ? sheet.channel.getFactory().createEmpty()
            : sheet.channel.getFactory().copy(sheet.seed.get());
    }

    public static <T> Keyframe<T> ensureKeyframe(UIKeyframeSheet sheet, float tick)
    {
        if (sheet == null)
        {
            return null;
        }

        tick = sheet.channel.getSourceTick(tick);

        for (Keyframe<T> keyframe : (List<Keyframe<T>>) sheet.channel.getKeyframes())
        {
            if (keyframe.getTick() == tick)
            {
                return keyframe;
            }
        }

        KeyframeSegment<T> segment = sheet.channel.find(tick);
        BaseValueBasic property = sheet.property;
        T value;

        if (segment != null)
        {
            value = segment.createInterpolated();
        }
        else if (property != null)
        {
            value = (T) sheet.channel.getFactory().copy(property.get());
        }
        else if (sheet.seed != null)
        {
            value = (T) sheet.channel.getFactory().copy(sheet.seed.get());
        }
        else
        {
            value = (T) sheet.channel.getFactory().createEmpty();
        }

        /* The lookup above guarantees no keyframe sits on the tick, so insertInheriting cannot
         * inherit from the keyframe it just wrote — only from the neighbour, as the hand-written
         * pair did (its `template != keyframe` guard could never fire here). */
        Keyframe<T> inserted = (Keyframe<T>) sheet.channel.insertInheriting(tick, value);
        sheet.selection.shiftAfterInsert(sheet.channel.indexOf(inserted));

        return inserted;
    }

    public static <T> void forEachSelectedKeyframe(UIKeyframes editor, Keyframe<?> keyframe, Consumer<Keyframe<T>> consumer)
    {
        if (editor == null || keyframe == null)
        {
            return;
        }

        Float tick = editor.getAutoKeyframeTick();

        for (UIKeyframeSheet sheet : editor.getGraph().getSheets())
        {
            if (sheet.channel.getFactory() != keyframe.getFactory())
            {
                continue;
            }

            if (tick != null && !sheet.selection.getSelected().isEmpty())
            {
                Keyframe<T> target = sheet.ensureKeyframe(tick);
                if (target != null) consumer.accept(target);
            }
            else
            {
                for (Keyframe selected : sheet.selection.getSelected()) consumer.accept((Keyframe<T>) selected);
            }
        }
    }

    public static <T> void forEachRecordedKeyframe(UIKeyframes editor, Keyframe<?> keyframe, int tick, Consumer<Keyframe<T>> consumer)
    {
        if (editor == null || keyframe == null)
        {
            return;
        }

        for (UIKeyframeSheet sheet : editor.getGraph().getSheets())
        {
            if (sheet.channel.getFactory() != keyframe.getFactory() || sheet.selection.getSelected().isEmpty())
            {
                continue;
            }

            Keyframe<T> recorded = ensureKeyframe(sheet, tick);

            if (recorded != null)
            {
                consumer.accept(recorded);
            }
        }
    }

    public static void addIKTargetSheets(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.IK_TARGET, null);
    }

    /**
     * One IK-controls track per form (only if it has enabled chains): a single
     * keyframe sheet whose value holds the per-chain scalars (weight, softness,
     * pole, enabled), layered over the form's IK config at playback — mirrors the
     * single pose track. It is not a form property, so it carries its owning form
     * for the editor to list chains.
     */
    public static void addIKControlSheet(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.IK_CONTROLS, null);
    }

    public static void addPoleTargetSheets(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.POLE_TARGET, null);
    }

    /**
     * One physics-controls track per form (only if it has physics chains): a single
     * keyframe sheet whose value holds the per-chain scalars (weight, gravity,
     * damping, stiffness, enabled), keyed by root bone and layered over the form's
     * physics config at playback — mirrors {@link #addIKControlSheet}. It is not a
     * form property, so it carries its owning form for the editor to list chains.
     */
    public static void addPhysicsControlSheet(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.PHYSICS_CONTROLS, null);
    }

    /** A fully populated physics-controls value seeded from the form's physics config (one entry per chain root), mirroring {@link ModelIKRuntime#ikControls(mchorse.bbs_mod.forms.forms.ModelForm)}. */
    private static PhysicsControls buildPhysicsControls(ModelForm modelForm)
    {
        PhysicsControls controls = new PhysicsControls();

        if (modelForm.physics.get() instanceof MapType map)
        {
            ModelPhysicsConfig config = ModelPhysicsIO.fromData(map);

            if (config != null && config.bones() != null)
            {
                for (Map.Entry<String, ModelPhysicsConfig.Bone> entry : config.bones().entrySet())
                {
                    ModelPhysicsConfig.Bone bone = entry.getValue();

                    if (bone == null)
                    {
                        continue;
                    }

                    PhysicsControl control = controls.get(entry.getKey());

                    control.weight = bone.weight();
                    control.gravity = bone.gravity();
                    control.damping = bone.damping();
                    control.stiffness = bone.stiffness();
                }
            }
        }

        return controls;
    }

    /**
     * One wind track per form that has physics chains: a single keyframe sheet whose value holds the
     * global wind scalars (strength, direction, turbulence), layered over the form's physics wind config
     * at playback. The wind is global, so — unlike the physics-controls track — it is not keyed by a chain.
     */
    public static void addWindControlSheet(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.WIND_CONTROLS, null);
    }

    /** A wind-control value seeded from the form's physics wind config, so a fresh keyframe matches the configured wind instead of drifting to defaults. */
    private static WindControl buildWindControl(ModelForm modelForm)
    {
        WindControl control = new WindControl();

        if (modelForm.physics.get() instanceof MapType map)
        {
            ModelPhysicsConfig config = ModelPhysicsIO.fromData(map);

            if (config != null)
            {
                ModelPhysicsConfig.Wind wind = config.wind();

                control.strength = wind.strength();
                control.local = wind.local();
                control.x = wind.x();
                control.y = wind.y();
                control.z = wind.z();
                control.turbulence = wind.turbulence();
                control.turbulenceSpeed = wind.turbulenceSpeed();
                control.turbulenceScale = wind.turbulenceScale();
            }
        }

        return control;
    }

    public static void addPhysicsTargetSheets(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(modelForm, properties, out, track -> track.kind() == TrackKind.PHYSICS_TARGET, null);
    }

    /** Compatibility collectors share the same descriptor discovery as both active timelines. */
    private static void addCatalogSheets(Form form, FormProperties properties, List<UIKeyframeSheet> out,
        Predicate<TrackDescriptor> filter, Map<String, Integer> depths)
    {
        if (form == null) return;
        List<TrackDescriptor> catalog = TrackCatalog.forPart(FormUtils.getRoot(form), properties, FormUtils.getPath(form));
        List<TrackDescriptor> selected = catalog.stream().filter(filter).toList();
        buildSheets(selected, out);
        if (depths == null) return;
        Map<TrackId, TrackDescriptor> parents = new HashMap<>();
        for (TrackDescriptor track : catalog) parents.put(track.id(), track);
        for (TrackDescriptor track : selected)
        {
            int depth = 0;
            TrackDescriptor parent = parents.get(track.parent());
            while (parent != null)
            {
                if (parent.kind() != TrackKind.PROPERTY) depth++;
                parent = parents.get(parent.parent());
            }
            depths.put(track.key(), depth);
        }
    }

    /** Collect every track a single form contributes to the timeline (its own properties plus model sub-tracks), used to populate the per-form track filter. */
    public static List<UIKeyframeSheet> collectFormTrackSheets(Form form)
    {
        List<UIKeyframeSheet> sheets = new ArrayList<>();
        if (form != null) addCatalogSheets(form, new FormProperties(""), sheets, track -> true, null);
        return sheets;
    }

    private static void addTargetSheet(List<UIKeyframeSheet> out, FormProperties properties, ModelForm modelForm, String id, String title, int color, Icon icon)
    {
        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.ANCHOR);

        out.add(new UIKeyframeSheet(id, IKey.constant(title), color, channel, null).icon(icon).form(modelForm));
    }

    public static void addSoundSheets(AbstractSoundForm form, FormProperties properties, List<UIKeyframeSheet> out)
    {
        addCatalogSheets(form, properties, out,
            track -> SoundKeyframeValue.groupFromChannel(track.key()) != null, null);
    }

    public static void clearIKTracks(Replay replay, ModelForm modelForm)
    {
        if (replay == null || modelForm == null)
        {
            return;
        }

        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        List<String> controllers = ModelIKRuntime.getControllers(model);
        List<String> poleControllers = ModelIKRuntime.getPoleControllers(model);
        String path = FormUtils.getPath(modelForm);

        BaseValue.edit(replay.properties, (props) ->
        {
            for (String controller : controllers)
            {
                props.remove(TrackId.ikTarget(path, controller));
            }

            for (String controller : poleControllers)
            {
                props.remove(TrackId.poleTarget(path, controller));
            }
        });
    }

    public static UIPropTransform getEditableTransform(UIKeyframeEditor editor)
    {
        if (editor == null || editor.editor == null)
        {
            return null;
        }

        if (editor.editor instanceof UITransformKeyframeFactory transformKeyframeFactory)
        {
            return transformKeyframeFactory.transform;
        }
        else if (editor.editor instanceof UIAnchorKeyframeFactory keyframeFactory)
        {
            return keyframeFactory.transform;
        }
        else if (editor.editor instanceof UIPoseKeyframeFactory keyframeFactory)
        {
            return keyframeFactory.poseEditor.transform;
        }
        else if (editor.editor instanceof UIPoseTransformKeyframeFactory keyframeFactory)
        {
            return keyframeFactory.transform;
        }

        return null;
    }

    public static UIPropTransform getFilmGizmoTransform(UIFilmPanel panel, float transition)
    {
        if (panel.getActivePreview().getViewController().getEditTarget().is(FilmTarget.Kind.ROOT))
        {
            UIReplayPropTransform transform = panel.replayEditor.replayTransform;
            transform.syncFromReplay(panel.replayEditor.getReplay(), panel.getController().getCurrentEntity(), panel.getKeyframeCursor(transition));
            return transform;
        }
        return getEditableTransform(panel.replayEditor.keyframeEditor);
    }

    public static boolean startFilmGizmo(UIFilmPanel panel, UIContext context, int stencilIndex, float gizmoTransition)
    {
        return panel != null && startFilmGizmo(panel, panel.getCamera(), panel.preview.getViewport(), context, stencilIndex, gizmoTransition);
    }

    public static boolean startFilmGizmo(UIFilmPanel panel, Camera camera, Area viewport, UIContext context, int stencilIndex, float gizmoTransition)
    {
        if (panel == null || panel.isFlying() || context.mouseButton != 0)
        {
            return false;
        }

        UIPropTransform transform = getFilmGizmoTransform(panel, gizmoTransition);
        GizmoDrag drag = buildFilmGizmoDrag(
            panel,
            camera,
            viewport,
            transform,
            gizmoTransition
        );

        boolean started = transform != null && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, drag);
        if (started && transform instanceof UIReplayPropTransform) panel.replayEditor.showReplayTracks();
        return started;
    }

    public static void configureFilmHotkeyDrag(UIFilmPanel panel, UIContext context)
    {
        if (panel == null) return;
        float transition = panel.replayEditor.getContext() == null ? 0F : panel.replayEditor.getContext().getTransition();
        UIReplayPropTransform replayTransform = panel.replayEditor.replayTransform;
        replayTransform.syncFromReplay(panel.replayEditor.getReplay(), panel.getController().getCurrentEntity(), panel.getKeyframeCursor(transition));
        replayTransform.hotkeyDrag(() -> buildFilmGizmoDrag(panel,
            panel.getActivePreview().getDisplayedCamera(), panel.getActivePreview().getViewport(), replayTransform,
            panel.isRunning() ? transition : 0F));
        UIPropTransform transform = getEditableTransform(panel.replayEditor.keyframeEditor);

        if (transform == null)
        {
            return;
        }

        transform.hotkeyDrag(() -> buildFilmGizmoDrag(
            panel,
            panel.getActivePreview().getDisplayedCamera(),
            panel.getActivePreview().getViewport(),
            transform,
            panel.replayEditor.getContext() == null ? 0F : panel.replayEditor.getContext().getTransition()
        ));

        /* World-space copy/paste only makes sense for an actor's bone in the scene, so the world
         * matrix provider is wired solely for the pose editor's transform (other tracks leave it off
         * and the world context actions stay hidden there). */
        boolean pose = panel.replayEditor.keyframeEditor.editor instanceof UIPoseKeyframeFactory;

        transform.worldTransform(pose ? new FilmBoneWorldProvider(panel) : null);
        transform.rotationConstrained(pose ? () -> isFilmBoneRotationConstrained(panel) : null);
    }

    private static boolean isFilmBoneRotationConstrained(UIFilmPanel panel)
    {
        UIKeyframeEditor keyframeEditor = panel.replayEditor.keyframeEditor;

        if (keyframeEditor == null || !(keyframeEditor.editor instanceof UIPoseKeyframeFactory))
        {
            return false;
        }

        IEntity entity = panel.getController().getCurrentEntity();
        Pair<String, Boolean> bone = keyframeEditor.getBone();

        if (entity == null || bone == null || bone.a == null)
        {
            return false;
        }

        UIKeyframeSheet sheet = keyframeEditor.getSheet(keyframeEditor.editor.getKeyframe());
        BaseValueBasic property = sheet == null ? null : FormUtils.getProperty(entity.getForm(), sheet.id);
        Form owner = property == null ? null : FormUtils.getForm(property);

        if (!(owner instanceof ModelForm modelForm))
        {
            return false;
        }

        ModelInstance instance = ModelFormRenderer.getModel(modelForm);

        return instance != null && ModelIKRuntime.isRotationConstrained(
            instance.model,
            modelForm,
            StringUtils.fileName(bone.a)
        );
    }

    public static GizmoDrag buildFilmGizmoDrag(
        UIFilmPanel panel,
        Camera camera,
        Area viewport,
        UIPropTransform transform,
        float transition
    )
    {
        GizmoDrag drag = GizmoDrag.fromRenderedGizmo(camera, viewport);

        if (drag == null || panel == null)
        {
            return drag;
        }

        IEntity entity = panel.getController().getCurrentEntity();

        drag.setGlobalAxes(FilmMatrices.getReplayWorldAxes(entity, transition));

        if (transform == null || transform.getTransform() == null)
        {
            return drag;
        }

        if (transform instanceof UIReplayPropTransform && entity != null)
        {
            drag.setRotateAxes(drag.gizmoWorldAxes);
            Matrix4f placement = FilmMatrices.getMatrixForRenderWithRotation(entity,
                camera.position.x, camera.position.y, camera.position.z, transition);
            drag.setFrameAxes(placement, placement);
            return drag;
        }

        UIKeyframeEditor keyframeEditor = panel.replayEditor.keyframeEditor;

        if (keyframeEditor == null)
        {
            return drag;
        }

        Pair<String, Boolean> bone = keyframeEditor.getBone();
        Replay replay = panel.replayEditor.getReplay();

        if (bone == null || bone.a == null || replay == null || entity == null)
        {
            if (keyframeEditor.isFormAnchorTrack() && replay != null && entity != null)
            {
                buildAnchorGizmoDrag(panel, camera, drag, transform, replay, entity, transition);
            }

            return drag;
        }

        Supplier<Matrix4f> matrixSampler = () ->
        {
            Matrix4f matrix = sampleFilmBoneMatrix(
                panel,
                camera,
                entity,
                replay,
                transition,
                bone.a,
                true
            );

            return matrix == null ? new Matrix4f() : MatrixStackUtils.stripScale(matrix);
        };

        drag.setRotateAxes(GizmoDrag.computeRotateAxes(transform.getTransform(), matrixSampler));

        Matrix3f translateJacobian = null;
        UIKeyframeSheet sheet = keyframeEditor.editor == null
            ? null
            : keyframeEditor.getSheet(keyframeEditor.editor.getKeyframe());
        boolean poseBone = sheet != null
            && !bone.a.isEmpty()
            && (sheet.isBoneTrack || keyframeEditor.editor instanceof UIPoseKeyframeFactory);
        Form editedForm = sheet == null
            ? null
            : (sheet.form != null ? sheet.form : (sheet.property == null ? null : FormUtils.getForm(sheet.property)));

        boolean mobPoseBone = poseBone && editedForm instanceof MobForm;

        if (mobPoseBone)
        {
            /* Model pixels: derive the basis straight from the bone's parent frame instead of
             * perturbing the keyframe. The numeric sampler re-applies the replay properties around
             * each sample, so a cursor that is not exactly on the edited keyframe shrinks the
             * perturbation by the interpolation weight - and a basis scaled by w inverts into a
             * drag amplified by 1/w. Deliberately no numeric fallback here: an unresolved bone
             * degrades to the rest basis in GizmoDrag#resolveTranslateJacobian, which is merely
             * axis-aligned, where the numeric one would be flat (dead drag) or tiny (runaway). */
            Matrix4f origin = sampleFilmBoneMatrix(
                panel,
                camera,
                entity,
                replay,
                transition,
                bone.a,
                false
            );

            if (origin != null)
            {
                translateJacobian = GizmoDrag.computeModelPartTranslateJacobian(origin);
            }
        }
        else
        {
            translateJacobian = GizmoDrag.computeTranslateJacobian(
                transform.getTransform(),
                () -> matrixSampler.get().getTranslation(new Vector3f())
            );
        }

        drag.setJacobian(GizmoDrag.resolveTranslateJacobian(translateJacobian, mobPoseBone));
        drag.modelPartTranslate(mobPoseBone);
        drag.setFrameAxes(
            sampleFilmBoneMatrix(panel, camera, entity, replay, transition, bone.a, true),
            sampleFilmBoneMatrix(panel, camera, entity, replay, transition, bone.a, false)
        );

        drag.setAdditiveRotationBase(filmPoseRotationBase(keyframeEditor, panel, camera, entity, replay, transition, bone.a));

        return drag;
    }

    private static Vector3f filmPoseRotationBase(
        UIKeyframeEditor keyframeEditor,
        UIFilmPanel panel,
        Camera camera,
        IEntity entity,
        Replay replay,
        float transition,
        String bonePath
    )
    {
        if (!(keyframeEditor.editor instanceof UIPoseKeyframeFactory))
        {
            return null;
        }

        UIKeyframeSheet sheet = keyframeEditor.getSheet(keyframeEditor.editor.getKeyframe());

        if (sheet == null)
        {
            return null;
        }

        BaseValueBasic property = FormUtils.getProperty(entity.getForm(), sheet.id);

        if (!(property instanceof ValuePose valuePose))
        {
            return null;
        }

        Vector3f evaluated = sampleFilmBoneEvaluatedRotation(panel, camera, entity, replay, transition, bonePath);

        return FormUtils.additivePoseRotationBase(valuePose, StringUtils.fileName(bonePath), evaluated);
    }

    /**
     * Re-apply the replay's animated properties before a placement sample. Samplers run outside the
     * render loop, so without this they would see whatever the last render left on the form.
     */
    private static void applyReplayProperties(UIFilmPanel panel, IEntity entity, Replay replay, float transition)
    {
        Form form = entity.getForm();

        if (form != null)
        {
            float tick = replay.getTick(panel.getCursor()) + panel.getRunner().getTransition(transition);

            replay.properties.applyProperties(form, tick);
        }
    }

    private static Matrix4f sampleFilmBoneMatrix(
        UIFilmPanel panel,
        Camera camera,
        IEntity entity,
        Replay replay,
        float transition,
        String bone,
        boolean useBoneMatrix
    )
    {
        applyReplayProperties(panel, entity, replay, transition);

        return FilmMatrices.getBoneCompositeMatrix(
            panel.getController().getEntities(),
            entity,
            replay,
            camera.position.x,
            camera.position.y,
            camera.position.z,
            transition,
            bone,
            useBoneMatrix
        );
    }

    private static Vector3f sampleFilmBoneEvaluatedRotation(
        UIFilmPanel panel,
        Camera camera,
        IEntity entity,
        Replay replay,
        float transition,
        String bone
    )
    {
        applyReplayProperties(panel, entity, replay, transition);

        return FilmMatrices.getGizmoBoneEvaluatedRotation(
            panel.getController().getEntities(),
            entity,
            replay,
            camera.position.x,
            camera.position.y,
            camera.position.z,
            transition,
            bone
        );
    }

    private static void buildAnchorGizmoDrag(
        UIFilmPanel panel,
        Camera camera,
        GizmoDrag drag,
        UIPropTransform transform,
        Replay replay,
        IEntity entity,
        float transition
    )
    {
        Supplier<Matrix4f> matrixSampler = () ->
        {
            applyReplayProperties(panel, entity, replay, transition);

            Matrix4f matrix = FilmMatrices.getGizmoAnchorCompositeMatrix(
                panel.getController().getEntities(),
                entity,
                replay,
                camera.position.x,
                camera.position.y,
                camera.position.z,
                transition
            );

            return matrix == null ? new Matrix4f() : matrix;
        };

        drag.setRotateAxes(GizmoDrag.computeRotateAxes(transform.getTransform(), matrixSampler));
        drag.setJacobian(GizmoDrag.computeTranslateJacobian(
            transform.getTransform(),
            () -> matrixSampler.get().getTranslation(new Vector3f())
        ));

        applyReplayProperties(panel, entity, replay, transition);
    }

    /* Picking form and form properties */

    public static void pickForm(UIKeyframeEditor keyframeEditor, ICursor cursor, Form form, String bone)
    {
        pickForm(keyframeEditor, cursor, form, bone, false);
    }

    public static void pickForm(UIKeyframeEditor keyframeEditor, ICursor cursor, Form form, String bone, boolean insert)
    {
        if (form == null || keyframeEditor == null || bone.isEmpty())
        {
            return;
        }

        /* Keep the live pose factory intact while Ctrl toggles bones; rebuilding
         * the selected keyframe would reset the accumulated multi-selection. */
        if (!insert && Window.isCtrlPressed()
            && keyframeEditor.editor instanceof UIPoseKeyframeFactory poseFactory
            && poseFactory.poseEditor.hasBone(bone))
        {
            IUIKeyframeGraph graph = keyframeEditor.view.getGraph();

            /* The picked bone's row can sit under a folded pose tab: reveal it before the pose
             * editor takes the selection, or the highlight is invisible. */
            keyframeEditor.view.getDopeSheet().revealSheet(graph.getSheet(graph.getSelected()));
            poseFactory.poseEditor.selectBone(bone, true);

            return;
        }

        String path = FormUtils.getPath(form);
        String boneKey = PerLimbService.toPoseBoneKey(path, bone);

        if (!insert)
        {
            IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
            Keyframe selected = graph.getSelected();
            UIKeyframeSheet currentSheet = selected != null ? graph.getSheet(selected) : null;
            PerLimbService.PoseBonePath currentPath = currentSheet != null && currentSheet.id != null ? PerLimbService.parsePoseBonePath(currentSheet.id) : null;
            if (currentPath != null && !path.equals(currentPath.formPath()))
            {
                return;
            }
            if (isPoseSheet(currentSheet, path))
            {
                /* Reveal first: a pose row under a folded category or pose tab would otherwise
                 * take the selection while its timeline row stayed hidden. */
                keyframeEditor.view.getDopeSheet().revealSheet(currentSheet);

                float tick = keyframeCursor(keyframeEditor.view, cursor);
                Keyframe closest = getClosestKeyframe(currentSheet, tick);
                if (closest != null)
                {
                    if (currentSheet.selection.getSelected().size() <= 1)
                    {
                        forceSelectInSheet(graph, currentSheet, closest);
                    }
                    cursor.setCursor(closest.getTick());
                }
                updatePoseEditorBoneSelection(keyframeEditor, bone);
                return;
            }
        }

        if (insert)
        {
            UIKeyframeSheet sheet = resolveBoneSheet(keyframeEditor, boneKey, path);

            if (sheet == null)
            {
                return;
            }

            if (isPoseSheet(sheet, path))
            {
                insertIntoPoseSheet(keyframeEditor, cursor, bone, sheet);

                return;
            }

            IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
            Keyframe selected = graph.getSelected();
            UIKeyframeSheet currentSheet = selected != null ? graph.getSheet(selected) : null;

            if (isPoseSheet(currentSheet, path))
            {
                return;
            }

            pickProperty(keyframeEditor, cursor, bone, sheet, true);

            return;
        }

        UIKeyframeSheet sheet = resolveBoneSheet(keyframeEditor, boneKey, path);

        if (sheet != null)
        {
            pickProperty(keyframeEditor, cursor, bone, sheet, false);
        }
    }

    private static UIKeyframeSheet resolveBoneSheet(UIKeyframeEditor keyframeEditor, String boneKey, String formPath)
    {
        return resolveBoneSheet(keyframeEditor.view.getGraph(), boneKey, formPath);
    }

    static UIKeyframeSheet resolveBoneSheet(IUIKeyframeGraph graph, String boneKey, String formPath)
    {
        UIKeyframeSheet sheet = graph.getSheet(boneKey);

        if (sheet == null)
        {
            UIKeyframeSheet caseInsensitive = null;

            for (UIKeyframeSheet candidate : graph.getSheets())
            {
                if (candidate.id != null && candidate.id.equalsIgnoreCase(boneKey))
                {
                    if (caseInsensitive != null)
                    {
                        caseInsensitive = null;

                        break;
                    }

                    caseInsensitive = candidate;
                }
            }

            sheet = caseInsensitive;
        }

        if (sheet != null)
        {
            if (sheet.channel.isEmpty())
            {
                UIKeyframeSheet poseSheet = getPreferredPoseSheet(graph, formPath);

                if (poseSheet != null)
                {
                    return poseSheet;
                }
            }

            return sheet;
        }

        return getPreferredPoseSheet(graph, formPath);
    }

    private static UIKeyframeSheet getPoseSheet(IUIKeyframeGraph graph, String formPath)
    {
        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            if (isPoseSheet(sheet, formPath))
            {
                return sheet;
            }
        }

        return null;
    }

    private static UIKeyframeSheet getPreferredPoseSheet(IUIKeyframeGraph graph, String formPath)
    {
        Keyframe selected = graph.getSelected();
        UIKeyframeSheet current = selected != null ? graph.getSheet(selected) : null;

        if (isPoseSheet(current, formPath))
        {
            return current;
        }

        UIKeyframeSheet last = graph.getLastSheet();

        if (isPoseSheet(last, formPath))
        {
            return last;
        }

        return getPoseSheet(graph, formPath);
    }

    private static void pickProperty(UIKeyframeEditor keyframeEditor, ICursor cursor, String bone, String key, boolean insert)
    {
        UIKeyframeSheet sheet = keyframeEditor.view.getGraph().getSheet(key);

        if (sheet != null)
        {
            pickProperty(keyframeEditor, cursor, bone, sheet, insert);
        }
    }

    private static void pickProperty(UIKeyframeEditor keyframeEditor, ICursor filmPanel, String bone, UIKeyframeSheet sheet, boolean insert)
    {
        /* Both the insert and the plain pick write into this sheet, and either can be aimed at a row
         * hidden by a folded category or parent track. */
        keyframeEditor.view.getDopeSheet().revealSheet(sheet);

        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        float tick = keyframeCursor(keyframeEditor.view, filmPanel);

        if (insert)
        {
            Keyframe keyframe = graph.addKeyframe(sheet, tick, null);
            graph.selectKeyframe(keyframe);
            return;
        }

        Keyframe closest = getClosestKeyframe(sheet, tick);

        PerLimbService.PoseBonePath path = PerLimbService.parsePoseBonePath(sheet.id);
        String boneForEditor = path != null ? path.bone() : bone;

        if (closest != null)
        {
            if (sheet.selection.getSelected().size() <= 1)
            {
                forceSelectInSheet(graph, sheet, closest);
            }
            updatePoseEditorBoneSelection(keyframeEditor, boneForEditor);
            filmPanel.setCursor(closest.getTick());
        }
        else
        {
            updatePoseEditorBoneSelection(keyframeEditor, boneForEditor);
        }
    }

    private static Keyframe getClosestKeyframe(UIKeyframeSheet sheet, float tick)
    {
        KeyframeSegment segment = sheet.channel.find(tick);

        return segment != null ? segment.getClosest() : null;
    }

    /** The authoring tick the shared playhead points at, snapped when tick snapping is enabled. */
    private static float keyframeCursor(UIKeyframes view, ICursor cursor)
    {
        UIContext context = view.getContext();

        return cursor.getKeyframeCursor(context == null ? 0F : context.getTransition());
    }

    private static Keyframe getKeyframeAt(UIKeyframeSheet sheet, float tick)
    {
        for (Object object : sheet.channel.getKeyframes())
        {
            Keyframe keyframe = (Keyframe) object;

            if (keyframe.getTick() == tick)
            {
                return keyframe;
            }
        }

        return null;
    }

    private static void insertIntoPoseSheet(UIKeyframeEditor keyframeEditor, ICursor cursor, String bone, UIKeyframeSheet poseSheet)
    {
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        float tick = keyframeCursor(keyframeEditor.view, cursor);
        Keyframe existing = getKeyframeAt(poseSheet, tick);

        if (existing != null)
        {
            if (poseSheet.selection.getSelected().size() <= 1)
            {
                forceSelectInSheet(graph, poseSheet, existing);
            }
        }
        else
        {
            Keyframe keyframe = graph.addKeyframe(poseSheet, tick, null);

            graph.selectKeyframe(keyframe);
        }

        updatePoseEditorBoneSelection(keyframeEditor, bone);
    }

    static boolean isPoseSheet(UIKeyframeSheet sheet, String formPath)
    {
        if (sheet == null || sheet.id == null)
        {
            return false;
        }

        String prefix = formPath.isEmpty() ? "" : formPath + FormUtils.PATH_SEPARATOR;

        return sheet.id.equals(prefix + "pose") || sheet.id.startsWith(prefix + "pose_overlay");
    }

    private static void forceSelectInSheet(IUIKeyframeGraph graph, UIKeyframeSheet sheet, Keyframe keyframe)
    {
        /* Level-pick must deterministically activate exactly clicked sheet/keyframe */
        graph.clearSelection();
        sheet.selection.add(keyframe);
        graph.pickKeyframe(keyframe);
    }

    private static void updatePoseEditorBoneSelection(UIKeyframeEditor keyframeEditor, String bone)
    {
        if (keyframeEditor.editor instanceof UIPoseKeyframeFactory poseFactory)
        {
            poseFactory.poseEditor.selectBone(bone);
        }
    }

    /* Converting Blockbench model keyframes to pose keyframes */

    public static void animationToPoseKeyframes(
        UIKeyframeEditor keyframeEditor, UIKeyframeSheet sheet,
        ModelForm modelForm, IEntity entity,
        float tick, String animationKey, boolean onlyKeyframes, int length, int step
    ) {
        ModelInstance model = ModelFormRenderer.getModel(modelForm);
        Animation animation = model.animations.get(animationKey);

        if (animation != null)
        {
            keyframeEditor.view.getDopeSheet().clearSelection();

            if (onlyKeyframes)
            {
                List<Float> list = getTicks(animation);

                for (float i : list)
                {
                    fillAnimationPose(sheet, i, model, entity, animation, tick);
                }
            }
            else
            {
                for (int i = 0; i < length; i += step)
                {
                    fillAnimationPose(sheet, i, model, entity, animation, tick);
                }
            }

            keyframeEditor.view.getDopeSheet().pickSelected();
        }
    }

    private static List<Float> getTicks(Animation animation)
    {
        Set<Float> integers = new HashSet<>();

        for (AnimationPart value : animation.parts.values())
        {
            for (KeyframeChannel<MolangExpression> channel : value.channels)
            {
                for (Keyframe<MolangExpression> keyframe : channel.getKeyframes())
                {
                    integers.add(keyframe.getTick());
                }
            }
        }

        ArrayList<Float> ticks = new ArrayList<>(integers);

        Collections.sort(ticks);

        return ticks;
    }

    private static void fillAnimationPose(UIKeyframeSheet sheet, float i, ModelInstance model, IEntity entity, Animation animation, float current)
    {
        model.model.resetPose();
        model.model.apply(entity, animation, i, 1F, 0F, false);

        int insert = sheet.channel.insert(current + i, model.model.createPose());

        sheet.selection.add(insert);
    }

    @SuppressWarnings("unchecked")
    public static void posesToLimbTracks(Replay replay, UIKeyframeSheet poseSheet)
    {
        if (replay != null) posesToLimbTracks(replay.properties, poseSheet);
    }

    public static boolean posesToLimbTracks(FormProperties properties, UIKeyframeSheet poseSheet)
    {
        if (properties == null || poseSheet == null || poseSheet.getPosedForm() == null)
        {
            return false;
        }

        TrackId track = TrackId.parse(poseSheet.id);

        if (track == null) return false;

        String formPath = track.formPath();
        Form form = UIReplaysEditor.getSheetForm(poseSheet);
        ModelForm modelForm = poseSheet.getPoseForm();
        ModelInstance model = modelForm == null ? null : ModelFormRenderer.getModel(modelForm);
        List<String> bones = new ArrayList<>(FormUtilsClient.getBoneHierarchy(form).getBoneIds());

        bones.removeIf(bone -> model != null && PoseBones.isHidden(model.getDisabledBones(), bone));

        List<Keyframe<Pose>> selectedKeyframes = (List<Keyframe<Pose>>) (List<?>) poseSheet.selection.getSelected();

        if (selectedKeyframes.isEmpty() || bones.isEmpty() || selectedKeyframes.stream().anyMatch(keyframe -> keyframe.getValue() == null))
        {
            return false;
        }

        /* Rest keys remain for animated bones, but untouched bones need no new track. */
        bones.removeIf(bone -> selectedKeyframes.stream().noneMatch(keyframe ->
        {
            PoseTransform transform = keyframe.getValue().get(bone);

            return transform != null && !transform.isDefault();
        }));

        if (bones.isEmpty()) return false;

        /* Snapshot the collection so undo also removes the channels created by this conversion. */
        BaseValue.edit(properties, target ->
        {
            for (Keyframe<Pose> keyframe : selectedKeyframes)
            {
                Pose pose = keyframe.getValue();

                for (String bone : bones)
                {
                    KeyframeChannel<PoseTransform> limbChannel = (KeyframeChannel<PoseTransform>) target.getOrCreate(TrackId.bone(formPath, bone));

                    if (limbChannel == null) continue;

                    PoseTransform transform = pose.get(bone);
                    PoseTransform copy = transform == null ? new PoseTransform() : (PoseTransform) transform.copy();
                    int index = limbChannel.insert(keyframe.getTick(), copy);

                    limbChannel.get(index).copyOverExtra(keyframe);
                }
            }

            poseSheet.selection.removeSelected();
        });

        return true;
    }

    /* Offer bone hierarchy options */

    public interface FormPicker
    {
        void pick(Form form, String bone, boolean insert);
    }

    public static boolean pickFormWithOffers(UIContext context, Pair<Form, String> pair, FormPicker picker)
    {
        return pickFormWithOffers(context, pair, picker, context.mouseButton,
            Window.isAltPressed(), Window.isCtrlPressed(), Window.isShiftPressed());
    }

    public static boolean pickFormWithOffers(UIContext context, Pair<Form, String> pair, FormPicker picker,
        int button, boolean alt, boolean control, boolean shift)
    {
        boolean select = button == 0 || (button == 2 && control);
        boolean insert = button == 1;

        if (pair == null || pair.a == null || (!select && !insert))
        {
            return false;
        }

        if (alt && !control)
        {
            offerAdjacent(context, pair.a, pair.b, (bone) -> picker.pick(pair.a, bone, insert));
        }
        else if (shift)
        {
            offerHierarchy(context, pair.a, pair.b, (bone) -> picker.pick(pair.a, bone, insert));
        }
        else
        {
            picker.pick(pair.a, pair.b, insert);
        }

        return true;
    }

    public static void offerAdjacent(UIContext context, Form form, String bone, Consumer<String> consumer)
    {
        if (form == null)
        {
            return;
        }

        if (!bone.isEmpty())
        {
            BoneHierarchy hierarchy = FormUtilsClient.getBoneHierarchy(form);

            if (hierarchy.getBone(bone) == null)
            {
                return;
            }

            context.replaceContextMenu((menu) ->
            {
                Map<String, String> labels = hierarchy.getLabels();

                for (BoneHierarchy.Bone adjacent : hierarchy.getAdjacent(bone))
                {
                    menu.action(Icons.LIMB, IKey.constant(labels.getOrDefault(adjacent.id(), adjacent.name())), () -> consumer.accept(adjacent.id()));
                }

                menu.autoKeys();
            });
        }
    }

    public static void offerHierarchy(UIContext context, Form form, String bone, Consumer<String> consumer)
    {
        if (form == null)
        {
            return;
        }

        if (!bone.isEmpty())
        {
            BoneHierarchy hierarchy = FormUtilsClient.getBoneHierarchy(form);

            if (hierarchy.getBone(bone) == null)
            {
                return;
            }

            context.replaceContextMenu((menu) ->
            {
                Map<String, String> labels = hierarchy.getLabels();

                for (BoneHierarchy.Bone ancestor : hierarchy.getAncestors(bone))
                {
                    String label = labels.getOrDefault(ancestor.id(), ancestor.name());

                    menu.action(Icons.LIMB, IKey.constant(label), () -> consumer.accept(ancestor.id()));
                }

                menu.autoKeys();
            });
        }
    }
}
