package mchorse.bbs_mod.api.client.events;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * An event whose listeners are handed over one at a time, and which is fired as a plain loop.
 *
 * <p>The addon bus finds its subscribers by reflection and keys them by parameter type, which is
 * exactly the wrong trade for an event that runs every tick or every frame: the lookup would sit
 * on the hot path, and a listener could not be a lambda at all. So the events that fire per frame
 * carry a table of their own instead — {@code register} adds to it, {@code invoker()} is the
 * composite that walks it in registration order, and a host with nothing registered walks an empty
 * list. That is the whole of the no-listener cost.</p>
 *
 * <p>Registration is not tied to an addon's lifecycle: a listener stays until
 * {@link #unregister(Object)} takes it away, so an addon that follows a per-film object has to
 * detach it on the same event that created it.</p>
 */
public final class FunctionalEvent<T>
{
    private final List<T> listeners = new CopyOnWriteArrayList<>();
    private final T dispatcher;

    /**
     * @param combiner makes the composite that walks the listeners. It is called once, here, and
     *                 handed this event's live list, so the composite reads it at fire time and
     *                 never has to be rebuilt.
     */
    public FunctionalEvent(Function<List<T>, T> combiner)
    {
        this.dispatcher = Objects.requireNonNull(combiner, "combiner").apply(this.listeners);
    }

    /** Adds a listener; listeners fire in the order they were registered. */
    public void register(T listener)
    {
        if (listener != null)
        {
            this.listeners.add(listener);
        }
    }

    /** Takes a listener back out; returns whether it was there. */
    public boolean unregister(T listener)
    {
        return listener != null && this.listeners.remove(listener);
    }

    /** The composite to fire. Meaningless to call when nothing is registered, and harmless. */
    public T invoker()
    {
        return this.dispatcher;
    }

    public boolean hasListeners()
    {
        return !this.listeners.isEmpty();
    }
}
