package mchorse.bbs_mod.resources;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class AssetProvider
{
    private final Map<String, List<ISourcePack>> sourcePacks = new ConcurrentHashMap<>();

    public void registerFirst(ISourcePack pack)
    {
        if (pack == null)
        {
            return;
        }

        this.sourcePacks.computeIfAbsent(pack.getPrefix(), (k) -> new CopyOnWriteArrayList<>()).add(0, pack);
    }

    public void register(ISourcePack pack)
    {
        if (pack == null)
        {
            return;
        }

        this.sourcePacks.computeIfAbsent(pack.getPrefix(), (k) -> new CopyOnWriteArrayList<>()).add(pack);
    }

    /**
     * Registers one source pack with an idempotent removal handle. The handle
     * removes only the exact instance it registered, which lets a hot plugin
     * replace its generation without disturbing another pack using the same
     * source prefix.
     */
    public AutoCloseable registerCloseable(ISourcePack pack)
    {
        return this.registerWithHandle(pack, false);
    }

    /** Registers a source pack at the front of its prefix with a close handle. */
    public AutoCloseable registerFirstCloseable(ISourcePack pack)
    {
        return this.registerWithHandle(pack, true);
    }

    private AutoCloseable registerWithHandle(ISourcePack pack, boolean first)
    {
        if (first)
        {
            this.registerFirst(pack);
        }
        else
        {
            this.register(pack);
        }

        if (pack == null)
        {
            return () -> {};
        }

        AtomicBoolean closed = new AtomicBoolean();

        return () ->
        {
            if (!closed.compareAndSet(false, true))
            {
                return;
            }

            List<ISourcePack> packs = this.sourcePacks.get(pack.getPrefix());

            if (packs != null)
            {
                packs.remove(pack);

                if (packs.isEmpty())
                {
                    this.sourcePacks.remove(pack.getPrefix(), packs);
                }
            }
        };
    }

    public Collection<String> getSourceKeys()
    {
        return this.sourcePacks.keySet();
    }

    private List<ISourcePack> getPacks(String source)
    {
        List<ISourcePack> sourcePacks = this.sourcePacks.get(source);

        return sourcePacks == null ? Collections.emptyList() : sourcePacks;
    }

    public InputStream getAsset(Link link) throws IOException
    {
        if (link == null)
        {
            throw new FileNotFoundException("Link is null!");
        }

        List<ISourcePack> packs = this.getPacks(link.source);

        for (ISourcePack pack : packs)
        {
            if (pack.hasAsset(link))
            {
                return pack.getAsset(link);
            }
        }

        throw new FileNotFoundException("Asset " + link + " couldn't be found!");
    }

    public File getFile(Link link)
    {
        if (link == null)
        {
            return null;
        }

        List<ISourcePack> packs = this.getPacks(link.source);

        for (ISourcePack pack : packs)
        {
            File file = pack.getFile(link);

            if (file != null)
            {
                return file;
            }
        }

        return null;
    }

    public Link getLink(File file)
    {
        for (List<ISourcePack> sourcePacks : this.sourcePacks.values())
        {
            for (ISourcePack sourcePack : sourcePacks)
            {
                Link link = sourcePack.getLink(file);

                if (link != null)
                {
                    return link;
                }
            }
        }

        return null;
    }

    public Collection<Link> getLinksFromPath(Link link)
    {
        return this.getLinksFromPath(link, true);
    }

    public Collection<Link> getLinksFromPath(Link link, boolean recursive)
    {
        Set<Link> links = new HashSet<>();
        List<ISourcePack> packs = this.getPacks(link.source);

        for (ISourcePack pack : packs)
        {
            pack.getLinksFromPath(links, link, recursive);
        }

        return links;
    }
}
