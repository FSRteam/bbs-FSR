package mchorse.bbs_mod.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class NetworkZoomSessionsTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkZoomSessionsTest passed");
    }

    static void runAll()
    {
        testOffUsesCommandCapturedAtZoomOn();
        testDuplicateStatesAndTransitionInterval();
        testOwnersAreIndependent();
        testReplacementConnectionCannotBeClearedByOldLogout();
        testDisconnectAndResetCleanup();
        testGracefulServerDrainIsExactlyOnce();
        testClockRollbackDoesNotBypassCooldown();
        testInvalidInputsAndEmptyCommands();
        testServerWiringUsesExactConnectionIdentity();
    }

    private static void testOffUsesCommandCapturedAtZoomOn()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        NetworkZoomSessions.Transition on = sessions.turnOn(owner, connection, "gun_a_on", "gun_a_off");

        check(on.accepted() && on.command().equals("gun_a_on"), "zoom-on did not return the validated stack's command");
        check(sessions.isActive(owner, connection), "zoom-on did not establish an active session");
        check(!sessions.turnOn(owner, connection, "gun_b_on", "gun_b_off").accepted(), "a duplicate on state replaced the captured stack commands");

        NetworkZoomSessions.Transition off = sessions.turnOff(owner, connection);

        check(off.accepted() && off.command().equals("gun_a_off"), "zoom-off did not use the command captured before the held item changed");
        check(!sessions.isActive(owner, connection), "zoom-off left the session active");
        check(!sessions.turnOff(owner, connection).accepted(), "a duplicate off state produced another command transition");
    }

    private static void testDuplicateStatesAndTransitionInterval()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(sessions.turnOn(owner, connection, "on", "off").accepted(), "the initial on transition was rejected");

        now.set(10L);

        check(sessions.turnOff(owner, connection).accepted(), "a rapid off transition must remain available for fail-safe cleanup");

        now.set(109L);

        check(!sessions.turnOn(owner, connection, "on", "off").accepted(), "zoom-on bypassed the minimum transition interval");

        now.set(110L);

        check(sessions.turnOn(owner, connection, "on", "off").accepted(), "zoom-on was rejected at the exact cooldown boundary");
    }

    private static void testOwnersAreIndependent()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();

        check(sessions.turnOn(alice, aliceConnection, "alice_on", "alice_off").accepted(), "Alice's initial transition was rejected");
        check(sessions.turnOn(bob, bobConnection, "bob_on", "bob_off").accepted(), "Alice's state blocked Bob's independent session");
        check(sessions.turnOff(alice, aliceConnection).command().equals("alice_off"), "Alice received another owner's off command");
        check(sessions.turnOff(bob, bobConnection).command().equals("bob_off"), "Bob received another owner's off command");
    }

    private static void testReplacementConnectionCannotBeClearedByOldLogout()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID owner = UUID.randomUUID();
        EqualConnection oldConnection = new EqualConnection("same-owner");
        EqualConnection replacementConnection = new EqualConnection("same-owner");

        check(oldConnection != replacementConnection && oldConnection.equals(replacementConnection),
            "the replacement regression requires equal but non-identical connection tokens");
        check(sessions.turnOn(owner, oldConnection, "old_on", "old_off").accepted(),
            "the old connection could not establish its zoom state");
        check(sessions.turnOn(owner, replacementConnection, "new_on", "new_off").accepted(),
            "a stale equal connection token blocked the replacement connection");
        check(sessions.size() == 2, "equal connection tokens collapsed into one zoom state");

        NetworkZoomSessions.Transition oldCleanup = sessions.clearOwner(owner, oldConnection);

        check(oldCleanup.accepted() && oldCleanup.command().equals("old_off"),
            "old logout did not return its own captured cleanup command");
        check(!sessions.isActive(owner, oldConnection), "old logout retained its exact connection state");
        check(sessions.isActive(owner, replacementConnection), "old logout cleared the replacement connection state");
        check(!sessions.turnOff(owner, oldConnection).accepted(),
            "the retired connection could control the replacement zoom state");
        check(sessions.turnOff(owner, replacementConnection).command().equals("new_off"),
            "the replacement connection lost its captured cleanup command");
    }

    private static void testDisconnectAndResetCleanup()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object bobConnection = new Object();

        sessions.turnOn(alice, aliceConnection, "alice_on", "alice_off");

        NetworkZoomSessions.Transition cleanup = sessions.clearOwner(alice, aliceConnection);

        check(cleanup.accepted() && cleanup.command().equals("alice_off"), "disconnect did not return the active session's cleanup command");
        check(sessions.size() == 0, "disconnect retained the owner's zoom state");
        check(!sessions.clearOwner(alice, aliceConnection).accepted(), "duplicate disconnect cleanup produced another command");

        sessions.turnOn(bob, bobConnection, "bob_on", "bob_off");
        sessions.reset();

        check(sessions.size() == 0 && !sessions.isActive(bob, bobConnection), "server reset retained zoom sessions");
    }

    private static void testClockRollbackDoesNotBypassCooldown()
    {
        AtomicLong now = new AtomicLong(100L);
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(sessions.turnOn(owner, connection, "on", "off").accepted(), "the initial on transition was rejected");

        now.set(50L);

        check(sessions.turnOff(owner, connection).accepted(), "clock rollback prevented fail-safe zoom-off");

        now.set(199L);

        check(!sessions.turnOn(owner, connection, "on", "off").accepted(), "clock rollback moved the cooldown baseline backwards");

        now.set(200L);

        check(sessions.turnOn(owner, connection, "on", "off").accepted(), "new monotonic time did not release the cooldown");
    }

    private static void testGracefulServerDrainIsExactlyOnce()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Object aliceConnection = new Object();
        Object aliceReplacement = new Object();
        Object bobConnection = new Object();

        sessions.turnOn(alice, aliceConnection, "alice_on", "alice_off");
        sessions.turnOn(alice, aliceReplacement, "alice_replacement_on", "alice_replacement_off");
        sessions.turnOn(bob, bobConnection, "bob_on", "bob_off");
        sessions.turnOff(bob, bobConnection);

        java.util.List<NetworkZoomSessions.ActiveTransition> drained = sessions.drainActive();

        check(drained.size() == 2, "server stop did not retain both exact active connection cleanups");
        check(hasDrain(drained, alice, aliceConnection, "alice_off"),
            "server stop lost the original connection cleanup identity");
        check(hasDrain(drained, alice, aliceReplacement, "alice_replacement_off"),
            "server stop lost the replacement connection cleanup identity");
        check(sessions.drainActive().isEmpty(), "a second server stop drained the same zoom cleanup twice");
        check(sessions.size() == 0, "graceful server drain retained zoom state");
    }

    private static void testInvalidInputsAndEmptyCommands()
    {
        AtomicLong now = new AtomicLong();
        NetworkZoomSessions sessions = new NetworkZoomSessions(now::get, 100L);
        UUID owner = UUID.randomUUID();
        Object connection = new Object();

        check(!sessions.turnOn(null, connection, "on", "off").accepted(), "a missing owner established a zoom session");
        check(!sessions.turnOn(owner, null, "on", "off").accepted(), "a missing connection established a zoom session");
        check(!sessions.turnOff(null, connection).accepted(), "a missing owner produced an off transition");
        check(!sessions.turnOff(owner, null).accepted(), "a missing connection produced an off transition");

        NetworkZoomSessions.Transition on = sessions.turnOn(owner, connection, null, null);
        NetworkZoomSessions.Transition off = sessions.turnOff(owner, connection);

        check(on.accepted() && !on.hasCommand(), "an empty on command should still establish a commandless transition");
        check(off.accepted() && !off.hasCommand(), "an empty captured off command should still close the transition once");

        boolean rejectedInterval = false;

        try
        {
            new NetworkZoomSessions(now::get, 0L);
        }
        catch (IllegalArgumentException e)
        {
            rejectedInterval = true;
        }

        check(rejectedInterval, "a non-positive transition interval was accepted");
    }

    private static void testServerWiringUsesExactConnectionIdentity()
    {
        String source;

        try
        {
            source = Files.readString(Path.of("src/main/java/mchorse/bbs_mod/network/ServerNetwork.java"));
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read ServerNetwork zoom wiring", e);
        }

        check(source.contains("List<NetworkZoomSessions.ActiveTransition> zoomCleanup"),
            "server reset still expects the obsolete owner-only zoom drain");
        check(source.contains("player == active.connectionIdentity()"),
            "server reset can execute an old connection's off command on its replacement");
        check(source.contains("zoomSessions.clearOwner(playerId, player)"),
            "logout cleanup is not bound to the exact connection identity");
        check(source.contains("zoomSessions.turnOn(owner, player, properties.cmdZoomOn, properties.cmdZoomOff)"),
            "zoom-on state is not bound to the exact connection identity");
        check(source.contains("zoomSessions.turnOff(owner, player)"),
            "zoom-off can control another connection with the same UUID");
    }

    private static boolean hasDrain(
        java.util.List<NetworkZoomSessions.ActiveTransition> drained,
        UUID owner,
        Object connectionIdentity,
        String command
    )
    {
        for (NetworkZoomSessions.ActiveTransition active : drained)
        {
            if (owner.equals(active.owner())
                && active.connectionIdentity() == connectionIdentity
                && active.transition().command().equals(command))
            {
                return true;
            }
        }

        return false;
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
