package mchorse.bbs_mod.plugin.hotreload.phase0;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Builds complete plugin fixture JARs using only the running JDK. */
final class FixtureJarBuilder
{
    static final String PLUGIN_CLASS = "phase0.fixture.ReloadableFixturePlugin";
    static final String PLUGIN_ID = "phase0-fixture";

    Path build(Path output, String version, String marker) throws IOException
    {
        Path parent = output.toAbsolutePath().getParent();

        Files.createDirectories(parent);
        Path work = Files.createTempDirectory(parent, "fixture-build-");
        Path sources = work.resolve("sources");
        Path classes = work.resolve("classes");

        try
        {
            List<Path> sourceFiles = this.writeSources(sources, version, marker);

            Files.createDirectories(classes);
            this.compile(sourceFiles, classes);
            this.writeJar(output, classes, version);

            return output;
        }
        finally
        {
            deleteRecursively(work);
        }
    }

    ShadowArtifact shadowCopy(Path source, Path cacheRoot) throws IOException
    {
        String hash = sha256(source);
        Path shadow = cacheRoot.resolve(PLUGIN_ID).resolve(hash).resolve("plugin.jar");

        Files.createDirectories(shadow.getParent());
        Files.copy(source, shadow, StandardCopyOption.REPLACE_EXISTING);

        if (!hash.equals(sha256(shadow)))
        {
            throw new AssertionError("shadow artifact hash changed during copy");
        }

        return new ShadowArtifact(shadow, hash);
    }

    void replaceSource(Path candidate, Path source) throws IOException
    {
        try
        {
            Files.move(candidate, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException exception)
        {
            Files.move(candidate, source, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    String manifestVersion(Path artifact) throws IOException
    {
        try (JarFile jar = new JarFile(artifact.toFile()))
        {
            JarEntry entry = jar.getJarEntry("META-INF/bbs-plugin.json");

            if (entry == null)
            {
                throw new AssertionError("fixture manifest is missing");
            }

            try (InputStream stream = jar.getInputStream(entry))
            {
                String manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                String field = "\"version\":\"";
                int start = manifest.indexOf(field);

                if (start < 0)
                {
                    throw new AssertionError("fixture manifest version is missing");
                }

                start += field.length();
                int end = manifest.indexOf('"', start);

                return manifest.substring(start, end);
            }
        }
    }

    boolean containsBundledHostApi(Path artifact) throws IOException
    {
        try (JarFile jar = new JarFile(artifact.toFile()))
        {
            return jar.getJarEntry("mchorse/bbs_mod/plugin/hotreload/phase0/api/Phase0Plugin.class") != null
                && jar.getJarEntry("mchorse/bbs_mod/plugin/hotreload/phase0/api/Phase0Host.class") != null;
        }
    }

    private List<Path> writeSources(Path root, String version, String marker) throws IOException
    {
        List<Path> sources = new ArrayList<>();

        sources.add(this.writeSource(root, "mchorse/bbs_mod/plugin/hotreload/phase0/api/Phase0Host.java", """
            package mchorse.bbs_mod.plugin.hotreload.phase0.api;

            public interface Phase0Host
            {
                void stage(String pluginId, String version, String marker);
            }
            """));
        sources.add(this.writeSource(root, "mchorse/bbs_mod/plugin/hotreload/phase0/api/Phase0Plugin.java", """
            package mchorse.bbs_mod.plugin.hotreload.phase0.api;

            public interface Phase0Plugin
            {
                String id();
                String version();
                String marker();
                void prepare(Phase0Host host);
                void start();
                void stop();
                boolean isStarted();
            }
            """));
        sources.add(this.writeSource(root, "phase0/fixture/ReloadableFixturePlugin.java", """
            package phase0.fixture;

            import mchorse.bbs_mod.plugin.hotreload.phase0.api.Phase0Host;
            import mchorse.bbs_mod.plugin.hotreload.phase0.api.Phase0Plugin;

            public final class ReloadableFixturePlugin implements Phase0Plugin
            {
                private boolean prepared;
                private boolean started;

                @Override
                public String id()
                {
                    return "%s";
                }

                @Override
                public String version()
                {
                    return "%s";
                }

                @Override
                public String marker()
                {
                    return "%s";
                }

                @Override
                public void prepare(Phase0Host host)
                {
                    if (this.prepared)
                    {
                        throw new IllegalStateException("fixture generation prepared twice");
                    }

                    host.stage(this.id(), this.version(), this.marker());
                    this.prepared = true;
                }

                @Override
                public void start()
                {
                    if (!this.prepared)
                    {
                        throw new IllegalStateException("fixture generation started before prepare");
                    }

                    this.started = true;
                }

                @Override
                public void stop()
                {
                    this.started = false;
                }

                @Override
                public boolean isStarted()
                {
                    return this.started;
                }
            }
            """.formatted(escape(PLUGIN_ID), escape(version), escape(marker))));

        return sources;
    }

    private Path writeSource(Path root, String relative, String source) throws IOException
    {
        Path path = root.resolve(relative);

        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);

        return path;
    }

    private void compile(List<Path> sources, Path classes) throws IOException
    {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null)
        {
            throw new IllegalStateException("Phase 0 fixture compilation requires a full JDK");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8))
        {
            List<String> options = List.of(
                "--release", Integer.toString(Runtime.version().feature()),
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()
            );
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sources);
            boolean compiled = Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics, options, null, units).call());

            if (!compiled)
            {
                StringBuilder message = new StringBuilder("fixture compilation failed:");

                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
                {
                    message.append(System.lineSeparator()).append(diagnostic);
                }

                throw new AssertionError(message);
            }
        }
    }

    private void writeJar(Path output, Path classes, String version) throws IOException
    {
        try (OutputStream outputStream = Files.newOutputStream(output);
            JarOutputStream jar = new JarOutputStream(outputStream);
            Stream<Path> paths = Files.walk(classes))
        {
            List<Path> classFiles = paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();

            for (Path classFile : classFiles)
            {
                this.putEntry(jar, classes.relativize(classFile).toString().replace('\\', '/'), Files.readAllBytes(classFile));
            }

            String manifest = "{\"schema\":1,\"kind\":\"code\",\"id\":\"%s\",\"version\":\"%s\"," +
                "\"commonEntrypoint\":\"%s\",\"api\":\"[1.0,2.0)\",\"side\":\"common\"}";

            this.putEntry(jar, "META-INF/bbs-plugin.json", manifest.formatted(PLUGIN_ID, version, PLUGIN_CLASS).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void putEntry(JarOutputStream jar, String name, byte[] bytes) throws IOException
    {
        JarEntry entry = new JarEntry(name);

        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static String sha256(Path path) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream stream = Files.newInputStream(path))
            {
                byte[] buffer = new byte[16 * 1024];
                int read;

                while ((read = stream.read(buffer)) != -1)
                {
                    digest.update(buffer, 0, read);
                }
            }

            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new AssertionError("SHA-256 is required by the JDK", exception);
        }
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return;
        }

        try (Stream<Path> paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    record ShadowArtifact(Path path, String hash)
    {}
}
