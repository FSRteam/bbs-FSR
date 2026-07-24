package mchorse.bbs_mod.ui.framework.notifications;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.themes.UIThemeMotion;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Lerps;

public class Notification
{
    public static final int TOTAL_LENGTH = 80;

    /** One notification tick is 50 ms (20 ticks per second). */
    private static final float MS_PER_TICK = 50F;

    public IKey message;
    public int background;
    public int color;

    public int tick;

    public Notification(IKey message, int background, int color)
    {
        this.message = message;
        this.background = background | Colors.A100;
        this.color = color| Colors.A100;

        this.tick = TOTAL_LENGTH;
    }

    public boolean isExpired()
    {
        return this.tick <= 0;
    }

    /**
     * Slide in/out factor (0 = off screen, 1 = in place). The enter/exit
     * length and easing come from the theme's notification motion entry;
     * disabled motion means the notification simply sits in place for its
     * whole lifetime.
     */
    public float getFactor(float transition)
    {
        UIThemeMotion spec = UIMotions.notification();
        int duration = UIMotions.duration(spec);

        if (duration <= 0)
        {
            return 1F;
        }

        float ticks = Math.max(1F, duration / MS_PER_TICK);
        float envelope = Lerps.envelope(this.tick - transition, 0F, ticks, TOTAL_LENGTH - ticks, TOTAL_LENGTH);

        return spec.easing.interpolate(0F, 1F, envelope);
    }

    public void update()
    {
        this.tick -= 1;
    }
}
