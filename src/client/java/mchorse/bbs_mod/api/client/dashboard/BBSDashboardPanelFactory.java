package mchorse.bbs_mod.api.client.dashboard;

@FunctionalInterface
public interface BBSDashboardPanelFactory
{
    BBSDashboardPanelContent create() throws Exception;
}
