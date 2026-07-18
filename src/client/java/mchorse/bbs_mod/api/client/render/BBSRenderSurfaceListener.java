package mchorse.bbs_mod.api.client.render;

/**
 * Addon-owned surface consumer. Demand is sampled on a bounded core worker and
 * cached before the render thread reads it, but implementations should still
 * return a thread-safe snapshot without doing network or disk I/O. Frame
 * callbacks run on the dedicated JPEG encoder thread. Callback admission is
 * atomically fenced against stream invalidation; a callback admitted before
 * invalidation may finish, so consumers must retain and compare the frame's
 * opaque generation around any longer handoff.
 */
public interface BBSRenderSurfaceListener
{
    BBSRenderSurfaceDemand demand();

    void onFrame(BBSRenderSurfaceFrame frame);
}
