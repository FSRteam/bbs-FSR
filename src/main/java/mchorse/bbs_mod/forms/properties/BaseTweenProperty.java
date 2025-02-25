package mchorse.bbs_mod.forms.properties;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;

public abstract class BaseTweenProperty <T> extends BaseProperty<T>
{
    private final IKeyframeFactory<T> factory;

    public BaseTweenProperty(Form form, String key, T value, IKeyframeFactory<T> factory)
    {
        super(form, key, value);

        this.factory = factory;
    }

    public IKeyframeFactory<T> getFactory()
    {
        return this.factory;
    }

    @Override
    public boolean canCreateChannel()
    {
        return this.canAnimate;
    }

    @Override
    public KeyframeChannel createChannel(String key)
    {
        return new KeyframeChannel(key, this.factory);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj instanceof BaseTweenProperty<?> property && property.factory == this.factory)
        {
            return this.factory.compare(this.value, property.value);
        }

        return super.equals(obj);
    }

    @Override
    public BaseType toData()
    {
        return this.factory.toData(this.value);
    }

    @Override
    public void fromData(BaseType data)
    {
        this.set(this.factory.fromData(data));
    }
}