package mchorse.bbs_mod.plugin.loader;

import java.util.List;

/** Delegation policy shared by plugin validation and generation classloaders. */
public final class PluginClassLoaderPolicy
{
    private static final List<String> PARENT_ONLY_CLASS_PREFIXES = List.of(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "net.minecraft.",
        "net.neoforged.",
        "mchorse.bbs_mod.",
        "org.slf4j."
    );

    private static final List<String> PARENT_ONLY_RESOURCE_PREFIXES = List.of(
        "java/",
        "javax/",
        "jdk/",
        "sun/",
        "com/sun/",
        "net/minecraft/",
        "net/neoforged/",
        "mchorse/bbs_mod/",
        "org/slf4j/"
    );

    private PluginClassLoaderPolicy() {}

    public static boolean isParentOnlyClass(String className)
    {
        return className != null && PARENT_ONLY_CLASS_PREFIXES.stream().anyMatch(className::startsWith);
    }

    public static boolean isParentOnlyResource(String resourceName)
    {
        return resourceName != null && PARENT_ONLY_RESOURCE_PREFIXES.stream().anyMatch(resourceName::startsWith);
    }

    public static boolean isProtectedClassEntry(String jarEntry)
    {
        if (jarEntry == null || !jarEntry.endsWith(".class"))
        {
            return false;
        }

        String logicalName = jarEntry;
        String multiReleasePrefix = "META-INF/versions/";

        if (logicalName.startsWith(multiReleasePrefix))
        {
            int versionEnd = logicalName.indexOf('/', multiReleasePrefix.length());

            if (versionEnd < 0 || versionEnd + 1 >= logicalName.length())
            {
                return true;
            }

            logicalName = logicalName.substring(versionEnd + 1);
        }

        return isParentOnlyClass(logicalName.substring(0, logicalName.length() - 6).replace('/', '.'));
    }
}
