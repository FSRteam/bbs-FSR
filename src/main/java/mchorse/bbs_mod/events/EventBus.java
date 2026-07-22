package mchorse.bbs_mod.events;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventBus
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscribers = new ConcurrentHashMap<>();

    /**
     * Registers the given subscriber to receive events.
     */
    public void register(Object subscriber)
    {
        this.registerCloseable(subscriber);
    }

    /**
     * Registers a subscriber and returns an idempotent teardown handle.
     * Existing startup addons can keep using {@link #register(Object)}.
     */
    public EventSubscription registerCloseable(Object subscriber)
    {
        if (subscriber == null)
        {
            LOGGER.warn("[bbs-eventbus] ignored null subscriber");

            return () -> {};
        }

        List<RegisteredSubscription> registered = new ArrayList<>();

        for (Method method : subscriber.getClass().getDeclaredMethods())
        {
            RegisteredSubscription subscription = this.subscribe(subscriber, method);

            if (subscription != null)
            {
                registered.add(subscription);
            }
        }

        LOGGER.info("[bbs-eventbus] registered subscriber {} with {} handler(s)",
            subscriber.getClass().getName(),
            registered.size());

        AtomicBoolean closed = new AtomicBoolean();

        return () ->
        {
            if (!closed.compareAndSet(false, true))
            {
                return;
            }

            for (RegisteredSubscription entry : registered)
            {
                CopyOnWriteArrayList<Subscription> entries = this.subscribers.get(entry.eventType);

                if (entries != null)
                {
                    entries.remove(entry.subscription);

                    if (entries.isEmpty())
                    {
                        this.subscribers.remove(entry.eventType, entries);
                    }
                }
            }

            LOGGER.info("[bbs-eventbus] unregistered subscriber {} with {} handler(s)",
                subscriber.getClass().getName(),
                registered.size());
        };
    }

    /** Registers a typed host callback with an idempotent removal handle. */
    public <T> EventSubscription subscribe(Class<T> eventType, Consumer<? super T> callback)
    {
        if (eventType == null || callback == null)
        {
            return () -> {};
        }

        Subscription subscription = new Subscription((event) -> callback.accept(eventType.cast(event)));
        CopyOnWriteArrayList<Subscription> entries = this.subscribers.computeIfAbsent(
            eventType,
            (clazz) -> new CopyOnWriteArrayList<>()
        );
        entries.add(subscription);
        AtomicBoolean closed = new AtomicBoolean();

        return () ->
        {
            if (!closed.compareAndSet(false, true))
            {
                return;
            }

            entries.remove(subscription);

            if (entries.isEmpty())
            {
                this.subscribers.remove(eventType, entries);
            }
        };
    }

    private RegisteredSubscription subscribe(Object subscriber, Method method)
    {
        if (method.isAnnotationPresent(Subscribe.class))
        {
            if (method.getParameterCount() != 1)
            {
                LOGGER.warn("[bbs-eventbus] ignored handler {}#{} because it has {} parameter(s)",
                    subscriber.getClass().getName(),
                    method.getName(),
                    method.getParameterCount());

                return null;
            }

            method.setAccessible(true);
            Class<?> eventType = method.getParameterTypes()[0];
            Subscription subscription = new Subscription(subscriber, method);

            this.subscribers
                .computeIfAbsent(eventType, (clazz) -> new CopyOnWriteArrayList<>())
                .add(subscription);

            LOGGER.info("[bbs-eventbus] subscribed {}#{} to {}",
                subscriber.getClass().getName(),
                method.getName(),
                eventType.getName());

            return new RegisteredSubscription(eventType, subscription);
        }

        return null;
    }

    /**
     * Posts the given event to the event bus.
     */
    public void post(Object event)
    {
        CopyOnWriteArrayList<Subscription> eventSubscribers = this.subscribers.get(event.getClass());

        if (eventSubscribers == null || eventSubscribers.isEmpty())
        {
            LOGGER.debug("[bbs-eventbus] no subscribers for {}", event.getClass().getName());

            return;
        }

        LOGGER.info("[bbs-eventbus] posting {} to {} subscriber(s)",
            event.getClass().getName(),
            eventSubscribers.size());

        for (Subscription subscription : eventSubscribers)
        {
            try
            {
                subscription.invoke(event);
            }
            catch (Throwable e)
            {
                LOGGER.error("[bbs-eventbus] handler {}#{} failed for {}",
                    subscription.description(),
                    subscription.method == null ? "callback" : subscription.method.getName(),
                    event.getClass().getName(),
                    e);
            }
        }
    }

    private record RegisteredSubscription(Class<?> eventType, Subscription subscription) {}
}
