package mchorse.bbs_mod.plugin.artifact;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mchorse.bbs_mod.api.plugin.BBSPluginArtifactLimits;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Content-addressed shadow-copy store. Staging never changes current/previous;
 * only an explicit commit rotates the retention pointer.
 */
public final class PluginArtifactStore
{
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final String RETENTION_FILE = "previous.json";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path root;
    private final BBSPluginArtifactLimits limits;

    public PluginArtifactStore(Path cacheRoot)
    {
        this(cacheRoot, BBSPluginArtifactLimits.DEFAULT);
    }

    public PluginArtifactStore(Path cacheRoot, BBSPluginArtifactLimits limits)
    {
        if (cacheRoot == null)
        {
            throw new IllegalArgumentException("cacheRoot is null");
        }

        this.limits = limits;
        Path absolute = cacheRoot.toAbsolutePath().normalize();

        try
        {
            if (Files.exists(absolute, NO_FOLLOW) && Files.isSymbolicLink(absolute))
            {
                throw new IllegalArgumentException("cache root cannot be a symbolic link");
            }

            Files.createDirectories(absolute);

            if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, NO_FOLLOW))
            {
                throw new IllegalArgumentException("cache root must be a real directory");
            }

            this.root = absolute.toRealPath();
        }
        catch (IOException error)
        {
            throw new IllegalArgumentException("cannot create plugin cache root", error);
        }
    }

    public Path root()
    {
        return this.root;
    }

    public synchronized PluginShadowArtifact stage(PluginValidatedArtifact validated)
        throws PluginArtifactStoreException
    {
        if (validated == null)
        {
            throw new PluginArtifactStoreException("VALIDATED_NULL", "validated artifact is null");
        }

        String id = validated.descriptor().id();
        Path idDirectory = childDirectory(this.root, id);
        Path hashDirectory = childDirectory(idDirectory, validated.sha256());
        Path target = hashDirectory.resolve("plugin.jar");

        try
        {
            if (Files.exists(idDirectory, NO_FOLLOW))
            {
                rejectSymlink(idDirectory, "PLUGIN_CACHE_ID_SYMLINK");
            }

            Files.createDirectories(idDirectory);
            rejectSymlink(idDirectory, "PLUGIN_CACHE_ID_SYMLINK");

            if (Files.exists(hashDirectory, NO_FOLLOW))
            {
                rejectSymlink(hashDirectory, "PLUGIN_CACHE_HASH_SYMLINK");
            }

            Files.createDirectories(hashDirectory);
            rejectSymlink(hashDirectory, "PLUGIN_CACHE_HASH_SYMLINK");

            if (Files.exists(target, NO_FOLLOW))
            {
                rejectSymlink(target, "PLUGIN_CACHE_ARTIFACT_SYMLINK");
                verifyFile(target, validated.sha256(), validated.sizeBytes());

                return new PluginShadowArtifact(id, validated.sha256(), target,
                    validated.sizeBytes(), validated.manifest(), validated.descriptor());
            }

            Path temporary = Files.createTempFile(idDirectory, ".candidate-", ".tmp");

            try
            {
                copyAndVerify(validated.source(), temporary, validated.sha256(), validated.sizeBytes());
                moveAtomic(temporary, target);
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }

            return new PluginShadowArtifact(id, validated.sha256(), target,
                validated.sizeBytes(), validated.manifest(), validated.descriptor());
        }
        catch (PluginArtifactStoreException error)
        {
            throw error;
        }
        catch (IOException error)
        {
            throw new PluginArtifactStoreException("SHADOW_COPY", "cannot publish shadow copy", error);
        }
    }

    /** Rotate current to previous and publish a new current pointer atomically. */
    public synchronized PluginArtifactRetention commit(PluginShadowArtifact artifact)
        throws PluginArtifactStoreException
    {
        if (artifact == null)
        {
            throw new PluginArtifactStoreException("ARTIFACT_NULL", "shadow artifact is null");
        }

        try
        {
            Path idDirectory = childDirectory(this.root, artifact.pluginId());
            Path target = childDirectory(idDirectory, artifact.sha256()).resolve("plugin.jar");
            rejectSymlink(idDirectory, "PLUGIN_CACHE_ID_SYMLINK");
            rejectSymlink(target, "PLUGIN_CACHE_ARTIFACT_SYMLINK");
            verifyFile(target, artifact.sha256(), artifact.sizeBytes());

            PluginArtifactRetention old = readRetention(artifact.pluginId());
            String previous = old.currentHash();

            if (artifact.sha256().equals(previous))
            {
                previous = old.previousHash();
            }

            PluginArtifactRetention next = new PluginArtifactRetention(artifact.pluginId(),
                artifact.sha256(), previous);
            writeRetention(next);
            return next;
        }
        catch (PluginArtifactStoreException error)
        {
            throw error;
        }
        catch (IOException | RuntimeException error)
        {
            throw new PluginArtifactStoreException("RETENTION_COMMIT", "cannot commit artifact retention pointer", error);
        }
    }

    public synchronized PluginArtifactRetention readRetention(String pluginId)
        throws PluginArtifactStoreException
    {
        try
        {
            Path idDirectory = childDirectory(this.root, pluginId);
            Path metadata = idDirectory.resolve(RETENTION_FILE);

            if (!Files.exists(metadata, NO_FOLLOW))
            {
                return new PluginArtifactRetention(pluginId, null, null);
            }

            rejectSymlink(metadata, "RETENTION_SYMLINK");
            long size = Files.size(metadata);

            if (size > 16 * 1024)
            {
                throw new PluginArtifactStoreException("RETENTION_SIZE", "retention metadata is too large");
            }

            JsonObject json = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();

            if (!json.has("schema") || !json.get("schema").isJsonPrimitive()
                || !json.get("schema").getAsJsonPrimitive().isNumber()
                || json.get("schema").getAsInt() != 1)
            {
                throw new PluginArtifactStoreException("RETENTION_SCHEMA", "unsupported retention metadata schema");
            }

            String id = getText(json, "id");
            String current = getOptionalHash(json, "current");
            String previous = getOptionalHash(json, "previous");

            if (!pluginId.equals(id))
            {
                throw new PluginArtifactStoreException("RETENTION_ID", "retention metadata id does not match its directory");
            }

            if (current != null)
            {
                verifyPointer(pluginId, current);
            }

            if (previous != null)
            {
                verifyPointer(pluginId, previous);
            }

            return new PluginArtifactRetention(pluginId, current, previous);
        }
        catch (PluginArtifactStoreException error)
        {
            throw error;
        }
        catch (IOException | RuntimeException error)
        {
            throw new PluginArtifactStoreException("RETENTION_READ", "cannot read retention metadata", error);
        }
    }

    public synchronized Optional<PluginArtifactReference> current(String pluginId)
        throws PluginArtifactStoreException
    {
        return resolve(pluginId, readRetention(pluginId).currentHash());
    }

    public synchronized Optional<PluginArtifactReference> previous(String pluginId)
        throws PluginArtifactStoreException
    {
        return resolve(pluginId, readRetention(pluginId).previousHash());
    }

    private Optional<PluginArtifactReference> resolve(String pluginId, String hash)
        throws PluginArtifactStoreException
    {
        if (hash == null)
        {
            return Optional.empty();
        }

        Path target = childDirectory(childDirectory(this.root, pluginId), hash).resolve("plugin.jar");
        rejectSymlink(target, "PLUGIN_CACHE_ARTIFACT_SYMLINK");

        try
        {
            long size = Files.size(target);
            verifyFile(target, hash, size);
            return Optional.of(new PluginArtifactReference(pluginId, hash, target, size));
        }
        catch (IOException error)
        {
            throw new PluginArtifactStoreException("RETENTION_TARGET", "retention target is unavailable", error);
        }
    }

    private void writeRetention(PluginArtifactRetention retention) throws IOException, PluginArtifactStoreException
    {
        Path idDirectory = childDirectory(this.root, retention.pluginId());

        if (Files.exists(idDirectory, NO_FOLLOW))
        {
            rejectSymlink(idDirectory, "PLUGIN_CACHE_ID_SYMLINK");
        }

        Files.createDirectories(idDirectory);
        rejectSymlink(idDirectory, "PLUGIN_CACHE_ID_SYMLINK");

        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.add("id", new JsonPrimitive(retention.pluginId()));

        if (retention.currentHash() != null)
        {
            json.add("current", new JsonPrimitive(retention.currentHash()));
        }

        if (retention.previousHash() != null)
        {
            json.add("previous", new JsonPrimitive(retention.previousHash()));
        }

        Path temporary = Files.createTempFile(idDirectory, ".retention-", ".tmp");

        try
        {
            Files.writeString(temporary, GSON.toJson(json), StandardOpenOption.TRUNCATE_EXISTING);

            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
            {
                channel.force(true);
            }

            moveAtomic(temporary, idDirectory.resolve(RETENTION_FILE));
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }
    }

    private static String getText(JsonObject json, String name) throws PluginArtifactStoreException
    {
        if (!json.has(name) || !json.get(name).isJsonPrimitive() || !json.get(name).getAsJsonPrimitive().isString())
        {
            throw new PluginArtifactStoreException("RETENTION_FIELD", "retention field '" + name + "' is missing or invalid");
        }

        return json.get(name).getAsString();
    }

    private static String getOptionalHash(JsonObject json, String name) throws PluginArtifactStoreException
    {
        if (!json.has(name))
        {
            return null;
        }

        String value = getText(json, name);

        if (!PluginArtifactNames.isSha256(value))
        {
            throw new PluginArtifactStoreException("RETENTION_HASH", "retention hash is invalid");
        }

        return value;
    }

    private void verifyPointer(String pluginId, String hash) throws IOException, PluginArtifactStoreException
    {
        Path target = childDirectory(childDirectory(this.root, pluginId), hash).resolve("plugin.jar");
        rejectSymlink(target, "PLUGIN_CACHE_ARTIFACT_SYMLINK");
        verifyFile(target, hash, Files.size(target));
    }

    private void copyAndVerify(Path source, Path target, String expectedHash, long expectedSize) throws IOException, PluginArtifactStoreException
    {
        MessageDigest digest = sha256();
        long copied = 0;

        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, NO_FOLLOW))
        {
            throw new PluginArtifactStoreException("SOURCE_TYPE", "validated source is no longer a regular non-symlink file");
        }

        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING))
        {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1)
            {
                copied += read;

                if (copied > this.limits.maxJarBytes())
                {
                    throw new PluginArtifactStoreException("SHADOW_SIZE", "source grew past the configured artifact limit");
                }

                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }

        String actual = HexFormat.of().formatHex(digest.digest());

        if (copied != expectedSize || !expectedHash.equals(actual))
        {
            throw new PluginArtifactStoreException("SOURCE_CHANGED", "source changed while creating shadow copy");
        }

        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
    }

    private static void verifyFile(Path target, String expectedHash, long expectedSize)
        throws IOException, PluginArtifactStoreException
    {
        if (!Files.isRegularFile(target, NO_FOLLOW) || Files.size(target) != expectedSize)
        {
            throw new PluginArtifactStoreException("SHADOW_TARGET", "shadow artifact target is not a regular file with the expected size");
        }

        MessageDigest digest = sha256();

        try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS))
        {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1)
            {
                digest.update(buffer, 0, read);
            }
        }

        if (!expectedHash.equals(HexFormat.of().formatHex(digest.digest())))
        {
            throw new PluginArtifactStoreException("SHADOW_HASH", "shadow artifact hash does not match its path");
        }
    }

    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException error)
        {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void moveAtomic(Path source, Path target) throws IOException, PluginArtifactStoreException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException error)
        {
            throw new PluginArtifactStoreException("ATOMIC_MOVE_UNSUPPORTED", "filesystem does not support atomic shadow publication", error);
        }
    }

    private static Path childDirectory(Path parent, String name) throws PluginArtifactStoreException
    {
        if (!PluginArtifactNames.isPluginId(name) && !PluginArtifactNames.isSha256(name))
        {
            throw new PluginArtifactStoreException("PATH_COMPONENT", "invalid cache path component");
        }

        Path child = parent.resolve(name).normalize();

        if (!child.startsWith(parent))
        {
            throw new PluginArtifactStoreException("PATH_CONTAINMENT", "cache path escapes its parent");
        }

        return child;
    }

    private static void rejectSymlink(Path path, String code) throws PluginArtifactStoreException
    {
        if (Files.isSymbolicLink(path))
        {
            throw new PluginArtifactStoreException(code, "cache path cannot be a symbolic link");
        }
    }
}
