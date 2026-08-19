package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginDescriptor;
import mchorse.bbs_mod.api.plugin.BBSPluginDiagnostic;
import mchorse.bbs_mod.api.plugin.BBSPluginDiagnosticSeverity;
import mchorse.bbs_mod.api.plugin.BBSPluginDiagnosticSink;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginManifest;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;
import mchorse.bbs_mod.api.plugin.BBSPluginState;
import mchorse.bbs_mod.api.plugin.BBSPluginStopReason;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.events.EventSubscription;
import mchorse.bbs_mod.plugin.artifact.PluginArtifactStore;
import mchorse.bbs_mod.plugin.artifact.PluginArtifactStoreException;
import mchorse.bbs_mod.plugin.artifact.PluginArtifactValidation;
import mchorse.bbs_mod.plugin.artifact.PluginArtifactValidator;
import mchorse.bbs_mod.plugin.artifact.PluginShadowArtifact;
import mchorse.bbs_mod.plugin.artifact.PluginValidatedArtifact;
import mchorse.bbs_mod.plugin.content.PluginContentKind;
import mchorse.bbs_mod.plugin.content.PluginContentSnapshot;
import mchorse.bbs_mod.plugin.host.PluginRoutingSourcePack;
import mchorse.bbs_mod.plugin.loader.PluginGenerationClassLoader;
import mchorse.bbs_mod.plugin.runtime.ActivePluginGeneration;
import mchorse.bbs_mod.plugin.runtime.ActivePluginIndex;
import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.plugin.watch.PluginApplyMode;
import mchorse.bbs_mod.plugin.watch.PluginArtifactFingerprint;
import mchorse.bbs_mod.plugin.watch.PluginDirectoryWatcher;
import mchorse.bbs_mod.plugin.watch.PluginWatchIntent;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.ISourcePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * FSR-owned hot plugin lifecycle. It is deliberately independent from the
 * NeoForge mod/addon lifecycle and serializes every filesystem intent.
 */
