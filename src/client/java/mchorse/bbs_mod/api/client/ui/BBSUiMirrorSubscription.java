package mchorse.bbs_mod.api.client.ui;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

/**
 * Runtime demand handle for one UI mirror listener.
 *
 * <p>The subscription starts inactive. Addons should switch it on only while
 * at least one authorized viewer needs UI frames, then switch it off as soon
 * as the last viewer leaves. State changes are lock-free from the caller's
 * perspective and never invoke addon callbacks inline.</p>
 */
public interface BBSUiMirrorSubscription extends AutoCloseable
{
    /** Result of the descriptor/capability/duplicate registration checks. */
    BBSRegistrationResult registration();

    /** Whether this subscription currently requests frame and asset capture. */
    boolean active();

    /**
     * Enable or disable frame and asset demand. Disabling discards queued
     * demand work; session lifecycle notifications remain ordered.
     */
    void setActive(boolean active);

    /**
     * Remove this listener. Closing is idempotent and retains a final ordered
     * session-close notification when a mirror session is still open.
     */
    @Override
    void close();
}
