package mchorse.bbs_mod.api.client.dashboard;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

/** Runtime visibility and unregister handle for one addon Dashboard overlay. */
public interface BBSDashboardOverlaySubscription extends AutoCloseable
{
    BBSRegistrationResult registration();

    boolean visible();

    void setVisible(boolean visible);

    @Override
    void close();
}
