package mchorse.bbs_mod.plugin.artifact;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import mchorse.bbs_mod.api.plugin.BBSPluginArtifactLimits;
import mchorse.bbs_mod.api.plugin.BBSPluginCapability;
import mchorse.bbs_mod.api.plugin.BBSPluginKind;
import mchorse.bbs_mod.api.plugin.BBSPluginManifest;
import mchorse.bbs_mod.api.plugin.BBSPluginReloadMode;
import mchorse.bbs_mod.api.plugin.BBSPluginSide;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict, bounded decoder for manifest schema 1. */
public final class PluginManifestDecoder
{
    public static final String MANIFEST_PATH = BBSPluginManifest.PATH;

    private final BBSPluginArtifactLimits limits;

    public PluginManifestDecoder()
    {
        this(BBSPluginArtifactLimits.DEFAULT);
    }

    public PluginManifestDecoder(BBSPluginArtifactLimits limits)
    {
        this.limits = limits;
    }

    public BBSPluginManifest decode(byte[] bytes) throws PluginArtifactException
    {
        if (bytes == null || bytes.length == 0 || bytes.length > this.limits.maxManifestBytes())
        {
            throw invalid("MANIFEST_SIZE", "manifest exceeds the configured size limit");
        }

        try (InputStream input = new ByteArrayInputStream(bytes))
        {
            return decode(input);
        }
        catch (IOException error)
        {
            throw invalid("MANIFEST_READ", "cannot read manifest", error);
        }
    }

    public BBSPluginManifest decode(Path manifestFile) throws PluginArtifactException
    {
        if (manifestFile == null)
        {
            throw invalid("MANIFEST_NULL", "manifest path is null");
        }

        try (InputStream input = Files.newInputStream(manifestFile, LinkOption.NOFOLLOW_LINKS))
        {
            return decode(input);
        }
        catch (IOException error)
        {
            throw invalid("MANIFEST_READ", "cannot read manifest path", error);
        }
    }

    public BBSPluginManifest decode(InputStream input) throws PluginArtifactException
    {
        if (input == null)
        {
            throw invalid("MANIFEST_NULL", "manifest input is null");
        }

        byte[] bounded;

        try
        {
            bounded = readBounded(input);
        }
        catch (IOException error)
        {
            throw invalid("MANIFEST_READ", "cannot read manifest", error);
        }

        try (Reader chars = new InputStreamReader(new ByteArrayInputStream(bounded), StandardCharsets.UTF_8))
        {
            return decodeReader(chars);
        }
        catch (IOException | IllegalStateException | NumberFormatException error)
        {
            throw invalid("MANIFEST_JSON", "malformed manifest JSON", error);
        }
    }

    private BBSPluginManifest decodeReader(Reader chars) throws IOException, PluginArtifactException
    {
        JsonReader reader = new JsonReader(chars);
        reader.setLenient(false);
        reader.beginObject();

        Set<String> fields = new HashSet<>();
        Integer schema = null;
        String kindName = null;
        String id = null;
        String displayName = null;
        String version = null;
        String api = null;
        String commonEntrypoint = null;
        String clientEntrypoint = null;
        String sideName = null;
        Set<BBSPluginCapability> capabilities = new LinkedHashSet<>();
        List<String> dependencies = new ArrayList<>();
        String reloadName = BBSPluginReloadMode.HOT.wireName();

        while (reader.hasNext())
        {
            String name = reader.nextName();

            if (!fields.add(name))
            {
                throw invalid("MANIFEST_DUPLICATE_FIELD", "duplicate manifest field '" + name + "'");
            }

            switch (name)
            {
                case "schema" -> schema = nextInt(reader, "schema");
                case "kind" -> kindName = nextText(reader, "kind");
                case "id" -> id = nextText(reader, "id");
                case "displayName" -> displayName = nextText(reader, "displayName");
                case "version" -> version = nextText(reader, "version");
                case "api" -> api = nextText(reader, "api");
                case "commonEntrypoint" -> commonEntrypoint = nextNullableText(reader, "commonEntrypoint");
                case "clientEntrypoint" -> clientEntrypoint = nextNullableText(reader, "clientEntrypoint");
                case "side" -> sideName = nextText(reader, "side");
                case "capabilities" -> readCapabilities(reader, capabilities);
                case "dependencies" -> readDependencies(reader, dependencies);
                case "reload" -> reloadName = nextText(reader, "reload");
                default -> throw invalid("MANIFEST_UNKNOWN_FIELD", "unknown manifest field '" + name + "'");
            }
        }

        reader.endObject();

        if (reader.peek() != JsonToken.END_DOCUMENT)
        {
            throw invalid("MANIFEST_TRAILING_DATA", "manifest contains trailing JSON data");
        }

        if (schema == null || schema != BBSPluginManifest.SCHEMA_VERSION)
        {
            throw invalid("MANIFEST_SCHEMA", "unsupported manifest schema '" + schema + "'");
        }

        if (kindName == null || id == null || version == null || api == null || sideName == null)
        {
            throw invalid("MANIFEST_REQUIRED_FIELD", "manifest is missing a required field");
        }

        final BBSPluginKind kind;
        final BBSPluginSide side;
        final BBSPluginReloadMode reload;

        try
        {
            kind = BBSPluginKind.fromWireName(kindName);
            side = BBSPluginSide.fromWireName(sideName);
            reload = BBSPluginReloadMode.fromWireName(reloadName);
        }
        catch (IllegalArgumentException error)
        {
            throw invalid("MANIFEST_ENUM", error.getMessage(), error);
        }

        try
        {
            return new BBSPluginManifest(BBSPluginManifest.SCHEMA_VERSION, kind, id, displayName, version, api,
                commonEntrypoint, clientEntrypoint, side, capabilities, dependencies, reload);
        }
        catch (IllegalArgumentException error)
        {
            throw invalid("MANIFEST_VALUE", error.getMessage(), error);
        }
    }

