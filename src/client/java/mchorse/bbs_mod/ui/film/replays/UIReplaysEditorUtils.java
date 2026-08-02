package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.ik.IKControls;
import mchorse.bbs_mod.cubic.ik.ModelIKConfig;
import mchorse.bbs_mod.cubic.ik.ModelIKIO;
import mchorse.bbs_mod.cubic.ik.ModelIKRuntime;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControls;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.BoneHierarchy;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.ui.film.ICursor;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
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

public class UIReplaysEditorUtils
{
    private static final int BONE_TRACK_HUE_COUNT = 12;

    public static void insertPoseKeyframesAtTick(Replay replay, float tick)
    {
        if (replay == null)
        {
            return;
        }

        BaseValue.edit(replay.properties, (props) ->
        {
            for (KeyframeChannel<?> channel : props.properties.values())
            {
                if (!PerLimbService.isPoseBoneChannel(channel.getId()))
                {
                    continue;
                }

                KeyframeChannel<PoseTransform> poseChannel = (KeyframeChannel<PoseTransform>) channel;
                KeyframeSegment<PoseTransform> segment = poseChannel.find(tick);
                PoseTransform value = segment != null ? segment.createInterpolated() : new PoseTransform();

                int index = poseChannel.insert(tick, value);
                Keyframe<PoseTransform> kf = poseChannel.get(index);

                Keyframe<PoseTransform> template = segment != null ? segment.a : null;
                if (template != null && template != kf)
                {
                    kf.copyOverExtra(template);
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
        if (!(form instanceof PoseForm poseForm))
        {
            return;
        }

        BoneHierarchy hierarchy = FormUtilsClient.getBoneHierarchy(form);

        if (hierarchy.getBones().isEmpty())
        {
            return;
        }

        String path = FormUtils.getPath(form);
        String id = FormControlKeys.toGlintControlKey(path);
        IKey title = path.isEmpty()
            ? UIKeys.POSE_CONTEXT_GLINT_LAYER
            : IKey.constant(path + "/" + UIKeys.POSE_CONTEXT_GLINT_LAYER.get());
        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.GLINT);

        out.add(new UIKeyframeSheet(id, title, Colors.MAGENTA, false, channel, null)
            .icon(Icons.LIGHT).form(form).seed(() -> buildGlintControls(poseForm, hierarchy)));
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
        if (!(form instanceof PoseForm poseForm) || !poseForm.getBoneTracks().get())
        {
            return;
        }

        BoneHierarchy hierarchy = FormUtilsClient.getBoneHierarchy(form);

        if (hierarchy.getBones().isEmpty())
        {
            return;
        }

        Map<String, Integer> parentToColor = new HashMap<>();
        Map<String, String> labels = hierarchy.getLabels(false);
        int[] hueIndex = {0};

        for (BoneHierarchy.Bone bone : hierarchy.getBones())
        {
            String colorGroup = bone.layerId() + "\u0000" + (bone.parentId() == null ? "" : bone.parentId());
            int color = parentToColor.computeIfAbsent(colorGroup, (p) ->
                Colors.HSVtoRGB((hueIndex[0]++ % BONE_TRACK_HUE_COUNT) / (float) BONE_TRACK_HUE_COUNT, 0.7F, 0.7F).getRGBColor()
            );

            String path = FormUtils.getPath(form);
            String boneKey = PerLimbService.toPoseBoneKey(path, bone.id());
            String label = labels.getOrDefault(bone.id(), bone.name());
            String title = path.isEmpty() ? label : path + "/" + label;
            KeyframeChannel channel = properties.registerChannel(boneKey, KeyframeFactories.POSE_TRANSFORM);
            ValueTransform transform = new ValueTransform(boneKey, new PoseTransform());

            out.add(new UIKeyframeSheet(boneKey, IKey.constant(title), color, false, channel, transform, true).form(form));

            if (depthBySheetId != null)
            {
                depthBySheetId.put(boneKey, bone.depth());
            }
        }
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
        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        /* A model with at most one material ignores the material system entirely (its single texture is
         * driven by form.texture), so it exposes no per-material texture tracks - see the renderer. */
        if (model.materials.size() <= 1)
        {
            return;
        }

        String path = FormUtils.getPath(modelForm);

        for (String material : model.materials)
        {
            if (material == null || material.isEmpty())
            {
                continue;
            }

            String id = PerLimbService.toMaterialTextureKey(path, material);
            String title = path.isEmpty() ? "texture/" + material : path + "/texture/" + material;
            KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.LINK);

            /* Seed the sheet's value with the material's current default texture (editor pick, else
             * folder/Kd, else the form/model default) so a new keyframe starts there instead of null -
             * the texture picker then opens at that texture rather than the root. */
            Link materialDefault = modelForm.materialTextures.getLink(material);

            if (materialDefault == null)
            {
                materialDefault = model.getMaterialTexture(material, model.getTexture());
            }

            ValueLink property = new ValueLink(id, materialDefault);

            out.add(new UIKeyframeSheet(id, IKey.constant(title), Colors.BLUE, false, channel, property).icon(Icons.MATERIAL).form(modelForm));
        }
    }

    public static <T> Keyframe<T> ensureKeyframe(UIKeyframeSheet sheet, float tick)
    {
        if (sheet == null)
        {
            return null;
        }

        for (Keyframe<T> keyframe : (List<Keyframe<T>>) sheet.channel.getKeyframes())
        {
            if (keyframe.getTick() == tick)
            {
                return keyframe;
            }
        }

        KeyframeSegment<T> segment = sheet.channel.find(tick);
        BaseValueBasic property = sheet.property;
        Keyframe<T> template = null;
        T value;

        if (segment != null)
        {
            value = segment.createInterpolated();
            template = segment.a;
        }
        else if (property != null)
        {
            value = (T) sheet.channel.getFactory().copy(property.get());
        }
        else if (sheet.seed != null)
        {
            value = (T) sheet.seed.get();
        }
        else
        {
            value = (T) sheet.channel.getFactory().createEmpty();
        }

        int index = sheet.channel.insert(tick, value);
        Keyframe<T> keyframe = (Keyframe<T>) sheet.channel.get(index);

        if (template != null && template != keyframe)
        {
            keyframe.copyOverExtra(template);
        }

        return keyframe;
    }

    public static <T> void forEachSelectedKeyframe(UIKeyframes editor, Keyframe<?> keyframe, Consumer<Keyframe<T>> consumer)
    {
        if (editor == null || keyframe == null)
        {
            return;
        }

        for (UIKeyframeSheet sheet : editor.getGraph().getSheets())
        {
            if (sheet.channel.getFactory() != keyframe.getFactory())
            {
                continue;
            }

            for (Keyframe selected : sheet.selection.getSelected())
            {
                consumer.accept((Keyframe<T>) selected);
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
        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        model.form = modelForm;
        List<String> controllers = ModelIKRuntime.getControllers(model);
        String path = FormUtils.getPath(modelForm);

        for (String controller : controllers)
        {
            if (controller == null || controller.isEmpty())
            {
                continue;
            }

            String id = PerLimbService.toIKTargetKey(path, controller);
            String title = path.isEmpty() ? "ik/" + controller : path + "/ik/" + controller;

            addTargetSheet(out, properties, modelForm, id, title, Colors.CYAN, null);
        }
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
        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        model.form = modelForm;

        if (ModelIKRuntime.getControllers(model).isEmpty())
        {
            return;
        }

        String path = FormUtils.getPath(modelForm);
        String id = FormControlKeys.toIKControlKey(path);
        String title = path.isEmpty() ? "ik" : path + "/ik";

        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.IK);

        out.add(new UIKeyframeSheet(id, IKey.constant(title), Colors.YELLOW, false, channel, null)
            .icon(Icons.LIMB).form(modelForm).seed(() -> buildIKControls(modelForm)));
    }

    /** A fully populated IK-controls value seeded from the form's IK config (one entry per enabled chain), so a fresh keyframe matches what the editor shows instead of an empty container that drifts to defaults. */
    private static IKControls buildIKControls(ModelForm modelForm)
    {
        IKControls controls = new IKControls();

        if (modelForm.ik.get() instanceof MapType map)
        {
            ModelIKConfig config = ModelIKIO.fromData(map);

            if (config != null && config.chains() != null)
            {
                for (ModelIKConfig.Chain chain : config.chains())
                {
                    if (chain == null || !chain.enabled() || chain.tip() == null || chain.tip().isEmpty())
                    {
                        continue;
                    }

                    IKControl control = controls.get(chain.tip());

                    control.weight = chain.weight();
                    control.softness = chain.softness();
                    control.poleAngle = chain.poleAngle();
                    control.pole = chain.pole();
                    control.enabled = chain.enabled();
                }
            }
        }

        return controls;
    }

    public static void addPoleTargetSheets(ModelForm modelForm, FormProperties properties, List<UIKeyframeSheet> out)
    {
        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        model.form = modelForm;
        List<String> controllers = ModelIKRuntime.getPoleControllers(model);
        String path = FormUtils.getPath(modelForm);

        for (String controller : controllers)
        {
            if (controller == null || controller.isEmpty())
            {
                continue;
            }

            String id = PerLimbService.toPoleTargetKey(path, controller);
            String title = path.isEmpty() ? "pole/" + controller : path + "/pole/" + controller;

            addTargetSheet(out, properties, modelForm, id, title, Colors.ORANGE, null);
        }
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
        ModelPhysicsConfig physics = null;

        if (modelForm.physics.get() instanceof MapType map)
        {
            physics = ModelPhysicsIO.fromData(map);
        }

        if (physics == null || physics.bones() == null || physics.bones().isEmpty())
        {
            return;
        }

        String path = FormUtils.getPath(modelForm);
        String id = FormControlKeys.toPhysicsControlKey(path);
        String title = path.isEmpty() ? "physics" : path + "/physics";

        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.PHYSICS);

        out.add(new UIKeyframeSheet(id, IKey.constant(title), Colors.GREEN, false, channel, null)
            .icon(Icons.DROP).form(modelForm).seed(() -> buildPhysicsControls(modelForm)));
    }

    /** A fully populated physics-controls value seeded from the form's physics config (one entry per chain root), mirroring {@link #buildIKControls}. */
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
        ModelPhysicsConfig physics = null;

        if (modelForm.physics.get() instanceof MapType map)
        {
            physics = ModelPhysicsIO.fromData(map);
        }

        if (physics == null || physics.bones() == null || physics.bones().isEmpty())
        {
            return;
        }

        String path = FormUtils.getPath(modelForm);
        String id = FormControlKeys.toWindControlKey(path);
        String title = path.isEmpty() ? "wind" : path + "/wind";

        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.WIND);

        out.add(new UIKeyframeSheet(id, IKey.constant(title), Colors.CYAN, false, channel, null)
            .icon(Icons.ARROW_RIGHT).form(modelForm).seed(() -> buildWindControl(modelForm)));
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
        ModelInstance model = ModelFormRenderer.getModel(modelForm);

        if (model == null)
        {
            return;
        }

        ModelPhysicsConfig physics = null;

        if (modelForm.physics.get() instanceof MapType map)
        {
            physics = ModelPhysicsIO.fromData(map);
        }

        if (physics == null || physics.bones() == null)
        {
            return;
        }

        String path = FormUtils.getPath(modelForm);

        for (Map.Entry<String, ModelPhysicsConfig.Bone> entry : physics.bones().entrySet())
        {
            String rootBone = entry.getKey();
            String id = PerLimbService.toPhysicsTargetKey(path, rootBone);
            String title = path.isEmpty() ? "physics/" + rootBone : path + "/physics/" + rootBone;

            addTargetSheet(out, properties, modelForm, id, title, Colors.MAGENTA, Icons.TIME);
        }
    }

