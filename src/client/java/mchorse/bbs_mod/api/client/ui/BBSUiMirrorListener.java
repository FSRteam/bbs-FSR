package mchorse.bbs_mod.api.client.ui;

@FunctionalInterface
public interface BBSUiMirrorListener
{
    /** Called on this listener's bounded serial handoff worker. */
    default void onSessionOpened(BBSUiSessionInfo session)
    {}

    /**
     * Called on this listener's bounded serial handoff worker. Implementations
     * should hand off the immutable bytes without blocking.
     */
    default void onAssetAvailable(BBSUiAssetBytes asset)
    {}

    /** Called on this listener's bounded serial handoff worker. */
    void onFrame(BBSUiFrame frame);

    /** Called on this listener's bounded serial handoff worker. */
    default void onSessionClosed(long sessionId)
    {}
}
