package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;

/** Hooks around each actual form draw, paired even if the renderer fails. */
public final class FormRenderEvents
{
    public static final FunctionalEvent<Render> BEFORE = event();
    public static final FunctionalEvent<Render> AFTER = event();

    private static FunctionalEvent<Render> event()
    {
        return new FunctionalEvent<>(listeners -> (form, context) ->
        {
            for (Render listener : listeners)
            {
                listener.onFormRender(form, context);
            }
        });
    }

    public interface Render
    {
        void onFormRender(Form form, FormRenderingContext context);
    }

    private FormRenderEvents()
    {}
}
