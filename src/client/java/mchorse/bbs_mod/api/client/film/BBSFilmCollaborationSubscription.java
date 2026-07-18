package mchorse.bbs_mod.api.client.film;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSFilmCollaborationSubscription extends AutoCloseable
{
    BBSRegistrationResult registration();

    boolean active();

    @Override
    void close();
}
