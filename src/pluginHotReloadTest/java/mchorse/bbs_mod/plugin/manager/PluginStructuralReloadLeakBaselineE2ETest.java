package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.plugin.BBSPluginState;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.particles.ParticleParser;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Acceptance #7: after many alternating structural-capability generations, every host
 * registration face must return to its pre-test baseline - no plugin {@code Class} left
 * registered anywhere, and the retired generation classloaders must be collectible.
 *
 * <p>Uses the same bare-{@code JavaExec} bootstrap and fixture-jar mechanics as {@link
 * PluginStructuralCapabilitiesE2ETest} (see that class for why this is necessary and why
 * the client-only capabilities are out of scope for this launcher).</p>
 */
public final class PluginStructuralReloadLeakBaselineE2ETest
{
    private static final String PLUGIN_ID = "leakfixture";
    private static final String PARTICLE_ID = "leak_fixture_component";
    private static final Link FORM_LINK = Link.create("leak-fixture:widget");
    private static final Link CAMERA_CLIP_LINK = Link.create("leak-fixture:camera_widget");
    private static final Link ACTION_CLIP_LINK = Link.create("leak-fixture:action_widget");

    /**
     * The acceptance criterion asks for 100 alternations. Each alternation is a real
     * install + a directory rescan + an await on the manager's lifecycle executor, so the
     * wall-clock cost is dominated by that round trip rather than by compilation (the two
     * fixture jars are built once, up front, and just copied in on each iteration).
     */
    private static final int ITERATIONS = 100;

    private PluginStructuralReloadLeakBaselineE2ETest() {}

