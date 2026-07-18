package mchorse.bbs_mod.utils.manager;

import mchorse.bbs_mod.settings.values.core.ValueGroup;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Folder based manager
 */
public abstract class FolderManager <T extends ValueGroup> implements IManager<T>
{
    protected Supplier<File> folder;

    public FolderManager(Supplier<File> folder)
    {
        this.folder = folder;
    }

    public File getFolder()
    {
        File file = this.folder == null ? null : this.folder.get();

        if (file == null)
        {
            return null;
        }

        if (!file.exists())
        {
            file.mkdirs();
        }

        return file;
    }

    @Override
    public boolean exists(String name)
    {
        File file = this.getFile(name);

        return file != null && file.exists();
    }

    @Override
    public boolean rename(String from, String to)
    {
        File file = this.getFile(from);
        File destination = this.getFile(to);

        if (file != null && destination != null && file.exists())
        {
            if (file.renameTo(destination))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean delete(String name)
    {
        File file = this.getFile(name);

        return file != null && file.delete();
    }

    /**
     * Add a folder.
     */
    public boolean addFolder(String path)
    {
        File folder = this.getFolder(path);

        if (folder == null || folder.exists())
        {
            return false;
        }

        return folder.mkdirs();
    }

    /**
     * Rename given folder to another name.
     */
    public boolean renameFolder(String from, String to)
    {
        File folder = this.getFolder(from);
        File destination = this.getFolder(to);

        if (folder != null && destination != null && folder.isDirectory())
        {
            if (folder.renameTo(destination))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Delete given folder. It only works if the folder is empty.
     */
    public boolean deleteFolder(String path)
    {
        File folder = this.getFolder(path);

        if (folder != null && folder.isDirectory())
        {
            if (folder.delete())
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public Collection<String> getKeys()
    {
        Set<String> set = new HashSet<>();

        if (this.folder == null)
        {
            return set;
        }

        File root = this.getCanonicalRoot();

        if (root != null)
        {
            this.recursiveFind(set, root, "", root.toPath(), new HashSet<>());
        }

        return set;
    }

    private void recursiveFind(Set<String> set, File folder, String prefix, Path root, Set<Path> visited)
    {
        File canonicalFolder = this.getContainedFile(root, folder, true);

        if (canonicalFolder == null || !canonicalFolder.isDirectory() || !visited.add(canonicalFolder.toPath()))
        {
            return;
        }

        File[] entries = canonicalFolder.listFiles();

        if (entries == null)
        {
            return;
        }

        for (File file : entries)
        {
            if (this.getContainedFile(root, file, false) == null)
            {
                continue;
            }

            String name = file.getName();

            if (file.isFile() && this.isData(file))
            {
                set.add(prefix + name.substring(0, name.lastIndexOf(".")));
            }
            else if (file.isDirectory() && !file.getName().startsWith("_"))
            {
                File[] files = file.listFiles();

                if (files == null || files.length == 0)
                {
                    set.add(prefix + name + "/");
                }
                else
                {
                    this.recursiveFind(set, file, prefix + name + "/", root, visited);
                }
            }
        }
    }

    protected boolean isData(File file)
    {
        return file.getName().endsWith(this.getExtension());
    }

    public File getFile(String name)
    {
        return this.resolveContained(name, true);
    }

    public File getFolder(String path)
    {
        return this.resolveContained(path, false);
    }

    private File resolveContained(String value, boolean file)
    {
        Path relative = this.getRelativePath(value, file);

        if (relative == null)
        {
            return null;
        }

        File root = this.getCanonicalRoot();

        if (root == null)
        {
            return null;
        }

        if (file)
        {
            String fileName = relative.getFileName().toString() + this.getExtension();

            relative = relative.resolveSibling(fileName);
        }

        return this.getContainedFile(root.toPath(), root.toPath().resolve(relative).normalize().toFile(), false);
    }

    private Path getRelativePath(String value, boolean file)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        String path = value.replace('\\', '/');

        if (path.startsWith("/") || this.hasWindowsDrivePrefix(path) || (file && path.endsWith("/")))
        {
            return null;
        }

        try
        {
            Path relative = Path.of(path);

            if (relative.isAbsolute() || relative.getRoot() != null || relative.getNameCount() == 0)
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
        catch (InvalidPathException e)
        {
            return null;
        }
    }

    private boolean hasWindowsDrivePrefix(String path)
    {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }

    private File getCanonicalRoot()
    {
        try
        {
            File root = this.getFolder();

            return root == null ? null : root.getCanonicalFile();
        }
        catch (IOException | SecurityException e)
        {
            return null;
        }
    }

    private File getContainedFile(Path root, File candidate, boolean allowRoot)
    {
        try
        {
            File canonical = candidate.getCanonicalFile();
            Path path = canonical.toPath();

            if (!path.startsWith(root) || (!allowRoot && path.equals(root)))
            {
                return null;
            }

            return canonical;
        }
        catch (IOException | SecurityException e)
        {
            return null;
        }
    }

    protected String getExtension()
    {
        return ".json";
    }
}
