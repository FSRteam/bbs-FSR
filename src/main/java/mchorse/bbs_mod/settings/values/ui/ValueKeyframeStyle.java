package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.utils.keyframes.KeyframeStyle;

public class ValueKeyframeStyle extends BaseValueBasic<KeyframeStyle>
{
    public ValueKeyframeStyle(String id)
    {
        super(id, new KeyframeStyle());
    }

    @Override
    public void set(KeyframeStyle style, int flag)
    {
        super.set(style == null ? new KeyframeStyle() : style.copy(), flag);
    }

    @Override
    public BaseType toData()
    {
        MapType data = new MapType();
        this.value.toData(data);
        return data;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.value.reset();
        if (data.isMap()) this.value.fromData(data.asMap());
    }

    @Override
    protected KeyframeStyle copyValue(KeyframeStyle value)
    {
        return value == null ? null : value.copy();
    }
}
