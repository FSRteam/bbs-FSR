package mchorse.bbs_mod.plugin.hotreload.phase0.api;

/** Minimal host-owned staging surface used by the isolated Phase 0 fixture. */
public interface Phase0Host
{
    void stage(String pluginId, String version, String marker);
}
