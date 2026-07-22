package mchorse.bbs_mod.api.plugin;

/** Bounded parsing and artifact limits used by the host before entrypoint loading. */
public record BBSPluginArtifactLimits(long maxJarBytes, int maxEntryCount, long maxEntryBytes,
                                      long maxTotalUncompressedBytes, int maxManifestBytes,
                                      int maxStringChars, int maxCollectionEntries,
                                      int maxEntryNameChars)
{
    public static final BBSPluginArtifactLimits DEFAULT = new BBSPluginArtifactLimits(
        64L * 1024L * 1024L,
        16_384,
        32L * 1024L * 1024L,
        256L * 1024L * 1024L,
        64 * 1024,
        256,
        128,
        512
    );

    public BBSPluginArtifactLimits
    {
        if (maxJarBytes <= 0 || maxEntryCount <= 0 || maxEntryBytes <= 0
            || maxTotalUncompressedBytes <= 0 || maxManifestBytes <= 0
            || maxStringChars <= 0 || maxCollectionEntries <= 0 || maxEntryNameChars <= 0)
        {
            throw new IllegalArgumentException("plugin artifact limits must be positive");
        }

        if (maxEntryBytes > maxTotalUncompressedBytes)
        {
            throw new IllegalArgumentException("maxEntryBytes cannot exceed maxTotalUncompressedBytes");
        }
    }
}
