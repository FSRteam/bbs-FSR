package mchorse.bbs_mod.network;

import java.util.UUID;

/**
 * Monotonic client connection generation used to fence work that was decoded
 * on a network thread but has not yet run on the client thread.
 */
final class NetworkConnectionGate
{
    private UUID generation = UUID.randomUUID();
    private Object connectionIdentity;
    private Object playerIdentity;
    private TransferIdentity transferIdentity;
    private Object retiredConnectionIdentity;
    private Object retiredPlayerIdentity;

    /**
     * Atomically binds the current generation to one exact client transport and
     * LocalPlayer identity. The caller supplies its live Minecraft identities so
     * a delayed payload cannot claim an unbound post-logout generation.
     */
    public synchronized Scope capture(
        Object connectionIdentity,
        Object playerIdentity,
        Object currentConnectionIdentity,
        Object currentPlayerIdentity
    )
    {
        boolean playerReplaced = false;
        Object retiredTransferIdentity = null;

        if (connectionIdentity == null
            || playerIdentity == null
            || connectionIdentity != currentConnectionIdentity
            || playerIdentity != currentPlayerIdentity
            || connectionIdentity == this.retiredConnectionIdentity
            || playerIdentity == this.retiredPlayerIdentity)
        {
            return null;
        }

        if (this.connectionIdentity == null)
        {
            this.connectionIdentity = connectionIdentity;
            this.playerIdentity = playerIdentity;
            this.transferIdentity = new TransferIdentity();
        }
        else if (this.connectionIdentity != connectionIdentity)
        {
            return null;
        }
        else if (this.playerIdentity != playerIdentity)
        {
            /* Respawn/dimension replacement can install a new LocalPlayer on
             * the same live transport. Retire the old player scope atomically
             * and split every in-flight chunk transfer with a new token. */
            this.retiredPlayerIdentity = this.playerIdentity;
            retiredTransferIdentity = this.transferIdentity;
            this.transferIdentity.retire();
            this.playerIdentity = playerIdentity;
            this.transferIdentity = new TransferIdentity();
            playerReplaced = true;
        }

        return new Scope(
            this.generation,
            this.connectionIdentity,
            this.playerIdentity,
            this.transferIdentity,
            playerReplaced,
            retiredTransferIdentity
        );
    }

    public synchronized boolean isCurrent(
        Scope candidate,
        Object currentConnectionIdentity,
        Object currentPlayerIdentity
    )
    {
        return candidate != null
            && this.generation.equals(candidate.generation)
            && this.connectionIdentity == candidate.connectionIdentity
            && this.playerIdentity == candidate.playerIdentity
            && this.transferIdentity == candidate.transferIdentity
            && currentConnectionIdentity == candidate.connectionIdentity
            && currentPlayerIdentity == candidate.playerIdentity;
    }

    /**
     * Main-thread respawn/dimension lifecycle hook. It retires the old player
     * even when no BBS payload has yet bound this generation, then installs a
     * fresh transfer token for the replacement player on the same transport.
     */
    public synchronized Scope replacePlayer(
        Object connectionIdentity,
        Object oldPlayerIdentity,
        Object newPlayerIdentity,
        Object currentConnectionIdentity,
        Object currentPlayerIdentity
    )
    {
        if (connectionIdentity == null
            || oldPlayerIdentity == null
            || newPlayerIdentity == null
            || oldPlayerIdentity == newPlayerIdentity
            || connectionIdentity != currentConnectionIdentity
            || newPlayerIdentity != currentPlayerIdentity
            || connectionIdentity == this.retiredConnectionIdentity
            || newPlayerIdentity == this.retiredPlayerIdentity)
        {
            return null;
        }

        if (this.connectionIdentity == null)
        {
            this.connectionIdentity = connectionIdentity;
        }
        else if (this.connectionIdentity != connectionIdentity)
        {
            return null;
        }

        if (this.playerIdentity != null
            && this.playerIdentity != oldPlayerIdentity
            && this.playerIdentity != newPlayerIdentity)
        {
            return null;
        }

        boolean playerReplaced = this.playerIdentity != newPlayerIdentity;
        Object retiredTransferIdentity = playerReplaced ? this.transferIdentity : null;

        if (playerReplaced && this.transferIdentity != null)
        {
            this.transferIdentity.retire();
        }

        this.retiredPlayerIdentity = oldPlayerIdentity;
        this.playerIdentity = newPlayerIdentity;

        if (playerReplaced || this.transferIdentity == null)
        {
            this.transferIdentity = new TransferIdentity();
        }

        return new Scope(
            this.generation,
            this.connectionIdentity,
            this.playerIdentity,
            this.transferIdentity,
            playerReplaced,
            retiredTransferIdentity
        );
    }

    /** Legacy generation-only probe retained for isolated compatibility tests. */
    public synchronized UUID snapshot()
    {
        return this.generation;
    }

    /** Legacy generation-only probe retained for isolated compatibility tests. */
    public synchronized boolean isCurrent(UUID candidate)
    {
        return candidate != null && this.generation.equals(candidate);
    }

    public synchronized UUID rotate()
    {
        return this.rotate(null, null);
    }

    public synchronized UUID rotate(Object currentConnectionIdentity, Object currentPlayerIdentity)
    {
        UUID previous = this.generation;

        this.retiredConnectionIdentity = this.connectionIdentity != null
            ? this.connectionIdentity
            : currentConnectionIdentity;
        this.retiredPlayerIdentity = this.playerIdentity != null
            ? this.playerIdentity
            : currentPlayerIdentity;

        if (this.transferIdentity != null)
        {
            this.transferIdentity.retire();
        }

        this.connectionIdentity = null;
        this.playerIdentity = null;
        this.transferIdentity = null;
        this.generation = UUID.randomUUID();

        return previous;
    }

    static final class Scope
    {
        private final UUID generation;
        private final Object connectionIdentity;
        private final Object playerIdentity;
        private final Object transferIdentity;
        private final boolean playerReplaced;
        private final Object retiredTransferIdentity;

        private Scope(
            UUID generation,
            Object connectionIdentity,
            Object playerIdentity,
            Object transferIdentity,
            boolean playerReplaced,
            Object retiredTransferIdentity
        )
        {
            this.generation = generation;
            this.connectionIdentity = connectionIdentity;
            this.playerIdentity = playerIdentity;
            this.transferIdentity = transferIdentity;
            this.playerReplaced = playerReplaced;
            this.retiredTransferIdentity = retiredTransferIdentity;
        }

        UUID generation()
        {
            return this.generation;
        }

        Object connectionIdentity()
        {
            return this.connectionIdentity;
        }

        Object playerIdentity()
        {
            return this.playerIdentity;
        }

        Object transferIdentity()
        {
            return this.transferIdentity;
        }

        boolean playerReplaced()
        {
            return this.playerReplaced;
        }

        Object retiredTransferIdentity()
        {
            return this.retiredTransferIdentity;
        }
    }

    interface RetirementAwareConnectionIdentity
    {
        boolean isRetired();
    }

    private static final class TransferIdentity implements RetirementAwareConnectionIdentity
    {
        private volatile boolean retired;

        private void retire()
        {
            this.retired = true;
        }

        @Override
        public boolean isRetired()
        {
            return this.retired;
        }
    }
}
