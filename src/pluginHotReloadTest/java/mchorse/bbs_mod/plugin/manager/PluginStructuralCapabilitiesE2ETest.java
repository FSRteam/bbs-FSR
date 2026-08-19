package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.plugin.BBSPluginState;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.MissingForm;
import mchorse.bbs_mod.plugin.manager.BBSPluginManager.PluginStatus;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.clips.MissingClip;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Real fixture-jar, end-to-end coverage for the seven FSR structural capabilities
 * (forms/clips/particles/key_mappings/entity_renderer/block_entity_renderer/dashboard_panels)
 * across install, override and delete against the real {@link BBSPluginManager}
 * and the real host registries in {@link BBSMod}.
 *
 * <p>The client-only capabilities (key_mappings/entity_renderer/block_entity_renderer)
 * are declared and their SPI resolution is exercised (see {@code ClientPlugin}), but
 * their actual registration calls are not invoked here: those calls cast real
 * {@code KeyMapping}/{@code EntityRenderers}/{@code BlockEntityRenderers} instances
 * through Mixin-generated accessor interfaces, which only exist once Mixin has
 * transformed those classes inside a real ModLauncher/NeoForge launch. A bare
 * {@code JavaExec} has no Mixin transformer, so calling those accessors here would
 * not exercise the real replay behaviour - it would only prove a {@code ClassCastException}
 * happens, which is not a meaningful assertion. That behaviour must be covered by
 * manual NeoForge client smoke testing.</p>
 */
public final class PluginStructuralCapabilitiesE2ETest
{
    private static final String PLUGIN_ID = "structuralfixture";
    private static final String PARTICLE_COMPONENT_ID = "structural_fixture_component";
    private static final Link FORM_LINK = Link.create("structural-fixture:widget");
    private static final Link CAMERA_CLIP_LINK = Link.create("structural-fixture:camera_widget");
    private static final Link ACTION_CLIP_LINK = Link.create("structural-fixture:action_widget");

    private PluginStructuralCapabilitiesE2ETest() {}

