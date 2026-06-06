package mchorse.bbs_mod.events;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventBus
{
    private static final Logger LOGGER = LoggerFactory.getLogger(EventBus.class);

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscribers = new HashMap<>();

    /**
     * Registers the given subscriber to receive events.
     */
    public void register(Object subscriber)
    {
        if (subscriber == null)
        {
            LOGGER.warn("[bbs-eventbus] ignored null subscriber");

            return;
        }

        int registered = 0;

        for (Method method : subscriber.getClass().getDeclaredMethods())
        {
            if (this.subscribe(subscriber, method))
            {
                registered += 1;
            }
        }

        LOGGER.info("[bbs-eventbus] registered subscriber {} with {} handler(s)",
            subscriber.getClass().getName(),
            registered);
    }

    private boolean subscribe(Object subscriber, Method method)
    {
        if (method.isAnnotationPresent(Subscribe.class))
        {
            if (method.getParameterCount() != 1)
            {
                LOGGER.warn("[bbs-eventbus] ignored handler {}#{} because it has {} parameter(s)",
                    subscriber.getClass().getName(),
                    method.getName(),
                    method.getParameterCount());

                return false;
            }

            method.setAccessible(true);
            this.subscribers
                .computeIfAbsent(method.getParameterTypes()[0], (clazz) -> new CopyOnWriteArrayList<>())
                .add(new Subscription(subscriber, method));

            LOGGER.info("[bbs-eventbus] subscribed {}#{} to {}",
                subscriber.getClass().getName(),
                method.getName(),
                method.getParameterTypes()[0].getName());

            return true;
        }

        return false;
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
                subscription.method.invoke(subscription.target, event);
            }
            catch (Exception e)
            {
                LOGGER.error("[bbs-eventbus] handler {}#{} failed for {}",
                    subscription.target.getClass().getName(),
                    subscription.method.getName(),
                    event.getClass().getName(),
                    e);
            }
        }
    }
}
