package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.constraints.BoneConstraintsIO;
import mchorse.bbs_mod.cubic.ik.BoneIKIO;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.physics.BonePhysicsIO;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.utils.ValueBones;
import mchorse.bbs_mod.forms.forms.utils.ValueBoneConfig;
import mchorse.bbs_mod.forms.forms.utils.ValueMaterials;
import mchorse.bbs_mod.forms.values.ValueActionsConfig;
import mchorse.bbs_mod.forms.values.ValueShapeKeys;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueLinks;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.core.ValueWindControl;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Pose;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;

public class ModelForm extends Form implements PoseForm
{
    public static final Icon ICON = Icons.POSE;

    public final ValueLink texture = new ValueLink("texture", null);
    public final ValueMaterials materials = new ValueMaterials("materials");
    public final ValueLinks materialTextures = new ValueLinks("material_textures");
    public final ValueString model = new ValueString("model", "");
    public final ValuePose pose = new ValuePose("pose", new Pose());
    public final ValuePose poseOverlay = new ValuePose("pose_overlay", new Pose());
    public final ValueActionsConfig actions = new ValueActionsConfig("actions", new ActionsConfig());
    public final ValueColor color = new ValueColor("color", Color.white());
    public final ValueShapeKeys shapeKeys = new ValueShapeKeys("shape_keys", new ShapeKeys());
    public final ValueBoolean boneTracks = new ValueBoolean("bone_tracks", true);
    /** Canonical per-bone constraints, IK, joint freedom and physics configuration. */
    public final ValueBones bones = new ValueBones("bones");
    public final ValueWindControl wind = new ValueWindControl("wind", new WindControl());
    /** Legacy exchange views; bones and wind own the persisted configuration. */
    public final ValueData ik = new ValueBoneConfig("ik", this.bones, null);
    public final ValueData physics = new ValueBoneConfig("physics", this.bones, this.wind);
    public final ValueData pickingOverrides = new ValueData("picking_overrides");

    /**
     * The entity states an OptiFine CEM model asks about and a form cannot know — see
     * {@link mchorse.bbs_mod.cubic.jem.CemStatus}.
     * Every one of them lies over what the entity says, so their defaults change nothing, and they only
     * appear in the editor for a model that carries a CEM program.
     */
    public final ValueBoolean cemSitting = new ValueBoolean("cem_sitting", false);
    public final ValueBoolean cemTamed = new ValueBoolean("cem_tamed", false);
    public final ValueBoolean cemAggressive = new ValueBoolean("cem_aggressive", false);
    public final ValueBoolean cemOnShoulder = new ValueBoolean("cem_on_shoulder", false);
    public final ValueBoolean cemBurning = new ValueBoolean("cem_burning", false);
    public final ValueBoolean cemInLava = new ValueBoolean("cem_in_lava", false);
    public final ValueBoolean cemClimbing = new ValueBoolean("cem_climbing", false);
    public final ValueBoolean cemCrawling = new ValueBoolean("cem_crawling", false);
    public final ValueFloat cemHealth = new ValueFloat("cem_health", 1F);

    /**
     * Runtime per-material texture overrides driven by the per-material animation tracks
     * (keyed by material name). Set each frame by {@code FormProperties} during playback and
     * read first by the renderer's texture resolver; empty means "no track override, use the
     * material's default / the form's default texture".
     */
    public final transient Map<String, Link> materialTextureOverrides = new HashMap<>();

    public final transient Map<String, Color> materialColorOverrides = new HashMap<>();
    public final transient Map<String, Color> materialOverlayOverrides = new HashMap<>();
    public final transient Map<String, Float> materialLightingOverrides = new HashMap<>();
    public final transient Map<String, Integer> materialCullingOverrides = new HashMap<>();
    public final transient Map<String, Boolean> materialVisibilityOverrides = new HashMap<>();

    /** PBR slider overrides (keyed by material name, then by the slider's property name). */
    public final transient Map<String, Map<String, Float>> materialPbrOverrides = new HashMap<>();

