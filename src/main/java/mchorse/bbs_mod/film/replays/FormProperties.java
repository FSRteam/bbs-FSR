package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.cubic.glint.GlintControl;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValuePose;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.film.replays.tracks.MaterialTracks;
import mchorse.bbs_mod.film.replays.tracks.ConstraintTracks;
import mchorse.bbs_mod.api.FormPropertyAliases;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackBehaviour;
import mchorse.bbs_mod.film.replays.tracks.TrackBehaviours;
import mchorse.bbs_mod.film.replays.tracks.TrackContext;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.values.core.ValueBoneConstraint;
import mchorse.bbs_mod.cubic.constraints.BoneConstraint;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FormProperties extends ValueGroup
{
    public static final String POSE_PROPERTY = "pose";

    /** Canonical track addresses, shared by runtime and all timeline consumers. */
    public final Map<TrackId, KeyframeChannel> tracks = new LinkedHashMap<>();

    /** Live compatibility view retained for FSR addons compiled against textual track keys. */
    public final Map<String, KeyframeChannel> properties = new AbstractMap<>()
    {
        @Override
        public KeyframeChannel get(Object key)
        {
            return key instanceof String id ? FormProperties.this.get(TrackId.parse(id)) : null;
        }

        @Override
        public boolean containsKey(Object key)
        {
            return key instanceof String id && FormProperties.this.has(TrackId.parse(id));
        }

        @Override
        public KeyframeChannel put(String key, KeyframeChannel channel)
        {
            TrackId track = TrackId.parse(key);
            KeyframeChannel previous = FormProperties.this.get(track);
            FormProperties.this.put(track, channel);
            return previous;
        }

        @Override
        public KeyframeChannel remove(Object key)
        {
            TrackId track = key instanceof String id ? TrackId.parse(id) : null;
            KeyframeChannel previous = FormProperties.this.get(track);
            FormProperties.this.remove(track);
            return previous;
        }

        @Override
        public void clear()
        {
            for (KeyframeChannel channel : FormProperties.this.tracks.values())
            {
                FormProperties.this.remove(channel);
            }
            FormProperties.this.tracks.clear();
        }

        @Override
        public Set<Entry<String, KeyframeChannel>> entrySet()
        {
            return new AbstractSet<>()
            {
                @Override
                public int size() { return FormProperties.this.tracks.size(); }

                @Override
                public Iterator<Entry<String, KeyframeChannel>> iterator()
                {
                    Iterator<Entry<TrackId, KeyframeChannel>> iterator = FormProperties.this.tracks.entrySet().iterator();
                    return new Iterator<>()
                    {
                        private KeyframeChannel current;
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<String, KeyframeChannel> next()
                        {
                            Entry<TrackId, KeyframeChannel> entry = iterator.next();
                            this.current = entry.getValue();
                            return new SimpleEntry<>(entry.getKey().toKey(), entry.getValue())
                            {
                                @Override
                                public KeyframeChannel setValue(KeyframeChannel value)
                                {
                                    KeyframeChannel previous = FormProperties.this.properties.put(this.getKey(), value);
                                    current = value;
                                    super.setValue(value);
                                    return previous;
                                }
                            };
                        }
                        public void remove()
                        {
                            iterator.remove();
                            FormProperties.this.remove(this.current);
                        }
                    };
                }
            };
        }
    };

    /**
     * Channels that were read but could not be understood — the value factory
     * of this build is missing (an addon is not loaded). They are kept
     * verbatim and written back out on save.
     */
    private final Map<String, MapType> foreignChannels = new LinkedHashMap<>();
    private final Map<String, MapType> typedTemplates = new LinkedHashMap<>();
    private final List<MapType> foreignTracks = new ArrayList<>();

    public FormProperties(String id)
    {
        super(id);
    }

    public void shift(float tick)
    {
        for (KeyframeChannel<?> value : this.properties.values())
        {
            for (Keyframe<?> keyframe : value.getKeyframes())
            {
                keyframe.setTick(keyframe.getTick() + tick);
            }
        }
    }

    public KeyframeChannel get(TrackId track)
    {
        return track == null ? null : this.tracks.get(track);
    }

    public boolean has(TrackId track)
    {
        return track != null && this.tracks.containsKey(track);
    }

    public KeyframeChannel getOrCreate(Form root, String key)
    {
        return this.getOrCreate(root, TrackId.parse(key));
    }

    public KeyframeChannel getOrCreate(TrackId track)
    {
        return this.getOrCreate(null, track);
    }

    public KeyframeChannel getOrCreate(Form root, TrackId track)
    {
        track = FormPropertyAliases.resolve(track);
        if (track == null) return null;
        KeyframeChannel channel = this.get(track);
        if (channel != null) return channel;

        IKeyframeFactory factory = TrackBehaviours.factory(track);
        if (factory != null) return this.register(track, factory);

        BaseValue property = FormUtils.getProperty(root, track.toKey());
        return property == null ? null : this.create(property);
    }

    public KeyframeChannel create(BaseValue property)
    {
        if (property.isVisible() && property instanceof BaseKeyframeFactoryValue<?> value)
        {
            TrackId track = TrackId.parse(FormUtils.getPropertyPath(property));
            IKeyframeFactory factory = track != null && track.is(TrackKind.BONE)
                ? KeyframeFactories.POSE_TRANSFORM : value.getFactory();
            return this.register(track, factory);
        }
        return null;
    }

    public KeyframeChannel registerChannel(String key, IKeyframeFactory factory)
    {
        return this.register(TrackId.parse(key), factory);
    }

    public KeyframeChannel register(TrackId track, IKeyframeFactory factory)
    {
        track = FormPropertyAliases.resolve(track);
        if (track == null || factory == null) return null;
        KeyframeChannel channel = this.get(track);
        if (channel == null)
        {
            channel = new KeyframeChannel(track.toKey(), factory);
            this.put(track, channel);
        }
        return channel;
    }

    public void put(TrackId track, KeyframeChannel channel)
    {
        if (track == null || channel == null) return;
        KeyframeChannel previous = this.tracks.put(track, channel);
        if (previous != null) this.remove(previous);
        channel.setId(track.toKey());
        this.add(channel);
    }

    public void remove(TrackId track)
    {
        KeyframeChannel channel = this.tracks.remove(track);
        if (channel != null) this.remove(channel);
    }

    public void applyProperties(Form form, float tick)
    {
        this.applyProperties(form, tick, 1F);
    }

    public void applyProperties(Form form, float tick, float blend)
    {
        this.apply(TrackContext.of(form), tick, blend);
    }

    /** Properties first, bone contributions next, FSR glint and grouped sound last. */
    public void apply(TrackContext context, float tick, float blend)
    {
        if (context.root() == null) return;
        float amount = MathUtils.clamp(blend, 0F, 1F);
        Map<TrackId, KeyframeChannel> bones = new LinkedHashMap<>();
        Map<TrackId, KeyframeChannel> glint = new LinkedHashMap<>();
        Map<TrackId, KeyframeChannel> sound = new LinkedHashMap<>();

        /* An empty grouped sound sheet must not mask an older independent property track. */
        for (Map.Entry<TrackId, KeyframeChannel> entry : this.tracks.entrySet())
        {
            String key = entry.getKey().toKey();
            SoundKeyframeValue.Group group = SoundKeyframeValue.groupFromChannel(key);
            if (group != null)
            {
                sound.put(entry.getKey(), entry.getValue());
                AbstractSoundForm target = SoundKeyframeValue.formFromChannel(context.root(), key);
                if (target != null) SoundKeyframeValue.clearRuntime(target, group);
            }
        }

        for (Map.Entry<TrackId, KeyframeChannel> entry : this.tracks.entrySet())
        {
            if (sound.containsKey(entry.getKey())) continue;
            if (entry.getKey().is(TrackKind.BONE)) bones.put(entry.getKey(), entry.getValue());
            else if (FormControlKeys.isGlintControlChannel(entry.getKey().toKey())) glint.put(entry.getKey(), entry.getValue());
            else apply(context, entry.getKey(), entry.getValue(), tick, amount);
        }

        Set<String> reset = new HashSet<>();
        for (TrackId track : bones.keySet())
        {
            if (reset.add(track.formPath()) && !this.has(TrackId.property(track.formPath(), POSE_PROPERTY))
                && FormUtils.getForm(context.root(), track.formPath()) instanceof IPosedForm posed)
            {
                posed.getPose().setRuntimeValue(null);
            }
        }
        for (Map.Entry<TrackId, KeyframeChannel> entry : bones.entrySet())
            apply(context, entry.getKey(), entry.getValue(), tick, amount);

        for (Map.Entry<TrackId, KeyframeChannel> entry : glint.entrySet())
        {
            String path = entry.getKey().formPath();
            boolean posed = this.has(TrackId.property(path, POSE_PROPERTY))
                || bones.keySet().stream().anyMatch(track -> track.formPath().equals(path));
            if (!posed && FormUtils.getForm(context.root(), path) instanceof IPosedForm form)
                form.getPose().setRuntimeValue(null);
            apply(context, entry.getKey(), entry.getValue(), tick, amount);
        }
        for (Map.Entry<TrackId, KeyframeChannel> entry : sound.entrySet())
            if (!entry.getValue().isEmpty()) apply(context, entry.getKey(), entry.getValue(), tick, amount);
        notifyPoseChanges(context.root(), this.tracks.keySet());
    }

    /** Apply just one phase without resampling another replay's not-yet-updated anchor. */
    public void apply(TrackContext context, float tick, float blend, java.util.function.Predicate<TrackId> filter)
    {
        Set<TrackId> applied = new HashSet<>();
        for (Map.Entry<TrackId, KeyframeChannel> entry : this.tracks.entrySet())
        {
            if (filter.test(entry.getKey()))
            {
                apply(context, entry.getKey(), entry.getValue(), tick, blend);
                applied.add(entry.getKey());
            }
        }
        notifyPoseChanges(context.root(), applied);
    }

    private static void notifyPoseChanges(Form root, Iterable<TrackId> tracks)
    {
        if (root == null) return;
        Set<String> paths = new HashSet<>();
        for (TrackId track : tracks) paths.add(track.formPath());
        if (paths.isEmpty()) return;
        paths.add("");
        for (String path : paths)
        {
            Form form = FormUtils.getForm(root, path);
            if (form != null) form.bumpPoseVersion();
        }
    }

    private static void apply(TrackContext context, TrackId track, KeyframeChannel channel, float tick, float blend)
    {
        TrackBehaviour behaviour = TrackBehaviours.of(track);
        if (behaviour != null) behaviour.apply(context, track, channel, tick, blend);
    }

    public void resetProperties(Form form)
    {
        if (form == null) return;
        for (TrackId track : this.tracks.keySet())
        {
            TrackBehaviour behaviour = TrackBehaviours.of(track);
            if (behaviour != null) behaviour.reset(form, track);
        }
        notifyPoseChanges(form, this.tracks.keySet());
    }

    public void cleanUp()
    {
        Iterator<KeyframeChannel> it = this.properties.values().iterator();

        while (it.hasNext())
        {
            KeyframeChannel next = it.next();

            if (next.isEmpty())
            {
                it.remove();
                this.remove(next);
            }
        }
    }

    @Override
    public void fromData(BaseType data)
    {
        this.removeAll();
        this.properties.clear();
        this.foreignChannels.clear();
        this.typedTemplates.clear();
        this.foreignTracks.clear();

        if (!data.isMap())
        {
            return;
        }

        MapType map = data.asMap();

        if (map.has("tracks", BaseType.TYPE_LIST))
        {
            map = this.readTypedTracks(map.getList("tracks"));
        }

        for (String sourceKey : map.keys())
        {
            TrackId track = FormPropertyAliases.resolve(TrackId.parse(sourceKey));
            if (track == null) continue;
            String key = track.toKey();
            if (!key.equals(sourceKey) && map.has(key)) continue;
            MapType mapType = map.getMap(sourceKey);

            if (mapType.isEmpty())
            {
                continue;
            }

            KeyframeChannel property = new KeyframeChannel(key, null);

            property.fromData(mapType);

            /* Patch 1.1.1 changes to lighting property */
            if (key.endsWith("lighting") && property.getFactory() == KeyframeFactories.BOOLEAN)
            {
                KeyframeChannel newProperty = new KeyframeChannel(key, KeyframeFactories.FLOAT);

                for (Object keyframe : property.getKeyframes())
                {
                    Keyframe kf = (Keyframe) keyframe;
                    Boolean v = (Boolean) kf.getValue();

                    newProperty.insert(kf.getTick(), v ? 1F : 0F);
                }

                property = newProperty;
            }

            /* Convert transform to pose_transform for pose bone channels */
            if (property.getFactory() == KeyframeFactories.TRANSFORM && PerLimbService.isPoseBoneChannel(key))
            {
                KeyframeChannel newChannel = new KeyframeChannel(key, KeyframeFactories.POSE_TRANSFORM);

                for (Object o : property.getKeyframes())
                {
                    Keyframe kf = (Keyframe) o;
                    Object value = kf.getValue();
                    PoseTransform newValue = new PoseTransform();

                    if (value instanceof Transform)
                    {
                        newValue.copy((Transform) value);
                    }

                    Keyframe newKf = new Keyframe(kf.getId(), KeyframeFactories.POSE_TRANSFORM, kf.getTick(), newValue);

                    newKf.getInterpolation().copy(kf.getInterpolation());
                    newChannel.add(newKf);
                }

                newChannel.sort();
                property = newChannel;
            }

            if (property.getFactory() != null)
            {
                this.properties.put(key, property);
            }
            else
            {
                /* A channel whose value factory this build does not know (its
                 * addon is not loaded). Keep it verbatim and write it back out
                 * on save: dropping it made opening a film once without the
                 * addon erase its animation on the next save. */
                this.foreignChannels.put(key, (MapType) mapType.copy());
            }
        }
    }

    @Override
    public BaseType toData()
    {
        ListType records = new ListType();
        for (Map.Entry<TrackId, KeyframeChannel> entry : this.tracks.entrySet())
        {
            if (entry.getValue().isEmpty()) continue;
            records.add(this.trackData(entry.getKey(), entry.getValue().toData()));
        }
        for (Map.Entry<String, MapType> entry : this.foreignChannels.entrySet())
        {
            TrackId track = TrackId.parse(entry.getKey());
            if (track != null && !this.has(track)) records.add(this.trackData(track, entry.getValue()));
        }
        for (MapType foreign : this.foreignTracks) records.add(foreign.copy());
        MapType data = new MapType();
        data.put("tracks", records);
        return data;
    }

    private MapType trackData(TrackId track, BaseType channel)
    {
        MapType template = this.typedTemplates.get(track.toKey());
        MapType data = template == null ? new MapType() : (MapType) template.copy();
        data.putString("kind", track.kind().key);
        data.putString("form", track.formPath());
        data.putString("subject", track.subject());
        data.putString("prop", track.property());
        data.put("channel", channel.copy());
        return data;
    }

    private MapType readTypedTracks(ListType tracks)
    {
        MapType channels = new MapType();
        for (BaseType item : tracks)
        {
            if (!item.isMap()) continue;
            MapType record = item.asMap();
            TrackKind kind = TrackKind.byKey(record.getString("kind"));
            if (kind == null || !record.has("channel", BaseType.TYPE_MAP))
            {
                this.foreignTracks.add((MapType) record.copy());
                continue;
            }
            TrackId original = new TrackId(kind, record.getString("form"), record.getString("subject"), record.getString("prop"));
            TrackId address = FormPropertyAliases.resolve(original);
            String key = address.toKey();
            if (!address.equals(original) && hasCanonicalTrack(tracks, address)) continue;
            MapType template = (MapType) record.copy();
            template.putString("subject", address.subject());
            this.typedTemplates.put(key, template);
            channels.put(key, record.getMap("channel").copy());
        }
        return channels;
    }

    private static boolean hasCanonicalTrack(ListType records, TrackId track)
    {
        for (BaseType item : records)
        {
            if (!item.isMap()) continue;
            MapType data = item.asMap();
            if (track.kind().key.equals(data.getString("kind")) && track.formPath().equals(data.getString("form"))
                && track.subject().equals(data.getString("subject")) && track.property().equals(data.getString("prop")))
                return true;
        }
        return false;
    }

    @Override
    protected boolean canPersist(BaseValue value)
    {
        if (value instanceof KeyframeChannel<?> channel)
        {
            return !channel.isEmpty();
        }

        return super.canPersist(value);
    }
}
