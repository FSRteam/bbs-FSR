package mchorse.bbs_mod.network;

import java.util.UUID;

final class NetworkMutationSessionsTest
{
    public static void main(String[] args)
    {
        runAll();

        System.out.println("NetworkMutationSessionsTest passed");
    }

    static void runAll()
    {
        connectionLimitIsReservedBeforeStartupWork();
        connectionReplacementCleanupIsExact();
        globalLimitAndResetReleaseCapacity();
    }

    private static void connectionLimitIsReservedBeforeStartupWork()
    {
        check(NetworkMutationSessions.MAX_ACTIVE_FILMS_PER_CONNECTION == 4,
            "the exact-connection active film limit changed unexpectedly");

        NetworkMutationSessions sessions = new NetworkMutationSessions();
        UUID owner = UUID.randomUUID();
        Object connection = new Object();
        Object otherConnection = new Object();
        String dimension = "minecraft:overworld";

        for (int i = 0; i < NetworkMutationSessions.MAX_ACTIVE_FILMS_PER_CONNECTION; i++)
        {
            check(sessions.claimFilm("connection/" + i, owner, connection, dimension),
                "the exact connection was rejected below its active film limit");
        }

        check(sessions.claimFilm("connection/0", owner, connection, dimension),
            "an exact canonical film reservation was not idempotent at the connection limit");
        check(!sessions.claimFilm("connection/overflow", owner, connection, dimension),
            "a fifth active film was admitted for one exact connection");
        check(!sessions.hasFilm("connection/overflow"),
            "a rejected reservation mutated active film state before upstream loading");
        check(sessions.claimRecording("connection/0", owner, connection, dimension, 0, 0),
            "recording did not reuse its already-reserved film quota");
        check(!sessions.claimFilm("connection/overflow", owner, connection, dimension),
            "recording changed the four-film exact-connection boundary");

        sessions.releaseFilm("connection/0", UUID.randomUUID(), connection);
        sessions.releaseFilm("connection/0", owner, otherConnection);
        check(!sessions.claimFilm("connection/overflow", owner, connection, dimension),
            "an inexact startup rollback released another owner/connection reservation");

        sessions.releaseRecording(owner, connection);
        sessions.releaseFilm("connection/0", owner, connection);
        check(sessions.claimFilm("connection/overflow", owner, connection, dimension),
            "an exact startup/forced-stop rollback did not restore connection capacity");
    }

    private static void connectionReplacementCleanupIsExact()
    {
        NetworkMutationSessions sessions = new NetworkMutationSessions();
        UUID owner = UUID.randomUUID();
        Object oldConnection = new Object();
        Object newConnection = new Object();
        String dimension = "minecraft:overworld";

        for (int i = 0; i < NetworkMutationSessions.MAX_ACTIVE_FILMS_PER_CONNECTION; i++)
        {
            check(sessions.claimFilm("old/" + i, owner, oldConnection, dimension),
                "the old connection could not reserve its boundary session");
            check(sessions.claimFilm("new/" + i, owner, newConnection, dimension),
                "the replacement connection inherited the old connection's quota");
        }

        int releasedFilms = sessions.clearOwner(owner, oldConnection).size();

        check(releasedFilms == NetworkMutationSessions.MAX_ACTIVE_FILMS_PER_CONNECTION,
            "old logout did not release every exact retired-connection film");

        check(!sessions.hasFilm("old/0"),
            "old logout retained an exact retired-connection film session");
        check(sessions.ownsFilm("new/0", owner, newConnection, dimension),
            "a delayed old logout cleared the replacement connection's film session");
        check(!sessions.claimFilm("new/overflow", owner, newConnection, dimension),
            "old logout also released replacement-connection quota");
    }

    private static void globalLimitAndResetReleaseCapacity()
    {
        check(NetworkMutationSessions.MAX_ACTIVE_FILMS_GLOBAL == 64,
            "the global active film limit changed unexpectedly");

        NetworkMutationSessions sessions = new NetworkMutationSessions();
        UUID firstOwner = UUID.randomUUID();
        Object firstConnection = new Object();
        String dimension = "minecraft:overworld";

        for (int i = 0; i < NetworkMutationSessions.MAX_ACTIVE_FILMS_GLOBAL; i++)
        {
            UUID owner = i == 0 ? firstOwner : UUID.randomUUID();
            Object connection = i == 0 ? firstConnection : new Object();

            check(sessions.claimFilm("global/" + i, owner, connection, dimension),
                "a globally bounded film reservation was rejected below 64 sessions");
        }

        check(sessions.claimFilm("global/0", firstOwner, firstConnection, dimension),
            "an exact canonical film reservation was not idempotent at the global limit");
        check(!sessions.claimFilm("global/overflow", UUID.randomUUID(), new Object(), dimension),
            "a sixty-fifth active film was admitted globally");
        check(!sessions.hasFilm("global/overflow"),
            "a rejected global reservation mutated active film state");

        sessions.releaseFilm("global/0", firstOwner, firstConnection);
        check(sessions.claimFilm("global/reused", UUID.randomUUID(), new Object(), dimension),
            "an exact release did not restore global film capacity");

        sessions.reset();

        check(!sessions.hasFilm("global/reused"), "reset retained an active film reservation");
        check(sessions.claimFilm("global/after-reset", UUID.randomUUID(), new Object(), dimension),
            "reset did not restore global film capacity");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