    /** Collect every track a single form contributes to the timeline (its own properties plus model sub-tracks), used to populate the per-form track filter. */
    public static List<UIKeyframeSheet> collectFormTrackSheets(Form form)
    {
        List<UIKeyframeSheet> sheets = new ArrayList<>();

        if (form == null)
        {
            return sheets;
        }

        FormProperties properties = new FormProperties("");

        for (BaseValue property : form.getAll())
        {
            if (!property.isVisible() || property.getId().equals("anchor"))
            {
                continue;
            }

            String key = property.getId();
            KeyframeChannel channel = properties.getOrCreate(form, key);

            if (channel == null)
            {
                continue;
            }

            BaseValueBasic formProperty = FormUtils.getProperty(form, key);

            sheets.add(new UIKeyframeSheet(UIReplaysEditor.getColor(key), false, channel, formProperty).icon(UIReplaysEditor.getIcon(key)));
        }

        if (form instanceof ModelForm modelForm)
        {
            addMaterialTextureSheets(modelForm, properties, sheets);
            addPhysicsControlSheet(modelForm, properties, sheets);
            addWindControlSheet(modelForm, properties, sheets);
            addPhysicsTargetSheets(modelForm, properties, sheets);
            addIKControlSheet(modelForm, properties, sheets);
            addIKTargetSheets(modelForm, properties, sheets);
            addPoleTargetSheets(modelForm, properties, sheets);
        }

        addGlintControlSheet(form, properties, sheets);
        addBoneTrackSheets(form, properties, sheets);

        return sheets;
    }

