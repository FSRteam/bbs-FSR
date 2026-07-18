package mchorse.bbs_mod.network.compat;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic burst, shared-budget, summary, and bounded-key checks. */
public final class AddonBrokerDiagnosticLimiterTest
{
    private AddonBrokerDiagnosticLimiterTest()
    {}

    public static void main(String[] args)
    {
        run();
    }

    public static void run()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerDiagnosticLimiter limiter = new AddonBrokerDiagnosticLimiter(
            2,
            3,
            2,
            100L,
            now::get
        );

        check(limiter.acquire("player-a").allowed(), "first per-connection diagnostic was suppressed");
        check(limiter.acquire("player-a").allowed(), "second per-connection diagnostic was suppressed");
        check(!limiter.acquire("player-a").allowed(), "per-connection burst was not bounded");
        check(limiter.acquire("player-b").allowed(), "shared budget rejected its final admitted sample");
        check(!limiter.acquire("player-b").allowed(), "shared diagnostic burst was not bounded");

        now.set(100L);
        AddonBrokerDiagnosticLimiter.Decision resumedA = limiter.acquire("player-a");

        check(resumedA.allowed(), "new diagnostic window did not reopen");
        check(resumedA.connectionSuppressed() == 1L, "per-connection suppression summary was lost");
        check(resumedA.sharedSuppressed() == 2L, "shared suppression summary was lost");

        AddonBrokerDiagnosticLimiter.Decision resumedB = limiter.acquire("player-b");

        check(resumedB.allowed(), "second connection did not reopen in the new window");
        check(resumedB.connectionSuppressed() == 1L, "second connection suppression summary was lost");
        check(resumedB.sharedSuppressed() == 0L, "shared suppression summary was emitted more than once");

        AddonBrokerDiagnosticLimiter boundedKeys = new AddonBrokerDiagnosticLimiter(
            1,
            10,
            2,
            100L,
            now::get
        );

        boundedKeys.acquire("one");
        boundedKeys.acquire("two");
        boundedKeys.acquire("three");
        check(boundedKeys.trackedConnections() == 2, "connection diagnostic state grew beyond its bound");

        AddonBrokerDiagnosticLimiter clientGlobal = new AddonBrokerDiagnosticLimiter(
            2,
            2,
            1,
            100L,
            now::get
        );

        check(clientGlobal.acquire("server").allowed(), "first client diagnostic was suppressed");
        check(clientGlobal.acquire("server").allowed(), "second client diagnostic was suppressed");
        check(!clientGlobal.acquire("server").allowed(), "client diagnostic burst was not bounded");
        now.addAndGet(100L);

        AddonBrokerDiagnosticLimiter.Decision resumedClient = clientGlobal.acquire("server");

        check(resumedClient.allowed(), "client diagnostic window did not recover");
        check(resumedClient.sharedSuppressed() == 1L, "client suppression summary was lost");

        AddonBrokerDiagnosticLimiter cleanup = new AddonBrokerDiagnosticLimiter(
            1,
            4,
            2,
            100L,
            now::get
        );

        check(cleanup.acquire("old-connection").allowed(), "cleanup fixture did not admit its first event");
        check(cleanup.trackedConnections() == 1, "cleanup fixture did not track its connection");
        check(cleanup.clearConnection("old-connection") == 1,
            "exact diagnostic connection cleanup did not remove the old key");
        check(cleanup.clearConnection("old-connection") == 0,
            "diagnostic connection cleanup was not idempotent");
        check(cleanup.trackedConnections() == 0,
            "diagnostic connection cleanup retained stale state");

        cleanup.acquire("reset-connection");
        cleanup.reset();
        check(cleanup.trackedConnections() == 0,
            "diagnostic reset retained connection windows");
        check(cleanup.acquire("reset-connection").allowed(),
            "diagnostic reset did not restore the burst");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
