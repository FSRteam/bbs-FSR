package mchorse.bbs_mod.api.diagnostics;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSAddonDiagnosticSink
{
    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);

    BBSRegistrationResult record(BBSRegistrationResult result);
}
