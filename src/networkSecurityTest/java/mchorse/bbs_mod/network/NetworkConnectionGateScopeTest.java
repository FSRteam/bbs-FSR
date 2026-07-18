package mchorse.bbs_mod.network;

import java.util.UUID;

/** Behavioral regressions for delayed client transport/player scopes. */
final class NetworkConnectionGateScopeTest
{
    private NetworkConnectionGateScopeTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkConnectionGateScopeTest passed");
    }

    static void runAll()
    {
        NetworkConnectionGate gate = new NetworkConnectionGate();
        Object oldTransport = new Object();
        Object oldPlayer = new Object();
        NetworkConnectionGate.Scope oldScope = gate.capture(oldTransport, oldPlayer, oldTransport, oldPlayer);

        check(oldScope != null, "the initial exact client transport scope was rejected");
        check(gate.isCurrent(oldScope, oldTransport, oldPlayer), "the initial exact client transport scope was not current");

        UUID retiredGeneration = gate.rotate(oldTransport, oldPlayer);

        check(retiredGeneration.equals(oldScope.generation()), "transport rotation did not retire the bound generation");
        check(gate.capture(oldTransport, oldPlayer, oldTransport, oldPlayer) == null,
            "an old transport claimed the new generation after logout rotation while client fields were still stale");

        Object newTransport = new Object();
        Object newPlayer = new Object();
        NetworkConnectionGate.Scope newScope = gate.capture(newTransport, newPlayer, newTransport, newPlayer);

        check(newScope != null, "the replacement exact client transport scope was rejected");
        check(!gate.isCurrent(oldScope, newTransport, newPlayer), "queued work from the retired transport became current again");
        check(gate.isCurrent(newScope, newTransport, newPlayer), "the replacement transport scope was not current");

        Object respawnedPlayer = new Object();
        NetworkConnectionGate.Scope respawnScope = gate.replacePlayer(
            newTransport,
            newPlayer,
            respawnedPlayer,
            newTransport,
            respawnedPlayer
        );

        check(respawnScope != null && respawnScope.playerReplaced(),
            "an exact LocalPlayer replacement on the current transport did not establish a replacement scope");
        check(respawnScope.generation().equals(newScope.generation()),
            "a LocalPlayer replacement unexpectedly changed the transport generation");
        check(respawnScope.transferIdentity() != newScope.transferIdentity(),
            "a LocalPlayer replacement reused the old chunk-transfer identity");
        check(respawnScope.retiredTransferIdentity() == newScope.transferIdentity(),
            "a LocalPlayer replacement did not return the exact retired chunk-transfer identity");
        check(newScope.transferIdentity() instanceof NetworkConnectionGate.RetirementAwareConnectionIdentity retired
                && retired.isRetired(),
            "a LocalPlayer replacement returned an old token that was not atomically retired");
        check(respawnScope.transferIdentity() instanceof NetworkConnectionGate.RetirementAwareConnectionIdentity current
                && !current.isRetired(),
            "a LocalPlayer replacement created an already-retired current token");
        check(!gate.isCurrent(newScope, newTransport, respawnedPlayer),
            "queued work from the replaced LocalPlayer remained current");
        check(gate.isCurrent(respawnScope, newTransport, respawnedPlayer),
            "the exact replacement LocalPlayer scope was not current");
        check(gate.capture(newTransport, newPlayer, newTransport, respawnedPlayer) == null,
            "the replaced LocalPlayer reclaimed the live transport scope");

        Object logoutToken = respawnScope.transferIdentity();

        gate.rotate(newTransport, respawnedPlayer);
        check(logoutToken instanceof NetworkConnectionGate.RetirementAwareConnectionIdentity retired
                && retired.isRetired(),
            "logout rotation did not retire its current chunk-transfer token");

        NetworkConnectionGate unboundGate = new NetworkConnectionGate();
        Object unboundTransport = new Object();
        Object unboundOldPlayer = new Object();
        Object unboundNewPlayer = new Object();
        NetworkConnectionGate.Scope unboundClone = unboundGate.replacePlayer(
            unboundTransport,
            unboundOldPlayer,
            unboundNewPlayer,
            unboundTransport,
            unboundNewPlayer
        );

        check(unboundClone != null && unboundGate.isCurrent(unboundClone, unboundTransport, unboundNewPlayer),
            "Clone could not retire/rebind a player before the first BBS payload bound the transport generation");
        check(unboundClone.retiredTransferIdentity() == null,
            "an unbound Clone invented a retired chunk-transfer identity");
        check(unboundGate.capture(unboundTransport, unboundOldPlayer, unboundTransport, unboundOldPlayer) == null,
            "the Clone-retired player claimed an unbound transport generation after lifecycle replacement");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
