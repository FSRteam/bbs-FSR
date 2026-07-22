package mchorse.bbs_mod.api.plugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed representation of {@code META-INF/bbs-plugin.json} schema 1. */
public record BBSPluginManifest(int schema, BBSPluginKind kind, String id, String displayName,
                                String version, String api, String commonEntrypoint,
                                String clientEntrypoint, BBSPluginSide side,
                                Set<BBSPluginCapability> capabilities, List<String> dependencies,
                                BBSPluginReloadMode reload)
{
    public static final int SCHEMA_VERSION = 1;
    public static final String PATH = "META-INF/bbs-plugin.json";

    public BBSPluginManifest
    {
        if (schema != SCHEMA_VERSION)
        {
            throw new IllegalArgumentException("unsupported plugin manifest schema '" + schema + "'");
        }

        kind = Objects.requireNonNull(kind, "kind");
        id = requireNonBlank(id, "id");
        version = requireNonBlank(version, "version");
        api = requireNonBlank(api, "api");
        side = Objects.requireNonNull(side, "side");
        reload = Objects.requireNonNull(reload, "reload");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        capabilities = immutableCapabilities(capabilities);
        dependencies = immutableStrings(dependencies);
    }

    public BBSPluginDescriptor descriptor()
    {
        return new BBSPluginDescriptor(schema, kind, id, displayName, version, api,
            commonEntrypoint, clientEntrypoint, side, capabilities, dependencies, reload);
    }

    private static Set<BBSPluginCapability> immutableCapabilities(Set<BBSPluginCapability> values)
    {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }

    private static List<String> immutableStrings(List<String> values)
    {
        return List.copyOf(values == null ? List.of() : values);
    }

    private static String requireNonBlank(String value, String name)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(name + " is blank");
        }

        return value;
    }
}
