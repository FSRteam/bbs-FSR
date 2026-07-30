package mchorse.bbs_mod.utils.clips;

import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.factory.IFactory;
import mchorse.bbs_mod.plugin.manager.PluginStructuralInstanceTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class Clips extends ValueGroup
{
    private List<Clip> clips = new ArrayList<>();
    private IFactory<Clip, ClipFactoryData> factory;

    public Clips(String id, IFactory<Clip, ClipFactoryData> factory)
    {
        super(id);

        this.factory = factory;
        PluginStructuralInstanceTracker.track(this);
    }

    public IFactory<Clip, ClipFactoryData> getFactory()
    {
        return this.factory;
    }

    public int findFreeLayer(Clip clip)
    {
        int layer = clip.layer.get();

        main: while (true)
        {
            for (Clip newClip : this.clips)
            {
                float a1 = clip.tick.get();
                float a2 = a1 + clip.duration.get();
                float b1 = newClip.tick.get();
                float b2 = b1 + newClip.duration.get();

                if (layer == newClip.layer.get() && MathUtils.isInside(a1, a2, b1, b2))
                {
                    layer += 1;

                    continue main;
                }
            }

            break;
        }

        return layer;
    }

    public void sortLayers()
    {
        for (Clip clip : this.clips)
        {
            clip.layer.set(0);
        }

        for (Clip clip : this.clips)
        {
            for (Clip otherClip : this.clips)
            {
                if (clip == otherClip)
                {
                    continue;
                }

                boolean sameLayer = clip.layer.get() == otherClip.layer.get();
                boolean intersects = MathUtils.isInside(clip.tick.get(), clip.tick.get() + clip.duration.get(), otherClip.tick.get(), otherClip.tick.get() + otherClip.duration.get());

                if (sameLayer && intersects)
                {
                    otherClip.layer.set(otherClip.layer.get() + 1);
                }
            }
        }
    }

    public int getTopLayer()
    {
        int layer = 0;

        for (Clip clip : this.clips)
        {
            layer = Math.max(layer, clip.layer.get());
        }

        return layer;
    }

    /**
     * Calculate total duration of this camera work.
     */
    public int calculateDuration()
    {
        int max = 0;

        for (Clip clip : this.clips)
        {
            max = Math.max(max, clip.tick.get() + clip.duration.get());
        }

        return max;
    }

    public Clip get(int index)
    {
        return index >= 0 && index < this.clips.size() ? this.clips.get(index) : null;
    }

    public int size()
    {
        return this.clips.size();
    }

    public Clip getClipAt(int tick, int layer)
    {
        for (Clip clip : this.clips)
        {
            if (clip.isInside(tick) && clip.layer.get() == layer)
            {
                return clip;
            }
        }

        return null;
    }

    public <T extends Clip> List<T> getClips(Class<T> clazz)
    {
        List<T> clips = new ArrayList<>();

        for (Clip clip : this.clips)
        {
            if (clazz.isAssignableFrom(clip.getClass()))
            {
                clips.add(clazz.cast(clip));
            }
        }

        return clips;
    }

    public List<Clip> getClips(int tick)
    {
        return this.getClips(tick, Integer.MAX_VALUE);
    }

    public List<Clip> getClips(int tick, int maxLayer)
    {
        List<Clip> clipList = new ArrayList<>();

        for (Clip clip : this.clips)
        {
            boolean isGlobal = clip.isGlobal() && maxLayer == Integer.MAX_VALUE;

            if ((clip.isInside(tick) || isGlobal) && clip.layer.get() < maxLayer)
            {
                clipList.add(clip);
            }
        }

        clipList.sort(Comparator.comparingInt((a) -> a.layer.get()));

        return clipList;
    }

    /**
     * Get index of a given clip.
     *
     * @return index of a clip in the thing
     */
    public int getIndex(Clip clip)
    {
        return this.clips.indexOf(clip);
    }

    public void addClip(Clip clip)
    {
        this.preNotify();

        this.clips.add(clip);
        this.sync();

        this.postNotify();
    }

    public void remove(Clip clip)
    {
        this.preNotify();

        this.clips.remove(clip);
        this.sync();

        this.postNotify();
    }

    public void copyOver(Clips clips, int tick)
    {
        this.preNotify();

        this.clips.removeIf((next) -> next.tick.get() >= tick);

        for (Clip clip : clips.clips)
        {
            Clip copy = clip.copy();

            copy.tick.set(tick + copy.tick.get());
            this.addClip(copy);
        }

        this.sortLayers();
        this.sync();
        this.postNotify();
    }

    /* New value methods */

    public void sync()
    {
        this.removeAll();

        for (int i = 0, c = this.clips.size(); i < c; i++)
        {
            Clip clip = this.clips.get(i);

            clip.setId(String.valueOf(i));
            this.add(clip);
        }
    }

    public List<Clip> get()
    {
        this.recoverMissing();
        return Collections.unmodifiableList(this.clips);
    }

    public Snapshot snapshotStructuralTypes(Set<Class<?>> types)
    {
        if (types == null || types.isEmpty())
        {
            return Snapshot.empty(this);
        }

        java.util.Map<Integer, MapType> data = new java.util.LinkedHashMap<>();

        for (int i = 0; i < this.clips.size(); i += 1)
        {
            Clip clip = this.clips.get(i);

            if (types.contains(clip.getClass()))
            {
                MapType map = this.factory.toData(clip);
                data.put(i, (MapType) map.copy());
                this.clips.set(i, new MissingClip(map));
            }
        }

        if (!data.isEmpty())
        {
            this.sync();
        }

        return new Snapshot(this, data);
    }

    private void recoverMissing()
    {
        boolean changed = false;

        for (int i = 0; i < this.clips.size(); i += 1)
        {
            Clip current = this.clips.get(i);

            if (!(current instanceof MissingClip missing))
            {
                continue;
            }

            try
            {
                Clip recovered = this.factory.fromData(missing.sourceData());

                if (recovered != null)
                {
                    this.clips.set(i, recovered);
                    changed = true;
                }
            }
            catch (Exception ignored)
            {}
        }

        if (changed)
        {
            this.sync();
        }
    }

    public int findNextTick(int tick)
    {
        int output = Integer.MAX_VALUE;

        for (Clip clip : this.clips)
        {
            int left = clip.tick.get() - tick;
            int right = left + clip.duration.get();

            int a = Math.max(left, 0);
            int b = Math.max(right, 0);

            if (a > 0)
            {
                output = Math.min(output, a);
            }
            else if (b > 0)
            {
                output = Math.min(output, b);
            }
        }

        return tick + (output != Integer.MAX_VALUE ? output : 0);
    }

    public int findPreviousTick(int tick)
    {
        int output = Integer.MIN_VALUE;

        for (Clip clip : this.clips)
        {
            int left = clip.tick.get() - tick;
            int right = left + clip.duration.get();

            int a = Math.min(left, -0);
            int b = Math.min(right, -0);

            if (b < -0)
            {
                output = Math.max(output, b);
            }
            else if (a < -0)
            {
                output = Math.max(output, a);
            }
        }

        return tick + (output != Integer.MIN_VALUE ? output : 0);
    }

    public void shift(float tick)
    {
        for (Clip clip : this.clips)
        {
            clip.tick.set(Math.round(clip.tick.get() + tick));
        }
    }

    public void shift(double dx, double dy, double dz)
    {
        for (Clip clip : this.clips)
        {
            clip.shift(dx, dy, dz);
        }
    }

    /* Value implementation */

    @Override
    public BaseType toData()
    {
        ListType list = new ListType();

        for (Clip clip : this.clips)
        {
            list.add(clip instanceof MissingClip missing ? missing.sourceData() : this.factory.toData(clip));
        }

        return list;
    }

    @Override
    public void fromData(BaseType base)
    {
        this.clips.clear();

        for (BaseType type : base.asList())
        {
            if (!type.isMap())
            {
                continue;
            }

            try
            {
                Clip clip = this.factory.fromData(type.asMap());

                if (clip != null)
                {
                    this.clips.add(clip);
                }
            }
            catch (Exception e)
            {
                MapType map = type.asMap();

                if (map.getString("type").equalsIgnoreCase("bbs:circular"))
                {
                    KeyframeClip clip = new KeyframeClip();
                    Point point = new Point(0D, 0D, 0D);

                    point.fromData(map.getMap("start"));
                    clip.fromData(map);
                    clip.x.insert(0F, point.x);
                    clip.y.insert(0F, point.y);
                    clip.z.insert(0F, point.z);
                    clip.yaw.insert(0F, (double) map.getFloat("start"));
                    clip.yaw.insert(clip.duration.get(), (double) map.getFloat("start") + (double) map.getFloat("circles"));
                    clip.pitch.insert(0F, (double) map.getFloat("pitch"));
                    clip.roll.insert(0F, 0D);
                    clip.fov.insert(0F, (double) map.getFloat("fov"));
                    clip.distance.insert(0F, (double) map.getFloat("distance"));

                    this.clips.add(clip);
                }
                else
                {
                    this.clips.add(new MissingClip(map));
                }
            }
        }

        this.sync();
    }

    public static final class Snapshot
    {
        private final Clips holder;
        private final Map<Integer, MapType> data;

        private Snapshot(Clips holder, Map<Integer, MapType> data)
        {
            this.holder = holder;
            this.data = data;
        }

        private static Snapshot empty(Clips holder)
        {
            return new Snapshot(holder, Map.of());
        }

        public boolean isEmpty()
        {
            return this.data.isEmpty();
        }

        public int rebuild(BiConsumer<String, Throwable> failure)
        {
            int failed = 0;

            for (Map.Entry<Integer, MapType> entry : this.data.entrySet())
            {
                int index = entry.getKey();
                MapType source = entry.getValue();
                String typeKey = this.holder.factory.getTypeKey();

                if (!this.holder.factory.hasType(Link.create(source.getString(typeKey))))
                {
                    /* Type no longer registered (plugin generation retired it) - this is
                     * the expected degradation path, not a rebuild failure, so the
                     * placeholder stays without raising a diagnostic. */
                    this.holder.clips.set(index, new MissingClip(source));

                    continue;
                }

                try
                {
                    Clip clip = this.holder.factory.fromData(source);
                    this.holder.clips.set(index, clip == null ? new MissingClip(source) : clip);
                }
                catch (Throwable error)
                {
                    failed += 1;
                    this.holder.clips.set(index, new MissingClip(source));
                    failure.accept(source.getString(typeKey, "<unknown-clip>"), error);
                }
            }

            if (!this.data.isEmpty())
            {
                this.holder.sync();
            }

            return failed;
        }
    }
}
