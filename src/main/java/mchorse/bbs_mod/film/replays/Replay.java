package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.actions.SuperFakePlayer;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.data.migration.FilmStableIds;
import mchorse.bbs_mod.data.migration.FormStableIds;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class Replay extends ValueGroup
{
    public final ValueForm form = new ValueForm("form");
    public final ReplayKeyframes keyframes = new ReplayKeyframes("keyframes");
    public final FormProperties properties = new FormProperties("properties");
    public final Clips actions = new Clips("actions", BBSMod.getFactoryActionClips());

    public final ValueBoolean enabled = new ValueBoolean("enabled", true);
    /**
     * Single-level folder name for the replay list (empty = root). No {@code /} — see {@link #normalizeCategory(String)}.
     */
    public final ValueString category = new ValueString("category", "");
    public final ValueString label = new ValueString("label", "");
    public final ValueString nameTag = new ValueString("name_tag", "");
    public final ValueBoolean shadow = new ValueBoolean("shadow", true);
    public final ValueFloat shadowSize = new ValueFloat("shadow_size", 0.5F);
    /** When enabled, the shadow follows the form's perceived position (anchor/transform motion). */
    public final ValueBoolean shadowFollow = new ValueBoolean("shadow_follow", false);
    /** Extra world-space offset added to the followed shadow position (corrects a model's shifted floor level). */
    public final ValuePoint shadowOffset = new ValuePoint("shadow_offset", new Point(0, 0, 0));
    public final ValueInt looping = new ValueInt("looping", 0);

    public final ValueBoolean actor = new ValueBoolean("actor", false);
    public final ValueBoolean fp = new ValueBoolean("fp", false);
    public final ValueBoolean relative = new ValueBoolean("relative", false);
    public final ValuePoint relativeOffset = new ValuePoint("relativeOffset", new Point(0, 0, 0));

    public final ValueBoolean axesPreview = new ValueBoolean("axes_preview", false);
    public final ValueString axesPreviewBone = new ValueString("axes_preview_bone", "");

    public Replay(String id)
    {
        super(id);

        this.add(this.form);
        this.add(this.keyframes);
        this.add(this.properties);
        this.add(this.actions);

        this.add(this.enabled);
        this.add(this.category);
        this.add(this.label);
        this.add(this.nameTag);
        this.add(this.shadow);
        this.add(this.shadowSize);
        this.add(this.shadowFollow);
        this.add(this.shadowOffset);
        this.add(this.looping);

        this.add(this.actor);
        this.add(this.fp);
        this.add(this.relative);
        this.add(this.relativeOffset);

        this.add(this.axesPreview);
        this.add(this.axesPreviewBone);
    }

    /**
     * Normalizes a user-supplied category: trim, single segment (no {@code /}), empty = root.
     */
    public static String normalizeCategory(String raw)
    {
        if (raw == null)
        {
            return "";
        }

        String s = raw.trim();

        if (s.isEmpty())
        {
            return "";
        }

        int slash = s.indexOf('/');

        if (slash >= 0)
        {
            s = s.substring(0, slash).trim();
        }

        return s.isEmpty() ? "" : s;
    }

    /**
     * A replay also travels outside a film document — a clipboard replay, a replay preset — where
     * no film version gate applies, so the conversion has to happen here as well. It is the same
     * walk {@code FilmStableIds.migrate} does, and running it twice is a no-op.
     *
     * <p>The action targets are normalized against an <em>empty</em> replay list: a bare replay
     * carries no source film order, so a cross-film index cannot be resolved and fails closed to
     * "no target". An id that is already stable survives untouched, which is what keeps a
     * within-film copy/paste, and every load of an already-migrated film, pointing where it did.</p>
     */
    @Override
    public void fromData(BaseType data)
    {
        if (data.isMap())
        {
            FormStableIds.ensureReplay(data.asMap());
            FilmStableIds.migrateReplayReferences(data.asMap(), List.of());
        }

        super.fromData(data);
    }

    /**
     * Where this replay's own frame sits when it is {@link #relative}: its first keyframe plus the
     * authored offset. A relative replay is built around a fixed origin instead of around wherever
     * the camera happens to be, so every consumer that places it has to ask the same question — the
     * renderer, the bone matrices and the motion path all did it with their own copy of this sum.
     */
    public Vector3d getRelativeOrigin()
    {
        Point offset = this.relativeOffset.get();

        return new Vector3d(
            this.keyframes.x.interpolate(0F) + offset.x,
            this.keyframes.y.interpolate(0F) + offset.y,
            this.keyframes.z.interpolate(0F) + offset.z
        );
    }

    public String getName()
    {
        String label = this.label.get();

        if (!label.isEmpty())
        {
            return label;
        }

        Form form = this.form.get();

        if (form == null)
        {
            return "-";
        }

        return form.getDisplayName();
    }

    public void shift(float tick)
    {
        this.keyframes.shift(tick);
        this.properties.shift(tick);
        this.actions.shift(tick);
    }

    /**
     * The tick of the replay's last keyframe over its own channels and every property track —
     * where its authored motion ends; {@code -1} when it has no keyframes at all.
     */
    public float getLastKeyframeTick()
    {
        float last = -1F;

        for (KeyframeChannel<?> channel : this.keyframes.getChannels())
        {
            last = Math.max(last, lastTick(channel));
        }

        for (KeyframeChannel<?> channel : this.properties.properties.values())
        {
            last = Math.max(last, lastTick(channel));
        }

        return last;
    }

    private static float lastTick(KeyframeChannel<?> channel)
    {
        List<?> keyframes = channel.getKeyframes();

        return keyframes.isEmpty() ? -1F : ((Keyframe<?>) keyframes.get(keyframes.size() - 1)).getTick();
    }

    public void applyActions(LivingEntity actor, SuperFakePlayer fakePlayer, Film film, int tick)
    {
        this.applyActions(actor, fakePlayer, film, tick, () -> true);
    }

    public boolean applyActions(
        LivingEntity actor,
        SuperFakePlayer fakePlayer,
        Film film,
        int tick,
        BooleanSupplier continueAuthorized
    )
    {
        List<Clip> clips = this.actions.getClips(tick);

        return applyAuthorizedActions(clips, continueAuthorized, (action) ->
            action.apply(actor, fakePlayer, film, this, tick));
    }

    static boolean applyAuthorizedActions(
        List<Clip> clips,
        BooleanSupplier continueAuthorized,
        Consumer<ActionClip> apply
    )
    {
        if (clips == null || continueAuthorized == null || apply == null)
        {
            return false;
        }

        for (Clip clip : clips)
        {
            if (!(clip instanceof ActionClip action))
            {
                return false;
            }

            try
            {
                if (!continueAuthorized.getAsBoolean())
                {
                    return false;
                }
            }
            catch (RuntimeException e)
            {
                return false;
            }

            apply.accept(action);
        }

        return true;
    }

    public void applyClientActions(int tick, IEntity entity, Film film)
    {
        tick = this.getTick(tick);

        List<Clip> clips = this.actions.getClips(tick);

        for (Clip clip : clips)
        {
            if (clip instanceof ActionClip actionClip && actionClip.isClient())
            {
                actionClip.applyClient(entity, film, this, tick);
            }
        }
    }

    public int getTick(int tick)
    {
        return this.looping.get() > 0 ? tick % this.looping.get() : tick;
    }
}
