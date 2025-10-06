package mchorse.bbs_mod.settings.values.base;

import mchorse.bbs_mod.settings.values.IValueListener;

public abstract class BaseValueBasic <T> extends BaseValue
{
    protected T value;

    public BaseValueBasic(String id, T value)
    {
        super(id);

        this.value = value;
    }

    public T get()
    {
        return this.value;
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
}