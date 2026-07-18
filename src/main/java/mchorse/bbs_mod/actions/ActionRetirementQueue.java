package mchorse.bbs_mod.actions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Retains exact runtime identities until every retirement action succeeds. */
final class ActionRetirementQueue<T>
{
    @FunctionalInterface
    interface Retirement<T>
    {
        void retire(T value);
    }

    private final List<T> pending = new ArrayList<>();

    void retain(T value)
    {
        if (value == null)
        {
            return;
        }

        for (T pending : this.pending)
        {
            if (pending == value)
            {
                return;
            }
        }

        this.pending.add(value);
    }

    boolean isEmpty()
    {
        return this.pending.isEmpty();
    }

    int size()
    {
        return this.pending.size();
    }

    Throwable drain(Retirement<? super T> retirement)
    {
        Throwable failure = null;
        Iterator<T> iterator = this.pending.iterator();

        while (iterator.hasNext())
        {
            T value = iterator.next();

            try
            {
                retirement.retire(value);
                iterator.remove();
            }
            catch (RuntimeException | LinkageError e)
            {
                failure = ActionTeardown.append(failure, e);
            }
        }

        return failure;
    }
}
