package mchorse.bbs_mod.plugin.manager;

import mchorse.bbs_mod.plugin.runtime.PluginContributionLedger;
import mchorse.bbs_mod.plugin.runtime.PluginOwner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coverage for the "reject reload while recording/export is active" contract owned by
 * {@link PluginStructuralReloadCoordinator}. The real busy predicate lives in
 * {@code BBSPluginClientStructuralBridge.isBusy()}, a client-only bridge that requires a
 * real NeoForge/Mixin launch and is unreachable from a bare {@code JavaExec}; but the
 * coordinator's busy predicate is a plain injectable {@link java.util.function.BooleanSupplier},
 * so this test drives it directly with a real {@link PluginStructuralRegistrationWindow}
 * generation swap - exactly the shape {@code BBSPluginManager} wires it with in production.
 */
public final class PluginStructuralReloadBusyRejectionTest
{
    private PluginStructuralReloadBusyRejectionTest() {}

    public static void main(String[] args) throws Exception
    {
        rejectsWhileBusyThenAcceptsTheSameIntentOnceClear();

        System.out.println("PluginStructuralReloadBusyRejectionTest: all tests passed");
    }

    private static void rejectsWhileBusyThenAcceptsTheSameIntentOnceClear() throws Exception
    {
        AtomicBoolean busy = new AtomicBoolean(true);
        PluginStructuralReloadCoordinator coordinator = new PluginStructuralReloadCoordinator(Runnable::run, busy::get);

        PluginOwner incumbentOwner = new PluginOwner("busy-fixture", 1L);
        PluginContributionLedger incumbentLedger = new PluginContributionLedger(incumbentOwner);
        PluginStructuralRegistrationWindow incumbent = new PluginStructuralRegistrationWindow(incumbentOwner);
        List<String> incumbentEvents = new ArrayList<>();
        stageDummy(incumbent, incumbentLedger, "incumbent", incumbentEvents);
        incumbent.activate();

        PluginOwner candidateOwner = new PluginOwner("busy-fixture", 2L);
        PluginContributionLedger candidateLedger = new PluginContributionLedger(candidateOwner);
        PluginStructuralRegistrationWindow candidate = new PluginStructuralRegistrationWindow(candidateOwner, incumbent.keys());
        List<String> candidateEvents = new ArrayList<>();
        stageDummy(candidate, candidateLedger, "candidate", candidateEvents);

        List<String> failures = new ArrayList<>();

        try
        {
            coordinator.replace(incumbent, candidate, (type, error) -> failures.add(type));
            throw new AssertionError("reload should have been rejected while busy");
        }
        catch (PluginStructuralReloadCoordinator.ReloadBusyException expected)
        {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(),
                "busy rejection must carry a human-readable message");
        }

        check(incumbentEvents.equals(List.of("apply:incumbent")),
            "busy rejection must leave the incumbent generation active (no snapshot/teardown)");
        check(candidateEvents.isEmpty(),
            "busy rejection must not activate the candidate generation");
        check(failures.isEmpty(),
            "busy rejection must not run any rebuild-failure callback");

        /* Same intent, busy cleared: the swap must now go through normally. */
        busy.set(false);
        coordinator.replace(incumbent, candidate, (type, error) -> failures.add(type));

        check(incumbentEvents.equals(List.of("apply:incumbent", "undo:incumbent")),
            "clearing busy must retire the incumbent generation exactly once (no revival)");
        check(candidateEvents.equals(List.of("apply:candidate")),
            "clearing busy must activate the candidate generation for the same intent");
        check(failures.isEmpty(), "a contentless swap must not report any rebuild failure");

        incumbentLedger.close();
        candidateLedger.close();
    }

    private static void stageDummy(
        PluginStructuralRegistrationWindow window,
        PluginContributionLedger ledger,
        String label,
        List<String> events
    )
    {
        window.stage(
            "dummy",
            PluginStructuralRegistrationWindow.Kind.FORM,
            DummyType.class,
            ledger,
            () -> events.add("apply:" + label),
            () -> events.add("undo:" + label)
        );
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class DummyType
    {}
}
