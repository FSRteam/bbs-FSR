package mchorse.bbs_mod.plugin.hotreload.phase0.api;

/** Minimal lifecycle contract used only by the Phase 0 classloader spike. */
public interface Phase0Plugin
{
    String id();

    String version();

    String marker();

    void prepare(Phase0Host host);

    void start();

    void stop();

    boolean isStarted();
}
