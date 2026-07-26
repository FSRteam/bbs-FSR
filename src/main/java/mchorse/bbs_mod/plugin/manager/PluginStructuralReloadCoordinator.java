package mchorse.bbs_mod.plugin.manager;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Executes one complete structural generation replacement at a host safepoint. */
final class PluginStructuralReloadCoordinator
{
    @FunctionalInterface
    interface Safepoint
    {
        void run(Runnable operation);
    }

    private final Safepoint safepoint;
    private final BooleanSupplier busy;

    PluginStructuralReloadCoordinator(Safepoint safepoint, BooleanSupplier busy)
    {
        this.safepoint = Objects.requireNonNull(safepoint, "safepoint");
        this.busy = Objects.requireNonNull(busy, "busy");
    }

    void replace(
        PluginStructuralRegistrationWindow incumbent,
        PluginStructuralRegistrationWindow candidate,
        BiConsumer<String, Throwable> rebuildFailure
    )
    {
        boolean incumbentStructural = incumbent != null && incumbent.hasContributions();
        boolean candidateStructural = candidate != null && candidate.hasContributions();

        if (!incumbentStructural && !candidateStructural)
        {
            return;
        }

        this.safepoint.run(() ->
        {
            if (this.busy.getAsBoolean())
            {
                throw new ReloadBusyException();
            }

            PluginStructuralInstanceTracker.Snapshot snapshot = incumbent == null
                ? PluginStructuralInstanceTracker.snapshot(java.util.Set.of(), java.util.Set.of())
                : PluginStructuralInstanceTracker.snapshot(incumbent.formTypes(), incumbent.clipTypes());

            if (incumbent != null)
            {
                incumbent.deactivate();
            }

            try
            {
                if (candidate != null)
                {
                    candidate.activate();
                }
            }
            catch (Throwable error)
            {
                if (incumbent != null)
                {
                    try
                    {
                        incumbent.activate();
                    }
                    catch (Throwable restoreError)
                    {
                        error.addSuppressed(restoreError);
                    }
                }

                snapshot.rebuild(rebuildFailure);
                throw error;
            }

            snapshot.rebuild(rebuildFailure);
        });
    }

    static final class ReloadBusyException extends IllegalStateException
    {
        ReloadBusyException()
        {
            super("structural plugin reload is unavailable while recording or export is active");
        }
    }
}
