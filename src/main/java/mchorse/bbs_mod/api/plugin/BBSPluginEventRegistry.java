package mchorse.bbs_mod.api.plugin;

import java.util.function.Consumer;

/**
 * Owner-aware internal event facade. It is deliberately not a NeoForge event
 * bus and returns a closeable host lease for every subscription.
 */
public interface BBSPluginEventRegistry
{
    <E> AutoCloseable subscribe(Class<E> eventType, Consumer<? super E> callback);
}
