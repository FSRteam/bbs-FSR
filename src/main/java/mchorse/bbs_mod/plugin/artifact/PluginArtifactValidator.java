package mchorse.bbs_mod.plugin.artifact;

import mchorse.bbs_mod.api.plugin.BBSPluginApiVersion;
import mchorse.bbs_mod.api.plugin.BBSPluginArtifactLimits;
import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginManifest;
import mchorse.bbs_mod.api.plugin.BBSPluginReloadMode;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;
import mchorse.bbs_mod.plugin.loader.PluginClassLoaderPolicy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs all artifact checks before a plugin class can be resolved or initialized. */
public final class PluginArtifactValidator
{
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final Pattern VERSION = Pattern.compile(
        "[0-9]+(?:\\.[0-9]+){0,3}(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
    );
    private static final Pattern CLASS_NAME = Pattern.compile("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern API_INTERVAL = Pattern.compile("(\\[|\\()\\s*([0-9]+(?:\\.[0-9]+)*)\\s*,\\s*([0-9]+(?:\\.[0-9]+)*)\\s*(\\]|\\))");
    private static final List<String> PROTECTED_PREFIXES = List.of(
        "java/",
        "net/minecraft/",
        "net/neoforged/",
        "mchorse/bbs_mod/",
        "org/slf4j/",
        "org/pf4j/",
        "mchorse/bbs_mod/plugin/internal/pf4j/"
    );

    private final BBSPluginArtifactLimits limits;
    private final PluginManifestDecoder manifestDecoder;

    public PluginArtifactValidator()
    {
        this(BBSPluginArtifactLimits.DEFAULT);
    }

    public PluginArtifactValidator(BBSPluginArtifactLimits limits)
    {
        this.limits = limits;
        this.manifestDecoder = new PluginManifestDecoder(limits);
    }

    public PluginArtifactValidation validate(Path pluginDirectory, Path sourceJar)
    {
        if (sourceJar == null)
        {
            throw new IllegalArgumentException("sourceJar is null");
        }

        Path source = sourceJar.toAbsolutePath().normalize();

        try
        {
            source = validateSourcePath(pluginDirectory, source);
            long size = Files.size(source);

            if (size <= 0 || size > this.limits.maxJarBytes())
            {
                return failure(source, "ARTIFACT_SIZE", "artifact size is outside the configured limit");
            }

            Fingerprint first = fingerprint(source);
            JarScan scan = scanJar(source);
            Fingerprint second = fingerprint(source);

            if (!first.equals(second))
            {
                return failure(source, "SOURCE_CHANGED", "source artifact changed while it was being validated");
            }

            BBSPluginManifest manifest;

            try
            {
                manifest = this.manifestDecoder.decode(scan.manifestBytes);
            }
            catch (PluginArtifactException error)
            {
                return new PluginArtifactValidation(source, error.status(), null, first.sha256, first.sizeBytes,
                    scan.entryCount, List.of(new PluginArtifactIssue(error.code(), error.getMessage())), null);
            }

            return validateManifestAndEntries(source, first, scan, manifest);
        }
        catch (PluginArtifactException error)
        {
            return new PluginArtifactValidation(source, error.status(), null, "", 0, 0,
                List.of(new PluginArtifactIssue(error.code(), error.getMessage())), null);
        }
        catch (IOException | SecurityException error)
        {
            return failure(source, "ARTIFACT_IO", "cannot read plugin artifact: " + safeMessage(error));
        }
    }

    private PluginArtifactValidation validateManifestAndEntries(Path source, Fingerprint fingerprint,
                                                                JarScan scan, BBSPluginManifest manifest)
    {
        List<PluginArtifactIssue> issues = new ArrayList<>();

        validateId(manifest.id(), "PLUGIN_ID", issues);
        validateVersion(manifest.version(), issues);
        validateEntrypoints(manifest, scan.entries, issues);
        validateDependencies(manifest, issues);

        if (manifest.kind() == BBSPluginKind.CONTENT && scan.hasClasses)
        {
            issues.add(new PluginArtifactIssue("CONTENT_CLASS", "content plugins cannot contain .class entries"));
        }

        if (!issues.isEmpty())
        {
            return result(source, PluginArtifactStatus.INVALID, manifest, fingerprint, scan, issues, null);
        }

        ApiCompatibility compatibility = checkApiCompatibility(manifest.api());

        if (compatibility == ApiCompatibility.MALFORMED)
        {
            issues.add(new PluginArtifactIssue("API_RANGE", "malformed plugin API range '" + manifest.api() + "'"));
            return result(source, PluginArtifactStatus.INVALID, manifest, fingerprint, scan, issues, null);
        }

        if (compatibility == ApiCompatibility.UNSUPPORTED)
        {
            issues.add(new PluginArtifactIssue("API_INCOMPATIBLE",
                "plugin API range '" + manifest.api() + "' does not include host SPI " + BBSPluginApiVersion.CURRENT));
            return result(source, PluginArtifactStatus.INCOMPATIBLE, manifest, fingerprint, scan, issues, null);
        }

        if (manifest.reload() == BBSPluginReloadMode.RESTART)
        {
            issues.add(new PluginArtifactIssue("RELOAD_RESTART", "manifest declares reload mode 'restart'"));
        }

        for (BBSPluginCapability capability : manifest.capabilities())
        {
            if (!capability.hotSafe())
            {
                issues.add(new PluginArtifactIssue("CAPABILITY_RESTART_REQUIRED",
                    "capability '" + capability.wireName() + "' is structural and requires restart"));
            }
        }

        for (String structuralEntry : scan.structuralEntries)
        {
            issues.add(new PluginArtifactIssue("STRUCTURAL_ENTRY",
                "artifact contains restart-only entry '" + structuralEntry + "'"));
        }

        if (!issues.isEmpty())
        {
            return result(source, PluginArtifactStatus.RESTART_REQUIRED, manifest, fingerprint, scan, issues, null);
        }

        PluginValidatedArtifact artifact = new PluginValidatedArtifact(source, manifest, manifest.descriptor(),
            fingerprint.sha256, fingerprint.sizeBytes, scan.entryCount);

        return result(source, PluginArtifactStatus.VALID, manifest, fingerprint, scan, List.of(), artifact);
    }

    private void validateEntrypoints(BBSPluginManifest manifest, Set<String> entries,
                                     List<PluginArtifactIssue> issues)
    {
        String common = normalizeOptional(manifest.commonEntrypoint());
        String client = normalizeOptional(manifest.clientEntrypoint());

        if (manifest.kind() == BBSPluginKind.CONTENT)
        {
            if (common != null || client != null)
            {
                issues.add(new PluginArtifactIssue("CONTENT_ENTRYPOINT",
                    "content plugins cannot declare Java entrypoints"));
            }

            return;
        }

        switch (manifest.side())
        {
            case COMMON ->
            {
                if (common == null)
                {
                    issues.add(new PluginArtifactIssue("COMMON_ENTRYPOINT_REQUIRED",
                        "common code plugins require commonEntrypoint"));
                }
            }
            case CLIENT ->
            {
                if (common != null)
                {
                    issues.add(new PluginArtifactIssue("COMMON_ENTRYPOINT_FORBIDDEN",
                        "client-only plugins cannot declare commonEntrypoint"));
                }

                if (client == null)
                {
                    issues.add(new PluginArtifactIssue("CLIENT_ENTRYPOINT_REQUIRED",
                        "client-only code plugins require clientEntrypoint"));
                }
            }
            case DEDICATED_SERVER ->
            {
                if (common == null)
                {
                    issues.add(new PluginArtifactIssue("COMMON_ENTRYPOINT_REQUIRED",
                        "dedicated-server code plugins require commonEntrypoint"));
                }

                if (client != null)
                {
                    issues.add(new PluginArtifactIssue("CLIENT_ENTRYPOINT_FORBIDDEN",
                        "dedicated-server plugins cannot declare clientEntrypoint"));
                }
            }
        }

        validateEntrypoint("commonEntrypoint", common, entries, issues);
        validateEntrypoint("clientEntrypoint", client, entries, issues);
    }

    private void validateEntrypoint(String field, String className, Set<String> entries,
                                    List<PluginArtifactIssue> issues)
    {
        if (className == null)
        {
            return;
        }

        if (!CLASS_NAME.matcher(className).matches())
        {
            issues.add(new PluginArtifactIssue("ENTRYPOINT_NAME", field + " is not a valid Java binary name"));
            return;
        }

        String path = className.replace('.', '/') + ".class";

        if (isProtectedEntry(path))
        {
            issues.add(new PluginArtifactIssue("ENTRYPOINT_PROTECTED", field + " is in a protected host package"));
        }
        else if (!entries.contains(path))
        {
            issues.add(new PluginArtifactIssue("ENTRYPOINT_MISSING", field + " class entry is missing"));
        }
    }

    private void validateDependencies(BBSPluginManifest manifest, List<PluginArtifactIssue> issues)
    {
        Set<String> dependencyIds = new HashSet<>();

        for (String dependency : manifest.dependencies())
        {
            int separator = dependency.indexOf('@');
            String dependencyId = separator < 0 ? dependency : dependency.substring(0, separator);

            if (dependencyId.equals(manifest.id()))
            {
                issues.add(new PluginArtifactIssue("DEPENDENCY_SELF", "plugin cannot depend on itself"));
            }
            else
            {
                validateId(dependencyId, "DEPENDENCY_ID", issues);
            }

            if (!dependencyIds.add(dependencyId))
            {
                issues.add(new PluginArtifactIssue("DEPENDENCY_DUPLICATE_ID",
                    "dependency id '" + dependencyId + "' is declared more than once"));
            }

            if (separator >= 0 && (separator == dependency.length() - 1
                || dependency.indexOf('@', separator + 1) >= 0
                || !isVersionConstraint(dependency.substring(separator + 1))))
            {
                issues.add(new PluginArtifactIssue("DEPENDENCY_RANGE", "dependency '" + dependency + "' has an invalid range"));
            }
        }
    }

    private static void validateId(String id, String code, List<PluginArtifactIssue> issues)
    {
        if (!PluginArtifactNames.isPluginId(id))
        {
            issues.add(new PluginArtifactIssue(code,
                "plugin id '" + id + "' is not a portable lowercase identifier"));
        }
    }

    private static void validateVersion(String version, List<PluginArtifactIssue> issues)
    {
        if (version == null || !VERSION.matcher(version).matches())
        {
            issues.add(new PluginArtifactIssue("PLUGIN_VERSION", "plugin version is invalid"));
        }
    }

    private Path validateSourcePath(Path pluginDirectory, Path source) throws PluginArtifactException, IOException
    {
        if (pluginDirectory == null)
        {
            throw invalid("PLUGIN_DIRECTORY", "plugin directory is null");
        }

        Path root = pluginDirectory.toAbsolutePath().normalize();

        if (!Files.isDirectory(root, NO_FOLLOW) || Files.isSymbolicLink(root))
        {
            throw invalid("PLUGIN_DIRECTORY", "plugin directory must be a real directory, not a symlink");
        }

        if (!source.startsWith(root) || source.equals(root))
        {
            throw invalid("SOURCE_CONTAINMENT", "artifact is outside the plugin directory");
        }

        Path cursor = root;

        for (Path component : root.relativize(source))
        {
            cursor = cursor.resolve(component);

            if (Files.isSymbolicLink(cursor))
            {
                throw invalid("SOURCE_SYMLINK", "artifact path contains a symbolic link");
            }
        }

        Path realRoot = root.toRealPath();
        Path realSource = source.toRealPath();

        if (!realSource.startsWith(realRoot))
        {
            throw invalid("SOURCE_CONTAINMENT", "artifact resolves outside the plugin directory");
        }

        if (!Files.isRegularFile(source, NO_FOLLOW))
        {
            throw invalid("SOURCE_TYPE", "artifact must be a regular file");
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);

        if (!filename.endsWith(".jar"))
        {
            throw invalid("SOURCE_EXTENSION", "plugin artifact must have a .jar extension");
        }

        return source;
    }

    private JarScan scanJar(Path source) throws IOException, PluginArtifactException
    {
        Set<String> entries = new HashSet<>();
        Set<String> foldedEntries = new HashSet<>();
        List<String> structuralEntries = new ArrayList<>();
        byte[] manifestBytes = null;
        byte[] standardManifestBytes = null;
        int entryCount = 0;
        long totalBytes = 0;
        boolean hasClasses = false;
        Manifest standardManifest;

        try (InputStream sourceInput = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             JarInputStream jar = new JarInputStream(sourceInput, true))
        {
            standardManifest = jar.getManifest();

            if (standardManifest != null)
            {
                entries.add("META-INF/MANIFEST.MF");
                foldedEntries.add("meta-inf/manifest.mf");
                entryCount += 1;
            }

            JarEntry entry;

            while ((entry = jar.getNextJarEntry()) != null)
            {
                String name = validateEntryName(entry.getName(), entry.isDirectory());

                if (++entryCount > this.limits.maxEntryCount())
                {
                    throw invalid("ENTRY_COUNT", "artifact contains too many entries");
                }

                if (!entries.add(name) || !foldedEntries.add(name.toLowerCase(Locale.ROOT)))
                {
                    throw invalid("ENTRY_DUPLICATE", "artifact contains duplicate or case-colliding entry '" + name + "'");
                }

                String checkName = normalizeMultiReleaseEntry(name);

                if (isProtectedEntry(checkName))
                {
                    throw invalid("PROTECTED_PACKAGE", "artifact embeds protected host package entry '" + name + "'");
                }

                String lower = name.toLowerCase(Locale.ROOT);
                String checkLower = checkName.toLowerCase(Locale.ROOT);
                hasClasses |= checkLower.endsWith(".class");

                if (isStructuralEntry(checkLower))
                {
                    structuralEntries.add(name);
                }

                if (entry.isDirectory())
                {
                    jar.closeEntry();
                    continue;
                }

                long declaredSize = entry.getSize();

                if (declaredSize > this.limits.maxEntryBytes())
                {
                    throw invalid("ENTRY_SIZE", "entry exceeds the configured size limit: " + name);
                }

                boolean pluginManifest = PluginManifestDecoder.MANIFEST_PATH.equals(name);

                if (pluginManifest && manifestBytes != null)
                {
                    throw invalid("MANIFEST_DUPLICATE", "artifact contains more than one plugin manifest");
                }

                if (lower.equals(PluginManifestDecoder.MANIFEST_PATH.toLowerCase(Locale.ROOT)) && !pluginManifest)
                {
                    throw invalid("MANIFEST_CASE", "plugin manifest path must be exactly " + PluginManifestDecoder.MANIFEST_PATH);
                }

                ByteArrayOutputStream manifestOutput = pluginManifest
                    ? new ByteArrayOutputStream((int) Math.min(Math.max(0, declaredSize), 8192))
                    : null;
                boolean rawStandardManifest = lower.equals("meta-inf/manifest.mf");
                ByteArrayOutputStream standardManifestOutput = rawStandardManifest
                    ? new ByteArrayOutputStream((int) Math.min(Math.max(0, declaredSize), 8192))
                    : null;
                long actualSize = 0;

                byte[] buffer = new byte[8192];
                int read;

                while ((read = jar.read(buffer)) != -1)
                {
                    actualSize += read;

                    if (actualSize > this.limits.maxEntryBytes())
                    {
                        throw invalid("ENTRY_SIZE", "entry exceeds the configured size limit: " + name);
                    }

                    if (totalBytes > this.limits.maxTotalUncompressedBytes() - read)
                    {
                        throw invalid("ARTIFACT_EXPANDED_SIZE", "artifact exceeds the total expanded size limit");
                    }

                    totalBytes += read;

                    if (manifestOutput != null)
                    {
                        if (actualSize > this.limits.maxManifestBytes())
                        {
                            throw invalid("MANIFEST_SIZE", "manifest exceeds the configured size limit");
                        }

                        manifestOutput.write(buffer, 0, read);
                    }

                    if (standardManifestOutput != null)
                    {
                        if (actualSize > this.limits.maxManifestBytes())
                        {
                            throw invalid("STANDARD_MANIFEST_SIZE", "JAR manifest exceeds the configured manifest size limit");
                        }

                        standardManifestOutput.write(buffer, 0, read);
                    }
                }

                jar.closeEntry();

                if (declaredSize >= 0 && declaredSize != actualSize)
                {
                    throw invalid("ENTRY_SIZE_MISMATCH", "entry size changed while reading: " + name);
                }

                if (manifestOutput != null)
                {
                    manifestBytes = manifestOutput.toByteArray();
                }

                if (standardManifestOutput != null)
                {
                    standardManifestBytes = standardManifestOutput.toByteArray();
                }
            }
        }

        if (standardManifest == null && standardManifestBytes != null)
        {
            standardManifest = new Manifest(new ByteArrayInputStream(standardManifestBytes));
        }

        structuralEntries.addAll(structuralManifestAttributes(standardManifest));

        if (manifestBytes == null)
        {
            throw invalid("MANIFEST_MISSING", "artifact does not contain " + PluginManifestDecoder.MANIFEST_PATH);
        }

        return new JarScan(Set.copyOf(entries), List.copyOf(structuralEntries), manifestBytes,
            entryCount, hasClasses);
    }

    private String validateEntryName(String name, boolean directory) throws PluginArtifactException
    {
        if (name == null || name.isEmpty() || name.length() > this.limits.maxEntryNameChars()
            || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
            || name.indexOf('\0') >= 0)
        {
            throw invalid("ENTRY_NAME", "artifact contains an unsafe entry name");
        }

        String[] segments = name.split("/", -1);

        for (int i = 0; i < segments.length; i++)
        {
            String segment = segments[i];
            boolean finalDirectorySeparator = directory && i == segments.length - 1 && segment.isEmpty();

            if (!finalDirectorySeparator && (segment.isEmpty() || segment.equals(".") || segment.equals("..")))
            {
                throw invalid("ENTRY_NAME", "artifact contains an unsafe entry name '" + name + "'");
            }
        }

        return name;
    }

    /**
     * Multi-release class and resource entries are selected as if their
     * {@code META-INF/versions/<n>/} prefix were absent. Apply that same view
     * before host-package and structural-entry checks so versioned payloads
     * cannot bypass the safety boundary. The original name remains in the
     * artifact index and diagnostics.
     */
    private static String normalizeMultiReleaseEntry(String name)
    {
        String normalized = name;

        while (normalized.toLowerCase(Locale.ROOT).startsWith("meta-inf/versions/"))
        {
            int versionStart = "META-INF/versions/".length();
            int separator = normalized.indexOf('/', versionStart);

            if (separator < 0)
            {
                return normalized;
            }

            String version = normalized.substring(versionStart, separator);

            if (!version.matches("[0-9]+"))
            {
                return normalized;
            }

            try
            {
                if (Integer.parseInt(version) < 9)
                {
                    return normalized;
                }
            }
            catch (NumberFormatException error)
            {
                return normalized;
            }

            normalized = normalized.substring(separator + 1);
        }

        return normalized;
    }

    private static List<String> structuralManifestAttributes(Manifest manifest)
    {
        if (manifest == null)
        {
            return List.of();
        }

        List<String> found = new ArrayList<>();
        Attributes attributes = manifest.getMainAttributes();

        Set<String> structuralNames = Set.of("fmlmodtype", "mixinconfigs", "accesstransformer", "modloader", "coremod");

        for (Object key : attributes.keySet())
        {
            String name = String.valueOf(key);

            if (structuralNames.contains(name.toLowerCase(Locale.ROOT)) && attributes.getValue(name) != null)
            {
                found.add("META-INF/MANIFEST.MF:" + name);
            }
        }

        return found;
    }

    private static boolean isProtectedEntry(String name)
    {
        if (PluginClassLoaderPolicy.isProtectedClassEntry(name))
        {
            return true;
        }

        String lower = name.toLowerCase(Locale.ROOT);

        for (String prefix : PROTECTED_PREFIXES)
        {
            if (lower.startsWith(prefix))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isStructuralEntry(String lower)
    {
        if (lower.equals("meta-inf/neoforge.mods.toml") || lower.equals("meta-inf/mods.toml")
            || lower.equals("meta-inf/coremods.json") || lower.startsWith("meta-inf/coremods/")
            || lower.endsWith(".mixins.json") || lower.endsWith(".mixin.json")
            || lower.equals("meta-inf/services/cpw.mods.modlauncher.api.itransformationservice")
            || lower.equals("meta-inf/services/net.neoforged.neoforgespi.language.imodlanguageloader"))
        {
            return true;
        }

        if (lower.startsWith("meta-inf/accesstransformer") && lower.endsWith(".cfg"))
        {
            return true;
        }

        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
            || lower.endsWith(".jnilib");
    }

    private static Fingerprint fingerprint(Path source) throws IOException
    {
        MessageDigest digest;

        try
        {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error)
        {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }

        long size = 0;

        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1)
            {
                digest.update(buffer, 0, read);
                size += read;
            }
        }

        return new Fingerprint(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static ApiCompatibility checkApiCompatibility(String range)
    {
        String value = range == null ? "" : range.trim();
        int[] current = parseNumericVersion(BBSPluginApiVersion.CURRENT);

        if (value.matches("[0-9]+(?:\\.[0-9]+)*"))
        {
            int[] exact = parseNumericVersion(value);
            return exact == null ? ApiCompatibility.MALFORMED
                : compareVersions(current, exact) == 0 ? ApiCompatibility.SUPPORTED : ApiCompatibility.UNSUPPORTED;
        }

        Matcher matcher = API_INTERVAL.matcher(value);

        if (!matcher.matches())
        {
            return ApiCompatibility.MALFORMED;
        }

        int[] lower = parseNumericVersion(matcher.group(2));
        int[] upper = parseNumericVersion(matcher.group(3));

        if (lower == null || upper == null || compareVersions(lower, upper) > 0)
        {
            return ApiCompatibility.MALFORMED;
        }

        int lowerComparison = compareVersions(current, lower);
        int upperComparison = compareVersions(current, upper);
        boolean lowerIncluded = matcher.group(1).equals("[");
        boolean upperIncluded = matcher.group(4).equals("]");
        boolean aboveLower = lowerComparison > 0 || (lowerComparison == 0 && lowerIncluded);
        boolean belowUpper = upperComparison < 0 || (upperComparison == 0 && upperIncluded);

        return aboveLower && belowUpper ? ApiCompatibility.SUPPORTED : ApiCompatibility.UNSUPPORTED;
    }

    private static boolean isVersionConstraint(String value)
    {
        if (value.matches("[0-9]+(?:\\.[0-9]+)*"))
        {
            return parseNumericVersion(value) != null;
        }

        Matcher matcher = API_INTERVAL.matcher(value);

        if (!matcher.matches())
        {
            return false;
        }

        int[] lower = parseNumericVersion(matcher.group(2));
        int[] upper = parseNumericVersion(matcher.group(3));

        if (lower == null || upper == null)
        {
            return false;
        }

        int compared = compareVersions(lower, upper);

        return compared < 0 || (compared == 0 && matcher.group(1).equals("[") && matcher.group(4).equals("]"));
    }

    private static int[] parseNumericVersion(String value)
    {
        String[] parts = value.split("\\.");
        int[] result = new int[parts.length];

        try
        {
            for (int i = 0; i < parts.length; i++)
            {
                if (parts[i].length() > 9)
                {
                    return null;
                }

                result[i] = Integer.parseInt(parts[i]);
            }

            return result;
        }
        catch (NumberFormatException error)
        {
            return null;
        }
    }

    private static int compareVersions(int[] left, int[] right)
    {
        int length = Math.max(left.length, right.length);

        for (int i = 0; i < length; i++)
        {
            int leftPart = i < left.length ? left[i] : 0;
            int rightPart = i < right.length ? right[i] : 0;
            int compared = Integer.compare(leftPart, rightPart);

            if (compared != 0)
            {
                return compared;
            }
        }

        return 0;
    }

    private static String normalizeOptional(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private static PluginArtifactValidation result(Path source, PluginArtifactStatus status,
                                                   BBSPluginManifest manifest, Fingerprint fingerprint,
                                                   JarScan scan, List<PluginArtifactIssue> issues,
                                                   PluginValidatedArtifact artifact)
    {
        return new PluginArtifactValidation(source, status, manifest, fingerprint.sha256,
            fingerprint.sizeBytes, scan.entryCount, issues, artifact);
    }

    private static PluginArtifactValidation failure(Path source, String code, String message)
    {
        return PluginArtifactValidation.failure(source, PluginArtifactStatus.INVALID, code, message);
    }

    private static PluginArtifactException invalid(String code, String message)
    {
        return new PluginArtifactException(PluginArtifactStatus.INVALID, code, message);
    }

    private static String safeMessage(Throwable error)
    {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private enum ApiCompatibility
    {
        SUPPORTED,
        UNSUPPORTED,
        MALFORMED
    }

    private record Fingerprint(String sha256, long sizeBytes) {}

    private record JarScan(Set<String> entries, List<String> structuralEntries,
                           byte[] manifestBytes, int entryCount, boolean hasClasses) {}
}
