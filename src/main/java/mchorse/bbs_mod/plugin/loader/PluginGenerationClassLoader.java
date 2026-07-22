package mchorse.bbs_mod.plugin.loader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One closeable, child-first classloader for one verified plugin generation. */
public final class PluginGenerationClassLoader extends URLClassLoader
{
    static
    {
        registerAsParallelCapable();
    }

    private final AtomicBoolean closed = new AtomicBoolean();

    public PluginGenerationClassLoader(URL artifact, ClassLoader parent)
    {
        super(new URL[] {artifact}, parent);
    }

    public boolean isClosed()
    {
        return this.closed.get();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (this.getClassLoadingLock(name))
        {
            Class<?> loaded = this.findLoadedClass(name);

            if (loaded == null)
            {
                if (PluginClassLoaderPolicy.isParentOnlyClass(name))
                {
                    /* Protected types must exist in the host. Never fall back to a plugin copy. */
                    loaded = this.getParent().loadClass(name);
                }
                else
                {
                    try
                    {
                        loaded = this.findClass(name);
                    }
                    catch (ClassNotFoundException notPrivate)
                    {
                        loaded = this.getParent().loadClass(name);
                    }
                }
            }

            if (resolve)
            {
                this.resolveClass(loaded);
            }

            return loaded;
        }
    }

    @Override
    public URL getResource(String name)
    {
        if (PluginClassLoaderPolicy.isParentOnlyResource(name))
        {
            return this.getParent().getResource(name);
        }

        URL resource = this.findResource(name);

        return resource == null ? this.getParent().getResource(name) : resource;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        if (PluginClassLoaderPolicy.isParentOnlyResource(name))
        {
            return this.getParent().getResources(name);
        }

        Set<URL> resources = new LinkedHashSet<>();

        this.findResources(name).asIterator().forEachRemaining(resources::add);
        this.getParent().getResources(name).asIterator().forEachRemaining(resources::add);

        return Collections.enumeration(resources);
    }

    @Override
    public void close() throws IOException
    {
        if (this.closed.compareAndSet(false, true))
        {
            super.close();
        }
    }
}
