package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.plugin.manager.BBSPluginManager.PluginStatus;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;
import mchorse.bbs_mod.plugin.watch.PluginArtifactFingerprint;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Real drop-in, replace, content-refresh and delete coverage for BBSPluginManager. */
public final class BBSPluginManagerIntegrationTest
{
    private BBSPluginManagerIntegrationTest() {}

    public static void main(String[] args) throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-manager-");
        EventBus eventBus = new EventBus();
        AssetProvider assets = new AssetProvider();
        BBSPluginManager manager = new BBSPluginManager(root, false, eventBus, assets);

        try
        {
            manager.start();
            Path directory = root.resolve("config/bbs/plugins");
            await(() -> Files.isDirectory(directory), 3_000L, "plugin directory was not created");

            Path v1 = buildCodeArtifact(root, "1.0.0", "v1", false);
            install(v1, directory.resolve("fixture.jar"));
            await(() -> active(manager, "fixture", "1.0.0"), 5_000L, "v1 was not auto-installed");

            AtomicReference<String> observed = new AtomicReference<>();
            eventBus.post(new ManagerProbeEvent(observed));
            check("v1".equals(observed.get()), "v1 event callback did not route through the active generation");

            Path v2 = buildCodeArtifact(root, "2.0.0", "v2", false);
            install(v2, directory.resolve("fixture.jar"));
            await(() -> active(manager, "fixture", "2.0.0"), 5_000L, "v2 was not auto-reloaded");
            observed.set(null);
            eventBus.post(new ManagerProbeEvent(observed));
            check("v2".equals(observed.get()), "old generation callback remained active after replacement");

            PluginStatus beforeForcedReload = currentStatus(manager, "fixture");
            check(beforeForcedReload != null && beforeForcedReload.state() == mchorse.bbs_mod.api.plugin.BBSPluginState.ACTIVE,
                "fixture must be active before forced same-hash reload");
            manager.reload("fixture");
            await(() ->
            {
                PluginStatus current = currentStatus(manager, "fixture");

                return current != null
                    && current.state() == mchorse.bbs_mod.api.plugin.BBSPluginState.ACTIVE
                    && current.generation() > beforeForcedReload.generation()
                    && current.sha256().equals(beforeForcedReload.sha256());
            }, 5_000L, "explicit reload did not create a new generation for an unchanged artifact");
            observed.set(null);
            eventBus.post(new ManagerProbeEvent(observed));
            check("v2".equals(observed.get()), "same-hash reload did not rebuild the active callback");

            Path invalid = buildCodeArtifact(root, "3.0.0", "v3", true);
            install(invalid, directory.resolve("fixture.jar"));
            await(() -> hasFailure(manager, "fixture", "3.0.0", "PREPARE"), 5_000L, "failed candidate was not diagnosed");
            observed.set(null);
            eventBus.post(new ManagerProbeEvent(observed));
            check("v2".equals(observed.get()), "failed candidate displaced the incumbent");

            Files.delete(directory.resolve("fixture.jar"));
            await(() -> state(manager, "fixture") == mchorse.bbs_mod.api.plugin.BBSPluginState.LOGICALLY_UNLOADED,
                5_000L, "delete did not unload the active plugin");
            observed.set(null);
            eventBus.post(new ManagerProbeEvent(observed));
            check(observed.get() == null, "deleted plugin still received an event");

            Path contentV1 = buildContentArtifact(root, "1.0.0", "content-v1");
            install(contentV1, directory.resolve("contentfixture.jar"));
            await(() -> active(manager, "contentfixture", "1.0.0"), 5_000L, "content plugin was not auto-installed");
            check("content-v1".equals(readAsset(assets)), "content snapshot was not projected into AssetProvider");

            Path contentV2 = buildContentArtifact(root, "2.0.0", "content-v2");
            install(contentV2, directory.resolve("contentfixture.jar"));
            await(() -> active(manager, "contentfixture", "2.0.0"), 5_000L, "content plugin was not auto-reloaded");
            check("content-v2".equals(readAsset(assets)), "content replacement did not refresh the host source proxy");

            Path clientOnlyContent = buildContentArtifact(
                root,
                "1.0.0",
                "client-only-content",
                "clientcontentfixture",
                "client"
            );
            install(clientOnlyContent, directory.resolve("clientcontentfixture.jar"));
            await(() -> hasFailure(manager, "clientcontentfixture", "1.0.0", "PREPARE"),
                5_000L, "dedicated-server manager accepted a client-only content plugin");
            PluginStatus clientOnlyStatus = currentStatus(manager, "clientcontentfixture");
            check(clientOnlyStatus != null
                    && clientOnlyStatus.lastMessage().contains("client-only plugin cannot load"),
                "client-only content rejection did not preserve its diagnostic");

            Files.delete(directory.resolve("contentfixture.jar"));
            await(() -> state(manager, "contentfixture") == mchorse.bbs_mod.api.plugin.BBSPluginState.LOGICALLY_UNLOADED,
                5_000L, "content delete did not unload");

            System.out.println("BBSPluginManagerIntegrationTest: drop-in install/reload/content/delete passed");
        }
        finally
        {
            manager.close();
            deleteTree(root);
        }
    }

    private static String readAsset(AssetProvider provider) throws IOException
    {
        try (InputStream input = provider.getAsset(Link.assets("strings/hot.txt")))
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean active(BBSPluginManager manager, String id, String version)
    {
        return manager.diagnostics().stream().anyMatch((status) ->
            id.equals(status.pluginId())
                && version.equals(status.version())
                && status.state() == mchorse.bbs_mod.api.plugin.BBSPluginState.ACTIVE
        );
    }

    private static boolean hasFailure(BBSPluginManager manager, String id, String version, String code)
    {
        return manager.diagnostics().stream().anyMatch((status) ->
            id.equals(status.pluginId())
                && version.equals(status.version())
                && code.equals(status.lastCode())
                && status.state() == mchorse.bbs_mod.api.plugin.BBSPluginState.FAILED
        );
    }

    private static mchorse.bbs_mod.api.plugin.BBSPluginState state(BBSPluginManager manager, String id)
    {
        return manager.diagnostics().stream()
            .filter((status) -> id.equals(status.pluginId()))
            .map(PluginStatus::state)
            .findFirst()
            .orElse(null);
    }

    private static PluginStatus currentStatus(BBSPluginManager manager, String id)
    {
        return manager.diagnostics().stream()
            .filter((status) -> id.equals(status.pluginId()))
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

    private static Path buildCodeArtifact(Path root, String version, String marker, boolean fail) throws Exception
    {
        String source = "package fixture;"
            + "import mchorse.bbs_mod.api.plugin.*;"
            + "import mchorse.bbs_mod.plugin.manager.ManagerProbeEvent;"
            + "public final class FixturePlugin implements BBSPlugin {"
            + "public void prepare(BBSPluginContext context) {"
            + (fail ? "throw new IllegalStateException(\"fixture prepare failure\");" : "context.events().subscribe(ManagerProbeEvent.class, event -> event.record(\"" + marker + "\"));")
            + "} }";
        Map<String, byte[]> classes = compile(root.resolve("compile-" + marker), "fixture.FixturePlugin", source);
        String manifest = "{\"schema\":1,\"kind\":\"code\",\"id\":\"fixture\",\"version\":\""
            + version + "\",\"commonEntrypoint\":\"fixture.FixturePlugin\",\"api\":\"[1.0,2.0)\","
            + "\"side\":\"common\",\"capabilities\":[\"events\"],\"dependencies\":[],\"reload\":\"hot\"}";
        return jar(root.resolve("fixture-" + marker + ".jar"), manifest, classes);
    }

    private static Path buildContentArtifact(Path root, String version, String text) throws IOException
    {
        return buildContentArtifact(root, version, text, "contentfixture", "common");
    }

    private static Path buildContentArtifact(
        Path root,
        String version,
        String text,
        String pluginId,
        String side
    ) throws IOException
    {
        String manifest = "{\"schema\":1,\"kind\":\"content\",\"id\":\"" + pluginId + "\",\"version\":\""
            + version + "\",\"api\":\"[1.0,2.0)\",\"side\":\"" + side + "\",\"capabilities\":[\"resources\"],"
            + "\"dependencies\":[],\"reload\":\"hot\"}";
        return jar(root.resolve(pluginId + "-" + version + ".jar"), manifest,
            Map.of("assets/strings/hot.txt", text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, byte[]> compile(Path root, String className, String source) throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null)
        {
            throw new IllegalStateException("JDK compiler is required for the fixture");
        }

        Files.createDirectories(root);
        Path sourceFile = root.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Path output = root.resolve("classes");
        Files.createDirectories(output);
        String classpath = System.getProperty("java.class.path");
        int result = compiler.run(null, null, null, "--release", "21", "-classpath", classpath,
            "-d", output.toString(), sourceFile.toString());

        if (result != 0)
        {
            throw new AssertionError("fixture compiler exited with " + result);
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();

        try (var files = Files.walk(output))
        {
            files.filter((path) -> path.toString().endsWith(".class")).forEach((path) ->
            {
                try
                {
                    classes.put(output.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
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
}
