package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.properties.FloatProperty;
import mchorse.bbs_mod.forms.properties.IntegerProperty;

public class FramebufferForm extends Form
{
    public final IntegerProperty width = new IntegerProperty(this, "width", 512);
    public final IntegerProperty height = new IntegerProperty(this, "height", 512);
    public final FloatProperty scale = new FloatProperty(this, "scale", 0.5F);

    public FramebufferForm()
    {
        this.width.cantAnimate();
        this.height.cantAnimate();

        this.register(this.width);
        this.register(this.height);
        this.register(this.scale);
    }
}