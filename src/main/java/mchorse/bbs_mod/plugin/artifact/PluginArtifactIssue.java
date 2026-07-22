package mchorse.bbs_mod.plugin.artifact;

import java.util.Objects;

/** Stable validation issue; it does not retain an exception or plugin class. */
public record PluginArtifactIssue(String code, String message)
{
    public PluginArtifactIssue
    {
        code = require(code, "code", 128);
        message = require(message, "message", 2048);
    }

    private static String require(String value, String name, int max)
    {
        Objects.requireNonNull(value, name);

        if (value.isBlank() || value.length() > max)
        {
            throw new IllegalArgumentException(name + " is blank or too long");
        }

        return value;
    }
}
