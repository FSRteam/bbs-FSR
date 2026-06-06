package mchorse.bbs_mod.resources.packs;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.loader.LoaderAccessHolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InternalAssetsSourcePack implements ISourcePack
{
    private String prefix;
    private String internalPrefix;
    private Class clazz;
    private String modId;

    private boolean isForge;

    private List<String> zipCache = new ArrayList<>();
    private String cachedZipPath;
    private long cachedZipLastModified;

    public InternalAssetsSourcePack()
    {
        this(BBSMod.MOD_ID, Link.ASSETS, "assets/bbs/assets", InternalAssetsSourcePack.class);
    }

    public InternalAssetsSourcePack(String prefix, String internalPrefix, Class clazz)
    {
        this(BBSMod.MOD_ID, prefix, internalPrefix, clazz);
    }

    public InternalAssetsSourcePack(String modId, String prefix, String internalPrefix, Class clazz)
    {
        this.prefix = prefix;
        this.internalPrefix = internalPrefix;
        this.clazz = clazz;
        this.modId = modId;

        try
        {
            Class.forName("net.minecraftforge.common.MinecraftForge");

            isForge = true;
        }
        catch (Exception e)
        {}
    }

    @Override
    public String getPrefix()
    {
        return this.prefix;
    }

    @Override
    public boolean hasAsset(Link link)
    {
        return this.clazz.getClassLoader().getResource(this.internalPrefix + "/" + link.path) != null;
    }

    @Override
    public InputStream getAsset(Link link) throws IOException
    {
        return this.clazz.getClassLoader().getResourceAsStream(this.internalPrefix + "/" + link.path);
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
        if (isForge)
        {
            this.stupidWorkaround(links, link, recursive);

            return;
        }

        try
        {
            this.handleSourceFile(this.resolveSourceFile(), link, links, recursive);
        }
        catch (Exception e)
        {
            e.printStackTrace();

            this.stupidWorkaround(links, link, recursive);
        }
    }

    private void stupidWorkaround(Collection<Link> links, Link link, boolean recursive)
    {
        try
        {
            this.handleSourceFile(this.resolveSourceFile(), link, links, recursive);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private File resolveSourceFile() throws Exception
    {
        Path modFile = null;

        try
        {
            modFile = LoaderAccessHolder.get().getModFile(this.modId).orElse(null);
        }
        catch (Exception e)
        {}

        if (modFile != null)
        {
            return modFile.toFile();
        }

        URL url = this.clazz.getProtectionDomain().getCodeSource().getLocation();

        return Paths.get(url.toURI()).toFile();
    }

    private void handleSourceFile(File file, Link link, Collection<Link> links, boolean recursive)
    {
        if (file == null)
        {
            return;
        }

        if (file.isDirectory())
        {
            this.getLinksFromFolder(this.getResourcesFolder(file), link, links, recursive);
        }
        else if (file.getName().endsWith(".jar") || file.getName().endsWith(".zip"))
        {
            this.getLinksFromZipFile(file, link, links, recursive);
        }
    }

    /**
     * Get resources folder. In case this is run in development environment,
     * the project can be compiled to two folders "classes/" and "resources/."
     * To get the right folder, this method checks if the folder with
     * assets exists.
     */
    private File getResourcesFolder(File file)
    {
        if (new File(file, this.internalPrefix).exists())
        {
            return file;
        }

        for (File subFile : file.getParentFile().listFiles())
        {
            if (new File(subFile, this.internalPrefix).exists())
            {
                return subFile;
            }
        }

        /* In development environment, the assets are separate from classes, and for this
         * reason for the files to be found, I have to use this ugly workaround. Also, I
         * don't think it works outside of IntelliJ, so RIP... */
        if (LoaderAccessHolder.get().isDevelopmentEnvironment())
        {
            File buildFolder = file.getParentFile().getParentFile().getParentFile();
            File resources = new File(buildFolder, "resources/main/");

            if (new File(resources, this.internalPrefix).exists())
            {
                return resources;
            }

            resources = new File(buildFolder, "resources/client/");

            if (resources.isDirectory())
            {
                return resources;
            }
        }

        return file;
    }

    private void getLinksFromFolder(File folder, Link link, Collection<Link> links, boolean recursive)
    {
        File file = new File(folder, this.internalPrefix + "/" + link.path);

        ExternalAssetsSourcePack.getLinksFromPathRecursively(file, links, link, link.path, recursive ? 9999 : 1);
    }

    /* Zip handling */

    private void getLinksFromZipFile(File file, Link link, Collection<Link> links, boolean recursive)
    {
        /**
         * Zip files can be big sometimes, so there is no point to
         * read the zip file every time...
         */
        try (ZipFile zipFile = new ZipFile(file))
        {
            String zipPath = file.getAbsolutePath();
            long zipLastModified = file.lastModified();
            boolean sameZipSource = zipPath.equals(this.cachedZipPath) && zipLastModified == this.cachedZipLastModified;

            if (!sameZipSource)
            {
                this.zipCache.clear();
                this.cachedZipPath = zipPath;
                this.cachedZipLastModified = zipLastModified;
            }

            if (this.zipCache.isEmpty())
            {
                Enumeration<? extends ZipEntry> it = zipFile.entries();

                while (it.hasMoreElements())
                {
                    String name = it.nextElement().getName();

                    if (name.startsWith(this.internalPrefix))
                    {
                        this.zipCache.add(name);
                    }
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        this.handleLinksFromZipFile(link, links, recursive);
    }

    private void handleLinksFromZipFile(Link link, Collection<Link> links, boolean recursive)
    {
        DataPath assetsPath = new DataPath(this.internalPrefix + "/");

        for (String zipName : this.zipCache)
        {
            DataPath zipPath = new DataPath(zipName);
            DataPath fullPath = new DataPath(assetsPath + "/" + link.path);

            if (!zipPath.equals(fullPath) && zipPath.startsWith(fullPath))
            {
                for (int i = 0; i < assetsPath.size(); i++)
                {
                    zipPath.strings.remove(0);
                    fullPath.strings.remove(0);
                }

                if (!recursive && zipPath.size() != fullPath.size() + 1)
                {
                    continue;
                }

                links.add(new Link(this.prefix, zipPath + (zipPath.folder ? "/" : "")));
            }
        }
    }
}
