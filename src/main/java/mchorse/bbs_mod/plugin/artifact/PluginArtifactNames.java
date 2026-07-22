package mchorse.bbs_mod.plugin.artifact;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class PluginArtifactNames
{
    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9](?:[a-z0-9_.-]{0,62}[a-z0-9])?");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
        "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private PluginArtifactNames() {}

    static boolean isPluginId(String value)
    {
        if (value == null || !PLUGIN_ID.matcher(value).matches() || value.contains(".."))
        {
            return false;
        }

        String lower = value.toLowerCase(Locale.ROOT);
        String deviceName = lower.contains(".") ? lower.substring(0, lower.indexOf('.')) : lower;

        return !WINDOWS_RESERVED_NAMES.contains(deviceName);
    }

    static boolean isSha256(String value)
    {
        return value != null && SHA_256.matcher(value).matches();
    }
}
