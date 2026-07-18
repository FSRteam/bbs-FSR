package mchorse.bbs_mod.api.client.film;

public interface BBSFilmCollaborationListener
{
    default void onSessionOpened(BBSFilmSession session)
    {}

    default void onLocalMutations(BBSFilmMutationBatch batch)
    {}

    default void onCheckpointRequired(BBSFilmCheckpointRequired checkpoint)
    {}

    default void onPresence(BBSFilmPresence presence)
    {}

    /** Last synchronous opportunity to request a snapshot while the session is still readable. */
    default void onSessionClosing(BBSFilmSession session)
    {}

    default void onSessionClosed(long sessionId)
    {}
}
