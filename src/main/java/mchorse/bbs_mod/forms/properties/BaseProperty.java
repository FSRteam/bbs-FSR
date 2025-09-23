package mchorse.bbs_mod.forms.properties;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.Objects;

public abstract class BaseProperty <T> implements IFormProperty<T>
{
    protected final Form form;
    protected String key;
    protected T value;

    protected boolean canAnimate = true;

    public BaseProperty(Form form, String key, T value)
    {
        this.form = form;
        this.key = key;
        this.value = value;
    }

    public void cantAnimate()
    {
        this.canAnimate = false;
    }

    @Override
    public String getKey()
    {
        return this.key;
    }

    @Override
    public Form getForm()
    {
        return this.form;
    }

    @Override
    public void set(T value)
    {
        this.value = value;
    }

    @Override
    public T get()
    {
        return this.value;
    }

    @Override
    public void update()
    {}

    @Override
    public boolean canCreateChannel()
    {
        return false;
    }

    @Override
    public KeyframeChannel createChannel(String key)
    {
        return null;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (super.equals(obj))
        {
            return true;
        }

        if (obj instanceof BaseProperty)
        {
            BaseProperty baseValue = (BaseProperty) obj;

            return Objects.equals(this.value, baseValue.value);
        }

        return false;
    }
}