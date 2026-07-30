package bbssmokefixture.v2;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.UIContext;

/**
 * 2.0 form appearance: a solid green box, larger than the 1.0 red box, both
 * in the world and in the form list's preview panel.
 */
public final class SmokeFormRenderer extends FormRenderer<SmokeForm>
{
    public SmokeFormRenderer(SmokeForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        context.batcher.box(x1 + 2, y1 + 2, x2 - 2, y2 - 2, 0xFF22CC55);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        RenderSystem.enableDepthTest();
        Draw.renderBox(context.stack, -0.65D, 0D, -0.65D, 1.3D, 1.9D, 1.3D, 0.1F, 0.9F, 0.3F, 0.9F);
    }
}
