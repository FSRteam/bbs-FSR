package mchorse.bbs_mod.api.client.render;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

/**
 * Closeable lifetime for one host-owned render-surface listener.
 *
 * <p>Closing is idempotent. A callback admitted before close may finish, but
 * no later demand sample or frame callback is admitted.</p>
 */
public interface BBSRenderSurfaceSubscription extends AutoCloseable
{
    BBSRegistrationResult registration();

    /** Whether this registration is present and is the currently routed active generation. */
    boolean active();

    @Override
    void close();
}
