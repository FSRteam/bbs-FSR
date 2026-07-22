package mchorse.bbs_mod.api.plugin;

import java.util.Objects;

/** A bounded, serializable diagnostic snapshot. It deliberately has no Throwable or plugin object reference. */
public record BBSPluginDiagnostic(long timestampMillis, BBSPluginDiagnosticSeverity severity,
                                  String code, String message, String errorType)
{
    public BBSPluginDiagnostic
    {
        severity = Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code", 128);
        message = requireText(message, "message", 2048);
        errorType = errorType == null ? "" : limit(errorType, 256, "errorType");
    }

    public BBSPluginDiagnostic(long timestampMillis, BBSPluginDiagnosticSeverity severity,
                               String code, String message)
    {
        this(timestampMillis, severity, code, message, "");
    }

    private static String requireText(String value, String name, int max)
    {
        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException(name + " is blank");
        }

        return limit(value, max, name);
    }

    private static String limit(String value, int max, String name)
    {
        if (value.length() > max)
        {
            throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        }

        return value;
    }
}
