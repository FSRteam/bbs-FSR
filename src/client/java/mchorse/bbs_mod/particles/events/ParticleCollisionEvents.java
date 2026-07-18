package mchorse.bbs_mod.particles.events;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.DoubleType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ParticleCollisionEvents
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

        if (data.isString())
        {
            if (!data.asString().trim().isEmpty())
            {
                this.entries.add(new Entry(data.asString(), 0));
                this.raw = data.copy();
            }

            return;
        }

        if (data.isMap())
        {
            Entry entry = Entry.fromData(data.asMap());

            if (!entry.event.isEmpty())
            {
                this.entries.add(entry);
            }

            this.raw = data.copy();

            return;
        }

        if (!data.isList())
        {
            this.raw = data.copy();

            return;
        }

        for (BaseType element : data.asList())
        {
            if (element.isString() && !element.asString().trim().isEmpty())
            {
                this.entries.add(new Entry(element.asString(), 0));
                this.elementOrder.add(this.entries.get(this.entries.size() - 1));

                continue;
            }

            if (!element.isMap())
            {
                this.elementOrder.add(element.copy());
                this.hasRawSiblings = true;

                continue;
            }

            MapType map = element.asMap();
            Entry entry = map.has("event") && map.get("event").isString()
                ? Entry.fromData(map)
                : null;

            if (entry == null || entry.event.isEmpty())
            {
                this.elementOrder.add(element.copy());
                this.hasRawSiblings = true;
            }
            else
            {
                this.entries.add(entry);
                this.elementOrder.add(entry);
            }
        }

        if (!this.entries.isEmpty() || this.hasRawSiblings)
        {
            this.raw = data.copy();
        }
    }

    public Entry add(String event, double minSpeed)
    {
        Entry entry = new Entry(event, minSpeed);

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
        if (entry.event != null && !entry.event.trim().isEmpty())
        {
            list.add(entry.toData());
        }
    }

    public boolean isEmpty()
    {
        return this.entries.isEmpty() && this.raw == null && !this.hasRawSiblings;
    }

    public void replaceEvent(String oldId, String newId)
    {
        boolean changed = false;

        for (Entry entry : this.entries)
        {
            if (entry.event.equals(oldId))
            {
                entry.event = newId;
                changed = true;
            }
        }

        if (changed)
        {
            this.markEdited();
        }
    }

    public void markEdited()
    {
        this.edited = true;
        this.raw = null;
    }

    public static class Entry
    {
        public String event = "";
        public double minSpeed = 2;

        private final Map<String, BaseType> extra = new LinkedHashMap<>();
        private BaseType rawMinSpeed;

        public Entry()
        {}

        public Entry(String event, double minSpeed)
        {
            this.event = event == null ? "" : event.trim();
            this.setMinSpeed(minSpeed);
        }

        public static Entry fromData(MapType map)
        {
            Entry entry = new Entry();

            if (map.has("event"))
            {
                entry.event = map.getString("event").trim();
            }

            if (map.has("min_speed"))
            {
                entry.setMinSpeedData(map.get("min_speed"));
            }

            for (Map.Entry<String, BaseType> mapEntry : map)
            {
                String key = mapEntry.getKey();

                if (!key.equals("event") && !key.equals("min_speed"))
                {
                    entry.extra.put(key, mapEntry.getValue().copy());
                }
            }

            return entry;
        }

        public void setMinSpeed(double minSpeed)
        {
            this.minSpeed = Math.max(0, minSpeed);
            this.rawMinSpeed = null;
        }

        public void setMinSpeedData(BaseType data)
        {
            this.rawMinSpeed = data == null ? null : data.copy();

            if (data != null && data.isNumeric())
            {
                this.minSpeed = Math.max(0, data.asNumeric().doubleValue());
            }
        }

        public BaseType toData()
        {
            MapType map = new MapType(false);

            for (Map.Entry<String, BaseType> entry : this.extra.entrySet())
            {
                map.put(entry.getKey(), entry.getValue().copy());
            }

            map.putString("event", this.event.trim());
            map.put("min_speed", this.toMinSpeedData());

            return map;
        }

        private BaseType toMinSpeedData()
        {
            if (this.rawMinSpeed != null)
            {
                return this.rawMinSpeed.copy();
            }

            if (this.minSpeed == (int) this.minSpeed)
            {
                return new IntType((int) this.minSpeed);
            }

            return new DoubleType(this.minSpeed);
        }
    }
}
