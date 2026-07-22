package mchorse.bbs_mod.api.plugin;

/** Host-owned diagnostic sink for one generation. Implementations must keep history bounded. */
public interface BBSPluginDiagnosticSink
{
    void report(BBSPluginDiagnosticSeverity severity, String code, String message);

    default void info(String code, String message)
    {
        report(BBSPluginDiagnosticSeverity.INFO, code, message);
    }

    default void warn(String code, String message)
    {
        report(BBSPluginDiagnosticSeverity.WARNING, code, message);
    }

    default void error(String code, String message)
    {
        report(BBSPluginDiagnosticSeverity.ERROR, code, message);
    }
}
