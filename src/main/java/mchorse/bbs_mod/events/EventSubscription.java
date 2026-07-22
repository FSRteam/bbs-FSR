package mchorse.bbs_mod.events;

/** A removable group of handlers registered on the internal BBS event bus. */
@FunctionalInterface
public interface EventSubscription extends AutoCloseable
{
    @Override
    void close();
}
