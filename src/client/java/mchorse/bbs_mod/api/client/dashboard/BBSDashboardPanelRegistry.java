package mchorse.bbs_mod.api.client.dashboard;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;

public interface BBSDashboardPanelRegistry
{
    BBSRegistrationResult register(BBSDashboardPanelSpec spec, BBSDashboardPanelFactory factory);
}
