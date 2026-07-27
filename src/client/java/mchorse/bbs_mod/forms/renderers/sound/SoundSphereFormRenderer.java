package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Drives the sphere sound form's playback and draws its picker preview.
 *
 * <p>The form itself has nothing visible in the world; the emission shape is
 * drawn separately by the guide renderer while the form is selected.</p>
 */
public class SoundSphereFormRenderer extends FormRenderer<SoundSphereForm> implements ITickable
{
    private final SoundFormRuntime runtime = new SoundFormRuntime();

    public SoundSphereFormRenderer(SoundSphereForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        context.batcher.icon(Icons.SOUND, (x1 + x2) / 2F, (y1 + y2) / 2F, 0.5F, 0.5F);
    }

    @Override
    public void tick(IEntity entity)
    {
        this.runtime.tick(this.form, entity);
    }

    @Override
    public void release()
    {
        this.runtime.release();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        this.runtime.updateTimeline(this.form, context);

        SoundGuideInteraction.render(context, this.form);
    }
}
