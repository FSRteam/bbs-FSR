package mchorse.bbs_mod.settings.values.base;

import mchorse.bbs_mod.settings.values.IValueListener;

import java.util.Objects;

public abstract class BaseValueBasic <T> extends BaseValue
{
    protected T value;
    protected T runtimeValue;
    private T defaultValue;

    public BaseValueBasic(String id, T value)
    {
        super(id);

        this.value = value;
        this.captureDefault();
    }

    protected final void captureDefault()
    {
        this.defaultValue = this.copyValue(this.value);
    }

    protected T copyValue(T value)
    {
        return value;
    }

    protected boolean compareValue(T a, T b)
    {
        return Objects.equals(a, b);
    }

    public T getDefaultValue()
    {
        return this.defaultValue;
    }

    @Override
    public void reset()
    {
        this.set(this.copyValue(this.defaultValue));
    }

    @Override
    public boolean isDefault()
    {
        return this.compareValue(this.value, this.defaultValue);
    }

    public T get()
    {
        if (this.runtimeValue != null)
        {
            return this.runtimeValue;
        }

        return this.value;
    }

    public T getOriginalValue()
    {
        return this.value;
    }

    public T getRuntimeValue()
    {
        return this.runtimeValue;
    }

    public void set(T value)
    {
        this.set(value, IValueListener.FLAG_DEFAULT);
    }

    public void set(T value, int flag)
    {
        this.preNotify(flag);
        this.value = value;
        this.postNotify(flag);
    }

    public void setRuntimeValue(T value)
    {
        this.runtimeValue = value;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (super.equals(obj))
        {
            return true;
        }

        if (obj instanceof BaseValueBasic)
        {
            BaseValueBasic baseValue = (BaseValueBasic) obj;

            return Objects.equals(this.value, baseValue.value);
        }

        return false;
    }
}
