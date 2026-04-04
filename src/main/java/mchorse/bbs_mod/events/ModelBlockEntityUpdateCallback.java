package mchorse.bbs_mod.events;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

        public ModelBlockEntityUpdateCallback invoker()
        {
            return (entity) ->
            {
                for (ModelBlockEntityUpdateCallback callback : this.listeners)
                {
                    callback.update(entity);
                }
            };
        }
    }
}
