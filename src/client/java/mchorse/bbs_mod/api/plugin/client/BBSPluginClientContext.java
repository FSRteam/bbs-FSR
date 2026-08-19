package mchorse.bbs_mod.api.plugin.client;

import mchorse.bbs_mod.api.client.dashboard.BBSDashboardPanelRegistry;

public interface BBSPluginClientContext
{
    BBSPluginKeyMappingRegistry keyMappings();

    BBSPluginRendererRegistry renderers();

    BBSPluginFormClientRegistry forms();

    BBSPluginClipClientRegistry clips();

    BBSDashboardPanelRegistry dashboardPanels();
}
