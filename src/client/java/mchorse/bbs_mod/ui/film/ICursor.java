package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;

public interface ICursor
{
    public int getCursor();

    public void setCursor(int tick);

    public default boolean isRunning()
    {
        return false;
    }

    /** The timeline steps during snapped playback; paused Shift scrubbing keeps its fraction. */
    public default float getTimelineCursor(float transition)
    {
        return this.isRunning() && BBSSettings.editorSnapToTicks.get() ? this.getCursor() : this.getCursor(transition);
    }

    /** Render/sample time, including the fraction between simulation ticks. */
    public default float getCursor(float transition)
    {
        return this.getCursor();
    }

    public default void setCursor(float tick)
    {
        this.setCursor(Math.round(tick));
    }

    /** Authoring time; simulation and clip boundaries still use whole ticks. */
    public default float getKeyframeCursor(float transition)
    {
        return BBSSettings.editorSnapToTicks.get() ? this.getCursor() : this.getCursor(transition);
    }
}
