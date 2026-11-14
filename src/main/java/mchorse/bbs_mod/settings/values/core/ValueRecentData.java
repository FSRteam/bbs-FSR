package mchorse.bbs_mod.settings.values.core;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Persisted, newest-first document history grouped by content type. */
public class ValueRecentData extends BaseValue
{
    public static class Entry
    {
        public final String id;
        public final long time;
        public Entry(String id, long time) { this.id = id; this.time = time; }
    }

    private final Map<String, List<Entry>> recent = new LinkedHashMap<>();
    private int limit = 20;

    public ValueRecentData(String id) { super(id); }
    public ValueRecentData limit(int limit) { this.limit = Math.max(0, limit); return this; }
    public List<Entry> get(String type)
    {
        List<Entry> entries = this.recent.get(type);
        return entries == null ? Collections.emptyList() : Collections.unmodifiableList(entries);
    }

    public void touch(String type, String id)
    {
        if (type == null || id == null) return;
        List<Entry> entries = this.recent.computeIfAbsent(type, key -> new ArrayList<>());
        this.preNotify();
        entries.removeIf(entry -> entry.id.equals(id));
        entries.add(0, new Entry(id, System.currentTimeMillis()));
        while (this.limit > 0 && entries.size() > this.limit) entries.remove(entries.size() - 1);
        this.postNotify();
    }

    public void forget(String type, String id) { this.rewrite(type, value -> value.equals(id) ? null : value); }
    public void forgetFolder(String type, String path)
    {
        String prefix = path.endsWith("/") ? path : path + "/";
        this.rewrite(type, value -> value.startsWith(prefix) ? null : value);
    }
    public void rename(String type, String from, String to) { this.rewrite(type, value -> value.equals(from) ? to : value); }
    public void renameFolder(String type, String fromPath, String name)
    {
        fromPath = normalizeFolder(fromPath);
        name = normalizeFolder(name);
        String oldPrefix = fromPath + "/";
        int slash = fromPath.lastIndexOf('/');
        String parent = slash >= 0 ? fromPath.substring(0, slash + 1) : "";
        String next = (name.contains("/") ? name : parent + name) + "/";
        this.rewrite(type, value -> value.startsWith(oldPrefix) ? next + value.substring(oldPrefix.length()) : value);
    }

    private static String normalizeFolder(String path)
    {
        path = path.trim();

        while (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    private void rewrite(String type, UnaryOperator<String> mapper)
    {
        List<Entry> entries = this.recent.get(type);
        if (entries == null) return;
        List<Entry> mapped = new ArrayList<>(entries.size());
        boolean changed = false;
        for (Entry entry : entries)
        {
            String id = mapper.apply(entry.id);
            if (id == null) changed = true;
            else { mapped.add(id.equals(entry.id) ? entry : new Entry(id, entry.time)); changed |= !id.equals(entry.id); }
        }
        if (changed) { this.preNotify(); entries.clear(); entries.addAll(mapped); this.postNotify(); }
    }

    @Override
    public BaseType toData()
    {
        MapType map = new MapType();
        for (Map.Entry<String, List<Entry>> group : this.recent.entrySet())
        {
            ListType list = new ListType();
            for (Entry entry : group.getValue()) { MapType value = new MapType(); value.putString("id", entry.id); value.putLong("time", entry.time); list.add(value); }
            map.put(group.getKey(), list);
        }
        return map;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.recent.clear();
        if (data == null || !data.isMap()) return;
        for (String type : data.asMap().keys())
        {
            BaseType raw = data.asMap().get(type);
            if (raw == null || !raw.isList()) continue;
            List<Entry> entries = new ArrayList<>();
            for (BaseType item : raw.asList()) if (item.isMap())
            {
                String id = item.asMap().getString("id", "");
                if (!id.isEmpty()) entries.add(new Entry(id, item.asMap().getLong("time", 0L)));
            }
            this.recent.put(type, entries);
        }
    }
}
