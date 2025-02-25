package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.properties.BooleanProperty;
import mchorse.bbs_mod.forms.properties.FloatProperty;
import mchorse.bbs_mod.forms.properties.LinkProperty;
import mchorse.bbs_mod.resources.Link;

public class TrailForm extends Form
{
    public final LinkProperty texture = new LinkProperty(this, "texture", Link.assets("textures/default_trail.png"));
    public final FloatProperty length = new FloatProperty(this, "length", 10F);
    public final BooleanProperty loop = new BooleanProperty(this, "loop", false);
    public final BooleanProperty paused = new BooleanProperty(this, "paused", false);
    
    public TrailForm()
    {
        this.register(this.texture);
        this.register(this.length);
        this.register(this.loop);
        this.register(this.paused);
    }
}