package bbssmokefixture.v2;

import mchorse.bbs_mod.api.plugin.BBSPlugin;
import mchorse.bbs_mod.api.plugin.BBSPluginContext;
import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Common-side entrypoint for the 2.0 generation of the on-disk smoke
 * fixture plugin. Registers the exact same ids as {@code bbssmokefixture.v1}
 * so dropping this jar in over the 1.0 jar exercises the "light-shader-style"
 * override path: same plugin id, same structural ids, new implementation
 * class and new visible behavior.
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
            new ClipFactoryData(Icons.CAMERA, 0x22CC55));
        require(cameraClip, "camera clip");

        BBSRegistrationResult actionClip = context.clips().registerActionClip(
            Link.create("bbssmokefixture:action_widget"), SmokeActionClip.class,
            new ClipFactoryData(Icons.CAMERA, 0x22CC55));
        require(actionClip, "action clip");

        BBSRegistrationResult particle = context.particles().registerComponent(
            "bbssmokefixture_tint", "bbssmokefixture.v2.SmokeParticleComponent");
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
