package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.properties.BooleanProperty;
import mchorse.bbs_mod.forms.properties.ColorProperty;
import mchorse.bbs_mod.forms.properties.FloatProperty;
import mchorse.bbs_mod.forms.properties.LinkProperty;
import mchorse.bbs_mod.forms.properties.Vector4fProperty;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector4f;

public class BillboardForm extends Form
{
    public final LinkProperty texture = new LinkProperty(this, "texture", null);
    public final BooleanProperty billboard = new BooleanProperty(this, "billboard", false);
    public final BooleanProperty linear = new BooleanProperty(this, "linear", false);
    public final BooleanProperty mipmap = new BooleanProperty(this, "mipmap", false);
    public final Vector4fProperty crop = new Vector4fProperty(this, "crop", new Vector4f(0, 0, 0, 0));
    public final BooleanProperty resizeCrop = new BooleanProperty(this, "resizeCrop", false);
    public final ColorProperty color = new ColorProperty(this, "color", Color.white());
    public final FloatProperty offsetX = new FloatProperty(this, "offsetX", 0F);
    public final FloatProperty offsetY = new FloatProperty(this, "offsetY", 0F);
    public final FloatProperty rotation = new FloatProperty(this, "rotation", 0F);
    public final BooleanProperty shading = new BooleanProperty(this, "shading", true);

    public BillboardForm()
    {
        super();

        this.linear.cantAnimate();
        this.mipmap.cantAnimate();
        this.resizeCrop.cantAnimate();
        this.shading.cantAnimate();

        this.register(this.texture);
        this.register(this.billboard);
        this.register(this.linear);
        this.register(this.mipmap);
        this.register(this.crop);
        this.register(this.resizeCrop);
        this.register(this.color);
        this.register(this.offsetX);
        this.register(this.offsetY);
        this.register(this.rotation);
        this.register(this.shading);
    }

    @Override
    public String getDefaultDisplayName()
    {
        Link link = this.texture.get();

        return link == null ? "none" : link.toString();
    }
}