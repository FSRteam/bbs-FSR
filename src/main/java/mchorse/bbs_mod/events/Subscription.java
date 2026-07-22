package mchorse.bbs_mod.events;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Class for all subscribers.
 */
public class Subscription
{
    public final Object target;
    public final Method method;
    private final Consumer<Object> callback;

    public Subscription(Object target, Method method)
    {
        this.target = target;
        this.method = method;
        this.callback = null;

        if (!method.canAccess(target))
        {
            method.setAccessible(true);
        }
    }

    public Subscription(Consumer<Object> callback)
    {
        this.target = callback;
        this.method = null;
        this.callback = Objects.requireNonNull(callback, "callback");
    }

    public void invoke(Object event) throws Exception
    {
        if (this.callback != null)
        {
            this.callback.accept(event);
        }
        else
        {
            this.method.invoke(this.target, event);
        }
    }

    public String description()
    {
        return this.method == null ? this.target.getClass().getName() : this.target.getClass().getName() + "#" + this.method.getName();
    }
}
