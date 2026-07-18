package mchorse.bbs_mod.addon.v2;

import mchorse.bbs_mod.api.addon.BBSAddonPhase;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

import java.util.Objects;
import java.util.function.Supplier;

/** One callback-scoped admission window shared by every registration facade. */
final class BBSAddonRegistrationWindow
{
    private final BBSAddonPhase phase;
    private final String source;
    private boolean open = true;

    BBSAddonRegistrationWindow(BBSAddonPhase phase, String source)
    {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.source = source == null || source.isBlank() ? "<unknown>" : source;
    }

    synchronized BBSRegistrationResult run(
        BBSAddonDiagnosticRecord diagnostics,
        String id,
        String facade,
        Supplier<BBSRegistrationResult> action
    )
    {
        String key = id == null || id.isBlank() ? "<blank>" : id;

        if (!this.open)
        {
            String reason = "registration facade '" + facade + "' is closed after phase=" + this.phase + " source=" + this.source;

            diagnostics.warn(reason);

            return diagnostics.record(BBSRegistrationResult.rejected(key, reason));
        }

        if (this.phase != BBSAddonPhase.REGISTER_COMMON)
        {
            String reason = "registration facade '" + facade + "' is not available during phase=" + this.phase + " source=" + this.source;

            diagnostics.warn(reason);

            return diagnostics.record(BBSRegistrationResult.rejected(key, reason));
        }

        return action.get();
    }

    synchronized void close()
    {
        this.open = false;
    }
}
