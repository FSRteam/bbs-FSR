package mchorse.bbs_mod.api.client.dashboard;

@FunctionalInterface
public interface BBSDashboardOverlayFactory
{
    BBSDashboardOverlayContent create() throws Exception;
}
