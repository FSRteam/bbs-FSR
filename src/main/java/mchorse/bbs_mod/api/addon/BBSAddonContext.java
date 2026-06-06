package mchorse.bbs_mod.api.addon;

import mchorse.bbs_mod.api.diagnostics.BBSAddonDiagnosticSink;

import java.nio.file.Path;
import java.util.Optional;

public interface BBSAddonContext
{
    BBSAddonDescriptor descriptor();

    BBSAddonDiagnosticSink diagnostics();

    Path gameDir();

    boolean isDevelopmentEnvironment();

    boolean isModLoaded(String modId);

    Optional<String> getModVersion(String modId);
}