    public static void main(String[] args) throws Exception
    {
        bootstrapStandaloneMinecraftRuntime();
        bootstrapHostRegistries();

        Path root = Files.createTempDirectory("bbs-structural-leak-");
        EventBus eventBus = new EventBus();
        AssetProvider assets = new AssetProvider();
        BBSPluginManager manager = new BBSPluginManager(root, false, eventBus, assets);
        ParticleParser particleParser = new ParticleParser();

        /* ParticleParser.refreshApi2Components() resolves hot-plugin components through
         * BBSMod.getAddonParticleComponentClasses(), which reads the static
         * BBSMod.activePluginManager field rather than any manager instance directly - the
         * real client wiring sets it from onCommonSetup, which is out of reach here. */
        setActivePluginManager(manager);

        try
        {
            manager.start();
            Path directory = root.resolve("config/bbs/plugins");
            await(() -> Files.isDirectory(directory), 3_000L, "plugin directory was not created");
            Path artifact = directory.resolve(PLUGIN_ID + ".jar");

            FormArchitect forms = BBSMod.getForms();
            MapFactory<Clip, ?> cameraClips = BBSMod.getFactoryCameraClips();
            MapFactory<Clip, ?> actionClips = BBSMod.getFactoryActionClips();

            int formsBaseline = forms.getKeys().size();
            int cameraBaseline = cameraClips.getKeys().size();
            int actionBaseline = actionClips.getKeys().size();
            int particleComponentsBaseline = manager.particleComponents().size();
            particleParser.refreshApi2Components();
            check(!particleParser.components.containsKey(PARTICLE_ID), "particle parser already knows the fixture id before install");
            check(!registeredApi2(particleParser).containsKey(PARTICLE_ID), "particle parser registered-table already knows the fixture id before install");
            check(!failedApi2(particleParser).containsKey(PARTICLE_ID), "particle parser failed-table already knows the fixture id before install");

            Path v1 = buildArtifact(root, "1.0.0", "v1");
            Path v2 = buildArtifact(root, "2.0.0", "v2");

            ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
            WeakReference<ClassLoader> firstGenerationLoader = null;
            long start = System.nanoTime();

            for (int i = 0; i < ITERATIONS; i += 1)
            {
                boolean odd = i % 2 == 1;
                Path source = odd ? v2 : v1;
                String version = odd ? "2.0.0" : "1.0.0";

                install(source, artifact);
                manager.rescan();
                int iteration = i;
                await(() -> active(manager, version), 5_000L, "iteration " + iteration + " did not become active (" + version + ")");

                if (i == 0)
                {
                    Class<? extends mchorse.bbs_mod.forms.forms.Form> formType = forms.getTypeClass(FORM_LINK);
                    check(formType != null, "first generation did not register the form type");
                    firstGenerationLoader = new WeakReference<>(formType.getClassLoader(), queue);

                    particleParser.refreshApi2Components();
                    check(particleParser.components.containsKey(PARTICLE_ID), "particle parser did not pick up the first generation's component");
                }
            }

            long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
            System.out.println("PluginStructuralReloadLeakBaselineE2ETest: " + ITERATIONS
                + " alternating 1.0/2.0 reloads took " + elapsedMillis + "ms");

            Files.delete(artifact);
            await(() -> state(manager) == BBSPluginState.LOGICALLY_UNLOADED, 5_000L, "final delete did not unload the fixture");

            check(forms.getKeys().size() == formsBaseline, "form registry did not return to baseline");
            check(cameraClips.getKeys().size() == cameraBaseline, "camera clip registry did not return to baseline");
            check(actionClips.getKeys().size() == actionBaseline, "action clip registry did not return to baseline");
            check(forms.getTypeClass(FORM_LINK) == null, "form registry left the fixture type registered");
            check(cameraClips.getTypeClass(CAMERA_CLIP_LINK) == null, "camera clip registry left the fixture type registered");
            check(actionClips.getTypeClass(ACTION_CLIP_LINK) == null, "action clip registry left the fixture type registered");
            check(manager.particleComponents().size() == particleComponentsBaseline, "particle component registry did not return to baseline");

            particleParser.refreshApi2Components();
            check(!particleParser.components.containsKey(PARTICLE_ID), "particle parser left the fixture component registered");
            check(!registeredApi2(particleParser).containsKey(PARTICLE_ID), "particle parser registered-table left the fixture component behind");
            check(!failedApi2(particleParser).containsKey(PARTICLE_ID), "particle parser failed-table left the fixture component behind");

            boolean collected = observeCollection(firstGenerationLoader, queue, java.time.Duration.ofSeconds(5));
            check(collected, "the first generation's classloader was not collectible after " + ITERATIONS + " alternations");

            System.out.println("PluginStructuralReloadLeakBaselineE2ETest: registration faces, particle tables and classloaders all returned to baseline");
        }
        finally
        {
            manager.close();
            setActivePluginManager(null);
            deleteTree(root);
        }
    }

