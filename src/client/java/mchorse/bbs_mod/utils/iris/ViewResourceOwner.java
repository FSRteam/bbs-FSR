package mchorse.bbs_mod.utils.iris;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Tracks only resources created by one pipeline, including an interrupted constructor. */
public final class ViewResourceOwner implements AutoCloseable
{
    private static final ThreadLocal<ViewResourceOwner> CAPTURE = new ThreadLocal<>();
    private static final Map<Object, ViewResourceOwner> OWNERS = new IdentityHashMap<>();

    private final Map<Object, Runnable> resources = new IdentityHashMap<>();
    private final List<Object> order = new ArrayList<>();
    private boolean capturing = true;

    private ViewResourceOwner()
    {}

    public static boolean isCapturing()
    {
        return CAPTURE.get() != null;
    }

    public static ViewResourceOwner begin()
    {
        if (CAPTURE.get() != null)
        {
            throw new IllegalStateException("Nested pipeline resource construction");
        }

        ViewResourceOwner owner = new ViewResourceOwner();

        CAPTURE.set(owner);

        return owner;
    }

    public static void track(Object resource, Runnable release)
    {
        ViewResourceOwner owner = CAPTURE.get();

        if (owner != null && !owner.resources.containsKey(resource))
        {
            owner.resources.put(resource, release);
            owner.order.add(resource);
            OWNERS.put(resource, owner);
        }
    }

    public static void released(Object resource)
    {
        ViewResourceOwner owner = OWNERS.remove(resource);

        if (owner != null)
        {
            owner.resources.remove(resource);
        }
    }

    public void finishConstruction()
    {
        if (this.capturing)
        {
            if (CAPTURE.get() != this)
            {
                throw new IllegalStateException("Wrong pipeline construction owner");
            }

            CAPTURE.remove();
            this.capturing = false;
        }
    }

    @Override
    public void close()
    {
        this.finishConstruction();

        Throwable failure = null;

        for (int i = this.order.size() - 1; i >= 0; i--)
        {
            Object resource = this.order.get(i);
            Runnable release = this.resources.remove(resource);

            OWNERS.remove(resource);

            if (release == null)
            {
                continue;
            }

            try
            {
                release.run();
            }
            catch (Throwable exception)
            {
                if (failure == null)
                {
                    failure = exception;
                }
                else if ((exception instanceof VirtualMachineError && !(failure instanceof VirtualMachineError))
                    || (exception instanceof Error && !(failure instanceof Error)))
                {
                    exception.addSuppressed(failure);
                    failure = exception;
                }
                else if (failure != exception)
                {
                    failure.addSuppressed(exception);
                }
            }
        }

        this.order.clear();

        if (failure instanceof Error error)
        {
            throw error;
        }

        if (failure != null)
        {
            throw new IllegalStateException("Could not release all view pipeline resources", failure);
        }
    }
}
