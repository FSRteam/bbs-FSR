package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParticleLoopingDistanceEvents
{
    public final List<Entry> entries = new ArrayList<>();

    /* Parsed Entry instances and raw BaseType siblings in their original order. */
    private final List<Object> elementOrder = new ArrayList<>();
    private BaseType raw;
    private boolean edited;
    private boolean hasRawSiblings;

    public void fromData(BaseType data)
    {
        this.entries.clear();
        this.elementOrder.clear();
        this.raw = null;
        this.edited = false;
        this.hasRawSiblings = false;

        if (data == null)
        {
            return;
        }

        if (!data.isList())
        {
            this.raw = data.copy();

            return;
        }

        for (BaseType element : data.asList())
        {
            if (!element.isMap())
            {
                this.elementOrder.add(element.copy());
                this.hasRawSiblings = true;

                continue;
            }

            MapType map = element.asMap();

            if (!map.has("effects"))
            {
                this.elementOrder.add(element.copy());
                this.hasRawSiblings = true;

                continue;
            }

            Entry entry = new Entry();

            if (map.has("distance"))
            {
                entry.setDistanceData(map.get("distance"));
            }

            if (map.has("effects"))
            {
                entry.effects.fromData(map.get("effects"));
            }

            for (Map.Entry<String, BaseType> mapEntry : map)
            {
                String key = mapEntry.getKey();

                if (!key.equals("distance") && !key.equals("effects"))
                {
                    entry.extra.put(key, mapEntry.getValue().copy());
                }
            }

            this.entries.add(entry);
            this.elementOrder.add(entry);
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

        for (Object element : this.elementOrder)
        {
            if (element instanceof Entry entry)
            {
                if (this.entries.contains(entry))
                {
                    this.addEntry(list, entry);
                }
            }
            else
            {
                list.add(((BaseType) element).copy());
            }
        }

        for (Entry entry : this.entries)
        {
            if (!this.elementOrder.contains(entry))
            {
                this.addEntry(list, entry);
            }
        }

        return list.isEmpty() ? null : list;
    }

    private void addEntry(ListType list, Entry entry)
    {
        BaseType effects = entry.effects.toData();

        if (effects == null)
        {
            return;
        }

        MapType map = new MapType(false);

        for (Map.Entry<String, BaseType> extra : entry.extra.entrySet())
        {
            map.put(extra.getKey(), extra.getValue().copy());
        }

        map.put("distance", entry.toDistanceData());
        map.put("effects", effects);
        list.add(map);
    }

    public boolean isEmpty()
    {
        return this.entries.isEmpty() && this.raw == null && !this.hasRawSiblings;
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
        private final Map<String, BaseType> extra = new LinkedHashMap<>();
        private BaseType rawDistance;
        private String parsedDistance;
        private double distanceValue;

        public double getDistance()
        {
            if (this.parsedDistance == null ? this.distance == null : this.parsedDistance.equals(this.distance))
            {
                return this.distanceValue;
            }

            try
            {
                this.distanceValue = Double.parseDouble(this.distance);
            }
            catch (Exception e)
            {
                this.distanceValue = 0;
            }

            this.parsedDistance = this.distance;

            return this.distanceValue;
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
