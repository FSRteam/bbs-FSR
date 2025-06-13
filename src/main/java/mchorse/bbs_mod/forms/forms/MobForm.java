package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.properties.BooleanProperty;
import mchorse.bbs_mod.forms.properties.LinkProperty;
import mchorse.bbs_mod.forms.properties.PoseProperty;
import mchorse.bbs_mod.forms.properties.StringProperty;
import mchorse.bbs_mod.utils.pose.Pose;

public class MobForm extends Form
{
    public final StringProperty mobID = new StringProperty(this, "mobId", "minecraft:chicken");
    public final StringProperty mobNBT = new StringProperty(this, "mobNbt", "");

    public final LinkProperty texture = new LinkProperty(this, "texture", null);
    public final BooleanProperty slim = new BooleanProperty(this, "slim", false);

    public final PoseProperty pose = new PoseProperty(this, "pose", new Pose());
    public final PoseProperty poseOverlay = new PoseProperty(this, "pose_overlay", new Pose());

    public MobForm()
    {
        this.slim.cantAnimate();

        this.register(this.mobID);
        this.register(this.mobNBT);
        this.register(this.pose);
        this.register(this.poseOverlay);
        this.register(this.texture);
        this.register(this.slim);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return this.mobID.get().isEmpty() ? super.getDefaultDisplayName() : this.mobID.get();
    }

    public boolean isPlayer()
    {
        return this.mobID.get().equals("minecraft:player");
    }
}