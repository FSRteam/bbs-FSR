package mchorse.bbs_mod.resources.packs;

import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class ExternalAssetsSourcePack implements ISourcePack
{
    public final String source;
    public final File folder;

    private boolean providesFiles;

    public static void getLinksFromPathRecursively(File folder, Collection<Link> links, Link link, String prefix, int i)
    {
        if (folder == null || links == null || link == null)
        {
            return;
        }

        try
        {
            Path root = resolveOwnershipPath(folder.toPath());

            if (root == null || !Files.isDirectory(root))
            {
                return;
            }

            getLinksFromPathRecursively(root, root.toFile(), links, link, prefix, i, new HashSet<>());
        }
        catch (IOException | SecurityException e)
        {}
    }

    private static void getLinksFromPathRecursively(
        Path root,
        File folder,
        Collection<Link> links,
        Link link,
        String prefix,
        int i,
        Set<Path> visited
    )
    {
        File containedFolder = getContainedFile(root, folder.toPath());

        if (containedFolder == null
            || !containedFolder.isDirectory()
            || !visited.add(containedFolder.toPath()))
        {
            return;
        }

        i -= 1;

        File[] files = containedFolder.listFiles();

        if (files == null)
        {
            return;
        }

        for (File file : files)
        {
            File contained = getContainedFile(root, file.toPath());

            if (contained == null)
            {
                continue;
            }

            String path = StringUtils.combinePaths(prefix, file.getName());
            boolean directory = contained.isDirectory();

            if (directory && i > 0)
            {
                getLinksFromPathRecursively(root, contained, links, link, path, i, visited);
            }

            links.add(new Link(link.source, path + (directory ? "/" : "")));
        }
    }

    public ExternalAssetsSourcePack(String source, File folder)
    {
        this.source = source;
        this.folder = folder;
    }

    public File getFolder()
    {
        return folder;
    }

    public ExternalAssetsSourcePack providesFiles()
    {
        this.providesFiles = true;

        return this;
    }

    @Override
    public String getPrefix()
    {
        return this.source;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        File file = this.getFileInternal(link);

        return file != null && file.exists();
    }

    @Override
    public InputStream getAsset(Link link) throws IOException
    {
        File file = this.getFileInternal(link);

        if (file == null)
        {
            throw new FileNotFoundException("Asset is outside source pack root: " + link);
        }

        return new FileInputStream(file);
    }

    @Override
    public File getFile(Link link)
    {
        return this.providesFiles ? this.getFileInternal(link) : null;
    }

    @Override
    public Link getLink(File file)
    {
        if (file == null || this.folder == null)
        {
            return null;
        }

        Path rootPath;
        Path filePath;

        try
        {
            rootPath = this.folder.toPath().toRealPath();
            filePath = resolveOwnershipPath(file.toPath());
        }
        catch (IOException | SecurityException e)
        {
            return null;
        }

        if (filePath == null || !filePath.startsWith(rootPath))
        {
            return null;
        }

        String path = rootPath.relativize(filePath).toString();

        return new Link(this.getPrefix(), path.replace(File.separatorChar, '/'));
    }

    private static Path resolveOwnershipPath(Path path) throws IOException
    {
        Path absolute = path.toAbsolutePath();

        try
        {
            return absolute.toRealPath();
        }
        catch (IOException e)
        {
            /* Only a suffix proven to be absent may fall back to its nearest real ancestor. */
        }

        Deque<Path> missing = new ArrayDeque<>();
        Path ancestor = absolute;

        while (ancestor != null && Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS))
        {
            Path name = ancestor.getFileName();

            if (name == null)
            {
                return null;
            }

            missing.addFirst(name);
            ancestor = ancestor.getParent();
        }

        if (missing.isEmpty() || ancestor == null || !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS))
        {
            return null;
        }

        Path resolved = ancestor.toRealPath();

        for (Path segment : missing)
        {
            resolved = resolved.resolve(segment);
        }

        return resolved.normalize();
    }

    private File getFileInternal(Link link)
    {
        if (this.folder == null || link == null || !Objects.equals(this.source, link.source))
        {
            return null;
        }

        Path relative = getRelativeLinkPath(link.path);

        if (relative == null)
        {
            return null;
        }

        try
        {
            Path root = this.folder.toPath().toRealPath();

            return getContainedFile(root, root.resolve(relative));
        }
        catch (IOException | SecurityException e)
        {
            return null;
        }
    }

    private static Path getRelativeLinkPath(String value)
    {
        if (value == null)
        {
            return null;
        }

        String path = value.replace('\\', '/');

        if (path.isEmpty())
        {
            return Path.of("");
        }

        try
        {
            Path relative = Path.of(path);

            if (relative.isAbsolute() || relative.getRoot() != null)
            {
                return null;
            }

            for (Path component : relative)
            {
                String name = component.toString();

                if (name.isEmpty() || name.equals(".") || name.equals(".."))
                {
                    return null;
                }
            }

            return relative;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static File getContainedFile(Path root, Path candidate)
    {
        try
        {
            Path resolved = resolveOwnershipPath(candidate);

            return resolved != null && resolved.startsWith(root) ? resolved.toFile() : null;
        }
        catch (IOException | SecurityException e)
        {
            return null;
        }
    }

    @Override
    public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive)
    {
        File folder = this.getFileInternal(link);

        if (folder != null && folder.isDirectory())
        {
            String path = link.path;

            if (path.endsWith("/"))
            {
                path = path.substring(0, path.length() - 1);
            }

            try
            {
                Path root = this.folder.toPath().toRealPath();

                getLinksFromPathRecursively(root, folder, links, link, path, recursive ? 9999 : 1, new HashSet<>());
            }
            catch (IOException | SecurityException e)
            {}
        }
    }
}
