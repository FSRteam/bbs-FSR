package mchorse.bbs_mod.api.forms;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;

public interface BBSFormRegistry
{
    BBSRegistrationResult register(Link id, Class<? extends Form> formType);
}
