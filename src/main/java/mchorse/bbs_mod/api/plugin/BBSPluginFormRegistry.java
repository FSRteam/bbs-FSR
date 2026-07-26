package mchorse.bbs_mod.api.plugin;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;

public interface BBSPluginFormRegistry
{
    BBSRegistrationResult register(Link id, Class<? extends Form> formType);
}
