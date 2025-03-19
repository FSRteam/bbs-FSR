package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.forms.forms.utils.ParticleSettings;
import mchorse.bbs_mod.forms.properties.BooleanProperty;
import mchorse.bbs_mod.forms.properties.FloatProperty;
import mchorse.bbs_mod.forms.properties.IntegerProperty;
import mchorse.bbs_mod.forms.properties.ParticleSettingsProperty;

public class VanillaParticleForm extends Form
{
    public final ParticleSettingsProperty settings = new ParticleSettingsProperty(this, "settings", new ParticleSettings());
    public final BooleanProperty paused = new BooleanProperty(this, "paused", false);
    public final BooleanProperty local = new BooleanProperty(this, "local", false);
    public final FloatProperty velocity = new FloatProperty(this, "velocity", 0.1F);
    public final IntegerProperty count = new IntegerProperty(this, "count", 5);
    public final IntegerProperty frequency = new IntegerProperty(this, "frequency", 5);
    public final FloatProperty scatteringYaw = new FloatProperty(this, "scattering_yaw", 0F);
    public final FloatProperty scatteringPitch = new FloatProperty(this, "scattering_pitch", 0F);
    public final FloatProperty offsetX = new FloatProperty(this, "offset_x", 0F);
    public final FloatProperty offsetY = new FloatProperty(this, "offset_y", 0F);
    public final FloatProperty offsetZ = new FloatProperty(this, "offset_z", 0F);

    public VanillaParticleForm()
    {
        super();

        this.local.cantAnimate();

        this.register(this.settings);
        this.register(this.paused);
        this.register(this.local);
        this.register(this.velocity);
        this.register(this.count);
        this.register(this.frequency);
        this.register(this.scatteringYaw);
        this.register(this.scatteringPitch);
        this.register(this.offsetX);
        this.register(this.offsetY);
        this.register(this.offsetZ);
    }
}