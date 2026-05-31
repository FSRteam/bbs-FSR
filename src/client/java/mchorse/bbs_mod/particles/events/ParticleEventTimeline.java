package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ParticleEventTimeline
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

        if (!data.isMap())
        {
            this.raw = data.copy();

            return;
        }

        for (Map.Entry<String, BaseType> entry : data.asMap())
        {
            Entry timelineEntry = new Entry(entry.getKey());

            timelineEntry.events.fromData(entry.getValue());
            this.entries.add(timelineEntry);
        }
    }

    public Entry add(String key)
    {
        Entry entry = new Entry(key);

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

        MapType map = new MapType(false);

        for (Entry entry : this.entries)
        {
            BaseType eventData = entry.events.toData();

            if (eventData != null && entry.key != null && !entry.key.trim().isEmpty())
            {
                map.put(entry.key.trim(), eventData);
            }
        }

        return map.isEmpty() ? null : map;
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

    public List<Entry> sortedEntries()
    {
        List<Entry> sorted = new ArrayList<>(this.entries);

        sorted.sort(Comparator.comparingDouble(Entry::getKeyValue));

        return sorted;
    }

    public static class Entry
    {
        public String key;
        public final ParticleEventTriggerList events = new ParticleEventTriggerList();

        public Entry(String key)
        {
            this.key = key;
        }

        public double getKeyValue()
        {
            try
            {
                return Double.parseDouble(this.key);
            }
            catch (Exception e)
            {
                return 0;
            }
        }

        public void setKey(double key)
        {
            this.key = ParticleEventTimeline.format(key);
        }
    }

    static String format(double number)
    {
        if (number == (long) number)
        {
            return Long.toString((long) number);
        }

        return Double.toString(number);
    }
}
