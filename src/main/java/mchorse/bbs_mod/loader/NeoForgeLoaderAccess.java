package mchorse.bbs_mod.loader;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * NeoForge implementation backed by FMLPaths, FMLEnvironment and ModList.
 */
public class NeoForgeLoaderAccess implements LoaderAccess
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-loader");
    private static final String ADDON_KEY = "bbs-addon";
    private static final String BBS_ADDON_INTERFACE_NAME = "mchorse.bbs_mod.events.BBSAddonMod";
    private static final Type BBS_ADDON_INTERFACE_TYPE = Type.getObjectType("mchorse/bbs_mod/events/BBSAddonMod");

    /** Prevent duplicate warnings for unsupported lookup keys and scan diagnostics. */
    private final ConcurrentHashMap<String, Boolean> warnedKeys = new ConcurrentHashMap<>();

    /** Collected addon instances injected by protocol-side collector. */
    private final Supplier<List<?>> addonSupplier;
    private volatile ScanSnapshot addonScanSnapshot;

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
        return !FMLEnvironment.production;
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
        if (type == null)
        {
            warnOnce("null-type", "[bbs-addon] loader lookup called with null type for key '{}', returning empty list", key);
            return Collections.emptyList();
        }

        if (!ADDON_KEY.equals(key))
        {
            warnOnce("unsupported-key:" + (key == null ? "<null>" : key), "[bbs-addon] loader lookup key unsupported: {} (returning empty list)", key);
            return Collections.emptyList();
        }

        List<T> result = resolveCollectedAddons(type);
        validateAddonScan(result, type);

        return result;
    }

    private <T> List<T> resolveCollectedAddons(Class<T> type)
    {
        List<?> addons;

        try
        {
            addons = this.addonSupplier.get();
        }
        catch (Exception | LinkageError e)
        {
            warnOnce("supplier-error", "[bbs-addon] addon supplier failed, returning empty list");
            LOGGER.debug("[bbs-addon] addon supplier failure details", e);
            return Collections.emptyList();
        }

        if (addons == null || addons.isEmpty())
        {
            return Collections.emptyList();
        }

        List<T> result = new ArrayList<>();
        int ignoredCount = 0;

        for (Object addon : addons)
        {
            if (type.isInstance(addon))
            {
                result.add(type.cast(addon));
            }
            else
            {
                ignoredCount += 1;
            }
        }

        if (ignoredCount > 0)
        {
            warnOnce("type-mismatch:" + type.getName(), "[bbs-addon] ignored {} addon(s) not assignable to {}", ignoredCount, type.getName());
        }

        return result;
    }

    private <T> void validateAddonScan(List<T> resolved, Class<T> requestedType)
    {
        ScanSnapshot snapshot = getOrBuildScanSnapshot();

        if (isDevelopmentEnvironment())
        {
            infoOnce("scan-summary", "[bbs-addon] scan validation summary: resolved={}, scannedCandidates={}, scannedModFiles={}, scannedAnnotations={}",
                resolved.size(), snapshot.addonClassNames.size(), snapshot.scannedModFiles, snapshot.scannedAnnotations);
        }

        if (!isBbsAddonType(requestedType) || resolved.isEmpty())
        {
            return;
        }

        if (snapshot.addonClassNames.isEmpty())
        {
            warnOnce("scan-empty", "[bbs-addon] scan validation found no '{}' candidates in ModList scan data", BBS_ADDON_INTERFACE_NAME);
            return;
        }

        for (T addon : resolved)
        {
            if (addon == null)
            {
                continue;
            }

            String className = addon.getClass().getName();

            if (!snapshot.addonClassNames.contains(className))
            {
                warnOnce("scan-mismatch:" + className, "[bbs-addon] scan mismatch: registered addon class '{}' not found in ModList scan data", className);
            }
        }
    }

    private ScanSnapshot getOrBuildScanSnapshot()
    {
        ScanSnapshot cached = this.addonScanSnapshot;

        if (cached != null)
        {
            return cached;
        }

        synchronized (this)
        {
            if (this.addonScanSnapshot == null)
            {
                this.addonScanSnapshot = buildScanSnapshot();
            }

            return this.addonScanSnapshot;
        }
    }

    private ScanSnapshot buildScanSnapshot()
    {
        ModList modList = ModList.get();

        if (modList == null)
        {
            warnOnce("scan-modlist-null", "[bbs-addon] ModList is unavailable during scan validation");
            return ScanSnapshot.EMPTY;
        }

        List<ModFileScanData> allScanData = modList.getAllScanData();

        if (allScanData == null || allScanData.isEmpty())
        {
            return ScanSnapshot.EMPTY;
        }

        Set<String> addonClassNames = new LinkedHashSet<>();
        int scannedAnnotations = 0;

        try
        {
            for (ModFileScanData scanData : allScanData)
            {
                if (scanData == null)
                {
                    continue;
                }

                // Record annotation count to prove the scan path actively reads annotation metadata.
                scannedAnnotations += scanData.getAnnotations().size();

                for (ModFileScanData.ClassData classData : scanData.getClasses())
                {
                    if (classData == null || classData.clazz() == null || classData.interfaces() == null)
                    {
                        continue;
                    }

                    if (classData.interfaces().contains(BBS_ADDON_INTERFACE_TYPE))
                    {
                        addonClassNames.add(classData.clazz().getClassName());
                    }
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            warnOnce("scan-failed", "[bbs-addon] scan validation failed, fallback to empty scan snapshot");
            LOGGER.debug("[bbs-addon] scan validation failure details", e);
            return ScanSnapshot.EMPTY;
        }

        return new ScanSnapshot(Collections.unmodifiableSet(addonClassNames), allScanData.size(), scannedAnnotations);
    }

    private boolean isBbsAddonType(Class<?> requestedType)
    {
        if (requestedType == null)
        {
            return false;
        }

        return BBS_ADDON_INTERFACE_NAME.equals(requestedType.getName()) || requestedType.isAssignableFrom(resolveBbsAddonClass());
    }

    private Class<?> resolveBbsAddonClass()
    {
        try
        {
            return Class.forName(BBS_ADDON_INTERFACE_NAME);
        }
        catch (ClassNotFoundException | LinkageError e)
        {
            return Object.class;
        }
    }

    private void warnOnce(String key, String message, Object... args)
    {
        if (this.warnedKeys.putIfAbsent("warn:" + key, Boolean.TRUE) == null)
        {
            LOGGER.warn(message, args);
        }
    }

    private void infoOnce(String key, String message, Object... args)
    {
        if (this.warnedKeys.putIfAbsent("info:" + key, Boolean.TRUE) == null)
        {
            LOGGER.info(message, args);
        }
    }

    private static final class ScanSnapshot
    {
        private static final ScanSnapshot EMPTY = new ScanSnapshot(Collections.emptySet(), 0, 0);

        private final Set<String> addonClassNames;
        private final int scannedModFiles;
        private final int scannedAnnotations;

        private ScanSnapshot(Set<String> addonClassNames, int scannedModFiles, int scannedAnnotations)
        {
            this.addonClassNames = addonClassNames;
            this.scannedModFiles = scannedModFiles;
            this.scannedAnnotations = scannedAnnotations;
        }
    }
}
