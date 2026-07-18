package mchorse.bbs_mod.network;

import mchorse.bbs_mod.data.types.BaseType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Connection-lifetime callback storage for remote manager requests. Callback
 * ids stay monotonic across resets so work queued by a closed connection
 * cannot consume a newly registered callback after reconnect.
 */
final class ManagerCallbackRegistry
{
    private final Map<Integer, Consumer<BaseType>> callbacks = new HashMap<>();
    private int nextId;

    public synchronized int register(Consumer<BaseType> callback)
    {
        if (callback == null)
        {
            return -1;
        }

        int start = this.nextId;

        do
        {
            int id = this.nextId;

            this.nextId = this.nextId == Integer.MAX_VALUE ? 0 : this.nextId + 1;

            if (!this.callbacks.containsKey(id))
            {
                this.callbacks.put(id, callback);

                return id;
            }
        }
        while (this.nextId != start);

        throw new IllegalStateException("No manager callback ids are available");
    }

    public synchronized Consumer<BaseType> remove(int id)
    {
        return this.callbacks.remove(id);
    }

    public synchronized void reset()
    {
        this.callbacks.clear();
    }

    synchronized int size()
    {
        return this.callbacks.size();
    }
}
