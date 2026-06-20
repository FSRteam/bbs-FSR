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

    private final List<Entry> sortedEntries = new ArrayList<>();
    private BaseType raw;
    private boolean edited;
    private boolean sortedDirty = true;

    public void fromData(BaseType data)
    {
        this.entries.clear();
        this.sortedEntries.clear();
        this.raw = null;
        this.edited = false;
        this.sortedDirty = true;

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
        this.sortedDirty = true;
    }

    public List<Entry> sortedEntries()
    {
        return new ArrayList<>(this.runtimeSortedEntries());
    }

    public List<Entry> runtimeSortedEntries()
    {
        if (!this.sortedDirty && this.sortedEntries.size() == this.entries.size())
        {
            for (int i = 0; i < this.entries.size(); i++)
            {
                if (this.entries.get(i).isKeyChanged())
                {
                    this.sortedDirty = true;

                    break;
                }
            }
        }
        else
        {
            this.sortedDirty = true;
        }

        if (this.sortedDirty)
        {
            this.sortedEntries.clear();
            this.sortedEntries.addAll(this.entries);
            this.sortedEntries.sort(Comparator.comparingDouble(Entry::getKeyValue));
            this.sortedDirty = false;
        }

        return this.sortedEntries;
    }

    public static class Entry
    {
        public String key;
        public final ParticleEventTriggerList events = new ParticleEventTriggerList();
        private String parsedKey;
        private double keyValue;

        public Entry(String key)
        {
            this.key = key;
        }

        public double getKeyValue()
        {
            if (!this.isKeyChanged())
            {
                return this.keyValue;
            }

            try
            {
                this.keyValue = Double.parseDouble(this.key);
            }
            catch (Exception e)
            {
                this.keyValue = 0;
            }

            this.parsedKey = this.key;

            return this.keyValue;
        }

        private boolean isKeyChanged()
        {
            return this.parsedKey == null ? this.key != null : !this.parsedKey.equals(this.key);
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
