package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.constraints.BoneConstraintsIO;
import mchorse.bbs_mod.cubic.ik.IKControl;
import mchorse.bbs_mod.cubic.physics.PhysicsControl;
import mchorse.bbs_mod.cubic.physics.WindControl;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.forms.utils.ValueBones;
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
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.pose.Pose;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModelForm extends Form implements PoseForm
{
    /**
     * Children of this form that a build newer than this one writes and this one does not model.
     *
     * <p>Upstream keeps the per-material appearance overrides in {@code materials} and the form's
     * global wind in {@code wind}. This build has neither — it still carries the material data in
     * its own {@code material_textures} layer and its wind in the {@code wind} blob upstream's
     * legacy reader shares — so nothing here claims those keys and, without this list, reading such
     * a file and saving it would delete them.</p>
     *
     * <p>Kept as raw data and written back untouched — see
     * {@link mchorse.bbs_mod.settings.values.core.ValueGroup#preservedUnknownKeys()}. That is a
     * stopgap, not support: this build still shows no material override from such a file. What it
     * prevents is the part that cannot be undone: the overrides used to disappear from disk with no
     * warning and nothing in the editor to suggest anything had been there.</p>
     *
     * <p>{@code bones} was on this list until this build learned to store constraints per bone —
     * see {@link #bones}. The key is claimed by a real child now, so it is no longer an unknown
     * child of this form.</p>
     */
    private static final Set<String> PRESERVED_UNKNOWN_KEYS = Set.of("materials", "wind");

    public final ValueLink texture = new ValueLink("texture", null);
    public final ValueLinks materialTextures = new ValueLinks("material_textures");
    public final ValueString model = new ValueString("model", "");
    public final ValuePose pose = new ValuePose("pose", new Pose());
    public final ValuePose poseOverlay = new ValuePose("pose_overlay", new Pose());
    public final ValueActionsConfig actions = new ValueActionsConfig("actions", new ActionsConfig());
    public final ValueColor color = new ValueColor("color", Color.white());
    public final ValueShapeKeys shapeKeys = new ValueShapeKeys("shape_keys", new ShapeKeys());
    public final ValueBoolean boneTracks = new ValueBoolean("bone_tracks", true);
    /**
     * The per-bone properties, in the shape a newer build writes them: one entry per bone the
     * author ever touched. This is where the rotation limits live now — the form-level
     * {@code constraints} blob they used to travel in is read once and unpacked into here, see
     * {@link #fromData(BaseType)}.
     */
    public final ValueBones bones = new ValueBones("bones");
    public final ValueData ik = new ValueData("ik");
    public final ValueData physics = new ValueData("physics");
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

    public final List<ValuePose> additionalOverlays = new ArrayList<>();

    /**
     * Runtime per-material texture overrides driven by the per-material animation tracks
     * (keyed by material name). Set each frame by {@code FormProperties} during playback and
     * read first by the renderer's texture resolver; empty means "no track override, use the
     * material's default / the form's default texture".
     */
    public final transient Map<String, Link> materialTextureOverrides = new HashMap<>();

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
        this.add(this.model);
        this.add(this.pose);
        this.add(this.poseOverlay);

        for (int i = 0; i < (BBSSettings.recordingPoseTransformOverlays.get()); i++)
        {
            ValuePose valuePose = new ValuePose("pose_overlay" + i, new Pose());

            this.additionalOverlays.add(valuePose);
            this.add(valuePose);
        }

        this.add(this.actions);
        this.add(this.color);
        this.add(this.shapeKeys);
        this.boneTracks.invisible();
        this.add(this.boneTracks);

        this.ik.invisible();
        this.physics.invisible();
        this.bones.invisible();
        this.pickingOverrides.invisible();
        this.add(this.ik);
        this.add(this.physics);
        this.add(this.bones);
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

    @Override
    protected Set<String> preservedUnknownKeys()
    {
        return PRESERVED_UNKNOWN_KEYS;
    }

    /**
     * An empty {@code bones} group is not written. The group is a container, not a value: a model
     * form that was never given bone properties keeps exactly the fields it had, so opening and
     * saving a project that predates the group does not rewrite every form in it.
     */
    @Override
    protected boolean canPersist(BaseValue value)
    {
        return !(value == this.bones && this.bones.toData().asMap().isEmpty()) && super.canPersist(value);
    }

    /**
     * The one-way migration at the read end.
     *
     * <p>Forms saved before the {@code bones} group kept the constraints as an opaque blob in the
     * exchange format; unpack it into the per-bone properties. The reset flag is {@code false}
     * because the blob and the group can name the same bones, and the blob is the older of the two
     * writers: merging it over the group — which is what {@code false} does — reproduces upstream's
     * order exactly, so the blob wins for the bones it names and the group keeps the rest, instead
     * of a bone the blob omits losing what the group gave it.</p>
     *
     * <p>{@code constraints} is no longer a child of this form, so the key is not claimed by
     * anything on the way back out and the blob disappears from the saved file. That is the point:
     * this is a migration, not a second storage location.</p>
     */
    @Override
    public void fromData(BaseType data)
    {
        super.fromData(data);

        if (data instanceof MapType map && map.has("constraints", BaseType.TYPE_MAP))
        {
            BoneConstraintsIO.read(map.getMap("constraints"), this.bones, false);
        }
    }
}
