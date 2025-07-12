package mchorse.bbs_mod.ui.film.audio;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.interps.Lerps;
import org.lwjgl.glfw.GLFW;

public class UIAudioRecorder extends UIElement
{
    private final OpenALRecorder recorder;
    private float volume;

    public UIAudioRecorder(OpenALRecorder recorder)
    {
        this.recorder = recorder;

        this.eventPropagataion(EventPropagation.BLOCK);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            this.recorder.stop();
            context.render.postRunnable(this::removeFromParent);

            return true;
        }

        return super.subKeyPressed(context);
    }

    @Override
    public void render(UIContext context)
    {
        this.volume = Lerps.lerp(this.volume, Interpolations.CUBIC_OUT.interpolate(0F, 1F, this.recorder.getVolume()), 0.5F);

        String label = UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_LABEL
            .format(this.recorder.getTime() / 1000F)
            .get();
        int x = this.area.mx();
        int y = this.area.my();
        int w = context.batcher.getFont().getWidth(label);
        double volume = Interpolations.EXP_OUT.interpolate(0F, 1F, this.volume);

        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.A50);
        context.batcher.icon(Icons.SPHERE, Colors.RED | Colors.A100, x - w / 2 - 12, y + context.batcher.getFont().getHeight() / 2, 0.5F, 0.5F);
        context.batcher.textShadow(label, x - w / 2, y);

        label = UIKeys.CAMERA_TIMELINE_CONTEXT_RECORD_MICROPHONE_SUBLABEL.get();
        w = context.batcher.getFont().getWidth(label);

        context.batcher.textShadow(label, x - w / 2, this.area.y(0.75F));

        x -= w / 2;

        context.batcher.box(x, y + 16, x + w, y + 20, Colors.A100);
        context.batcher.box(x, y + 16, x + (int) (w * volume), y + 20, Colors.WHITE);

        super.render(context);
    }
}