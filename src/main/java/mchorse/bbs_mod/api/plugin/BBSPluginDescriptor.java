package mchorse.bbs_mod.api.plugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable runtime identity passed to a plugin generation context. */
public record BBSPluginDescriptor(int schema, BBSPluginKind kind, String id, String displayName,
                                  String version, String api, String commonEntrypoint,
                                  String clientEntrypoint, BBSPluginSide side,
                                  Set<BBSPluginCapability> capabilities, List<String> dependencies,
                                  BBSPluginReloadMode reload)
{
    public BBSPluginDescriptor
    {
        if (schema != BBSPluginManifest.SCHEMA_VERSION)
        {
            throw new IllegalArgumentException("unsupported plugin descriptor schema '" + schema + "'");
        }

        kind = Objects.requireNonNull(kind, "kind");
        id = Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        version = Objects.requireNonNull(version, "version");
        api = Objects.requireNonNull(api, "api");
        side = Objects.requireNonNull(side, "side");
        reload = Objects.requireNonNull(reload, "reload");
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities == null ? Set.of() : capabilities));
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    }

    public static BBSPluginDescriptor from(BBSPluginManifest manifest)
    {
        return Objects.requireNonNull(manifest, "manifest").descriptor();
    }
}
