package mchorse.bbs_mod.api.events;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Public callback fired after a model block entity refreshes its resolved model state. */
public interface ModelBlockEntityUpdateCallback
{
    Event EVENT = new Event();

    void update(ModelBlockEntity entity);

    final class Event
    {
        private final List<ModelBlockEntityUpdateCallback> listeners = new CopyOnWriteArrayList<>();

        public void register(ModelBlockEntityUpdateCallback callback)
        {
            if (callback != null)
            {
                this.listeners.add(callback);
            }
        }

        public void invoker(ModelBlockEntity entity)
        {
            for (ModelBlockEntityUpdateCallback callback : this.listeners)
            {
                callback.update(entity);
            }
        }

        public ModelBlockEntityUpdateCallback invoker()
        {
            return this::invoker;
        }
    }
}
