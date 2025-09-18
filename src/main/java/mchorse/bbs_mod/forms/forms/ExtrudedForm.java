package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.properties.BooleanProperty;
import mchorse.bbs_mod.forms.properties.ColorProperty;
import mchorse.bbs_mod.forms.properties.LinkProperty;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.colors.Color;

public class ExtrudedForm extends Form
{
    public final LinkProperty texture = new LinkProperty(this, "texture", null);
    public final ColorProperty color = new ColorProperty(this, "color", Color.white());
    public final BooleanProperty billboard = new BooleanProperty(this, "billboard", false);

    public ExtrudedForm()
    {
        super();

        this.register(this.texture);
        this.register(this.color);
        this.register(this.billboard);
    }

    @Override
    public String getDefaultDisplayName()
    {
        Link link = this.texture.get();

        return link == null ? "none" : link.toString();
    }
}