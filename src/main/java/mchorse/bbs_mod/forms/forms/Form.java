package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.api.FormPropertyAliases;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.states.AnimationState;
import mchorse.bbs_mod.forms.states.AnimationStates;
import mchorse.bbs_mod.forms.states.StatePlayer;
import mchorse.bbs_mod.forms.values.ValueAnchor;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.settings.values.core.StableIds;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.core.ValueTransform;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.settings.values.ui.ValueStringKeys;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public abstract class Form extends ValueGroup
{
    /** Sentinel in {@link #disabledTracks} meaning every track of this form is hidden on the timeline. */
    public static final String DISABLED_ALL = "*";

    public final ValueBoolean pickable = new ValueBoolean("pickable", true);
    public final ValueColor overlayColor = new ValueColor("color_overlay", new Color(1F, 1F, 1F, 0F));
    public final ValueInt renderLayer = new ValueInt("render_layer", 0);

    public static final int LAYER_AUTO = 0;
    public static final int LAYER_SOLID = 1;
    public static final int LAYER_CUTOUT = 2;
    public static final int LAYER_TRANSLUCENT = 3;

    public final ValueBoolean visible = new ValueBoolean("visible", true);
    public final ValueStringKeys disabledTracks = new ValueStringKeys("disabled_tracks");
    public final ValueString trackName = new ValueString("track_name", "");
    public final ValueFloat lighting = new ValueFloat("lighting", 1F);
    public final ValueString name = new ValueString("name", "");
    public final ValueTransform transform = new ValueTransform("transform", new Transform());
    public final ValueTransform transformOverlay = new ValueTransform("transform_overlay", new Transform());
    public final List<ValuePose> additionalOverlays = new ArrayList<>();
    public final ValueFloat uiScale = new ValueFloat("uiScale", 1F);
    public final ValueAnchor anchor = new ValueAnchor("anchor", new Anchor());
    public final ValueBoolean shaderShadow = new ValueBoolean("shaderShadow", true);
    public final ValueBoolean additiveColor = new ValueBoolean("additive_color", false);

    /**
     * Draw after every other form of the pass (the client's FormRenderLast): the per-form cure
     * for a semi-transparent form hiding what is behind it, instead of reordering the list.
     */
    public final ValueBoolean renderLast = new ValueBoolean("render_last", false);

    /* Enchantment glint over the whole form: off, full, edge or vanilla. Model
     * forms drive their glint per bone from the pose instead, since a skeleton lets the
     * effect be placed far more precisely than one switch for the entire form. */
    public final ValueInt glintMode = new ValueInt("glint", GLINT_OFF);
    /* Glint tint, with alpha doubling as the effect's opacity. */
    public final ValueColor glintColor = new ValueColor("glint_color", Color.white());
    public final ValueFloat glintSpeed = new ValueFloat("glint_speed", 1F);
    public final ValueTransform glintTransform = new ValueTransform("glint_transform", new Transform());

    public static final int GLINT_OFF = 0;
    public static final int GLINT_FULL = 1;
    public static final int GLINT_EDGE = 2;
    public static final int GLINT_VANILLA = 3;

    /**
     * Whether this kind of form renders the glint above, which decides if the editor offers
     * the controls at all — an switch that visibly does nothing is worse than no switch.
     *
     * <p>Off by default: drawing the glint means re-drawing the form's geometry through a
     * a compatible vanilla render type, which only renderers with reusable geometry can do.
     * Model forms opt out too, driving their glint per bone from the pose instead.</p>
     */
    public boolean supportsGlint()
    {
        return false;
    }

    public final List<ValueTransform> additionalTransforms = new ArrayList<>();

    /* Hitbox properties */
    public final ValueBoolean hitbox = new ValueBoolean("hitbox", false);
    public final ValueFloat hitboxWidth = new ValueFloat("hitboxWidth", 0.5F);
    public final ValueFloat hitboxHeight = new ValueFloat("hitboxHeight", 1.8F);
    public final ValueFloat hitboxSneakMultiplier = new ValueFloat("hitboxSneakMultiplier", 0.9F);
    public final ValueFloat hitboxEyeHeight = new ValueFloat("hitboxEyeHeight", 0.9F);

    /* Morphing properties */
    public final ValueFloat hp = new ValueFloat("hp", 20F);
    public final ValueFloat speed = new ValueFloat("movement_speed", 0.1F);
    public final ValueFloat stepHeight = new ValueFloat("step_height", 0.5F);

    public final ValueInt hotkey = new ValueInt("keybind", 0);

    public final BodyPartManager parts = new BodyPartManager("parts");
    public final AnimationStates states = new AnimationStates("states");

    protected Object renderer;
    protected String cachedID;

    private final List<StatePlayer> statePlayers = new ArrayList<>();

    private int poseVersion;

    public int getPoseVersion()
    {
        this.syncOverlayTracks();
        return this.poseVersion;
    }

    public void bumpPoseVersion()
    {
        this.poseVersion += 1;
    }

    @Override
    public void postNotify(BaseValue value, int flag)
    {
        this.bumpPoseVersion();
        super.postNotify(value, flag);
    }

    public Form()
    {
        super("");

        this.disabledTracks.invisible();
        this.trackName.invisible();
        this.name.invisible();
        this.uiScale.invisible();
        this.shaderShadow.invisible();
        this.additiveColor.invisible();
        this.renderLast.invisible();

        this.pickable.invisible();
        this.renderLayer.invisible();
        this.add(this.pickable);
        this.add(this.overlayColor);
        this.add(this.renderLayer);
        this.add(this.visible);
        this.add(this.disabledTracks);
        this.add(this.trackName);
        this.add(this.lighting);
        this.add(this.name);
        this.add(this.transform);
        this.add(this.transformOverlay);

        this.add(this.uiScale);
        this.add(this.anchor);
        this.add(this.shaderShadow);
        this.add(this.additiveColor);
        this.add(this.renderLast);

        /* These remain persisted for the form editor and old Films, but the timeline now
         * exposes one dedicated glint-layer track instead of four implementation fields. */
        this.glintMode.invisible();
        this.glintColor.invisible();
        this.glintSpeed.invisible();
        this.glintTransform.invisible();
        this.add(this.glintMode);
        this.add(this.glintColor);
        this.add(this.glintSpeed);
        this.add(this.glintTransform);

        this.hitbox.invisible();
        this.hitboxWidth.invisible();
        this.hitboxHeight.invisible();
        this.hitboxSneakMultiplier.invisible();
        this.hitboxEyeHeight.invisible();

        this.add(this.hitbox);
        this.add(this.hitboxWidth);
        this.add(this.hitboxHeight);
        this.add(this.hitboxSneakMultiplier);
        this.add(this.hitboxEyeHeight);

        this.hp.invisible();
        this.speed.invisible();
        this.stepHeight.invisible();

        this.add(this.hp);
        this.add(this.speed);
        this.add(this.stepHeight);

        this.hotkey.invisible();

        this.add(this.hotkey);

        this.add(this.parts);
        this.add(this.states);
    }

    /** Apply global overlay counts to existing forms as well as newly created ones. */
    public final void syncOverlayTracks()
    {
        int previousTransforms = this.additionalTransforms.size();
        int transforms = BBSSettings.recordingTransformOverlays == null ? 0 : BBSSettings.recordingTransformOverlays.get();
        while (this.additionalTransforms.size() > transforms)
            this.remove(this.additionalTransforms.remove(this.additionalTransforms.size() - 1));
        while (this.additionalTransforms.size() < transforms)
        {
            ValueTransform value = new ValueTransform("transform_overlay" + this.additionalTransforms.size(), new Transform());
            this.additionalTransforms.add(value);
            this.add(value);
        }

        List<ValuePose> overlays = this instanceof IPosedForm ? this.additionalOverlays : null;
        int poses = overlays != null && super.get("pose") != null && BBSSettings.recordingPoseOverlays != null
            ? BBSSettings.recordingPoseOverlays.get() : 0;
        if (overlays == null)
        {
            if (previousTransforms != transforms) this.bumpPoseVersion();
            return;
        }
        int previousPoses = overlays.size();
        while (overlays.size() > poses) this.remove(overlays.remove(overlays.size() - 1));
        while (overlays.size() < poses)
        {
            ValuePose value = new ValuePose("pose_overlay" + overlays.size(), new Pose());
            overlays.add(value);
            this.add(value);
        }
        if (previousTransforms != transforms || previousPoses != poses) this.bumpPoseVersion();
    }

    @Override
    public List<BaseValue> getAll()
    {
        this.syncOverlayTracks();
        return super.getAll();
    }

    @Override
    public BaseValue get(String key)
    {
        this.syncOverlayTracks();
        return super.get(key);
    }

    public BaseValueBasic getBasic(String key)
    {
        BaseValue value = this.get(key);
        return value instanceof BaseValueBasic basic ? basic : null;
    }

    @Override
    public void copy(BaseValueGroup group)
    {
        this.syncOverlayTracks();
        super.copy(group);
    }

    public Object getRenderer()
    {
        return this.renderer;
    }

    public void setRenderer(Object renderer)
    {
        this.renderer = renderer;
    }

    public Form getParentForm()
    {
        BaseValue parentValue = this.getParent();

        while (parentValue != null)
        {
            if (parentValue instanceof Form form)
            {
                return form;
            }

            parentValue = parentValue.getParent();
        }

        return null;
    }

    /* Animation states */

    public boolean findState(int hotkey, IStateFoundCallback callback)
    {
        if (callback == null)
        {
            return false;
        }

        for (AnimationState state : this.states.getAllTyped())
        {
            if (state.keybind.get() == hotkey)
            {
                callback.acceptState(this, state);

                return true;
            }
        }

        return false;
    }

    public void clearStatePlayers()
    {
        this.statePlayers.clear();
    }

    public void playState(AnimationState state)
    {
        if (state != null)
        {
            if (state.looping.get())
            {
                for (StatePlayer statePlayer : this.statePlayers)
                {
                    if (statePlayer.getState() == state)
                    {
                        statePlayer.expire();

                        return;
                    }
                }
            }

            this.statePlayers.add(new StatePlayer(state));
        }
    }

    public void playState(String stateId)
    {
        this.playState(this.states.getById(stateId));
    }

    public void playMain()
    {
        this.clearStatePlayers();
        this.playState(this.states.getMainRandom());
    }

    public void applyStates(float transition)
    {
        if (!this.statePlayers.isEmpty())
        {
            this.bumpPoseVersion();
        }

        for (StatePlayer statePlayer : this.statePlayers)
        {
            statePlayer.assignValues(this, transition);
        }
    }

    public void unapplyStates()
    {
        if (!this.statePlayers.isEmpty())
        {
            this.bumpPoseVersion();
        }

        for (StatePlayer statePlayer : this.statePlayers)
        {
            statePlayer.resetValues(this);
        }
    }

    /* Morphing */

    public void onMorph(LivingEntity entity)
    {
        float hp = this.hp.get();
        float speed = this.speed.get();
        float stepHeight = this.stepHeight.get();

        if (hp != 20F)
        {
            if (entity.getAttribute(Attributes.MAX_HEALTH) != null)
            {
                entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);
            }

            entity.setHealth(hp);
        }

        if (speed != 0.1F && entity.getAttribute(Attributes.MOVEMENT_SPEED) != null)
        {
            entity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
        }

        if (stepHeight != 0.5F && entity.getAttribute(Attributes.STEP_HEIGHT) != null)
        {
            entity.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(stepHeight);
        }
    }

    public void onDemorph(LivingEntity entity)
    {
        if (entity.getAttribute(Attributes.MAX_HEALTH) != null)
        {
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20F);
        }

        entity.setHealth(20F);

        if (entity.getAttribute(Attributes.MOVEMENT_SPEED) != null)
        {
            entity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.1F);
        }

        if (entity.getAttribute(Attributes.STEP_HEIGHT) != null)
        {
            entity.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.5F);
        }
    }

    /* ID and display name */

    public String getFormId()
    {
        if (this.cachedID == null)
        {
            this.cachedID = BBSMod.getForms().getType(this).toString();
        }

        return this.cachedID;
    }

    public String getFormIdOrName()
    {
        String name = this.name.get();

        return name.isEmpty() ? this.getFormId() : name;
    }

    public final String getDisplayName()
    {
        String name = this.name.get();

        if (!name.isEmpty())
        {
            return name;
        }

        return this.getDefaultDisplayName();
    }

    protected String getDefaultDisplayName()
    {
        return this.getFormId();
    }

    public String getTrackName(String property)
    {
        String custom = this.trackName.get();

        if (property.isEmpty())
        {
            return custom;
        }

        TrackId track = TrackId.parse(property);
        String last;

        if (track == null)
        {
            int slash = property.lastIndexOf('/');

            last = slash == -1 ? property : property.substring(slash + 1);
        }
        else
        {
            /* Asked of the address itself: a bone track reads "head", not "pose.bones.head". */
            last = track.label();
        }

        /* An address segment (a body part's stable id, or a legacy index) is not a name. */
        boolean address = StableIds.isStableId(last) || StringUtils.isInteger(last);
        String leaf = address ? "" : last;
        String owner = this.getTrackLabel();

        if (owner.isEmpty())
        {
            return leaf;
        }

        return leaf.isEmpty() ? owner : owner + "/" + leaf;
    }

    /**
     * What a timeline calls this form when it prefixes one of its tracks: the animator's own track
     * name when set, otherwise the names of the forms it hangs under as a body part, root-first.
     * Empty for a root form, whose tracks stand for the replay itself and need no prefix.
     *
     * <p>This is the label side of a track's identity; the address side is
     * {@link FormUtils#getPath}. They must not be confused: the address is
     * built from random stable ids and is unreadable by design.</p>
     */
    public String getTrackLabel()
    {
        String custom = this.trackName.get();

        if (!custom.isEmpty())
        {
            return custom;
        }

        List<String> names = new ArrayList<>();
        Form form = this;

        while (form.getParentForm() != null)
        {
            names.add(form.getDisplayName());

            form = form.getParentForm();
        }

        Collections.reverse(names);

        return String.join("/", names);
    }

    /* Update */

    public void update(IEntity entity)
    {
        this.parts.update(entity);

        if (this.renderer instanceof ITickable)
        {
            ((ITickable) this.renderer).tick(entity);
        }

        Iterator<StatePlayer> it = this.statePlayers.iterator();

        while (it.hasNext())
        {
            StatePlayer next = it.next();

            next.update();

            if (next.canBeRemoved())
            {
                it.remove();
            }
        }
    }

    /* Data comparison and (de)serialization */

    @Override
    protected String resolveReadKey(String key)
    {
        return FormPropertyAliases.resolve(key);
    }

    @Override
    public void fromData(BaseType data)
    {
        this.syncOverlayTracks();
        if (data instanceof MapType map)
        {
            /* Compatibility with older forms */
            if (map.has("bodyParts"))
            {
                MapType bodyParts = map.getMap("bodyParts");

                if (bodyParts.has("parts"))
                {
                    map.remove("bodyParts");
                    map.put("parts", bodyParts.getList("parts"));
                }
            }
        }

        super.fromData(data);

        Set<String> migratedTracks = new LinkedHashSet<>();
        for (String key : this.disabledTracks.get())
        {
            TrackId track = FormPropertyAliases.resolve(TrackId.parse(key));
            migratedTracks.add(track == null ? key : track.toKey());
        }
        this.disabledTracks.get().clear();
        this.disabledTracks.get().addAll(migratedTracks);

        if (data instanceof MapType map)
        {
            /* Compatibility with state triggers */
            FormUtils.readOldStateTriggers(this, map);

            /* The "animatable" toggle was removed; a disabled one meant the form had no tracks,
             * which is now expressed by hiding every track of the form on the timeline. */
            if (map.has("animatable") && !map.getBool("animatable", true))
            {
                this.disabledTracks.get().add(DISABLED_ALL);
            }

            /* Pre-overlay FS forms used additive tint as a substitute for color overlay.
             * Current FSR saves write color_overlay and glint, and keep their explicit blend mode. */
            if (this.additiveColor.get() && !map.has("color_overlay") && !map.has("glint"))
            {
                if (this.get("color") instanceof ValueColor colorValue)
                {
                    Color color = colorValue.get();

                    this.overlayColor.set(new Color(color.r, color.g, color.b, color.a));
                    colorValue.set(Color.white());
                }

                this.additiveColor.set(false);
            }
        }
    }

    @Override
    public BaseType toData()
    {
        this.syncOverlayTracks();
        BaseType data = super.toData();

        if (data instanceof MapType map)
        {
            BBSMod.getForms().appendId(this, map);
        }

        return data;
    }

    public Icon getIcon()
    {
        return Icons.GEAR;
    }
}
