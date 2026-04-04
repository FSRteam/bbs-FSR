package mchorse.bbs_mod.loader;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge implementation backed by FMLPaths, FMLEnvironment and ModList.
 */
public class NeoForgeLoaderAccess implements LoaderAccess
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-loader");

    /** Prevent duplicate warnings for unsupported entrypoint keys. */
    private final ConcurrentHashMap<String, Boolean> warnedKeys = new ConcurrentHashMap<>();

    /** Collected addon instances injected by protocol-side collector. */
    private final Supplier<List<?>> addonSupplier;

    public NeoForgeLoaderAccess(Supplier<List<?>> addonSupplier)
    {
        this.addonSupplier = addonSupplier == null ? Collections::emptyList : addonSupplier;
    }

    @Override
    public Path getGameDir()
    {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isDevelopmentEnvironment()
    {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean isModLoaded(String modId)
    {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> getModVersion(String modId)
    {
        return ModList.get().getModContainerById(modId)
            .map(container -> container.getModInfo().getVersion().toString());
    }

    @Override
    public Optional<Path> getModFile(String modId)
    {
        IModFileInfo info = ModList.get().getModFileById(modId);

        if (info == null)
        {
            return Optional.empty();
        }

        IModFile file = info.getFile();

        return file == null ? Optional.empty() : Optional.ofNullable(file.getFilePath());
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type)
    {
        if ("bbs-addon".equals(key))
        {
            List<?> addons = addonSupplier.get();

            if (addons == null || addons.isEmpty())
            {
                return Collections.emptyList();
            }

            List<T> result = new ArrayList<>();

            for (Object addon : addons)
            {
                if (type.isInstance(addon))
                {
                    result.add(type.cast(addon));
                }
            }

            return result;
        }

        warnOnce(key == null ? "<null>" : key);
        return Collections.emptyList();
    }

    private void warnOnce(String key)
    {
        if (warnedKeys.putIfAbsent(key, Boolean.TRUE) == null)
        {
            LOGGER.warn("getEntrypoints key unsupported: {} (returning empty list)", key);
        }
    }
}
