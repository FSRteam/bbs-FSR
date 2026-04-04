package mchorse.bbs_mod.resources.packs;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface URLTextureErrorCallback
{
    Event EVENT = new Event();

    void onError(String url, URLError error);

    final class Event
    {
        private final List<URLTextureErrorCallback> listeners = new CopyOnWriteArrayList<>();

        public void register(URLTextureErrorCallback callback)
        {
            if (callback != null)
            {
                this.listeners.add(callback);
            }
        }

        public URLTextureErrorCallback invoker()
        {
            return (url, error) ->
            {
                for (URLTextureErrorCallback callback : this.listeners)
                {
                    callback.onError(url, error);
                }
            };
        }
    }
}