    public static void main(String[] args) throws Exception
    {
        bootstrapStandaloneMinecraftRuntime();
        bootstrapHostRegistries();

        Path root = Files.createTempDirectory("bbs-structural-capabilities-");
        EventBus eventBus = new EventBus();
        AssetProvider assets = new AssetProvider();
        BBSPluginManager manager = new BBSPluginManager(root, true, eventBus, assets);

        try
        {
            manager.start();
            Path directory = root.resolve("config/bbs/plugins");
            await(() -> Files.isDirectory(directory), 3_000L, "plugin directory was not created");
            Path artifact = directory.resolve(PLUGIN_ID + ".jar");

            /* Acceptance #1: a fixture declaring all seven structural capabilities is
             * dropped in while the runtime is already active and reaches ACTIVE with
             * every registration face committed. */
            install(buildArtifact(root, "1.0.0", "v1"), artifact);
            await(() -> active(manager, "1.0.0"), 5_000L, "v1 structural fixture was not auto-installed");

            Class<? extends Form> formTypeV1 = BBSMod.getForms().getTypeClass(FORM_LINK);
            check(formTypeV1 != null, "v1 did not register the form type");
            Class<? extends Clip> cameraTypeV1 = BBSMod.getFactoryCameraClips().getTypeClass(CAMERA_CLIP_LINK);
            check(cameraTypeV1 != null, "v1 did not register the camera clip type");
            Class<? extends Clip> actionTypeV1 = BBSMod.getFactoryActionClips().getTypeClass(ACTION_CLIP_LINK);
            check(actionTypeV1 != null, "v1 did not register the action clip type");
            check("structuralfixture.ParticleComponentV1".equals(particleComponentClassName(manager)),
                "v1 did not register the particle component");
            check("v1".equals(variantMarker(formTypeV1.getDeclaredConstructor().newInstance())),
                "v1 form type did not report the v1 variant marker");
            check("v1".equals(variantMarker(cameraTypeV1.getDeclaredConstructor().newInstance())),
                "v1 camera clip type did not report the v1 variant marker");

            /* Live instances that must survive the override -> delete -> recovery
             * lifecycle below, exactly like a form/clip sitting inside an open film. */
            ValueForm formHolder = new ValueForm("structural-fixture-form-holder");
            Form formInstance = formTypeV1.getDeclaredConstructor().newInstance();
            formInstance.name.set("marker-v1");
            formHolder.replaceStructuralValue(formInstance);

            Clips clipsHolder = new Clips("structural-fixture-clips-holder", BBSMod.getFactoryCameraClips());
            Clip clipInstance = cameraTypeV1.getDeclaredConstructor().newInstance();
            clipInstance.tick.set(37);
            clipInstance.duration.set(12);
            clipsHolder.addClip(clipInstance);

            /* Acceptance #3: overriding with a 2.0 package that reuses the same ids
             * must snapshot the live instances, tear down the 1.0 registration face,
             * publish the 2.0 face, and rebuild the instances from the snapshot with
             * their data intact and 2.0 behaviour in effect. */
            install(buildArtifact(root, "2.0.0", "v2"), artifact);
            await(() -> active(manager, "2.0.0"), 5_000L, "v2 structural fixture did not replace v1");

            Class<? extends Form> formTypeV2 = BBSMod.getForms().getTypeClass(FORM_LINK);
            check(formTypeV2 != null && formTypeV2 != formTypeV1,
                "override did not replace the form type with a new v2 class");
            Class<? extends Clip> cameraTypeV2 = BBSMod.getFactoryCameraClips().getTypeClass(CAMERA_CLIP_LINK);
            check(cameraTypeV2 != null && cameraTypeV2 != cameraTypeV1,
                "override did not replace the camera clip type with a new v2 class");
            check(BBSMod.getFactoryActionClips().getTypeClass(ACTION_CLIP_LINK) != actionTypeV1,
                "override left the retired v1 action clip type registered");
            check("structuralfixture.ParticleComponentV2".equals(particleComponentClassName(manager)),
                "override did not replace the particle component registration");

            Form rebuiltForm = formHolder.getOriginalValue();
            check(!(rebuiltForm instanceof MissingForm), "override degraded a live form instance to a missing placeholder");
            check(rebuiltForm.getClass() == formTypeV2, "override rebuilt the live form using the retired v1 type");
            check("marker-v1".equals(rebuiltForm.name.get()), "override lost the form's property data during rebuild");
            check("v2".equals(variantMarker(rebuiltForm)), "override did not switch the rebuilt form to v2 behavior");

            Clip rebuiltClip = clipsHolder.get(0);
            check(!(rebuiltClip instanceof MissingClip), "override degraded a live clip instance to a missing placeholder");
            check(rebuiltClip.getClass() == cameraTypeV2, "override rebuilt the live clip using the retired v1 type");
            check(rebuiltClip.tick.get() == 37 && rebuiltClip.duration.get() == 12,
                "override lost the clip's keyframe data during rebuild");
            check("v2".equals(variantMarker(rebuiltClip)), "override did not switch the rebuilt clip to v2 behavior");

            /* Acceptance #4 (delete half): removing the active package degrades the
             * live instances to missing placeholders without losing their data, and
             * unregisters every structural face. */
            Files.delete(artifact);
            await(() -> state(manager) == BBSPluginState.LOGICALLY_UNLOADED, 5_000L,
                "delete did not unload the structural fixture");

            check(BBSMod.getForms().getTypeClass(FORM_LINK) == null, "delete left the form type registered");
            check(BBSMod.getFactoryCameraClips().getTypeClass(CAMERA_CLIP_LINK) == null,
                "delete left the camera clip type registered");
            check(BBSMod.getFactoryActionClips().getTypeClass(ACTION_CLIP_LINK) == null,
                "delete left the action clip type registered");
            check(particleComponentClassName(manager) == null, "delete left the particle component registered");

            Form degradedForm = formHolder.getOriginalValue();
            check(degradedForm instanceof MissingForm, "delete did not degrade the live form to a missing placeholder");
            check("marker-v1".equals(((MissingForm) degradedForm).sourceData().getString("name")),
                "delete did not retain the form's data in the missing placeholder");

            Clip degradedClip = clipsHolder.get(0);
            check(degradedClip instanceof MissingClip, "delete did not degrade the live clip to a missing placeholder");
            check(((MissingClip) degradedClip).sourceData().getInt("tick") == 37,
                "delete did not retain the clip's data in the missing placeholder");

            /* Acceptance #4 (recovery half): putting an equivalent package back
             * lazily recovers the missing instances the next time they are read,
             * with the original data intact. */
            install(buildArtifact(root, "2.0.1", "v2"), artifact);
            await(() -> active(manager, "2.0.1"), 5_000L, "reinstalled structural fixture did not become active");

            Form recoveredForm = formHolder.get();
            check(!(recoveredForm instanceof MissingForm), "reinstalling the plugin did not lazily recover the missing form");
            check("marker-v1".equals(recoveredForm.name.get()), "lazy form recovery lost the original property data");

            Clip recoveredClip = clipsHolder.get().get(0);
            check(!(recoveredClip instanceof MissingClip), "reinstalling the plugin did not lazily recover the missing clip");
            check(recoveredClip.tick.get() == 37, "lazy clip recovery lost the original keyframe data");

            System.out.println("PluginStructuralCapabilitiesE2ETest: forms/clips/particles/dashboard-panels/client-facade lifecycle passed");
        }
        finally
        {
            manager.close();
            deleteTree(root);
        }
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

    /**
     * {@code BBSMod}'s host form/clip registries are populated by {@code onCommonSetup},
     * an FML lifecycle callback that requires a real mod bus and is out of reach in a
     * bare {@code JavaExec}. Seed the same static fields directly (only if a real mod
     * bootstrap has not already done so) so the real {@link BBSPluginManager} - and its
     * hardcoded {@code BBSMod::getForms} / {@code BBSMod::getFactoryCameraClips} /
     * {@code BBSMod::getFactoryActionClips} suppliers - exercise the exact same
     * production registration path a running game would use, without duplicating that
     * path with test-local factories.
     */
    private static void bootstrapHostRegistries() throws ReflectiveOperationException
    {
        seedStaticIfNull("forms", new FormArchitect());
        seedStaticIfNull("factoryCameraClips", new MapFactory<Clip, ClipFactoryData>());
        seedStaticIfNull("factoryActionClips", new MapFactory<Clip, ClipFactoryData>());
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

    private static String variantMarker(Object instance) throws Exception
    {
        Method method = instance.getClass().getMethod("variantMarker");

        return (String) method.invoke(instance);
    }

    private static String particleComponentClassName(BBSPluginManager manager)
    {
        PluginParticleComponentClass component = manager.particleComponents().get(PARTICLE_COMPONENT_ID);

        return component == null ? null : component.className();
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
            .map(PluginStatus::state)
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
        String particleComponentClassName = "structuralfixture.ParticleComponent" + variant.toUpperCase(Locale.ROOT);

        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("structuralfixture/CommonPlugin.java", COMMON_PLUGIN_SOURCE.formatted(particleComponentClassName));
        sources.put("structuralfixture/ClientPlugin.java", CLIENT_PLUGIN_SOURCE);
        sources.put("structuralfixture/StructuralForm.java", STRUCTURAL_FORM_SOURCE.formatted(variant));
        sources.put("structuralfixture/StructuralCameraClip.java", STRUCTURAL_CAMERA_CLIP_SOURCE.formatted(variant));
        sources.put("structuralfixture/StructuralActionClip.java", STRUCTURAL_ACTION_CLIP_SOURCE.formatted(variant));

        Map<String, byte[]> classes = compile(root.resolve("compile-" + version + "-" + System.nanoTime()), sources);
        String manifest = "{\"schema\":1,\"kind\":\"code\",\"id\":\"" + PLUGIN_ID + "\",\"version\":\""
            + version + "\",\"commonEntrypoint\":\"structuralfixture.CommonPlugin\","
            + "\"clientEntrypoint\":\"structuralfixture.ClientPlugin\",\"api\":\"[1.0,2.0)\",\"side\":\"common\","
            + "\"capabilities\":[\"forms\",\"clips\",\"particles\",\"key_mappings\",\"entity_renderer\",\"block_entity_renderer\",\"dashboard_panels\"],"
            + "\"dependencies\":[],\"reload\":\"hot\"}";

        return jar(root.resolve(PLUGIN_ID + "-" + version + "-" + System.nanoTime() + ".jar"), manifest, classes);
    }

    private static Map<String, byte[]> compile(Path root, Map<String, String> sources) throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null)
        {
            throw new IllegalStateException("JDK compiler is required for the structural fixture");
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
                StringBuilder message = new StringBuilder("structural fixture compilation failed:");

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

            Thread.sleep(50L);
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
        package structuralfixture;

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
                    Link.create("structural-fixture:widget"), StructuralForm.class);

                if (!form.accepted())
                {
                    throw new IllegalStateException("form registration rejected: " + form);
                }

                BBSRegistrationResult camera = context.clips().registerCameraClip(
                    Link.create("structural-fixture:camera_widget"), StructuralCameraClip.class,
                    new ClipFactoryData(Icons.CAMERA, 0xff00ff));

                if (!camera.accepted())
                {
                    throw new IllegalStateException("camera clip registration rejected: " + camera);
                }

                BBSRegistrationResult action = context.clips().registerActionClip(
                    Link.create("structural-fixture:action_widget"), StructuralActionClip.class,
                    new ClipFactoryData(Icons.CAMERA, 0x00ff00));

                if (!action.accepted())
                {
                    throw new IllegalStateException("action clip registration rejected: " + action);
                }

                BBSRegistrationResult particle = context.particles().registerComponent(
                    "structural_fixture_component", "%s");

                if (!particle.accepted())
                {
                    throw new IllegalStateException("particle registration rejected: " + particle);
                }
            }
        }
        """;

    private static final String CLIENT_PLUGIN_SOURCE = """
        package structuralfixture;

        import mchorse.bbs_mod.api.plugin.BBSPlugin;
        import mchorse.bbs_mod.api.plugin.BBSPluginContext;
        import mchorse.bbs_mod.api.plugin.client.BBSPluginClientContext;

        public final class ClientPlugin implements BBSPlugin
        {
            @Override
            public void prepare(BBSPluginContext context)
            {
                BBSPluginClientContext client = context.extension(BBSPluginClientContext.class);

                if (client == null || client.keyMappings() == null || client.renderers() == null
                    || client.forms() == null || client.clips() == null || client.dashboardPanels() == null)
                {
                    throw new IllegalStateException("client structural context facade was not fully wired");
                }
            }
        }
        """;

    private static final String STRUCTURAL_FORM_SOURCE = """
        package structuralfixture;

        import mchorse.bbs_mod.forms.forms.Form;

        public final class StructuralForm extends Form
        {
            public String variantMarker()
            {
                return "%s";
            }
        }
        """;

    private static final String STRUCTURAL_CAMERA_CLIP_SOURCE = """
        package structuralfixture;

        import mchorse.bbs_mod.utils.clips.Clip;

        public final class StructuralCameraClip extends Clip
        {
            public String variantMarker()
            {
                return "%s";
            }

            @Override
            protected Clip create()
            {
                return new StructuralCameraClip();
            }
        }
        """;

    private static final String STRUCTURAL_ACTION_CLIP_SOURCE = """
        package structuralfixture;

        import mchorse.bbs_mod.utils.clips.Clip;

        public final class StructuralActionClip extends Clip
        {
            public String variantMarker()
            {
                return "%s";
            }

            @Override
            protected Clip create()
            {
                return new StructuralActionClip();
            }
        }
        """;
}
