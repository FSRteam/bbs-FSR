package mchorse.bbs_mod.events.register;

import mchorse.bbs_mod.forms.forms.Form;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class RegisterFormCategoriesEvent
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterFormCategoriesEvent.class);

    private final Consumer<Form> extraFormConsumer;

    public RegisterFormCategoriesEvent(Consumer<Form> extraFormConsumer)
    {
        this.extraFormConsumer = extraFormConsumer;
    }

    public void addExtraForm(Form form)
    {
        if (form == null)
        {
            LOGGER.warn("[bbs-form-categories] addon attempted to add a null extra form");

            return;
        }

        if (this.extraFormConsumer != null)
        {
            this.extraFormConsumer.accept(form);
            LOGGER.info("[bbs-form-categories] addon added extra form {}", form.getClass().getName());
        }
    }
}
