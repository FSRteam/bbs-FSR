package mchorse.bbs_mod.data;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.util.thread.SidedThreadGroups;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Registry context shared by vanilla codecs, selected for the side that owns their data. */
public class GameRegistries
{
    public interface Source
    {
        HolderLookup.Provider lookup();

        boolean isOwnThread();
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    /* RegistryOps can retain its provider, so both the keys and cached ops must be weak. */
    private static final Map<HolderLookup.Provider, WeakReference<DynamicOps<Tag>>> OPS = new WeakHashMap<>();
    private static volatile Supplier<HolderLookup.Provider> clientRegistryAccess;

    private static final Source CLIENT_OVERRIDE = new Source()
    {
        @Override
        public HolderLookup.Provider lookup()
        {
            Supplier<HolderLookup.Provider> provider = clientRegistryAccess;

            return provider == null ? null : provider.get();
        }

        @Override
        public boolean isOwnThread()
        {
            return !isServerThread();
        }
    };

    public static void addSource(Source source)
    {
        if (source != null)
        {
            SOURCES.add(source);
        }
    }

    public static void addPreferredSource(Source source)
    {
        if (source != null)
        {
            SOURCES.add(0, source);
        }
    }

    /** Compatibility injection; replacing or clearing it never retains an earlier provider. */
    public static void setClientRegistryAccess(Supplier<HolderLookup.Provider> provider)
    {
        clientRegistryAccess = provider;
        SOURCES.remove(CLIENT_OVERRIDE);

        if (provider != null)
        {
            addPreferredSource(CLIENT_OVERRIDE);
        }
    }

    /** NeoForge server workers can live in descendants of the main server thread group. */
    public static boolean isServerThread()
    {
        for (ThreadGroup group = Thread.currentThread().getThreadGroup(); group != null; group = group.getParent())
        {
            if (group == SidedThreadGroups.SERVER)
            {
                return true;
            }
        }

        return false;
    }

    /** An owning side wins; otherwise the first currently available source is the fallback. */
    public static HolderLookup.Provider lookup()
    {
        HolderLookup.Provider fallback = null;

        for (Source source : SOURCES)
        {
            HolderLookup.Provider provider = source.lookup();

            if (provider == null)
            {
                continue;
            }

            if (source.isOwnThread())
            {
                return provider;
            }

            if (fallback == null)
            {
                fallback = provider;
            }
        }

        return fallback;
    }

    public static DynamicOps<Tag> nbtOps()
    {
        return nbtOps(lookup());
    }

    /** Explicit providers use the same per-registry cache as thread-selected codecs. */
    public static DynamicOps<Tag> nbtOps(HolderLookup.Provider provider)
    {
        if (provider == null)
        {
            return NbtOps.INSTANCE;
        }

        synchronized (OPS)
        {
            WeakReference<DynamicOps<Tag>> cached = OPS.get(provider);
            DynamicOps<Tag> ops = cached == null ? null : cached.get();

            if (ops == null)
            {
                ops = provider.createSerializationContext(NbtOps.INSTANCE);
                OPS.put(provider, new WeakReference<>(ops));
            }

            return ops;
        }
    }
}