    private static void addTargetSheet(List<UIKeyframeSheet> out, FormProperties properties, ModelForm modelForm, String id, String title, int color, Icon icon)
    {
        KeyframeChannel channel = properties.registerChannel(id, KeyframeFactories.ANCHOR);

        out.add(new UIKeyframeSheet(id, IKey.constant(title), color, false, channel, null).icon(icon).form(modelForm));
    }

    public static void addSoundSheets(AbstractSoundForm form, FormProperties properties, List<UIKeyframeSheet> out)
    {
        for (SoundKeyframeValue.Group group : SoundKeyframeValue.Group.values())
        {
            String id = SoundKeyframeValue.channelId(form, group);
            SoundKeyframeFactory factory = switch (group)
            {
                case SOUND -> KeyframeFactories.SOUND;
                case SHAPE -> KeyframeFactories.SOUND_SHAPE;
                case VISUALIZATION -> KeyframeFactories.SOUND_VISUALIZATION;
                case FALLOFF -> KeyframeFactories.SOUND_FALLOFF;
                case REFLECTIONS -> KeyframeFactories.SOUND_REFLECTIONS;
            };
            IKey title = switch (group)
            {
                case SOUND -> UIKeys.FORMS_EDITORS_SOUND_TITLE;
                case SHAPE -> UIKeys.FORMS_EDITORS_SOUND_SHAPE;
                case VISUALIZATION -> UIKeys.FORMS_EDITORS_SOUND_VISUALIZATION;
                case FALLOFF -> UIKeys.FORMS_EDITORS_SOUND_FALLOFF;
                case REFLECTIONS -> UIKeys.FORMS_EDITORS_SOUND_REFLECTIONS;
            };
            int color = switch (group)
            {
                case SOUND -> Colors.CYAN;
                case SHAPE -> Colors.YELLOW;
                case VISUALIZATION -> Colors.GREEN;
                case FALLOFF -> Colors.BLUE;
                case REFLECTIONS -> Colors.MAGENTA;
            };
            Icon icon = switch (group)
            {
                case SOUND -> Icons.SOUND;
                case SHAPE -> Icons.SHAPES;
                case VISUALIZATION -> Icons.VISIBLE;
                case FALLOFF -> Icons.FADING;
                case REFLECTIONS -> Icons.EXCHANGE;
            };
            KeyframeChannel<SoundKeyframeValue> channel = properties.registerChannel(id, factory);

            out.add(new UIKeyframeSheet(id, title, color, false, channel, null)
                .icon(icon).form(form).seed(() -> SoundKeyframeValue.capture(form, group)));
        }
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
                removeChannel(props, PerLimbService.toIKTargetKey(path, controller));
            }

