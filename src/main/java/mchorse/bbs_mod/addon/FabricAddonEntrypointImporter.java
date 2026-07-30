package mchorse.bbs_mod.addon;

import mchorse.bbs_mod.events.BBSAddonMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Imports Fabric's custom BBS entrypoints when Connector exposes Fabric Loader.
 * All Fabric API access stays reflective so plain NeoForge installations have
 * no Fabric Loader linkage.
 */
public final class FabricAddonEntrypointImporter
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricAddonEntrypointImporter.class);
    private static final String ENTRYPOINT_KEY = "bbs-addon";

    private final DiscoverySource source;

    public FabricAddonEntrypointImporter()
    {
        this(new ReflectiveDiscoverySource());
    }

    FabricAddonEntrypointImporter(DiscoverySource source)
    {
        this.source = source;
    }

    public ImportSummary importInto(BBSAddonCollector collector)
    {
        if (collector == null)
        {
            LOGGER.warn("[bbs-addon] cannot import Fabric entrypoints because the collector is null");

            return new ImportSummary(false, 0, 0, 0, 1);
        }

        Discovery discovery;

        try
        {
            discovery = this.source.discover(ENTRYPOINT_KEY, BBSAddonMod.class);
        }
        catch (RuntimeException | LinkageError e)
        {
            collector.recordExternalDiscoveryFailure("<fabric-loader>", "fabric-loader", e);
            LOGGER.warn("[bbs-addon] Fabric entrypoint discovery failed: {}", e.toString());
            LOGGER.debug("[bbs-addon] Fabric entrypoint discovery failure details", e);

            return new ImportSummary(true, 0, 0, 0, 1);
        }

        if (discovery == null)
        {
            IllegalStateException error = new IllegalStateException("Fabric discovery returned null");

            collector.recordExternalDiscoveryFailure("<fabric-loader>", "fabric-loader", error);
            LOGGER.warn("[bbs-addon] Fabric entrypoint discovery returned no result");

            return new ImportSummary(true, 0, 0, 0, 1);
        }

        if (!discovery.available())
        {
            return new ImportSummary(false, 0, 0, 0, 0);
        }

        int imported = 0;
        int rejected = 0;
        int failed = discovery.failures().size();

        for (DiscoveryFailure failure : discovery.failures())
        {
            collector.recordExternalDiscoveryFailure(failure.providerId(), failure.source(), failure.error());
            LOGGER.warn("[bbs-addon] failed to inspect Fabric entrypoint {}: {}", failure.source(), failure.error().toString());
            LOGGER.debug("[bbs-addon] Fabric entrypoint inspection failure details for " + failure.source(), failure.error());
        }

        for (Candidate candidate : discovery.candidates())
        {
            Object entrypoint;

            try
            {
                entrypoint = candidate.entrypoint().get();

                if (!(entrypoint instanceof BBSAddonMod addon))
                {
                    throw new IllegalStateException("entrypoint is not assignable to " + BBSAddonMod.class.getName());
                }

                if (collector.registerExternal(candidate.providerId(), addon, candidate.source()))
                {
                    imported += 1;
                }
                else
                {
                    rejected += 1;
                }
            }
            catch (RuntimeException | LinkageError e)
            {
                failed += 1;
                collector.recordExternalDiscoveryFailure(candidate.providerId(), candidate.source(), e);
                LOGGER.warn("[bbs-addon] failed to load Fabric entrypoint {}: {}", candidate.source(), e.toString());
                LOGGER.debug("[bbs-addon] Fabric entrypoint load failure details for " + candidate.source(), e);
            }
        }

        ImportSummary summary = new ImportSummary(
            true,
            discovery.candidates().size() + discovery.failures().size(),
            imported,
            rejected,
            failed
        );

        LOGGER.info("[bbs-addon] Fabric loader resolved {} candidate(s): imported={}, rejected={}, failed={}",
            summary.discovered(), summary.imported(), summary.rejected(), summary.failed());

        return summary;
    }

    interface DiscoverySource
    {
        Discovery discover(String key, Class<?> type);
    }

    record Candidate(String providerId, String source, Supplier<?> entrypoint)
    {}

    record Discovery(boolean available, List<Candidate> candidates, List<DiscoveryFailure> failures)
    {
        static Discovery unavailable()
        {
            return new Discovery(false, Collections.emptyList(), Collections.emptyList());
        }

        static Discovery available(List<Candidate> candidates)
        {
            return new Discovery(true, List.copyOf(candidates), Collections.emptyList());
        }
    }

    record DiscoveryFailure(String providerId, String source, Throwable error)
    {}

    public record ImportSummary(boolean loaderAvailable, int discovered, int imported, int rejected, int failed)
    {}

    private static final class ReflectiveDiscoverySource implements DiscoverySource
    {
        private static final String FABRIC_LOADER = "net.fabricmc.loader.api.FabricLoader";
        private static final String ENTRYPOINT_CONTAINER = "net.fabricmc.loader.api.entrypoint.EntrypointContainer";
        private static final String MOD_CONTAINER = "net.fabricmc.loader.api.ModContainer";
        private static final String MOD_METADATA = "net.fabricmc.loader.api.metadata.ModMetadata";

        @Override
        public Discovery discover(String key, Class<?> type)
        {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            if (classLoader == null)
            {
                classLoader = FabricAddonEntrypointImporter.class.getClassLoader();
            }

            Class<?> fabricLoader;

            try
            {
                fabricLoader = Class.forName(FABRIC_LOADER, false, classLoader);
            }
            catch (ClassNotFoundException e)
            {
                return Discovery.unavailable();
            }

            try
            {
                Class<?> entrypointContainer = Class.forName(ENTRYPOINT_CONTAINER, false, classLoader);
                Class<?> modContainer = Class.forName(MOD_CONTAINER, false, classLoader);
                Class<?> modMetadata = Class.forName(MOD_METADATA, false, classLoader);
                Method getInstance = fabricLoader.getMethod("getInstance");
                Method getEntrypointContainers = fabricLoader.getMethod("getEntrypointContainers", String.class, Class.class);
                Method getProvider = entrypointContainer.getMethod("getProvider");
                Method getEntrypoint = entrypointContainer.getMethod("getEntrypoint");
                Method getMetadata = modContainer.getMethod("getMetadata");
                Method getId = modMetadata.getMethod("getId");
                Object loader = invoke(getInstance, null);
                Object containers = invoke(getEntrypointContainers, loader, key, type);

                if (!(containers instanceof Iterable<?> iterable))
                {
                    throw new IllegalStateException("Fabric Loader returned a non-iterable entrypoint collection");
                }

                List<Candidate> candidates = new ArrayList<>();
                List<DiscoveryFailure> failures = new ArrayList<>();
                int index = 0;

                for (Object container : iterable)
                {
                    String containerSource = "fabric-loader:container-" + index;

                    try
                    {
                        Object provider = invoke(getProvider, container);
                        Object metadata = invoke(getMetadata, provider);
                        Object id = invoke(getId, metadata);

                        if (!(id instanceof String providerId) || providerId.isBlank())
                        {
                            throw new IllegalStateException("Fabric entrypoint provider id is blank");
                        }

                        String source = "fabric-loader:" + providerId;

                        candidates.add(new Candidate(providerId, source, () -> invoke(getEntrypoint, container)));
                    }
                    catch (RuntimeException | LinkageError e)
                    {
                        failures.add(new DiscoveryFailure("<unknown>", containerSource, e));
                    }

                    index += 1;
                }

                return new Discovery(true, List.copyOf(candidates), List.copyOf(failures));
            }
            catch (ReflectiveOperationException e)
            {
                throw new FabricEntrypointReflectionException("Fabric Loader entrypoint API is incompatible", e);
            }
        }

        private static Object invoke(Method method, Object owner, Object... arguments)
        {
            try
            {
                return method.invoke(owner, arguments);
            }
            catch (IllegalAccessException e)
            {
                throw new FabricEntrypointReflectionException("Fabric Loader entrypoint method is inaccessible", e);
            }
            catch (InvocationTargetException e)
            {
                Throwable cause = e.getCause();

                if (cause instanceof RuntimeException runtime)
                {
                    throw runtime;
                }
                if (cause instanceof Error error)
                {
                    throw error;
                }

                throw new FabricEntrypointReflectionException("Fabric Loader entrypoint method failed", cause);
            }
        }
    }

    private static final class FabricEntrypointReflectionException extends RuntimeException
    {
        private FabricEntrypointReflectionException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
