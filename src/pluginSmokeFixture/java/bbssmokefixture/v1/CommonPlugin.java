package bbssmokefixture.v1;

import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginContext;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Common-side entrypoint for the 1.0 generation of the on-disk smoke fixture
 * plugin (see {@code .trellis/tasks/07-23-fsr-plugin-structural-hot-reload}).
 *
 * <p>Unlike {@code PluginStructuralCapabilitiesE2ETest}'s runtime-compiled
 * fixture, this is a real, versioned source file whose compiled jar is meant
 * to be dropped into {@code config/bbs/plugins/} for manual NeoForge client
 * smoke testing of the structural hot-reload capabilities that a bare
 * JavaExec cannot exercise.</p>
 */
public final class CommonPlugin implements BBSPlugin
{
    @Override
    public void prepare(BBSPluginContext context)
    {
        BBSRegistrationResult form = context.forms().register(
            Link.create("bbssmokefixture:widget"), SmokeForm.class);
        require(form, "form");

        BBSRegistrationResult cameraClip = context.clips().registerCameraClip(
            Link.create("bbssmokefixture:camera_widget"), SmokeCameraClip.class,
            new ClipFactoryData(Icons.CAMERA, 0xDD2222));
        require(cameraClip, "camera clip");

        BBSRegistrationResult actionClip = context.clips().registerActionClip(
            Link.create("bbssmokefixture:action_widget"), SmokeActionClip.class,
            new ClipFactoryData(Icons.CAMERA, 0xDD2222));
        require(actionClip, "action clip");

        BBSRegistrationResult particle = context.particles().registerComponent(
            "bbssmokefixture_tint", "bbssmokefixture.v1.SmokeParticleComponent");
        require(particle, "particle component");
    }

    private static void require(BBSRegistrationResult result, String what)
    {
        if (!result.accepted())
        {
            throw new IllegalStateException(what + " registration rejected: " + result);
        }
    }
}
