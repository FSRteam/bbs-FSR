package mchorse.bbs_mod.plugin.artifact;

import mchorse.bbs_mod.api.plugin.BBSPluginArtifactLimits;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Narrow, dependency-free Phase 1 artifact contract launcher. */
public final class PluginArtifactContractTest
{
    private int assertions;

    public static void main(String[] args) throws Exception
    {
        new PluginArtifactContractTest().run();
    }

    private void run() throws Exception
    {
        Path root = Files.createTempDirectory("bbs-plugin-artifacts-");

        try
        {
            testValidation(root);
            testShadowStore(root);
            System.out.println("PluginArtifactContractTest passed " + this.assertions + " assertions");
        }
        finally
        {
            deleteTree(root);
        }
    }

    private void testValidation(Path root) throws Exception
    {
        PluginArtifactValidator validator = new PluginArtifactValidator();
        Path valid = root.resolve("valid.jar");

        writeJar(valid, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0, 1, 2}
        ));

        PluginArtifactValidation accepted = validator.validate(root, valid);
        check(accepted.accepted(), "valid code plugin is accepted");
        check(accepted.artifact().descriptor().id().equals("example"), "descriptor identity is normalized");
        check(accepted.artifact().sha256().length() == 64, "artifact has SHA-256 identity");

        Path content = root.resolve("content.jar");
        writeJar(content, contentManifest(), Map.of("assets/example/data.json", "{}".getBytes(StandardCharsets.UTF_8)));
        check(validator.validate(root, content).status() == PluginArtifactStatus.VALID,
            "classless content plugin is accepted");

        Path contentClass = root.resolve("content-class.jar");
        writeJar(contentClass, contentManifest(), Map.of("example/Unexpected.class", new byte[] {0}));
        check(validator.validate(root, contentClass).status() == PluginArtifactStatus.INVALID,
            "content plugin class entry is rejected");

        Path structural = root.resolve("structural.jar");
        writeJar(structural, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0},
            "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"".getBytes(StandardCharsets.UTF_8)
        ));
        check(validator.validate(root, structural).status() == PluginArtifactStatus.RESTART_REQUIRED,
            "NeoForge metadata is restart-only");

        Path versionedStructural = root.resolve("versioned-structural.jar");
        writeJar(versionedStructural, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0},
            "META-INF/versions/9/META-INF/neoforge.mods.toml",
            "modLoader=\"javafml\"".getBytes(StandardCharsets.UTF_8)
        ));
        check(validator.validate(root, versionedStructural).status() == PluginArtifactStatus.RESTART_REQUIRED,
            "versioned NeoForge metadata is restart-only");

        Path protectedPackage = root.resolve("protected.jar");
        writeJar(protectedPackage, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0},
            "mchorse/bbs_mod/HostCopy.class", new byte[] {0}
        ));
        check(validator.validate(root, protectedPackage).status() == PluginArtifactStatus.INVALID,
            "host package embedding is rejected");

        Path versionedProtectedPackage = root.resolve("versioned-protected.jar");
        writeJar(versionedProtectedPackage, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0},
            "META-INF/versions/21/mchorse/bbs_mod/HostCopy.class", new byte[] {0}
        ));
        check(validator.validate(root, versionedProtectedPackage).status() == PluginArtifactStatus.INVALID,
            "versioned host package embedding is rejected");

        Path incompatible = root.resolve("incompatible.jar");
        writeJar(incompatible, codeManifest("1.0.0", "example.ExamplePlugin", "[2.0,3.0)"), Map.of(
            "example/ExamplePlugin.class", new byte[] {0}
        ));
        check(validator.validate(root, incompatible).status() == PluginArtifactStatus.INCOMPATIBLE,
            "unsupported API range is incompatible");

        Path missingEntrypoint = root.resolve("missing-entrypoint.jar");
        writeJar(missingEntrypoint, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of());
        check(validator.validate(root, missingEntrypoint).status() == PluginArtifactStatus.INVALID,
            "missing entrypoint class is rejected before loading");

        Path outside = Files.createTempFile("bbs-plugin-outside-", ".jar");
        check(validator.validate(root, outside).status() == PluginArtifactStatus.INVALID,
            "outside artifact path is rejected");
        Files.deleteIfExists(outside);

        BBSPluginArtifactLimits tiny = new BBSPluginArtifactLimits(1024 * 1024, 32, 1024 * 1024,
            1024 * 1024, 32, 64, 8, 128);
        check(new PluginArtifactValidator(tiny).validate(root, valid).status() == PluginArtifactStatus.INVALID,
            "manifest size bound is enforced");

        BBSPluginArtifactLimits fewEntries = new BBSPluginArtifactLimits(1024 * 1024, 2, 1024 * 1024,
            1024 * 1024, 4096, 256, 16, 128);
        check(new PluginArtifactValidator(fewEntries).validate(root, valid).status() == PluginArtifactStatus.INVALID,
            "entry count bound includes the JAR manifest");

        Path duplicateField = root.resolve("duplicate-field.jar");
        writeJar(duplicateField, codeManifest("1.0.0", "example.ExamplePlugin", null)
            .replace("\"schema\":1,", "\"schema\":1,\"schema\":1,"), Map.of(
                "example/ExamplePlugin.class", new byte[] {0}
            ));
        check(validator.validate(root, duplicateField).status() == PluginArtifactStatus.INVALID,
            "duplicate manifest fields are rejected");

        Path structuralCapability = root.resolve("structural-capability.jar");
        writeJar(structuralCapability, codeManifest("1.0.0", "example.ExamplePlugin", null)
            .replace("[\"events\"]", "[\"forms\"]"), Map.of(
                "example/ExamplePlugin.class", new byte[] {0}
            ));
        check(validator.validate(root, structuralCapability).status() == PluginArtifactStatus.RESTART_REQUIRED,
            "structural manifest capability is restart-only");

        Path unsafeEntry = root.resolve("unsafe-entry.jar");
        writeJar(unsafeEntry, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {0},
            "../escape.txt", new byte[] {0}
        ));
        check(validator.validate(root, unsafeEntry).status() == PluginArtifactStatus.INVALID,
            "unsafe JAR entry path is rejected");

        Path nativeContent = root.resolve("native-content.jar");
        writeJar(nativeContent, contentManifest(), Map.of("native/example.dll", new byte[] {0}));
        check(validator.validate(root, nativeContent).status() == PluginArtifactStatus.RESTART_REQUIRED,
            "native content artifact is restart-only");

        try
        {
            Path symlink = root.resolve("symlink.jar");
            Files.createSymbolicLink(symlink, valid.getFileName());
            check(validator.validate(root, symlink).status() == PluginArtifactStatus.INVALID,
                "source symlink is rejected");
        }
        catch (UnsupportedOperationException | IOException ignored)
        {
            // Symlinks are unavailable on some Windows test accounts.
        }
    }

    private void testShadowStore(Path root) throws Exception
    {
        PluginArtifactValidator validator = new PluginArtifactValidator();
        PluginArtifactStore store = new PluginArtifactStore(root.resolve("cache"));
        Path v1 = root.resolve("example-v1.jar");
        Path v2 = root.resolve("example-v2.jar");

        writeJar(v1, codeManifest("1.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {1}
        ));
        PluginValidatedArtifact first = validator.validate(root, v1).artifact();
        PluginShadowArtifact firstShadow = store.stage(first);
        check(!firstShadow.path().equals(v1), "shadow path is separate from source");
        check(Files.readAllBytes(firstShadow.path()).length == Files.readAllBytes(v1).length,
            "shadow copy has source bytes");
        store.commit(firstShadow);

        writeJar(v2, codeManifest("2.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {2}
        ));
        PluginValidatedArtifact second = validator.validate(root, v2).artifact();
        PluginShadowArtifact secondShadow = store.stage(second);
        PluginArtifactRetention retention = store.commit(secondShadow);
        check(retention.current().orElseThrow().equals(second.sha256()), "new artifact is current");
        check(retention.previous().orElseThrow().equals(first.sha256()), "old artifact is previous");
        check(store.current("example").orElseThrow().sha256().equals(second.sha256()), "current pointer verifies hash");
        check(store.previous("example").orElseThrow().sha256().equals(first.sha256()), "previous pointer verifies hash");

        byte[] originalShadow = Files.readAllBytes(firstShadow.path());
        Files.move(v1, root.resolve("example-v1-replaced.jar"), StandardCopyOption.REPLACE_EXISTING);
        check(java.util.Arrays.equals(originalShadow, Files.readAllBytes(firstShadow.path())),
            "shadow remains readable after source replacement");

        Path changed = root.resolve("changed.jar");
        writeJar(changed, codeManifest("3.0.0", "example.ExamplePlugin", null), Map.of(
            "example/ExamplePlugin.class", new byte[] {3}
        ));
        PluginValidatedArtifact validated = validator.validate(root, changed).artifact();
        Files.writeString(changed, "changed-after-validation", StandardCharsets.UTF_8);

        try
        {
            store.stage(validated);
            throw new AssertionError("source change should reject shadow copy");
        }
        catch (PluginArtifactStoreException error)
        {
            check(error.code().equals("SOURCE_CHANGED"), "source change has stable diagnostic code");
        }
    }

    private static String codeManifest(String version, String commonEntrypoint, String api)
    {
        return "{\"schema\":1,\"kind\":\"code\",\"id\":\"example\",\"version\":\""
            + version + "\",\"commonEntrypoint\":\"" + commonEntrypoint
            + "\",\"api\":\"" + (api == null ? "[1.0,2.0)" : api)
            + "\",\"side\":\"common\",\"capabilities\":[\"events\"],\"dependencies\":[],\"reload\":\"hot\"}";
    }

    private static String contentManifest()
    {
        return "{\"schema\":1,\"kind\":\"content\",\"id\":\"example\",\"version\":\"1.0.0\","
            + "\"api\":\"[1.0,2.0)\",\"side\":\"common\",\"capabilities\":[\"resources\"],"
            + "\"dependencies\":[],\"reload\":\"hot\"}";
    }

    private static void writeJar(Path path, String manifestJson, Map<String, byte[]> entries) throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");

        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output, manifest))
        {
            put(jar, PluginManifestDecoder.MANIFEST_PATH, manifestJson.getBytes(StandardCharsets.UTF_8));

            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet())
            {
                put(jar, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws IOException
    {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }

    private void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }

        this.assertions++;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).forEach(path -> {
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
}
