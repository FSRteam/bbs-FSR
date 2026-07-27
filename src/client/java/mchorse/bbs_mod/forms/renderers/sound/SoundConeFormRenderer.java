package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Drives the cone sound form's playback and draws its picker preview.
 *
 * <p>The apex sits at the form's own position, so the emission point passed to
 * the scheduler is simply the entity position — the same point the guide draws
 * the cone's tip at.</p>
 */
public class SoundConeFormRenderer extends FormRenderer<SoundConeForm> implements ITickable
{
    private final SoundFormRuntime runtime = new SoundFormRuntime();

    public SoundConeFormRenderer(SoundConeForm form)
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
