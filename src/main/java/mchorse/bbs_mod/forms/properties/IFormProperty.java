package mchorse.bbs_mod.forms.properties;

import mchorse.bbs_mod.data.IDataSerializable;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

public interface IFormProperty <T> extends IDataSerializable<BaseType>
{
    /**
     * Get this property's key (which is used for serialization).
     */
    public String getKey();

    /**
     * Get this property's form owner.
     */
    public Form getForm();

    /**
     * Overwite the value of this property (this also stops tweening).
     */
    public void set(T value);

    /**
     * Get current value of the property (which isn't affected by tweening).
     */
    public T get();

    /**
     * Checks whether this property can create a generic keyframe channel
     */
    public boolean canCreateChannel();

    /**
     * Create a generic keyframe channel that can be used with this property
     */
    public KeyframeChannel createChannel(String key);
}