    private static void setActivePluginManager(BBSPluginManager manager) throws ReflectiveOperationException
    {
        Field field = BBSMod.class.getDeclaredField("activePluginManager");
        field.setAccessible(true);
        field.set(null, manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> registeredApi2(ParticleParser parser) throws ReflectiveOperationException
    {
        Field field = ParticleParser.class.getDeclaredField("registeredApi2ComponentClasses");
        field.setAccessible(true);

        return (Map<String, ?>) field.get(parser);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> failedApi2(ParticleParser parser) throws ReflectiveOperationException
    {
        Field field = ParticleParser.class.getDeclaredField("failedApi2ComponentClasses");
        field.setAccessible(true);

        return (Map<String, ?>) field.get(parser);
    }

    private static boolean observeCollection(WeakReference<ClassLoader> reference, ReferenceQueue<ClassLoader> queue, java.time.Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline)
        {
            if (reference.get() == null || queue.poll() == reference)
            {
                return true;
            }

            System.gc();
            Thread.sleep(50L);
        }

        return reference.get() == null;
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    private static void bootstrapHostRegistries() throws ReflectiveOperationException
    {
        seedStaticIfNull("forms", new FormArchitect());
        seedStaticIfNull("factoryCameraClips", new MapFactory<Clip, mchorse.bbs_mod.camera.clips.ClipFactoryData>());
        seedStaticIfNull("factoryActionClips", new MapFactory<Clip, mchorse.bbs_mod.camera.clips.ClipFactoryData>());
    }

    private static void seedStaticIfNull(String fieldName, Object value) throws ReflectiveOperationException
    {
        Field field = BBSMod.class.getDeclaredField(fieldName);
        field.setAccessible(true);

        if (field.get(null) == null)
        {
            field.set(null, value);
        }
    }

    private static boolean active(BBSPluginManager manager, String version)
    {
        return manager.diagnostics().stream().anyMatch((status) ->
            PLUGIN_ID.equals(status.pluginId())
                && version.equals(status.version())
                && status.state() == BBSPluginState.ACTIVE
        );
    }

    private static BBSPluginState state(BBSPluginManager manager)
    {
        return manager.diagnostics().stream()
            .filter((status) -> PLUGIN_ID.equals(status.pluginId()))
            .map(BBSPluginManager.PluginStatus::state)
            .findFirst()
            .orElse(null);
    }

    private static void install(Path source, Path destination) throws IOException
    {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);

        try
        {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported)
        {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path buildArtifact(Path root, String version, String variant) throws Exception
    {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("leakfixture/CommonPlugin.java", COMMON_PLUGIN_SOURCE);
        sources.put("leakfixture/LeakForm.java", LEAK_FORM_SOURCE.formatted(variant));
        sources.put("leakfixture/LeakCameraClip.java", LEAK_CAMERA_CLIP_SOURCE.formatted(variant));
        sources.put("leakfixture/LeakActionClip.java", LEAK_ACTION_CLIP_SOURCE.formatted(variant));
        sources.put("leakfixture/LeakParticleComponent.java", LEAK_PARTICLE_COMPONENT_SOURCE.formatted(variant));

        Map<String, byte[]> classes = compile(root.resolve("compile-" + version + "-" + System.nanoTime()), sources);
        String manifest = "{\"schema\":1,\"kind\":\"code\",\"id\":\"" + PLUGIN_ID + "\",\"version\":\""
            + version + "\",\"commonEntrypoint\":\"leakfixture.CommonPlugin\","
            + "\"api\":\"[1.0,2.0)\",\"side\":\"common\","
            + "\"capabilities\":[\"forms\",\"clips\",\"particles\"],"
            + "\"dependencies\":[],\"reload\":\"hot\"}";

        return jar(root.resolve(PLUGIN_ID + "-" + version + "-" + System.nanoTime() + ".jar"), manifest, classes);
    }

    private static Map<String, byte[]> compile(Path root, Map<String, String> sources) throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null)
        {
            throw new IllegalStateException("JDK compiler is required for the leak fixture");
        }

        Path sourceRoot = root.resolve("sources");
        Path classesRoot = root.resolve("classes");
        List<Path> sourceFiles = new ArrayList<>();

        for (Map.Entry<String, String> entry : sources.entrySet())
        {
            Path sourceFile = sourceRoot.resolve(entry.getKey());
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(sourceFile);
        }

        Files.createDirectories(classesRoot);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8))
        {
            List<String> options = List.of(
                "--release", "21",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesRoot.toString()
            );
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sourceFiles);
            boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics, options, null, units).call());

            if (!compiled)
            {
                StringBuilder message = new StringBuilder("leak fixture compilation failed:");

                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
                {
                    message.append(System.lineSeparator()).append(diagnostic);
                }

                throw new AssertionError(message.toString());
            }
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();

        try (var files = Files.walk(classesRoot))
        {
            files.filter((path) -> path.toString().endsWith(".class")).forEach((path) ->
            {
                try
                {
                    classes.put(classesRoot.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
                catch (IOException error)
                {
                    throw new RuntimeException(error);
                }
            });
        }

        return classes;
    }

    private static Path jar(Path path, String manifestJson, Map<String, byte[]> entries) throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (var output = Files.newOutputStream(path);
             var jar = new JarOutputStream(output, manifest))
        {
            put(jar, "META-INF/bbs-plugin.json", manifestJson.getBytes(StandardCharsets.UTF_8));

            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                put(jar, entry.getKey(), entry.getValue());
            }
        }

        return path;
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws IOException
    {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void await(Check condition, long timeoutMillis, String message) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (!condition.ok())
        {
            if (System.nanoTime() >= deadline)
            {
                throw new AssertionError(message);
            }

            Thread.sleep(20L);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach((path) ->
            {
                try
                {
                    Files.deleteIfExists(path);
                }
                catch (IOException error)
                {
                    throw new RuntimeException(error);
                }
            });
        }
    }

    @FunctionalInterface
    private interface Check
    {
        boolean ok() throws Exception;
    }

    private static final String COMMON_PLUGIN_SOURCE = """
        package leakfixture;

        import mchorse.bbs_mod.api.plugin.BBSPlugin;
        import mchorse.bbs_mod.api.plugin.BBSPluginContext;
        import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
        import mchorse.bbs_mod.camera.clips.ClipFactoryData;
        import mchorse.bbs_mod.resources.Link;
        import mchorse.bbs_mod.ui.utils.icons.Icons;

        public final class CommonPlugin implements BBSPlugin
        {
            @Override
            public void prepare(BBSPluginContext context)
            {
                BBSRegistrationResult form = context.forms().register(
                    Link.create("leak-fixture:widget"), LeakForm.class);

                if (!form.accepted())
                {
                    throw new IllegalStateException("form registration rejected: " + form);
                }

                BBSRegistrationResult camera = context.clips().registerCameraClip(
                    Link.create("leak-fixture:camera_widget"), LeakCameraClip.class,
                    new ClipFactoryData(Icons.CAMERA, 0xff00ff));

                if (!camera.accepted())
                {
                    throw new IllegalStateException("camera clip registration rejected: " + camera);
                }

                BBSRegistrationResult action = context.clips().registerActionClip(
                    Link.create("leak-fixture:action_widget"), LeakActionClip.class,
                    new ClipFactoryData(Icons.CAMERA, 0x00ff00));

                if (!action.accepted())
                {
                    throw new IllegalStateException("action clip registration rejected: " + action);
                }

                BBSRegistrationResult particle = context.particles().registerComponent(
                    "leak_fixture_component", "leakfixture.LeakParticleComponent");

                if (!particle.accepted())
                {
                    throw new IllegalStateException("particle registration rejected: " + particle);
                }
            }
        }
        """;

    private static final String LEAK_FORM_SOURCE = """
        package leakfixture;

        import mchorse.bbs_mod.forms.forms.Form;

        public final class LeakForm extends Form
        {
            public String variantMarker()
            {
                return "%s";
            }
        }
        """;

    private static final String LEAK_CAMERA_CLIP_SOURCE = """
        package leakfixture;

        import mchorse.bbs_mod.utils.clips.Clip;

        public final class LeakCameraClip extends Clip
        {
            public String variantMarker()
            {
                return "%s";
            }

            @Override
            protected Clip create()
            {
                return new LeakCameraClip();
            }
        }
        """;

    private static final String LEAK_ACTION_CLIP_SOURCE = """
        package leakfixture;

        import mchorse.bbs_mod.utils.clips.Clip;

        public final class LeakActionClip extends Clip
        {
            public String variantMarker()
            {
                return "%s";
            }

            @Override
            protected Clip create()
            {
                return new LeakActionClip();
            }
        }
        """;

    private static final String LEAK_PARTICLE_COMPONENT_SOURCE = """
        package leakfixture;

        import mchorse.bbs_mod.particles.components.ParticleComponentBase;

        public final class LeakParticleComponent extends ParticleComponentBase
        {
            public String variantMarker()
            {
                return "%s";
            }
        }
        """;
}
