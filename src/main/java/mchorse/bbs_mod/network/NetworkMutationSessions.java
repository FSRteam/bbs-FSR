package mchorse.bbs_mod.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Tracks the server-authoritative sessions that allow a client to mutate
 * world model blocks or a running film. Network payloads never create these
 * sessions by themselves: a real model-block interaction or a successful
 * film start must establish ownership first.
 */
final class NetworkMutationSessions
{
    static final long MODEL_BLOCK_IDLE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(15L);
    static final int MAX_ACTIVE_FILMS_PER_CONNECTION = 4;
    static final int MAX_ACTIVE_FILMS_GLOBAL = 64;

    private final Map<UUID, ModelBlockSession> modelBlockSessions = new HashMap<>();
    private final Map<String, FilmSession> filmSessions = new HashMap<>();
    private final Map<UUID, RecordingSession> recordingSessions = new HashMap<>();
    private final LongSupplier nanoClock;

    NetworkMutationSessions()
    {
        this(System::nanoTime);
    }

    NetworkMutationSessions(LongSupplier nanoClock)
    {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public synchronized void openModelBlockSession(UUID owner, Object connectionIdentity, String dimension, long blockPos)
    {
        if (owner == null || connectionIdentity == null || dimension == null || dimension.isBlank())
        {
            return;
        }

        this.modelBlockSessions.put(owner, new ModelBlockSession(connectionIdentity, dimension, blockPos, this.nanoClock.getAsLong()));
    }

    public synchronized boolean refreshModelBlockSession(UUID owner, Object connectionIdentity, String dimension, long blockPos)
    {
        if (!this.hasModelBlockSession(owner, connectionIdentity, dimension, blockPos))
        {
            return false;
        }

        this.modelBlockSessions.put(owner, new ModelBlockSession(connectionIdentity, dimension, blockPos, this.nanoClock.getAsLong()));

        return true;
    }

    public synchronized boolean hasModelBlockSession(UUID owner, Object connectionIdentity, String dimension, long blockPos)
    {
        if (owner == null || connectionIdentity == null || dimension == null)
        {
            return false;
        }

        ModelBlockSession session = this.modelBlockSessions.get(owner);

        if (session == null || session.connectionIdentity != connectionIdentity)
        {
            return false;
        }

        if (!session.dimension.equals(dimension))
        {
            this.modelBlockSessions.remove(owner);

            return false;
        }

        if (session.blockPos != blockPos)
        {
            return false;
        }

        if (this.nanoClock.getAsLong() - session.lastActivityNanos >= MODEL_BLOCK_IDLE_TIMEOUT_NANOS)
        {
            this.modelBlockSessions.remove(owner);

            return false;
        }

        return true;
    }

    /**
     * Reserve one canonical film id before loading, spawning, or notifying the
     * client. Callers must release the reservation if later startup work fails.
     * Repeating the exact reservation is idempotent and consumes no new quota.
     */
    public synchronized boolean claimFilm(String filmId, UUID owner, Object connectionIdentity, String dimension)
    {
        if (filmId == null || filmId.isBlank() || owner == null || connectionIdentity == null || dimension == null || dimension.isBlank())
        {
            return false;
        }

        FilmSession existing = this.filmSessions.get(filmId);

        if (existing != null)
        {
            return existing.owner.equals(owner)
                && existing.connectionIdentity == connectionIdentity
                && existing.dimension.equals(dimension);
        }

        if (this.filmSessions.size() >= MAX_ACTIVE_FILMS_GLOBAL
            || this.countFilms(connectionIdentity) >= MAX_ACTIVE_FILMS_PER_CONNECTION)
        {
            return false;
        }

        this.filmSessions.put(filmId, new FilmSession(owner, connectionIdentity, dimension));

        return true;
    }

    public synchronized boolean ownsFilm(String filmId, UUID owner, Object connectionIdentity, String dimension)
    {
        if (filmId == null || owner == null || connectionIdentity == null || dimension == null)
        {
            return false;
        }

        FilmSession session = this.filmSessions.get(filmId);

        return session != null
            && session.owner.equals(owner)
            && session.connectionIdentity == connectionIdentity
            && session.dimension.equals(dimension);
    }

    public synchronized boolean ownsFilm(String filmId, UUID owner, Object connectionIdentity)
    {
        FilmSession session = filmId == null ? null : this.filmSessions.get(filmId);

        return session != null
            && owner != null
            && connectionIdentity != null
            && session.owner.equals(owner)
            && session.connectionIdentity == connectionIdentity;
    }

    public synchronized boolean hasFilm(String filmId)
    {
        return filmId != null && this.filmSessions.containsKey(filmId);
    }

    public synchronized void releaseFilm(String filmId, UUID owner, Object connectionIdentity)
    {
        FilmSession session = this.filmSessions.get(filmId);

        if (session != null
            && session.owner.equals(owner)
            && session.connectionIdentity == connectionIdentity)
        {
            this.filmSessions.remove(filmId);
        }
    }

    public synchronized boolean claimRecording(
        String filmId,
        UUID owner,
        Object connectionIdentity,
        String dimension,
        int replayId,
        int tick
    )
    {
        if (!this.ownsFilm(filmId, owner, connectionIdentity, dimension) || this.recordingSessions.containsKey(owner))
        {
            return false;
        }

        this.recordingSessions.put(owner, new RecordingSession(connectionIdentity, filmId, dimension, replayId, tick));

        return true;
    }

    public synchronized RecordingSession getRecording(UUID owner, Object connectionIdentity)
    {
        RecordingSession session = owner == null ? null : this.recordingSessions.get(owner);

        return session != null && session.connectionIdentity == connectionIdentity ? session : null;
    }

    public synchronized RecordingSession releaseRecording(UUID owner, Object connectionIdentity)
    {
        RecordingSession session = owner == null ? null : this.recordingSessions.get(owner);

        return session != null && session.connectionIdentity == connectionIdentity
            ? this.recordingSessions.remove(owner)
            : null;
    }

    /**
     * Clear every session owned by a disconnected player and return films that
     * should be stopped by the server lifecycle owner.
     */
    public synchronized List<String> clearOwner(UUID owner, Object connectionIdentity)
    {
        if (owner == null || connectionIdentity == null)
        {
            return List.of();
        }

        ModelBlockSession modelBlock = this.modelBlockSessions.get(owner);

        if (modelBlock != null && modelBlock.connectionIdentity == connectionIdentity)
        {
            this.modelBlockSessions.remove(owner);
        }

        RecordingSession recording = this.recordingSessions.get(owner);

        if (recording != null && recording.connectionIdentity == connectionIdentity)
        {
            this.recordingSessions.remove(owner);
        }

        List<String> films = new ArrayList<>();

        this.filmSessions.entrySet().removeIf((entry) ->
        {
            if (entry.getValue().owner.equals(owner)
                && entry.getValue().connectionIdentity == connectionIdentity)
            {
                films.add(entry.getKey());

                return true;
            }

            return false;
        });

        return List.copyOf(films);
    }

    public synchronized void reset()
    {
        this.modelBlockSessions.clear();
        this.filmSessions.clear();
        this.recordingSessions.clear();
    }

    private int countFilms(Object connectionIdentity)
    {
        int count = 0;

        for (FilmSession session : this.filmSessions.values())
        {
            if (session.connectionIdentity == connectionIdentity)
            {
                count += 1;
            }
        }

        return count;
    }

    private record ModelBlockSession(Object connectionIdentity, String dimension, long blockPos, long lastActivityNanos)
    {}

    private record FilmSession(UUID owner, Object connectionIdentity, String dimension)
    {}

    record RecordingSession(Object connectionIdentity, String filmId, String dimension, int replayId, int tick)
    {}
}
