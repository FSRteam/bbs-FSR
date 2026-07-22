package mchorse.bbs_mod.plugin;

import mchorse.bbs_mod.client.film.collaboration.FilmCollaborationGenerationTest;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceHotSubscriptionTest;
import mchorse.bbs_mod.network.compat.AddonPayloadBrokerHotRouteTest;
import mchorse.bbs_mod.plugin.artifact.PluginArtifactContractTest;
import mchorse.bbs_mod.plugin.hotreload.phase0.Phase0HotReloadTest;
import mchorse.bbs_mod.plugin.manager.BBSPluginManagerIntegrationTest;
import mchorse.bbs_mod.plugin.runtime.PluginRuntimePrimitivesTest;
import mchorse.bbs_mod.plugin.watch.PluginContentWatcherTest;

/** One deterministic JavaExec entrypoint for all hot-plugin contract gates. */
public final class PluginHotReloadTestSuite
{
    private PluginHotReloadTestSuite() {}

    public static void main(String[] args) throws Exception
    {
        Phase0HotReloadTest.main(args);
        PluginArtifactContractTest.main(args);
        PluginRuntimePrimitivesTest.main(args);
        PluginContentWatcherTest.main(args);
        AddonPayloadBrokerHotRouteTest.main(args);
        BBSPluginManagerIntegrationTest.main(args);
        BBSRenderSurfaceHotSubscriptionTest.runAll();
        FilmCollaborationGenerationTest.main(args);

        System.out.println("PluginHotReloadTestSuite: all hot-plugin gates passed");
    }
}