public final class BBSPluginManager implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-plugin-runtime");
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final String PLUGIN_MANIFEST = "META-INF/bbs-plugin.json";

    private final Path gameDirectory;
    private final Path pluginDirectory;
    private final Path dataDirectory;
    private final boolean physicalClient;
    private final Predicate<String> legacyAddonConflict;
    private final EventBus eventBus;
    private final AssetProvider assetProvider;
    private final PluginArtifactValidator validator = new PluginArtifactValidator();
    private final PluginArtifactStore artifactStore;
    private final ActivePluginIndex<PluginRuntimeGeneration> active = new ActivePluginIndex<>();
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor((runnable) ->
    {
        Thread thread = new Thread(runnable, "BBS plugin lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Long> nextGenerations = new HashMap<>();
    private final Map<String, PluginStatus> statuses = new LinkedHashMap<>();
    private final Map<Path, String> sourceOwners = new HashMap<>();
    private final Map<String, Path> activeSources = new HashMap<>();
    private final Set<String> disabled = new HashSet<>();
    private final Set<String> restartRequired = new HashSet<>();
    private final Map<PluginEventProxyKey, EventSubscription> eventProxies = new HashMap<>();
    private final Map<String, AutoCloseable> sourcePacks = new HashMap<>();
    private final PluginParticleComponents particleComponents = new PluginParticleComponents();
    private final PluginStructuralReloadCoordinator structuralReload = new PluginStructuralReloadCoordinator(
        this::runStructuralSafepoint,
        this::isStructuralReloadBusy
    );
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean autoApply = true;
    private volatile PluginDirectoryWatcher watcher;

    public BBSPluginManager(
        Path gameDirectory,
        boolean physicalClient,
        Predicate<String> legacyAddonConflict,
        EventBus eventBus,
        AssetProvider assetProvider
    )
    {
        this.gameDirectory = requireDirectory(gameDirectory, "gameDirectory");
        this.pluginDirectory = this.gameDirectory.resolve("config/bbs/plugins").normalize();
        this.dataDirectory = this.gameDirectory.resolve("config/bbs/plugin-data").normalize();
        this.physicalClient = physicalClient;
        this.legacyAddonConflict = legacyAddonConflict == null ? (id) -> false : legacyAddonConflict;
        this.eventBus = eventBus;
        this.assetProvider = assetProvider;
        this.artifactStore = new PluginArtifactStore(this.gameDirectory.resolve("config/bbs/plugin-cache"));
    }

    public BBSPluginManager(Path gameDirectory, boolean physicalClient, EventBus eventBus, AssetProvider assetProvider)
    {
        this(gameDirectory, physicalClient, (id) -> false, eventBus, assetProvider);
    }

    public Path pluginDirectory()
    {
        return this.pluginDirectory;
    }

    public Map<String, PluginParticleComponentClass> particleComponents()
    {
        return this.particleComponents.snapshot();
    }

    public boolean autoApply()
    {
        return this.autoApply;
    }

    public void setAutoApply(boolean autoApply)
    {
        this.autoApply = autoApply;
        PluginDirectoryWatcher current = this.watcher;

        if (current != null)
        {
            current.setAutoApply(autoApply);
        }
    }

    public List<PluginStatus> diagnostics()
    {
        if (this.closed.get())
        {
            return this.snapshotStatuses();
        }

        Future<List<PluginStatus>> future = this.lifecycle.submit(this::snapshotStatuses);

        try
        {
            return future.get(5, TimeUnit.SECONDS);
        }
        catch (Exception error)
        {
            throw new IllegalStateException("plugin lifecycle query failed", error);
        }
    }

    public void start()
    {
        if (this.closed.get() || !this.started.compareAndSet(false, true))
        {
            return;
        }

        this.lifecycle.execute(() ->
        {
            if (this.closed.get())
            {
                return;
            }

            try
            {
                Files.createDirectories(this.pluginDirectory);
                Files.createDirectories(this.dataDirectory);
                this.reconcile(this.autoApply);
                PluginDirectoryWatcher directoryWatcher = new PluginDirectoryWatcher(
                    this.pluginDirectory,
                    this.autoApply,
                    this::enqueueIntent,
                    this::recordWatcherError
                );
                this.watcher = directoryWatcher;
                directoryWatcher.start();
            }
            catch (Throwable error)
            {
                this.recordStatus("<runtime>", null, BBSPluginState.FAILED, "START", error);
                LOGGER.error("[bbs-plugin] failed to start hot plugin runtime", error);
            }
        });
    }

    public void rescan()
    {
        PluginDirectoryWatcher current = this.watcher;

        if (current != null)
        {
            current.requestRescan(true);
        }
        else
        {
            this.lifecycle.execute(() -> this.reconcile(true));
        }
    }

    public void reload(String pluginId)
    {
        this.lifecycle.execute(() ->
        {
            Path source = this.activeSources.get(pluginId);

            if (source == null)
            {
                this.recordStatus(pluginId, null, BBSPluginState.FAILED, "RELOAD_MISSING", null);
                return;
            }

            this.applyPath(source, true);
        });
    }

    public void disable(String pluginId)
    {
        this.lifecycle.execute(() ->
        {
            this.disabled.add(pluginId);
            Path source = this.activeSources.get(pluginId);
            this.unload(pluginId, BBSPluginStopReason.DISABLED);

            if (source != null)
            {
                this.activeSources.put(pluginId, source);
                this.sourceOwners.put(source, pluginId);
            }
        });
    }

    public void enable(String pluginId)
    {
        this.lifecycle.execute(() ->
        {
            this.disabled.remove(pluginId);
            Path source = this.activeSources.get(pluginId);

            if (source != null)
            {
                this.applyPath(source, true);
            }
            else
            {
                this.reconcile(true);
            }
        });
    }

    @Override
    public void close()
    {
        if (!this.closed.compareAndSet(false, true))
        {
            return;
        }

        PluginDirectoryWatcher current = this.watcher;

        if (current != null)
        {
            current.close();
        }

        Future<?> completion = this.lifecycle.submit(this::shutdownOnLifecycleThread);

        try
        {
            completion.get(15, TimeUnit.SECONDS);
        }
        catch (Exception error)
        {
            LOGGER.error("[bbs-plugin] hot plugin shutdown did not complete cleanly", error);
        }
        finally
        {
            this.lifecycle.shutdownNow();
        }
    }

    private void enqueueIntent(PluginWatchIntent intent)
    {
        if (this.closed.get())
        {
            return;
        }

        this.lifecycle.execute(() ->
        {
            try
            {
                switch (intent.action())
                {
                    case UPSERT ->
                    {
                        if (intent.applyMode() == PluginApplyMode.RELOAD_PENDING)
                        {
                            this.recordStatus(intentKey(intent), null, BBSPluginState.RELOAD_PENDING, "WATCH_PENDING", null);
                        }
                        else
                        {
                            this.applyPath(
                                intent.artifactPath().orElseThrow(),
                                intent.applyMode() == PluginApplyMode.MANUAL_APPLY,
                                intent.fingerprint().orElseThrow()
                            );
                        }
                    }
                    case DELETE ->
                    {
                        if (intent.applyMode() == PluginApplyMode.RELOAD_PENDING)
                        {
                            this.recordStatus(intentKey(intent), null, BBSPluginState.RELOAD_PENDING, "WATCH_PENDING", null);
                        }
                        else
                        {
                            /* A delete can be one half of an atomic rename. Scan
                             * and prepare visible replacements before retiring
                             * the missing source. */
                            this.reconcile(true);
                        }
                    }
                    case RECONCILE -> this.reconcile(intent.applyMode() != PluginApplyMode.RELOAD_PENDING);
                }
            }
            catch (Throwable error)
            {
                this.recordStatus(intentKey(intent), null, BBSPluginState.FAILED, "INTENT", error);
                LOGGER.error("[bbs-plugin] lifecycle intent failed", error);
            }
        });
    }

    private void reconcile(boolean apply)
    {
        try
        {
            Files.createDirectories(this.pluginDirectory);
        }
        catch (IOException error)
        {
            this.recordStatus("<directory>", null, BBSPluginState.FAILED, "DIRECTORY", error);
            return;
        }

        List<Path> artifacts;

        try (var files = Files.list(this.pluginDirectory))
        {
            artifacts = files
                .filter((path) -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                .filter((path) -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        catch (IOException error)
        {
            this.recordStatus("<directory>", null, BBSPluginState.FAILED, "SCAN", error);
            return;
        }

        if (!apply)
        {
            return;
        }

        Set<Path> present = Set.copyOf(artifacts);
        List<Path> removed = new ArrayList<>();

        for (Path source : this.sourceOwners.keySet())
        {
            if (!present.contains(source))
            {
                removed.add(source);
            }
        }

        for (Path artifact : artifacts)
        {
            this.applyPath(artifact, false);
        }

        for (Path source : removed)
        {
            this.unloadBySource(source, BBSPluginStopReason.REMOVED);
        }
    }

    private void applyPath(Path source, boolean force)
    {
        this.applyPath(source, force, null);
    }

    private void applyPath(Path source, boolean force, PluginArtifactFingerprint expectedFingerprint)
    {
        Path normalized = source.toAbsolutePath().normalize();

        if (this.disabled.stream().anyMatch((id) -> normalized.equals(this.activeSources.get(id))))
        {
            return;
        }

        PluginArtifactValidation validation = this.validator.validate(this.pluginDirectory, normalized);

        if (!validation.accepted())
        {
            String key = validation.manifest() == null ? normalized.getFileName().toString() : validation.manifest().id();
            BBSPluginState state = switch (validation.status())
            {
                case INCOMPATIBLE -> BBSPluginState.INCOMPATIBLE;
                case RESTART_REQUIRED -> BBSPluginState.RESTART_REQUIRED;
                default -> BBSPluginState.FAILED;
            };
            String message = validation.issues().isEmpty() ? validation.status().name() : validation.issues().get(0).message();

            this.recordStatus(key, validation.manifest() == null ? null : validation.manifest().descriptor(), state, "VALIDATE", new IllegalArgumentException(message));
            return;
        }

        PluginValidatedArtifact validated = validation.artifact();
        String pluginId = validated.descriptor().id();

        if (expectedFingerprint != null && !expectedFingerprint.sha256().equals(validated.sha256()))
        {
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.FAILED, "STALE_INTENT",
                new IllegalStateException("plugin source changed after the watcher accepted it"));
            return;
        }

        if (this.restartRequired.contains(pluginId))
        {
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.RESTART_REQUIRED, "RESTART_REQUIRED", null);
            return;
        }

        if (this.disabled.contains(pluginId))
        {
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.DISABLED, "DISABLED", null);
            return;
        }

        if (this.legacyAddonConflict.test(pluginId))
        {
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.FAILED, "ADDON_ID_CONFLICT",
                new IllegalStateException("hot plugin id collides with a startup addon"));
            return;
        }

        Path existingSource = this.activeSources.get(pluginId);

        if (existingSource != null && !existingSource.equals(normalized) && Files.exists(existingSource, LinkOption.NOFOLLOW_LINKS))
        {
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.FAILED, "DUPLICATE_ID",
                new IllegalStateException("another artifact already owns plugin id " + pluginId));
            return;
        }

        ActivePluginGeneration<PluginRuntimeGeneration> incumbent = this.active.active(pluginId);

        if (!force && incumbent != null && incumbent.contributions().artifactHash().equals(validated.sha256()))
        {
            this.sourceOwners.put(normalized, pluginId);
            this.activeSources.put(pluginId, normalized);
            return;
        }

        if (!force && this.statuses.get(pluginId) != null && this.statuses.get(pluginId).state() == BBSPluginState.RELOAD_PENDING)
        {
            return;
        }

        PluginOwner owner = new PluginOwner(pluginId, this.nextGenerations.merge(pluginId, 1L, Long::sum));
        PluginRuntimeGeneration candidate = null;
        PluginShadowArtifact shadow = null;
        boolean published = false;
        boolean structuralCommitted = false;

        try
        {
            shadow = this.artifactStore.stage(validated);
            Set<String> replaceableStructuralKeys = incumbent == null
                ? Set.of()
                : incumbent.contributions().structuralRegistrations().keys();
            candidate = this.prepare(shadow, owner, replaceableStructuralKeys);
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.STAGED, "PREPARE", null);

            PluginArtifactFingerprint commitFingerprint = PluginArtifactFingerprint.capture(normalized);

            if (!commitFingerprint.sha256().equals(validated.sha256()))
            {
                throw new IllegalStateException("plugin source changed while the candidate was being prepared");
            }

            this.installEventProxies(pluginId, candidate.eventRoutes().keySet());
            this.ensureSourcePack(pluginId, candidate.content() != null);
            PluginRuntimeGeneration committedCandidate = candidate;
            this.structuralReload.replace(
                incumbent == null ? null : incumbent.contributions().structuralRegistrations(),
                committedCandidate.structuralRegistrations(),
                (type, error) -> this.recordDiagnostic(
                    pluginId,
                    owner.generation(),
                    BBSPluginDiagnosticSeverity.ERROR,
                    "REBUILD_FAILED",
                    "failed to rebuild structural instance '" + type + "': " + safeMessage(error),
                    error.getClass().getName()
                )
            );
            structuralCommitted = true;
            ActivePluginGeneration<PluginRuntimeGeneration> publishedGeneration =
                new ActivePluginGeneration<>(owner, candidate);

            /* No fallible host operation belongs between the root swap and
             * capturing the incumbent that must be retired. */
            ActivePluginGeneration<PluginRuntimeGeneration> retired = this.active.replace(
                incumbent == null ? null : incumbent.owner(),
                publishedGeneration
            );
            published = true;

            this.sourceOwners.put(normalized, pluginId);
            this.activeSources.put(pluginId, normalized);
            this.recordStatus(pluginId, validated.descriptor(), BBSPluginState.ACTIVE, "COMMIT", null);

            if (candidate.content() == null)
            {
                this.closeSourcePack(pluginId);
            }

            this.refreshClientProjection();

            if (retired != null)
            {
                this.retire(pluginId, retired, BBSPluginStopReason.RELOAD);
            }

            this.pruneEventProxies(pluginId, candidate.eventRoutes().keySet());
            this.artifactStore.commit(shadow);
        }
        catch (Throwable error)
        {
            boolean busy = error instanceof PluginStructuralReloadCoordinator.ReloadBusyException;
            this.recordStatus(pluginId, validated.descriptor(), busy ? BBSPluginState.RELOAD_REJECTED_BUSY
                    : published ? BBSPluginState.RESTART_REQUIRED : BBSPluginState.FAILED,
                busy ? "RELOAD_REJECTED_BUSY" : published ? "POST_COMMIT" : "PREPARE", error);

            if (published)
            {
                this.restartRequired.add(pluginId);
            }
            else if (candidate != null)
            {
                if (structuralCommitted)
                {
                    try
                    {
                        this.structuralReload.replace(
                            candidate.structuralRegistrations(),
                            incumbent == null ? null : incumbent.contributions().structuralRegistrations(),
                            (type, rebuildError) -> LOGGER.warn("[bbs-plugin] rollback rebuild failed for {}", type, rebuildError)
                        );
                    }
                    catch (Throwable rollbackError)
                    {
                        error.addSuppressed(rollbackError);
                    }
                }

                this.closeCandidate(candidate);
            }

            if (!published && incumbent == null)
            {
                this.closeProxyRoutes(pluginId);
                this.closeSourcePack(pluginId);
            }
            else if (!published)
            {
                PluginRuntimeGeneration activeRuntime = incumbent.contributions();
                this.pruneEventProxies(pluginId, activeRuntime.eventRoutes().keySet());

                if (activeRuntime.content() == null)
                {
                    this.closeSourcePack(pluginId);
                }
            }
        }
    }

    private PluginRuntimeGeneration prepare(PluginShadowArtifact shadow, PluginOwner owner, Set<String> replaceableStructuralKeys) throws Exception
    {
        BBSPluginManifest manifest = shadow.manifest();
        BBSPluginDescriptor descriptor = shadow.descriptor();

        if (manifest.side() == BBSPluginSide.CLIENT && !this.physicalClient)
        {
            throw new IllegalStateException("client-only plugin cannot load on dedicated server");
        }

        if (manifest.side() == BBSPluginSide.DEDICATED_SERVER && this.physicalClient)
        {
            throw new IllegalStateException("dedicated-server-only plugin cannot load on client");
        }

        PluginContentSnapshot content = manifest.kind() == BBSPluginKind.CONTENT
            ? this.readContent(shadow, owner)
            : null;
        PluginGenerationClassLoader loader = null;
        List<BBSPlugin> entrypoints = new ArrayList<>();

        if (manifest.kind() == BBSPluginKind.CODE)
        {
            loader = new PluginGenerationClassLoader(shadow.path().toUri().toURL(), BBSPlugin.class.getClassLoader());

            try
            {
                this.instantiateEntrypoint(loader, manifest.commonEntrypoint(), entrypoints);

                if (this.physicalClient)
                {
                    this.instantiateEntrypoint(loader, manifest.clientEntrypoint(), entrypoints);
                }
            }
            catch (Throwable error)
            {
                try
                {
                    loader.close();
                }
                catch (Throwable closeError)
                {
                    error.addSuppressed(closeError);
                }

                throw error;
            }
        }

        PluginContributionLedger ledger = new PluginContributionLedger(owner);
        PluginRuntimeGeneration generation = null;

        try
        {
            Path pluginData = this.dataDirectory.resolve(owner.pluginId()).normalize();

            Files.createDirectories(pluginData);
            BBSPluginDiagnosticSink sink = this.diagnosticSink(owner);
            PluginStructuralRegistrationWindow structuralRegistrations = new PluginStructuralRegistrationWindow(owner, replaceableStructuralKeys);
            PluginGenerationContext context = new PluginGenerationContext(
                descriptor,
                owner,
                pluginData,
                sink,
                ledger,
                loader,
                structuralRegistrations,
                PluginStructuralRegistryAdapters.forms(
                    structuralRegistrations,
                    descriptor,
                    owner,
                    ledger,
                    BBSMod::getForms
                ),
                PluginStructuralRegistryAdapters.clips(
                    structuralRegistrations,
                    descriptor,
                    owner,
                    ledger,
                    BBSMod::getFactoryCameraClips,
                    BBSMod::getFactoryActionClips
                ),
                PluginStructuralRegistryAdapters.particles(
                    structuralRegistrations,
                    descriptor,
                    owner,
                    ledger,
                    this.particleComponents,
                    loader,
                    BBSMod::getAddonParticleComponentClasses
                ),
                (extensionType) -> this.createClientExtension(
                    extensionType,
                    descriptor,
                    owner,
                    ledger,
                    structuralRegistrations,
                    sink
                )
            );
            generation = new PluginRuntimeGeneration(
                owner,
                descriptor,
                shadow.sha256(),
                content,
                ledger,
                context,
                entrypoints,
                loader
            );
            generation.prepareAndStart();
            return generation;
        }
        catch (Throwable error)
        {
            try
            {
                if (generation != null)
                {
                    generation.close();
                }
                else
                {
                    ledger.close();

                    if (loader != null)
                    {
                        loader.close();
                    }
                }
            }
            catch (Throwable closeError)
            {
                error.addSuppressed(closeError);
            }

            if (error instanceof Exception exception)
            {
                throw exception;
            }

            if (error instanceof Error fatal)
            {
                throw fatal;
            }

            throw new RuntimeException(error);
        }
    }

    private void instantiateEntrypoint(ClassLoader loader, String className, List<BBSPlugin> target) throws Exception
    {
        if (className == null || className.isBlank())
        {
            return;
        }

        Class<?> type = Class.forName(className, true, loader);

        if (!BBSPlugin.class.isAssignableFrom(type))
        {
            throw new IllegalArgumentException("entrypoint does not implement BBSPlugin: " + className);
        }

        @SuppressWarnings("unchecked")
        Class<? extends BBSPlugin> pluginType = (Class<? extends BBSPlugin>) type;
        target.add(pluginType.getDeclaredConstructor().newInstance());
    }

    private PluginContentSnapshot readContent(PluginShadowArtifact shadow, PluginOwner owner) throws IOException
    {
        PluginContentSnapshot.Builder builder = PluginContentSnapshot.builder(
            owner.pluginId(),
            shadow.descriptor().version(),
            owner.generation(),
            shadow.sha256()
        );

        try (JarFile jar = new JarFile(shadow.path().toFile(), true))
        {
            var entries = jar.entries();

            while (entries.hasMoreElements())
            {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (entry.isDirectory() || name.equals(PLUGIN_MANIFEST) || !name.startsWith("assets/") && !name.startsWith("content/"))
                {
                    continue;
                }

                try (InputStream input = jar.getInputStream(entry))
                {
                    byte[] bytes = input.readAllBytes();
                    PluginContentKind kind = contentKind(name);
                    builder.put(kind, name, bytes);
                }
            }
        }

        return builder.build();
    }

    private static PluginContentKind contentKind(String name)
    {
        if (name.startsWith("assets/strings/") || name.startsWith("content/language/"))
        {
            return PluginContentKind.LANGUAGE;
        }

        if (name.startsWith("content/settings/"))
        {
            return PluginContentKind.SETTINGS;
        }

        if (name.startsWith("content/ui/"))
        {
            return PluginContentKind.UI;
        }

        if (name.startsWith("content/data/"))
        {
            return PluginContentKind.DATA;
        }

        return PluginContentKind.RESOURCE;
    }

    private void installEventProxies(String pluginId, Set<PluginEventRoute> routes)
    {
        if (this.eventBus == null)
        {
            if (!routes.isEmpty())
            {
                throw new IllegalStateException("hot event routes are unavailable before EventBus initialization");
            }

            return;
        }

        for (PluginEventRoute route : routes)
        {
            PluginEventProxyKey key = new PluginEventProxyKey(pluginId, route);

            if (this.eventProxies.containsKey(key))
            {
                continue;
            }

            EventSubscription subscription = this.subscribeProxy(pluginId, route);
            this.eventProxies.put(key, subscription);
        }
    }

    private EventSubscription subscribeProxy(String pluginId, PluginEventRoute route)
    {
        return this.subscribeProxyTyped(pluginId, route, route.eventType());
    }

    private <E> EventSubscription subscribeProxyTyped(String pluginId, PluginEventRoute route, Class<E> eventType)
    {
        return this.eventBus.subscribe(eventType, (event) ->
        {
            var lease = this.active.acquire(pluginId);

            if (lease == null)
            {
                return;
            }

            PluginRuntimeGeneration runtime = lease.contributions();

            try (lease)
            {
                if (runtime != null)
                {
                    runtime.dispatch(route, event);
                }
            }
            catch (PluginRuntimeGeneration.PluginCallbackException error)
            {
                this.recordStatus(pluginId, runtime == null ? null : runtime.descriptor(), BBSPluginState.FAILED, "CALLBACK", error);
            }
        });
    }

    private void ensureSourcePack(String pluginId, boolean required)
    {
        if (!required || this.assetProvider == null || this.sourcePacks.containsKey(pluginId))
        {
            return;
        }

        ISourcePack sourcePack = new PluginRoutingSourcePack(() ->
        {
            var lease = this.active.acquire(pluginId);

            if (lease == null)
            {
                return null;
            }

            try (lease)
            {
                return lease.contributions().content();
            }
        });
        this.sourcePacks.put(pluginId, this.assetProvider.registerFirstCloseable(sourcePack));
    }

    private void unloadBySource(Path source, BBSPluginStopReason reason)
    {
        Path normalized = source.toAbsolutePath().normalize();
        String pluginId = this.sourceOwners.remove(normalized);

        if (pluginId != null && normalized.equals(this.activeSources.get(pluginId)))
        {
            this.unload(pluginId, reason);
        }
    }

    private void unload(String pluginId, BBSPluginStopReason reason)
    {
        ActivePluginGeneration<PluginRuntimeGeneration> current = this.active.active(pluginId);
        boolean structuralRemoved = false;

        if (current != null)
        {
            try
            {
                PluginRuntimeGeneration runtime = current.contributions();
                this.structuralReload.replace(
                    runtime.structuralRegistrations(),
                    null,
                    (type, error) -> this.recordDiagnostic(
                        pluginId,
                        current.owner().generation(),
                        BBSPluginDiagnosticSeverity.ERROR,
                        "REBUILD_FAILED",
                        "failed to degrade structural instance '" + type + "': " + safeMessage(error),
                        error.getClass().getName()
                    )
                );
                structuralRemoved = true;
            }
            catch (PluginStructuralReloadCoordinator.ReloadBusyException error)
            {
                this.recordStatus(pluginId, current.contributions().descriptor(), BBSPluginState.RELOAD_REJECTED_BUSY,
                    "RELOAD_REJECTED_BUSY", error);
                return;
            }
        }

        ActivePluginGeneration<PluginRuntimeGeneration> removed;

        try
        {
            removed = this.active.remove(pluginId);
        }
        catch (Throwable error)
        {
            if (structuralRemoved && current != null)
            {
                try
                {
                    this.structuralReload.replace(null, current.contributions().structuralRegistrations(),
                        (type, rebuildError) -> LOGGER.warn("[bbs-plugin] unload rollback rebuild failed for {}", type, rebuildError));
                }
                catch (Throwable rollbackError)
                {
                    error.addSuppressed(rollbackError);
                }
            }

            this.recordStatus(pluginId, null, BBSPluginState.FAILED, "UNLOAD_ROUTE", error);
            return;
        }

        this.activeSources.remove(pluginId);

        if (removed == null)
        {
            this.closeProxyRoutes(pluginId);
            this.closeSourcePack(pluginId);
            this.recordStatus(pluginId, null, reason == BBSPluginStopReason.DISABLED ? BBSPluginState.DISABLED : BBSPluginState.LOGICALLY_UNLOADED, "UNLOAD", null);
            return;
        }

        this.retire(pluginId, removed, reason);
        this.closeProxyRoutes(pluginId);
        this.closeSourcePack(pluginId);
        this.refreshClientProjection();
    }

    private void retire(String pluginId, ActivePluginGeneration<PluginRuntimeGeneration> generation, BBSPluginStopReason reason)
    {
        PluginRuntimeGeneration runtime = generation.contributions();

        if (reason != BBSPluginStopReason.RELOAD)
        {
            this.recordStatus(pluginId, runtime.descriptor(), BBSPluginState.DRAINING, "DRAIN", null);
        }

        try
        {
            if (!generation.awaitDrained(DRAIN_TIMEOUT))
            {
                this.restartRequired.add(pluginId);
                this.recordStatus(pluginId, runtime.descriptor(), BBSPluginState.RESTART_REQUIRED, "DRAIN_TIMEOUT", null);
                return;
            }

            runtime.stop(reason);
            generation.retire();

            if (reason != BBSPluginStopReason.RELOAD)
            {
                this.recordStatus(pluginId, runtime.descriptor(), BBSPluginState.LOGICALLY_UNLOADED, "UNLOAD", null);
            }
        }
        catch (Throwable error)
        {
            this.restartRequired.add(pluginId);
            this.recordStatus(pluginId, runtime.descriptor(), BBSPluginState.RESTART_REQUIRED, "TEARDOWN", error);
        }
    }

    private void closeCandidate(PluginRuntimeGeneration candidate)
    {
        try
        {
            candidate.close();
        }
        catch (Throwable error)
        {
            LOGGER.warn("[bbs-plugin] failed to close rejected candidate {}", candidate.owner(), error);
        }
    }

    private void closeProxyRoutes(String pluginId)
    {
        List<PluginEventProxyKey> keys = new ArrayList<>();

        for (PluginEventProxyKey key : this.eventProxies.keySet())
        {
            if (pluginId.equals("<all>") || key.pluginId().equals(pluginId))
            {
                keys.add(key);
            }
        }

        for (PluginEventProxyKey key : keys)
        {
            EventSubscription subscription = this.eventProxies.remove(key);

            if (subscription != null)
            {
                subscription.close();
            }
        }
    }

    private void pruneEventProxies(String pluginId, Set<PluginEventRoute> retained)
    {
        List<PluginEventProxyKey> obsolete = new ArrayList<>();

        for (PluginEventProxyKey key : this.eventProxies.keySet())
        {
            if (key.pluginId().equals(pluginId) && !retained.contains(key.route()))
            {
                obsolete.add(key);
            }
        }

        for (PluginEventProxyKey key : obsolete)
        {
            EventSubscription subscription = this.eventProxies.remove(key);

            if (subscription != null)
            {
                subscription.close();
            }
        }
    }

    private void closeSourcePack(String pluginId)
    {
        AutoCloseable sourcePack = this.sourcePacks.remove(pluginId);

        if (sourcePack != null)
        {
            try
            {
                sourcePack.close();
            }
            catch (Exception error)
            {
                LOGGER.warn("[bbs-plugin] failed to remove source pack for {}", pluginId, error);
            }
        }
    }

    private void shutdownOnLifecycleThread()
    {
        PluginDirectoryWatcher current = this.watcher;

        if (current != null)
        {
            current.close();
            this.watcher = null;
        }

        List<String> ids = new ArrayList<>(this.active.snapshot().generations().keySet());

        for (String pluginId : ids)
        {
            this.unload(pluginId, BBSPluginStopReason.SHUTDOWN);
        }

        for (String pluginId : new ArrayList<>(this.sourcePacks.keySet()))
        {
            this.closeSourcePack(pluginId);
        }

        this.closeProxyRoutes("<all>");
        try
        {
            this.active.close();
        }
        catch (Throwable error)
        {
            LOGGER.error("[bbs-plugin] active generation index close failed", error);
        }
    }

    private BBSPluginDiagnosticSink diagnosticSink(PluginOwner owner)
    {
        return (severity, code, message) ->
            this.recordDiagnostic(owner.pluginId(), owner.generation(), severity, code, message, "");
    }

    private void recordWatcherError(Throwable error)
    {
        LOGGER.warn("[bbs-plugin] plugin directory watcher error; reconciliation will retry", error);
    }

    private synchronized void recordStatus(String pluginId, BBSPluginDescriptor descriptor, BBSPluginState state, String code, Throwable error)
    {
        String version = descriptor == null ? "" : descriptor.version();
        String hash = "";
        long generation = this.nextGenerations.getOrDefault(pluginId, 0L);
        ActivePluginGeneration<PluginRuntimeGeneration> activeGeneration = this.active.active(pluginId);

        if (activeGeneration != null)
        {
            hash = activeGeneration.contributions().artifactHash();
            generation = activeGeneration.owner().generation();
        }

        String message = error == null ? code : safeMessage(error);
        String errorType = error == null ? "" : error.getClass().getName();
        PluginStatus previous = this.statuses.get(pluginId);
        BBSPluginDescriptor effective = descriptor != null ? descriptor : (previous == null ? null : previous.descriptor());

        if (version.isEmpty() && effective != null)
        {
            version = effective.version();
        }

        this.statuses.put(pluginId, new PluginStatus(pluginId, version, generation, hash, state,
            System.currentTimeMillis(), code, message, errorType, effective));
        LOGGER.info("[bbs-plugin] id={} generation={} state={} code={} message={}", pluginId, generation, state, code, message);
    }

    private synchronized void recordDiagnostic(String pluginId, long generation, BBSPluginDiagnosticSeverity severity, String code, String message, String errorType)
    {
        PluginStatus previous = this.statuses.get(pluginId);

        if (previous != null)
        {
            if (generation < previous.generation())
            {
                return;
            }

            this.statuses.put(pluginId, previous.withDiagnostic(new BBSPluginDiagnostic(
                System.currentTimeMillis(), severity, code, message, errorType
            )));
        }
    }

    private synchronized List<PluginStatus> snapshotStatuses()
    {
        return List.copyOf(this.statuses.values());
    }

    private void refreshClientProjection()
    {
        if (!this.physicalClient)
        {
            return;
        }

        try
        {
            Class<?> bridge = Class.forName("mchorse.bbs_mod.plugin.client.BBSPluginClientRefresh", false, BBSPluginManager.class.getClassLoader());
            bridge.getMethod("refresh").invoke(null);
        }
        catch (ClassNotFoundException ignored)
        {
            /* The common source set is also used by dedicated servers. */
        }
        catch (Throwable error)
        {
            LOGGER.warn("[bbs-plugin] client content refresh failed", error);
        }
    }

    private void runStructuralSafepoint(Runnable operation)
    {
        if (!this.physicalClient)
        {
            operation.run();
            return;
        }

        try
        {
            Class<?> bridge = Class.forName(
                "mchorse.bbs_mod.plugin.client.BBSPluginClientStructuralBridge",
                false,
                BBSPluginManager.class.getClassLoader()
            );
            bridge.getMethod("runSafepoint", Runnable.class).invoke(null, operation);
        }
        catch (ClassNotFoundException ignored)
        {
            /* Common-only regression launchers do not include the client source set. */
            operation.run();
        }
        catch (InvocationTargetException error)
        {
            Throwable cause = error.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            if (cause instanceof Error fatal)
            {
                throw fatal;
            }

            throw new IllegalStateException("client structural safepoint failed", cause);
        }
        catch (ReflectiveOperationException error)
        {
            throw new IllegalStateException("client structural safepoint bridge is unavailable", error);
        }
    }

    private boolean isStructuralReloadBusy()
    {
        if (!this.physicalClient)
        {
            return false;
        }

        try
        {
            Class<?> bridge = Class.forName(
                "mchorse.bbs_mod.plugin.client.BBSPluginClientStructuralBridge",
                false,
                BBSPluginManager.class.getClassLoader()
            );

            return (boolean) bridge.getMethod("isBusy").invoke(null);
        }
        catch (ClassNotFoundException ignored)
        {
            return false;
        }
        catch (ReflectiveOperationException error)
        {
            throw new IllegalStateException("client structural busy guard failed", error);
        }
    }

    private Object createClientExtension(
        Class<?> extensionType,
        BBSPluginDescriptor descriptor,
        PluginOwner owner,
        PluginContributionLedger ledger,
        PluginStructuralRegistrationWindow structuralRegistrations,
        BBSPluginDiagnosticSink diagnostics
    )
    {
        if (!this.physicalClient)
        {
            return null;
        }

        try
        {
            Class<?> bridge = Class.forName(
                "mchorse.bbs_mod.plugin.client.BBSPluginClientStructuralBridge",
                false,
                BBSPluginManager.class.getClassLoader()
            );

            return bridge.getMethod(
                "createExtension",
                Class.class,
                BBSPluginDescriptor.class,
                PluginOwner.class,
                PluginContributionLedger.class,
                PluginStructuralRegistrationWindow.class,
                BBSPluginDiagnosticSink.class
            ).invoke(null, extensionType, descriptor, owner, ledger, structuralRegistrations, diagnostics);
        }
        catch (ClassNotFoundException ignored)
        {
            return null;
        }
        catch (InvocationTargetException error)
        {
            Throwable cause = error.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }

            throw new IllegalStateException("client plugin context extension failed", cause);
        }
        catch (ReflectiveOperationException error)
        {
            throw new IllegalStateException("client plugin context extension bridge is unavailable", error);
        }
    }

    private static Path requireDirectory(Path path, String name)
    {
        if (path == null)
        {
            throw new IllegalArgumentException(name + " is null");
        }

        return path.toAbsolutePath().normalize();
    }

    private static String safeMessage(Throwable error)
    {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String intentKey(PluginWatchIntent intent)
    {
        return intent.artifactPath()
            .map((path) -> path.getFileName().toString())
            .orElse("<reconcile>");
    }

    public record PluginStatus(String pluginId, String version, long generation, String sha256,
                               BBSPluginState state, long lastTransitionMillis, String lastCode,
                               String lastMessage, String lastErrorType, BBSPluginDescriptor descriptor)
    {
        private PluginStatus withDiagnostic(BBSPluginDiagnostic diagnostic)
        {
            return new PluginStatus(this.pluginId, this.version, this.generation, this.sha256,
                this.state, diagnostic.timestampMillis(), diagnostic.code(), diagnostic.message(), diagnostic.errorType(), this.descriptor);
        }
    }

    private record PluginEventProxyKey(String pluginId, PluginEventRoute route) {}

}