    public final transient Map<String, Vector3f> ikTargetOverrides = new HashMap<>();
    public final transient Map<String, Vector3f> poleTargetOverrides = new HashMap<>();
    public final transient Map<String, Float> ikTargetWeights = new HashMap<>();
    public final transient Map<String, Float> poleTargetWeights = new HashMap<>();
    public final transient Map<String, IKControl> ikControlOverrides = new HashMap<>();
    public final transient Map<String, Vector3f> physicsTargetOverrides = new HashMap<>();
    public final transient Map<String, Float> physicsTargetWeights = new HashMap<>();
    public final transient Map<String, PhysicsControl> physicsControlOverrides = new HashMap<>();
    /* The global wind override layered by the wind track at playback; null when the track has no keyframe. */
    public transient WindControl windControlOverride;

    public ModelForm()
    {
        super();

        this.add(this.texture);
        this.materialTextures.invisible();
        this.add(this.materialTextures);
        this.materials.invisible();
        this.add(this.materials);
        this.add(this.model);
        this.add(this.pose);
        this.add(this.poseOverlay);

        this.syncOverlayTracks();

        this.add(this.actions);
        this.add(this.color);
        this.add(this.shapeKeys);
        this.boneTracks.invisible();
        this.add(this.boneTracks);

        this.ik.invisible();
        this.physics.invisible();
        this.bones.invisible();
        this.wind.invisible();
        this.pickingOverrides.invisible();
        this.add(this.ik);
        this.add(this.physics);
        this.add(this.bones);
        this.add(this.wind);
        this.add(this.pickingOverrides);

        /* Visible, so each is a track of its own: a cat that sits down mid-take is a keyframe like
         * any other. */
        this.add(this.cemSitting);
        this.add(this.cemTamed);
        this.add(this.cemAggressive);
        this.add(this.cemOnShoulder);
        this.add(this.cemBurning);
        this.add(this.cemInLava);
        this.add(this.cemClimbing);
        this.add(this.cemCrawling);
        this.add(this.cemHealth);
    }

    @Override
    public ValuePose getPose()
    {
        return this.pose;
    }

    @Override
    public ValuePose getPoseOverlay()
    {
        return this.poseOverlay;
    }

    @Override
    public ValueBoolean getBoneTracks()
    {
        return this.boneTracks;
    }

    @Override
    public String getDefaultDisplayName()
    {
        return this.model.get();
    }

    /**
     * Empty dynamic groups are not written. A model form without bone or material properties
     * keeps those containers absent when the project is saved.
     */
    @Override
    protected boolean canPersist(BaseValue value)
    {
        return value != this.ik && value != this.physics
            && !(value == this.wind && this.wind.getOriginalValue().isDefault())
            && !(value == this.bones && this.bones.toData().asMap().isEmpty())
            && !(value == this.materials && this.materials.getAll().isEmpty()) && super.canPersist(value);
    }

    /** Merge legacy exchange maps over canonical fields, then persist only bones and wind. */
    @Override
    public void fromData(BaseType data)
    {
        if (!(data instanceof MapType map))
        {
            super.fromData(data);
            return;
        }

        /* Read canonical fields first, then merge legacy blobs in upstream order. Keeping
         * compatibility values out of this walk makes the result independent of key order. */
        MapType canonical = (MapType) map.copy();
        canonical.remove("ik");
        canonical.remove("physics");
        super.fromData(canonical);

        if (map.has("constraints", BaseType.TYPE_MAP)) BoneConstraintsIO.read(map.getMap("constraints"), this.bones, false);
        if (map.has("ik", BaseType.TYPE_MAP)) BoneIKIO.read(map.getMap("ik"), this.bones, false);
        if (map.has("physics", BaseType.TYPE_MAP)) BonePhysicsIO.read(map.getMap("physics"), this.bones, this.wind, false);
    }

    @Override
    public Icon getIcon()
    {
        return ICON;
    }
}
