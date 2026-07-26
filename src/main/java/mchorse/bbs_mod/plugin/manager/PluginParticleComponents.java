package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owner-aware particle component table used by the hot-plugin runtime. */
final class PluginParticleComponents
{
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    synchronized String className(String id)
    {
        Entry entry = this.entries.get(id);

        return entry == null ? null : entry.className();
    }

    synchronized void register(PluginOwner owner, String id, String className, ClassLoader classLoader)
    {
        this.entries.put(id, new Entry(owner, className, classLoader));
    }

    synchronized void unregister(PluginOwner owner, String id)
    {
        Entry entry = this.entries.get(id);

        if (entry != null && entry.owner().equals(owner))
        {
            this.entries.remove(id);
        }
    }

    synchronized Map<String, PluginParticleComponentClass> snapshot()
    {
        Map<String, PluginParticleComponentClass> snapshot = new LinkedHashMap<>();

        for (Map.Entry<String, Entry> entry : this.entries.entrySet())
        {
            snapshot.put(entry.getKey(), new PluginParticleComponentClass(entry.getValue().className(), entry.getValue().classLoader()));
        }

        return snapshot;
    }

    /** {@code classLoader} is the registering generation's classloader; never held onto beyond {@link #unregister}. */
    private record Entry(PluginOwner owner, String className, ClassLoader classLoader)
    {
        private Entry
        {
            owner = Objects.requireNonNull(owner, "owner");
            className = Objects.requireNonNull(className, "className");
        }
    }
}
