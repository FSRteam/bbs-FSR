package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;

import java.util.ArrayList;
import java.util.List;

public class ParticleLoopingDistanceEvents
{
    public final List<Entry> entries = new ArrayList<>();

    private BaseType raw;
    private boolean edited;

    public void fromData(BaseType data)
    {
        this.entries.clear();
        this.raw = null;
        this.edited = false;

        if (data == null)
        {
            return;
        }

        if (!data.isList())
        {
            this.raw = data.copy();

            return;
        }

        boolean supported = true;

        for (BaseType element : data.asList())
        {
            if (!element.isMap())
            {
                supported = false;

                continue;
            }

            MapType map = element.asMap();
            Entry entry = new Entry();

            if (map.has("distance"))
            {
                entry.setDistanceData(map.get("distance"));
            }

            if (map.has("effects"))
            {
                entry.effects.fromData(map.get("effects"));
            }

            this.entries.add(entry);
        }

        if (!supported)
        {
            this.raw = data.copy();
        }
    }

    public Entry add(double distance)
    {
        Entry entry = new Entry();

        entry.setDistance(distance);
        this.entries.add(entry);
        this.markEdited();

        return entry;
    }

    public BaseType toData()
    {
        if (this.raw != null && !this.edited)
        {
            return this.raw.copy();
        }

        ListType list = new ListType();

        for (Entry entry : this.entries)
        {
            BaseType effects = entry.effects.toData();

            if (effects == null)
            {
                continue;
            }

            MapType map = new MapType(false);

            map.put("distance", entry.toDistanceData());
            map.put("effects", effects);
            list.add(map);
        }

        return list.isEmpty() ? null : list;
    }

    public boolean isEmpty()
    {
        return this.entries.isEmpty() && this.raw == null;
    }

    public void markEdited()
    {
        this.edited = true;
        this.raw = null;
    }

    public static class Entry
    {
        public String distance = "1";
        public final ParticleEventTriggerList effects = new ParticleEventTriggerList();
        private BaseType rawDistance;

        public double getDistance()
        {
            try
            {
                return Double.parseDouble(this.distance);
            }
            catch (Exception e)
            {
                return 0;
            }
        }

        public void setDistance(double distance)
        {
            this.distance = ParticleEventTimeline.format(distance);
            this.rawDistance = null;
        }

        public void setDistance(String distance)
        {
            this.distance = distance == null ? "" : distance.trim();
            this.rawDistance = null;
        }

        public void setDistanceData(BaseType data)
        {
            this.rawDistance = data == null ? null : data.copy();

            if (data == null)
            {
                return;
            }

            if (data.isNumeric())
            {
                this.distance = ParticleEventTimeline.format(data.asNumeric().doubleValue());
            }
            else if (data.isString())
            {
                this.distance = data.asString();
            }
        }

        public BaseType toDistanceData()
        {
            if (this.rawDistance != null)
            {
                return this.rawDistance.copy();
            }

            String value = this.distance == null ? "" : this.distance.trim();

            if (value.isEmpty())
            {
                return new StringType("");
            }

            try
            {
                double number = Double.parseDouble(value);

                if (!value.contains(".") && !value.contains("e") && !value.contains("E") && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE)
                {
                    return new IntType((int) number);
                }

                return new DoubleType(number);
            }
            catch (NumberFormatException e)
            {}

            return new StringType(value);
        }
    }
}
