package mchorse.bbs_mod.plugin.host;

import mchorse.bbs_mod.plugin.content.PluginContentEntry;
import mchorse.bbs_mod.plugin.content.PluginContentSnapshot;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Supplier;

/** Host-owned source-pack proxy which resolves the currently active content snapshot. */
public final class PluginRoutingSourcePack implements ISourcePack
{
    private static final String ARCHIVE_PREFIX = "assets/";

    private final Supplier<PluginContentSnapshot> snapshot;

    public PluginRoutingSourcePack(Supplier<PluginContentSnapshot> snapshot)
    {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public String getPrefix()
    {
        return Link.ASSETS;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        return this.entry(link) != null;
    }

    @Override
    public InputStream getAsset(Link link) throws FileNotFoundException
    {
        PluginContentEntry entry = this.entry(link);

        if (entry == null)
        {
            throw new FileNotFoundException("Hot plugin asset is not active: " + link);
        }

        return entry.openStream();
    }

    @Override
    public File getFile(Link link)
    {
        return null;
    }

    @Override
    public Link getLink(File file)
    {
        return null;
    }

    @Override
    public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive)
    {
        if (links == null || link == null || !Link.ASSETS.equals(link.source))
        {
            return;
        }

        PluginContentSnapshot current = this.snapshot.get();

        if (current == null)
        {
            return;
        }

        String requested = clean(link.path);
        String prefix = ARCHIVE_PREFIX + requested;

        if (!prefix.endsWith("/") && !requested.isEmpty())
        {
            prefix += "/";
        }

        for (PluginContentEntry entry : current.entries().values())
        {
            if (!entry.path().startsWith(prefix))
            {
                continue;
            }

            String assetPath = entry.path().substring(ARCHIVE_PREFIX.length());
            String remainder = requested.isEmpty() ? assetPath : assetPath.substring(Math.min(assetPath.length(), requested.length() + 1));

            if (recursive || remainder.indexOf('/') < 0)
            {
                links.add(Link.assets(assetPath));
            }
        }
    }

    private PluginContentEntry entry(Link link)
    {
        if (link == null || !Link.ASSETS.equals(link.source))
        {
            return null;
        }

        PluginContentSnapshot current = this.snapshot.get();

        return current == null ? null : current.entry(ARCHIVE_PREFIX + clean(link.path)).orElse(null);
    }

    private static String clean(String path)
    {
        if (path == null || path.isEmpty())
        {
            return "";
        }

        String cleaned = path.replace('\\', '/');

        while (cleaned.startsWith("/"))
        {
            cleaned = cleaned.substring(1);
        }

        return cleaned;
    }
}
