package mchorse.bbs_mod.api.forms;

import mchorse.bbs_mod.api.registry.BBSRegistrationResult;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;

import java.util.function.Consumer;

public interface BBSFormRegistry
{
    BBSRegistrationResult register(Link id, Class<? extends Form> formType);

    /** Applies a modifier to every form created after registration. */
    BBSRegistrationResult registerModifier(Consumer<Form> modifier);
}
