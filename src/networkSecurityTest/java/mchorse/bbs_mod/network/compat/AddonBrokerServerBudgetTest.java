package mchorse.bbs_mod.network.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class AddonBrokerServerBudgetTest
{
    private AddonBrokerServerBudgetTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("AddonBrokerServerBudgetTest passed");
    }

    public static void runAll()
    {
        testMessageAndByteAdmissionIsAtomic();
        testDefaultFrameCompatibility();
        testGlobalAdmissionBoundsManyConnections();
        testAddonScopesHaveIndependentCredit();
        testReplacementConnectionUsesIdentityScope();
        testIdleCapacityAndReset();
        testRejectedScopeDoesNotRefreshIdleLifetime();
        testClockRollbackDoesNotRefill();
        testInvalidInputsAndLimits();
        testBrokerWiringPrecedesReceiverDispatch();
        testNetworkLifecycleWiring();
    }

    private static void testMessageAndByteAdmissionIsAtomic()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget messages = budget(now, 2, 1, 100, 50, 20, 10, 1_000, 500, 100L, 1_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(messages.tryAcquire(owner, connection, "addon-a", 10), "the first broker message was rejected");
        check(messages.tryAcquire(owner, connection, "addon-a", 10), "the second broker message was rejected");
        check(!messages.tryAcquire(owner, connection, "addon-a", 0), "the per-addon message burst was bypassed");

        AddonBrokerServerBudget bytes = budget(now, 3, 1, 20, 10, 3, 1, 25, 10, 100L, 1_000L, 8);
        Object byteConnection = new Object();

        check(bytes.tryAcquire(owner, byteConnection, "addon-a", 15), "a body within byte capacity was rejected");
        check(!bytes.tryAcquire(owner, byteConnection, "addon-a", 6), "the per-addon byte burst was bypassed");
        check(bytes.tryAcquire(owner, byteConnection, "addon-b", 10),
            "a rejected scope reservation partially consumed a global token");
        check(bytes.tryAcquire(owner, byteConnection, "addon-a", 0),
            "a rejected byte reservation partially consumed its scope message token");
    }

    private static void testDefaultFrameCompatibility()
    {
        UUID owner = UUID.randomUUID();
        Object serverConnection = new Object();
        AddonBrokerServerBudget server = new AddonBrokerServerBudget();

        for (int i = 0; i < 18; i++)
        {
            check(server.tryAcquire(owner, serverConnection, "addon-a", 28 * 1024),
                "default C2S budget rejected legal max frame " + i);
        }

        check(!server.tryAcquire(owner, serverConnection, "addon-a", 28 * 1024),
            "default C2S byte burst was bypassed");
        check(server.tryAcquire(owner, serverConnection, "addon-b", 28 * 1024),
            "one C2S addon exhausted another addon's default scope");

        Object clientGeneration = new Object();
        AddonBrokerServerBudget client = AddonBrokerServerBudget.clientDefaults();
        int maximumClientBody = 1024 * 1024 - 4096;

        for (int i = 0; i < 4; i++)
        {
            check(client.tryAcquire(owner, clientGeneration, "addon-a", maximumClientBody),
                "default S2C budget rejected legal near-1MiB frame " + i);
        }

        check(!client.tryAcquire(owner, clientGeneration, "addon-a", maximumClientBody),
            "default S2C per-addon byte burst was bypassed");
        check(client.tryAcquire(owner, clientGeneration, "addon-b", maximumClientBody),
            "one S2C addon exhausted another addon's default scope");
        check(client.clearConnection(owner, clientGeneration) == 2,
            "client generation cleanup did not remove every receiver addon scope");
    }

    private static void testGlobalAdmissionBoundsManyConnections()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 2, 1, 100, 50, 2, 1, 100, 50, 100L, 1_000L, 8);

        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 10), "the first global message was rejected");
        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 10), "the second global message was rejected");
        check(!budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 0), "many connections bypassed the global message burst");

        now.set(100L);
        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 10), "the global refill boundary did not restore credit");
    }

    private static void testAddonScopesHaveIndependentCredit()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 1_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(budget.tryAcquire(owner, connection, "addon-a", 1),
            "the first addon could not spend its scope token");
        check(!budget.tryAcquire(owner, connection, "addon-a", 1),
            "one addon bypassed its isolated scope burst");
        check(budget.tryAcquire(owner, connection, "addon-b", 1),
            "one addon's debt leaked into another receiver addon");
        check(budget.size() == 2, "two receiver addons did not create two bounded scopes");
        check(budget.clearConnection(owner, connection) == 2,
            "exact cleanup did not remove every addon scope for the connection");
    }

    private static void testReplacementConnectionUsesIdentityScope()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 1_000L, 8);
        UUID owner = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection("same-owner");
        EqualConnection replacement = new EqualConnection("same-owner");

        check(oldConnection != replacement && oldConnection.equals(replacement),
            "the replacement fixture must be equal but non-identical");
        check(budget.tryAcquire(owner, oldConnection, "addon-a", 1), "the old connection could not spend its token");
        check(!budget.tryAcquire(owner, oldConnection, "addon-a", 1), "the old connection exceeded its message burst");
        check(budget.tryAcquire(owner, replacement, "addon-a", 1), "the replacement inherited old connection debt");
        check(budget.clearConnection(owner, oldConnection) == 1, "old logout did not clear its exact broker state");
        check(budget.size() == 1, "old logout cleared the replacement broker state");
        check(!budget.tryAcquire(owner, replacement, "addon-a", 1), "old logout refilled replacement message credit");
    }

    private static void testIdleCapacityAndReset()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 500L, 2);

        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 1), "the first scope was rejected");
        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 1), "the second scope was rejected");
        check(!budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 1), "the broker scope-state bound was bypassed");

        now.set(500L);
        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 1), "idle broker states were not reclaimed for admission");
        check(budget.size() == 1, "idle admission did not remove every expired broker state");

        budget.reset();
        check(budget.size() == 0, "broker reset retained connection state");
        check(budget.tryAcquire(UUID.randomUUID(), new Object(), "addon-a", 100), "broker reset did not restore global byte credit");
    }

    private static void testRejectedScopeDoesNotRefreshIdleLifetime()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 500L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(budget.tryAcquire(owner, connection, "addon-a", 1),
            "the initial scope admission was rejected");

        now.set(50L);
        check(!budget.tryAcquire(owner, connection, "addon-a", 1),
            "a partially refilled scope unexpectedly admitted a second message");

        now.set(500L);
        check(budget.expireIdle() == 1 && budget.size() == 0,
            "a rejected scope attempt refreshed its idle lifetime");
    }

    private static void testClockRollbackDoesNotRefill()
    {
        AtomicLong now = new AtomicLong(1_000L);
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 1_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(budget.tryAcquire(owner, connection, "addon-a", 1), "the initial message token was rejected");
        now.set(900L);
        check(!budget.tryAcquire(owner, connection, "addon-a", 1), "clock rollback granted a broker message token");
        now.set(1_100L);
        check(budget.tryAcquire(owner, connection, "addon-a", 1), "monotonic recovery did not refill the broker token");
    }

    private static void testInvalidInputsAndLimits()
    {
        AtomicLong now = new AtomicLong();
        AddonBrokerServerBudget budget = budget(now, 1, 1, 100, 50, 10, 10, 1_000, 500, 100L, 1_000L, 8);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(!budget.tryAcquire(null, connection, "addon-a", 1), "a missing owner created broker state");
        check(!budget.tryAcquire(owner, null, "addon-a", 1), "a missing connection created broker state");
        check(!budget.tryAcquire(owner, connection, null, 1), "a missing receiver addon created broker state");
        check(!budget.tryAcquire(owner, connection, " ", 1), "a blank receiver addon created broker state");
        check(!budget.tryAcquire(owner, connection, "addon-a", -1), "a negative body size was accepted");
        check(budget.clearConnection(null, connection) == 0 && budget.clearConnection(owner, null) == 0,
            "invalid exact cleanup removed broker state");

        expectInvalid(() -> budget(now, 0, 1, 1, 1, 1, 1, 1, 1, 100L, 1_000L, 8),
            "zero connection message capacity was accepted");
        expectInvalid(() -> budget(now, 2, 1, 1, 1, 1, 1, 1, 1, 100L, 1_000L, 8),
            "global capacity smaller than one connection was accepted");
        expectInvalid(() -> budget(now, 1, 1, 1, 1, 1, 1, 1, 1, 0L, 1_000L, 8),
            "zero refill period was accepted");
        expectInvalid(() -> budget(now, 1, 1, 1, 1, 1, 1, 1, 1, 100L, 0L, 8),
            "zero idle timeout was accepted");
        expectInvalid(() -> budget(now, 1, 1, 1, 1, 1, 1, 1, 1, 100L, 1_000L, 0),
            "zero connection-state bound was accepted");
    }

    private static void testBrokerWiringPrecedesReceiverDispatch()
    {
        String source;

        try
        {
            source = Files.readString(Path.of("src/main/java/mchorse/bbs_mod/network/compat/AddonPayloadBroker.java"))
                .replaceAll("\\s+", " ");
        }
        catch (IOException exception)
        {
            throw new AssertionError("could not inspect addon broker budget wiring", exception);
        }

        assertOrdered(source,
            "public static void handleServerPayload",
            "BrokerFrame brokerFrame = readFrame",
            "SERVER_RECEIVERS.get(brokerFrame.id)",
            "if (receiver == null)",
            "String receiverOwner =",
            "receiver == null ? hotSelection.owner.pluginId() : receiver.ownerAddonId",
            "SERVER_BUDGET.tryAcquire(",
            "receiverOwner,",
            "server.execute(",
            "server.getPlayerList().getPlayer(player.getUUID()) != player",
            "queuedReceiver.receiver.receive");
        check(source.contains("clearServerConnection(UUID owner, Object connectionIdentity)"),
            "addon broker exposes no exact logout cleanup");
        check(source.contains("expireServerBudgetIdle()") && source.contains("resetServerBudget()"),
            "addon broker exposes no idle/reset lifecycle entry points");
        check(source.contains("SERVER_DIAGNOSTICS.clearConnection(owner.toString())")
                && source.contains("SERVER_DIAGNOSTICS.reset()")
                && source.contains("CLIENT_DIAGNOSTICS.reset()"),
            "addon broker diagnostics did not follow connection/reset lifecycle");
        assertOrdered(source,
            "Object connectionIdentity, Consumer<Runnable> dispatcher",
            "BrokerFrame brokerFrame = readFrame",
            "CLIENT_RECEIVERS.get(brokerFrame.id)",
            "if (receiver == null)",
            "if (connectionIdentity == null)",
            "String receiverOwner =",
            "receiver == null ? hotSelection.owner.pluginId() : receiver.ownerAddonId",
            "CLIENT_BUDGET.tryAcquire(",
            "receiverOwner,",
            "queuedReceiver.receiver.receive",
            "dispatcher.accept(delivery)");
        check(source.contains("handleClientPayload(frame, null, dispatcher)"),
            "legacy client broker overload does not fail closed without an exact identity");
        check(source.contains("clearClientConnection(Object connectionIdentity)")
                && source.contains("expireClientBudgetIdle()")
                && source.contains("resetClientBudget()"),
            "client broker exposes no exact cleanup/idle/reset lifecycle entry points");
    }

    private static void testNetworkLifecycleWiring()
    {
        String serverSource;
        String clientSource;

        try
        {
            serverSource = Files.readString(Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"))
                .replaceAll("\\s+", " ");
            clientSource = Files.readString(Path.of("src/client/java/mchorse/bbs_mod/network/ClientNetwork.java"))
                .replaceAll("\\s+", " ");
        }
        catch (IOException exception)
        {
            throw new AssertionError("could not inspect addon broker lifecycle wiring", exception);
        }

        assertOrdered(serverSource,
            "public static void reset()",
            "AddonPayloadBroker.resetServerBudget()");
        assertOrdered(serverSource,
            "private static void handleServerTick",
            "AddonPayloadBroker.expireServerBudgetIdle()");
        assertOrdered(serverSource,
            "private static void cleanupLoggedOutPlayer",
            "AddonPayloadBroker.clearServerConnection(playerId, player)");

        assertOrdered(clientSource,
            "public static void resetHandshake()",
            "UUID previous = connectionGate.rotate(getCurrentTransport(client), client.player)",
            "crusher.clearOwner(previous)",
            "AddonPayloadBroker.clearClientConnection(previous)",
            "AddonPayloadBroker.resetClientBudget()",
            "callbacks.reset()");
        assertOrdered(clientSource,
            "registerClientReceiver(ServerNetwork.CLIENT_ADDON_BROKER, ClientNetwork::handleAddonBrokerPacket)",
            "private static void handleAddonBrokerPacket",
            "AddonPayloadBroker.handleClientPayload(",
            "scope.generation()",
            "executeIfCurrent(client, scope, true, task)");
        assertOrdered(clientSource,
            "private static void handleClientTick",
            "crusher.expireIdleTransfers()",
            "AddonPayloadBroker.expireClientBudgetIdle()");
    }

    private static AddonBrokerServerBudget budget(
        AtomicLong now,
        long connectionMessageCapacity,
        long connectionMessageRefill,
        long connectionByteCapacity,
        long connectionByteRefill,
        long globalMessageCapacity,
        long globalMessageRefill,
        long globalByteCapacity,
        long globalByteRefill,
        long refillPeriod,
        long idleTimeout,
        int maxConnections
    )
    {
        return new AddonBrokerServerBudget(
            now::get,
            new AddonBrokerServerBudget.Limits(
                new AddonBrokerServerBudget.Rate(connectionMessageCapacity, connectionMessageRefill),
                new AddonBrokerServerBudget.Rate(connectionByteCapacity, connectionByteRefill),
                new AddonBrokerServerBudget.Rate(globalMessageCapacity, globalMessageRefill),
                new AddonBrokerServerBudget.Rate(globalByteCapacity, globalByteRefill),
                refillPeriod,
                idleTimeout,
                maxConnections
            )
        );
    }

    private static void expectInvalid(Runnable runnable, String message)
    {
        boolean rejected = false;

        try
        {
            runnable.run();
        }
        catch (IllegalArgumentException exception)
        {
            rejected = true;
        }

        check(rejected, message);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int index = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, index + 1);

            check(next >= 0, "missing addon broker marker: " + marker);
            check(next > index, "addon broker marker is out of order: " + marker);
            index = next;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private record EqualConnection(String id)
    {}
}