            for (String controller : poleControllers)
            {
                removeChannel(props, PerLimbService.toPoleTargetKey(path, controller));
            }
        });
    }

    private static void removeChannel(FormProperties props, String id)
    {
        KeyframeChannel channel = props.properties.get(id);

        if (channel != null)
        {
            channel.removeAll();
        }
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

    public static boolean startFilmGizmo(UIFilmPanel panel, UIContext context, int stencilIndex, float gizmoTransition)
    {
        if (panel == null || panel.isFlying() || context.mouseButton != 0)
        {
            return false;
        }

        UIPropTransform transform = getEditableTransform(panel.replayEditor.keyframeEditor);
        GizmoDrag drag = buildFilmGizmoDrag(
            panel,
            panel.getCamera(),
            panel.preview.getViewport(),
            transform,
            gizmoTransition
        );

        return transform != null && Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, transform, drag);
    }

    public static void configureFilmHotkeyDrag(UIFilmPanel panel, UIContext context)
    {
        UIPropTransform transform = panel == null ? null : getEditableTransform(panel.replayEditor.keyframeEditor);

        if (transform == null)
        {
            return;
        }

        transform.hotkeyDrag(() -> buildFilmGizmoDrag(
            panel,
            panel.getCamera(),
            panel.preview.getViewport(),
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

        return instance != null && ModelIKRuntime.isRotationConstrained(instance.model, modelForm, StringUtils.fileName(bone.a));
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

        drag.setGlobalAxes(BaseFilmController.getReplayWorldAxes(entity, transition));

        if (transform == null || transform.getTransform() == null)
        {
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

        java.util.function.Supplier<Matrix4f> matrixSampler = () ->
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

        Vector3f rotationOffset = sampleFilmBoneRotationOffset(panel, camera, entity, replay, transition, bone.a);

        drag.setRotateAxes(GizmoDrag.computeRotateAxes(transform.getTransform(), matrixSampler));
        drag.setRotate2Axes(GizmoDrag.computeRotateAxes(transform.getTransform(), true, matrixSampler));
        drag.setRotationParents(transform.getTransform(), rotationOffset, matrixSampler);

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
            float tick = panel.getCursor() + (panel.getRunner().isRunning() ? transition : 0F);

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

        return BaseFilmController.getBoneCompositeMatrix(
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

    private static Vector3f sampleFilmBoneRotationOffset(
        UIFilmPanel panel,
        Camera camera,
        IEntity entity,
        Replay replay,
        float transition,
        String bone
    )
    {
        applyReplayProperties(panel, entity, replay, transition);

        return BaseFilmController.getGizmoBoneRotationOffset(
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

        return BaseFilmController.getGizmoBoneEvaluatedRotation(
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
        java.util.function.Supplier<Matrix4f> matrixSampler = () ->
        {
            applyReplayProperties(panel, entity, replay, transition);

            Matrix4f matrix = BaseFilmController.getGizmoAnchorCompositeMatrix(
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
        drag.setRotate2Axes(GizmoDrag.computeRotateAxes(transform.getTransform(), true, matrixSampler));
        drag.setRotationParents(transform.getTransform(), null, matrixSampler);
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
                int tick = cursor.getCursor();
                Keyframe closest = getClosestKeyframe(currentSheet, tick);
                if (closest != null)
                {
                    if (currentSheet.selection.getSelected().size() <= 1)
                    {
                        forceSelectInSheet(graph, currentSheet, closest);
                    }
                    cursor.setCursor((int) closest.getTick());
                }
                updatePoseEditorBoneSelection(keyframeEditor, bone);
                return;
            }
        }

        if (insert)
        {
            IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
            Keyframe selected = graph.getSelected();
            UIKeyframeSheet currentSheet = selected != null ? graph.getSheet(selected) : null;

            if (isPoseSheet(currentSheet, path))
            {
                return;
            }

            pickProperty(keyframeEditor, cursor, bone, boneKey, true);
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
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        UIKeyframeSheet sheet = graph.getSheet(boneKey);

        if (sheet != null)
        {
            return sheet;
        }

        UIKeyframeSheet caseInsensitive = null;

        for (UIKeyframeSheet s : graph.getSheets())
        {
            if (s.id != null && s.id.equalsIgnoreCase(boneKey))
            {
                if (caseInsensitive != null)
                {
                    caseInsensitive = null;
                    break;
                }

                caseInsensitive = s;
            }
        }

        if (caseInsensitive != null)
        {
            return caseInsensitive;
        }

        return getActivePoseSheet(keyframeEditor, formPath);
    }

    private static UIKeyframeSheet getActivePoseSheet(UIKeyframeEditor keyframeEditor, String formPath)
    {
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        Keyframe selected = graph.getSelected();
        UIKeyframeSheet sheet = selected != null ? graph.getSheet(selected) : graph.getLastSheet();

        if (sheet == null || sheet.id == null)
        {
            return null;
        }

        String name = StringUtils.fileName(sheet.id);

        if (!name.startsWith("pose"))
        {
            return null;
        }

        if (sheet.property != null)
        {
            Form sheetForm = FormUtils.getForm(sheet.property);

            if (sheetForm != null)
            {
                return FormUtils.getPath(sheetForm).equals(formPath) ? sheet : null;
            }
        }

        if (formPath.isEmpty())
        {
            return sheet.id.contains(FormUtils.PATH_SEPARATOR) ? null : sheet;
        }

        String prefix = formPath + FormUtils.PATH_SEPARATOR;

        return sheet.id.startsWith(prefix) ? sheet : null;
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
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        int tick = filmPanel.getCursor();

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
            filmPanel.setCursor((int) closest.getTick());
        }
        else
        {
            updatePoseEditorBoneSelection(keyframeEditor, boneForEditor);
        }
    }

    private static Keyframe getClosestKeyframe(UIKeyframeSheet sheet, int tick)
    {
        KeyframeSegment segment = sheet.channel.find(tick);

        return segment != null ? segment.getClosest() : null;
    }

    private static boolean isPoseSheet(UIKeyframeSheet sheet, String formPath)
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
        int tick, String animationKey, boolean onlyKeyframes, int length, int step
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

    private static void fillAnimationPose(UIKeyframeSheet sheet, float i, ModelInstance model, IEntity entity, Animation animation, int current)
    {
        model.model.resetPose();
        model.model.apply(entity, animation, i, 1F, 0F, false);

        int insert = sheet.channel.insert(current + i, model.model.createPose());

        sheet.selection.add(insert);
    }

    @SuppressWarnings("unchecked")
    public static void posesToLimbTracks(Replay replay, UIKeyframeSheet poseSheet)
    {
        if (replay == null || poseSheet == null)
        {
            return;
        }

        String formPath = poseSheet.id.equals("pose") ? "" : poseSheet.id.substring(0, poseSheet.id.length() - (FormUtils.PATH_SEPARATOR + "pose").length());
        Form form = formPath.isEmpty() ? replay.form.get() : FormUtils.getForm(replay.form.get(), formPath);

        if (!(form instanceof PoseForm))
        {
            return;
        }

        List<String> bones = FormUtilsClient.getBoneHierarchy(form).getBoneIds();

        List<Keyframe<Pose>> selectedKeyframes = (List<Keyframe<Pose>>) (List<?>) poseSheet.selection.getSelected();

        if (selectedKeyframes.isEmpty())
        {
            return;
        }

        for (Keyframe<Pose> keyframe : selectedKeyframes)
        {
            Pose pose = keyframe.getValue();

            if (pose == null)
            {
                continue;
            }

            float tick = keyframe.getTick();

            for (String bone : bones)
            {
                String boneKey = PerLimbService.toPoseBoneKey(formPath, bone);
                KeyframeChannel<PoseTransform> limbChannel = (KeyframeChannel<PoseTransform>) replay.properties.getOrCreate(form, boneKey);

                if (limbChannel == null)
                {
                    continue;
                }

                PoseTransform transform = pose.get(bone);
                PoseTransform copy = (PoseTransform) transform.copy();
                int index = limbChannel.insert(tick, copy);
                Keyframe<PoseTransform> limbKf = limbChannel.get(index);

                limbKf.copyOverExtra(keyframe);
            }
        }
    }

    /* Offer bone hierarchy options */

    public interface FormPicker
    {
        void pick(Form form, String bone, boolean insert);
    }

    public static boolean pickFormWithOffers(UIContext context, Pair<Form, String> pair, FormPicker picker)
    {
        boolean select = context.mouseButton == 0 || (context.mouseButton == 2 && Window.isCtrlPressed());
        boolean insert = context.mouseButton == 1;

        if (pair == null || pair.a == null || (!select && !insert))
        {
            return false;
        }

        if (Window.isAltPressed() && !Window.isCtrlPressed())
        {
            offerAdjacent(context, pair.a, pair.b, (bone) -> picker.pick(pair.a, bone, insert));
        }
        else if (Window.isShiftPressed())
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
                Map<String, String> labels = hierarchy.getLabels(false);

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
                Map<String, String> labels = hierarchy.getLabels(false);

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
