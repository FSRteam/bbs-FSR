package mchorse.bbs_mod.events.register;

import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;

public class RegisterFormsEvent
{
    public final FormArchitect forms;

    public RegisterFormsEvent(FormArchitect forms)
    {
        this.forms = forms;
    }

    public void register(Link id, Class<? extends Form> form)
    {
        this.forms.register(id, form, null);
    }
}
