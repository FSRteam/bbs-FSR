package mchorse.bbs_mod.api.events;

import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;

import java.util.function.Consumer;

/** Registers values or other form additions applied to every subsequently created form. */
public final class RegisterFormModifiersEvent
{
    public void register(Consumer<Form> modifier)
    {
        FormArchitect.registerModifier(modifier);
    }
}
