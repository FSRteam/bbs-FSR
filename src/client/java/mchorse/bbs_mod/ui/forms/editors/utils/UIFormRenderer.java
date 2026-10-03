package mchorse.bbs_mod.ui.forms.editors.utils;

import mchorse.bbs_mod.api.client.events.FormPreviewEvents;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

public class UIFormRenderer extends UIModelRenderer
{
    public Form form;

    @Override
    protected void renderUserModel(UIContext context)
    {
        if (this.form == null)
        {
            return;
        }

        FormRenderingContext formContext = new FormRenderingContext()
            .set(FormRenderType.PREVIEW, this.entity, context.batcher.getContext().pose(), LightTexture.pack(15, 15), OverlayTexture.NO_OVERLAY, context.getTransition())
            .camera(this.camera)
            .simulationOwner(this)
            .localSimulation()
            .modelRenderer(context.getTick());

        FormUtilsClient.render(this.form, formContext);
    }

    /**
     * The form preview's overlay point, offered to both preview flavours: {@code UIPickableFormRenderer}
     * overrides {@link #renderUserModel(UIContext)} and does not call super, so this override - not an
     * inline call inside renderUserModel - is what makes the event reach the pickable preview too.
     * Reached through {@code UIModelRenderer}'s own call, which runs for both.
     */
    @Override
    protected void renderUserModelOverlay(UIContext context)
    {
        FormPreviewEvents.OVERLAY.invoker().render(this, context);
    }
}