    private void readCapabilities(JsonReader reader, Set<BBSPluginCapability> target)
        throws IOException, PluginArtifactException
    {
        if (reader.peek() != JsonToken.BEGIN_ARRAY)
        {
            throw invalid("MANIFEST_CAPABILITIES", "capabilities must be an array");
        }

        reader.beginArray();
        int count = 0;

        while (reader.hasNext())
        {
            if (++count > this.limits.maxCollectionEntries())
            {
                throw invalid("MANIFEST_COLLECTION_SIZE", "too many capabilities");
            }

            String value = nextText(reader, "capability");

            try
            {
                if (!target.add(BBSPluginCapability.fromWireName(value)))
                {
                    throw invalid("MANIFEST_DUPLICATE_CAPABILITY", "duplicate capability '" + value + "'");
                }
            }
            catch (IllegalArgumentException error)
            {
                throw invalid("MANIFEST_CAPABILITY", error.getMessage(), error);
            }
        }

        reader.endArray();
    }

    private void readDependencies(JsonReader reader, List<String> target)
        throws IOException, PluginArtifactException
    {
        if (reader.peek() != JsonToken.BEGIN_ARRAY)
        {
            throw invalid("MANIFEST_DEPENDENCIES", "dependencies must be an array");
        }

        reader.beginArray();
        Set<String> unique = new HashSet<>();
        int count = 0;

        while (reader.hasNext())
        {
            if (++count > this.limits.maxCollectionEntries())
            {
                throw invalid("MANIFEST_COLLECTION_SIZE", "too many dependencies");
            }

            String value = nextText(reader, "dependency");

            if (!unique.add(value))
            {
                throw invalid("MANIFEST_DUPLICATE_DEPENDENCY", "duplicate dependency '" + value + "'");
            }

            target.add(value);
        }

        reader.endArray();
    }

    private String nextText(JsonReader reader, String field) throws IOException, PluginArtifactException
    {
        if (reader.peek() != JsonToken.STRING)
        {
            throw invalid("MANIFEST_FIELD_TYPE", field + " must be a string");
        }

        String value = reader.nextString();

        if (value.isBlank() || value.length() > this.limits.maxStringChars()
            || value.chars().anyMatch(character -> character < 0x20))
        {
            throw invalid("MANIFEST_STRING_SIZE", field + " is blank or exceeds the string limit");
        }

        return value;
    }

    private String nextNullableText(JsonReader reader, String field) throws IOException, PluginArtifactException
    {
        if (reader.peek() == JsonToken.NULL)
        {
            reader.nextNull();
            return null;
        }

        return nextText(reader, field);
    }

    private int nextInt(JsonReader reader, String field) throws IOException, PluginArtifactException
    {
        if (reader.peek() != JsonToken.NUMBER)
        {
            throw invalid("MANIFEST_FIELD_TYPE", field + " must be an integer");
        }

        try
        {
            return reader.nextInt();
        }
        catch (NumberFormatException error)
        {
            throw invalid("MANIFEST_FIELD_TYPE", field + " must be an integer", error);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException, PluginArtifactException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(this.limits.maxManifestBytes(), 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;

        while ((read = input.read(buffer)) != -1)
        {
            total += read;

            if (total > this.limits.maxManifestBytes())
            {
                throw invalid("MANIFEST_SIZE", "manifest exceeds the configured size limit");
            }

            output.write(buffer, 0, read);
        }

        return output.toByteArray();
    }

    private static PluginArtifactException invalid(String code, String message)
    {
        return new PluginArtifactException(PluginArtifactStatus.INVALID, code, message);
    }

    private static PluginArtifactException invalid(String code, String message, Throwable cause)
    {
        return new PluginArtifactException(PluginArtifactStatus.INVALID, code, message, cause);
    }
}
