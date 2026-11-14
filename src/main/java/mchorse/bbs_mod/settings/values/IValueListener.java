package mchorse.bbs_mod.settings.values;

import mchorse.bbs_mod.settings.values.base.BaseValue;

public interface IValueListener
{
    public static final int FLAG_DEFAULT = 0b0;
    public static final int FLAG_UNMERGEABLE = 0b1;
    /** Samples from one live recording share a single undo entry until idle or explicitly sealed. */
    public static final int FLAG_BATCH = 0b10;

    public void accept(BaseValue value, int flag);
}
