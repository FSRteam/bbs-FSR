package mchorse.bbs_mod.api.addon;

import mchorse.bbs_mod.api.clips.BBSClipRegistry;
import mchorse.bbs_mod.api.events.BBSEventRegistry;
import mchorse.bbs_mod.api.forms.BBSFormRegistry;
import mchorse.bbs_mod.api.network.BBSNetworkRegistry;
import mchorse.bbs_mod.api.particles.BBSParticleRegistry;
import mchorse.bbs_mod.api.resources.BBSResourceRegistry;
import mchorse.bbs_mod.api.settings.BBSSettingsRegistry;

/**
 * Callback-scoped structural registration facade. Registration methods are
 * accepted only while {@link BBSAddon#register} is executing; retained facade
 * instances reject mutations after that phase closes.
 */
public interface BBSAddonRegistrationContext extends BBSAddonContext
{
    BBSResourceRegistry resources();

    BBSFormRegistry forms();

    BBSSettingsRegistry settings();

    BBSClipRegistry clips();

    BBSParticleRegistry particles();

    BBSNetworkRegistry network();

    BBSEventRegistry events();
}
