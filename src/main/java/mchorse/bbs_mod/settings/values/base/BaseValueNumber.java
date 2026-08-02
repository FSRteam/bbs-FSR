package mchorse.bbs_mod.settings.values.base;

import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;

public abstract class BaseValueNumber <T extends Number> extends BaseKeyframeFactoryValue<T>
{
    protected T min;
    protected T max;

    protected boolean slider;

    public BaseValueNumber(String id, IKeyframeFactory<T> factory, T defaultValue, T min, T max)
    {
        super(id, factory, defaultValue);

        this.min = min;
        this.max = max;
    }

    public T getMin()
    {
        return this.min;
    }

    public T getMax()
    {
        return this.max;
    }

    public boolean isSlider()
    {
        return this.slider;
    }

    /**
     * Offer this value as a track rather than a drag field. Worth it only when
     * both ends are declared and the whole span fits the track with a useful
     * step — a resolution or a tick count belongs in a field you type into,
     * however finite its bounds happen to be.
     */
    public BaseValueNumber<T> slider()
    {
        this.slider = true;

        return this;
    }

    @Override
    public void set(T value, int flag)
    {
        if (this.min != null && this.max != null)
        {
            value = this.clamp(value);
        }

        super.set(value, flag);
    }

    protected abstract T clamp(T value);
}