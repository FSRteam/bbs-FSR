package bbssmokefixture.v1;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.UIContext;

/**
 * 1.0 form appearance: a solid red box, both in the world and in the form
 * list's preview panel.
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
        context.batcher.box(x1 + 4, y1 + 4, x2 - 4, y2 - 4, 0xFFDD2222);
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        RenderSystem.enableDepthTest();
        Draw.renderBox(context.stack, -0.5D, 0D, -0.5D, 1D, 1.6D, 1D, 1F, 0.1F, 0.1F, 0.9F);
    }
}
