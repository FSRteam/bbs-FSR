package mchorse.bbs_mod.plugin.hotreload.phase0;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Child-first for plugin-private code, with shared/runtime packages forced
 * through the host loader so plugins cannot create a second API identity.
 */
final class Phase0GenerationLoader extends URLClassLoader
{
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "net.minecraft.",
        "net.neoforged.",
        "mchorse.bbs_mod.api.",
        "mchorse.bbs_mod.plugin.hotreload.phase0.api."
    );

    Phase0GenerationLoader(Path artifact, ClassLoader parent) throws Exception
    {
        super(new URL[] {artifact.toUri().toURL()}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        synchronized (this.getClassLoadingLock(name))
        {
            Class<?> loaded = this.findLoadedClass(name);

            if (loaded == null)
            {
                loaded = this.isParentFirst(name) ? this.loadParentFirst(name) : this.loadChildFirst(name);
            }

            if (resolve)
            {
                this.resolveClass(loaded);
            }

            return loaded;
        }
    }

    private Class<?> loadParentFirst(String name) throws ClassNotFoundException
    {
        try
        {
            return this.getParent().loadClass(name);
        }
        catch (ClassNotFoundException exception)
        {
            return this.findClass(name);
        }
    }

    private Class<?> loadChildFirst(String name) throws ClassNotFoundException
    {
        try
        {
            return this.findClass(name);
        }
        catch (ClassNotFoundException exception)
        {
            return this.getParent().loadClass(name);
        }
    }

    private boolean isParentFirst(String name)
    {
        return PARENT_FIRST_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
