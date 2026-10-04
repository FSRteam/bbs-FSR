package mchorse.bbs_mod.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The addon event channel.
 *
 * <p>An addon hands over an object — an entry point instance, or one registered through
 * {@code BBSAddonRegistrationContext.events()} — and every method of it carrying {@link Subscribe}
 * is indexed here by the type of that method's single parameter. {@link #post(Object)} then hands
 * an event to whoever asked for that exact type, or for one of its super classes.</p>
 *
 * <p><b>This is not a second bus for BBS's own events.</b> The two channels carry different
 * traffic and neither forwards into the other:</p>
 * <ul>
 *   <li>{@code mchorse.bbs_mod.events.EventBus} — {@code BBSMod.events} — carries BBS's internal
 *       events between its own subsystems, with the lease and unload semantics FSR gives it.</li>
 *   <li>this class carries only {@code mchorse.bbs_mod.api.events} and
 *       {@code mchorse.bbs_mod.api.client.events} to addons.</li>
 * </ul>
 *
 * <p>The table below is static, so every entry point feeds the same one and an addon that
 * registered through either of them receives the same events. It is also why the ordering of a
 * post is decided by BBS — by the phase in which FSR's initialization reaches the registration
 * point — and never by the bus: this class has no notion of priority, lifetime or cancellation.
 * A host with no addons has an empty table, so {@link #post(Object)} finds nothing and returns,
 * which is the whole of its behaviour in that case.</p>
 */
public class EventBus
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

    private static final Map<Class<?>, CopyOnWriteArrayList<Subscription>> SUBSCRIBERS = new ConcurrentHashMap<>();

    /**
     * The one instance of this channel. Both entry points converge on it; a caller may also keep
     * its own {@code new EventBus()} and still reach the same table.
     */
    public static final EventBus INSTANCE = new EventBus();

    /**
     * Registers the given subscriber to receive events.
     *
     * <p>Methods are collected from the whole class hierarchy rather than from the subscriber's
     * own class alone, so an addon can keep shared subscriptions in a base class. The most
     * specific declaration of a method wins: an override replaces the method it overrides
     * instead of being called next to it, and an override that drops {@link Subscribe}
     * unsubscribes it.</p>
     */
    public void register(Object subscriber)
    {
        if (subscriber == null)
        {
            return;
        }

        Set<String> visited = new HashSet<>();

        for (Class<?> clazz = subscriber.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass())
        {
            for (Method method : clazz.getDeclaredMethods())
            {
                if (visited.add(getSignature(method)))
                {
                    this.subscribe(subscriber, method);
                }
            }
        }
    }

    private static String getSignature(Method method)
    {
        StringBuilder builder = new StringBuilder(method.getName());

        for (Class<?> type : method.getParameterTypes())
        {
            builder.append(':').append(type.getName());
        }

        return builder.toString();
    }

    private void subscribe(Object subscriber, Method method)
    {
        if (method.isAnnotationPresent(Subscribe.class))
        {
            if (method.getParameterCount() != 1)
            {
                LOGGER.warn("[bbs-api] ignored {}.{}() — a subscriber method takes exactly one parameter, it has {}",
                    subscriber.getClass().getName(),
                    method.getName(),
                    method.getParameterCount());

                return;
            }

            SUBSCRIBERS
                .computeIfAbsent(method.getParameterTypes()[0], (clazz) -> new CopyOnWriteArrayList<>())
                .add(new Subscription(subscriber, method));
        }
    }

    /**
     * Posts the given event to the event bus.
     *
     * <p>Subscribers of the event's own class are called first, then those of its super classes,
     * so subscribing to a base event type receives every event derived from it.</p>
     */
    public void post(Object event)
    {
        if (event == null)
        {
            return;
        }

        for (Class<?> clazz = event.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass())
        {
            this.post(event, SUBSCRIBERS.get(clazz));
        }
    }

    private void post(Object event, CopyOnWriteArrayList<Subscription> eventSubscribers)
    {
        if (eventSubscribers == null || eventSubscribers.isEmpty())
        {
            return;
        }

        for (Subscription subscription : eventSubscribers)
        {
            try
            {
                subscription.method.invoke(subscription.target, event);
            }
            catch (Throwable e)
            {
                /* A subscriber blowing up used to vanish without a trace, which made a broken
                 * addon indistinguishable from an absent one — the single nastiest thing to debug
                 * on the addon side. Whatever it did wrong is not this bus's business to fix, but
                 * it is its business to say so, and to keep the remaining subscribers running. */
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;

                LOGGER.error("[bbs-api] subscriber {}.{}() failed to handle {}!",
                    subscription.target.getClass().getName(),
                    subscription.method.getName(),
                    event.getClass().getSimpleName(),
                    cause);
            }
        }
    }

    /** Whether anybody asked for this event type; a cheap pre-check for a caller about to post. */
    public static boolean hasSubscribers(Class<?> eventType)
    {
        CopyOnWriteArrayList<Subscription> entries = eventType == null ? null : SUBSCRIBERS.get(eventType);

        return entries != null && !entries.isEmpty();
    }
}
