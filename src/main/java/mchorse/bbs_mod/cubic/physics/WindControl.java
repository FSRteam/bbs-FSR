package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.IInterp;

/**
 * The animatable global wind properties, layered over the form's physics wind config at playback
 * (the whole wind is replaced wholesale when this track has a keyframe). Mirrors {@link PhysicsControl};
 * lives in the main source set, while the {@code ModelPhysicsConfig.Wind} config record is client-only,
 * so the defaults are duplicated here. Every field interpolates.
 */
public class WindControl implements IMapSerializable
{
    /* Mirror ModelPhysicsConfig.Wind.NONE; duplicated because that config record lives in the client source set. */
    public static final float DEFAULT_STRENGTH = 0F;
    public static final float DEFAULT_X = 1F;
    public static final float DEFAULT_Y = 0F;
    public static final float DEFAULT_Z = 0F;
    public static final float DEFAULT_TURBULENCE = 0.5F;
    public static final float DEFAULT_TURBULENCE_SPEED = 1F;
    public static final float DEFAULT_TURBULENCE_SCALE = 1F;

    public static final WindControl DEFAULT = new WindControl();

    public float strength = DEFAULT_STRENGTH;
    public float x = DEFAULT_X;
    public float y = DEFAULT_Y;
    public float z = DEFAULT_Z;
    public float turbulence = DEFAULT_TURBULENCE;
    public float turbulenceSpeed = DEFAULT_TURBULENCE_SPEED;
    public float turbulenceScale = DEFAULT_TURBULENCE_SCALE;

    public void identity()
    {
        this.strength = DEFAULT_STRENGTH;
        this.x = DEFAULT_X;
        this.y = DEFAULT_Y;
        this.z = DEFAULT_Z;
        this.turbulence = DEFAULT_TURBULENCE;
        this.turbulenceSpeed = DEFAULT_TURBULENCE_SPEED;
        this.turbulenceScale = DEFAULT_TURBULENCE_SCALE;
    }

    public void lerp(WindControl preA, WindControl a, WindControl b, WindControl postB, IInterp interp, float frac)
    {
        this.strength = (float) interp.interpolate(IInterp.context.set(preA.strength, a.strength, b.strength, postB.strength, frac));
        this.x = (float) interp.interpolate(IInterp.context.set(preA.x, a.x, b.x, postB.x, frac));
        this.y = (float) interp.interpolate(IInterp.context.set(preA.y, a.y, b.y, postB.y, frac));
        this.z = (float) interp.interpolate(IInterp.context.set(preA.z, a.z, b.z, postB.z, frac));
        this.turbulence = (float) interp.interpolate(IInterp.context.set(preA.turbulence, a.turbulence, b.turbulence, postB.turbulence, frac));
        this.turbulenceSpeed = (float) interp.interpolate(IInterp.context.set(preA.turbulenceSpeed, a.turbulenceSpeed, b.turbulenceSpeed, postB.turbulenceSpeed, frac));
        this.turbulenceScale = (float) interp.interpolate(IInterp.context.set(preA.turbulenceScale, a.turbulenceScale, b.turbulenceScale, postB.turbulenceScale, frac));
    }

    public void autoLerp(WindControl preA, WindControl a, WindControl b, WindControl postB, float pt, float at, float bt, float qt, boolean clamped, float frac)
    {
        this.strength = (float) AutoBezier.get(preA.strength, a.strength, b.strength, postB.strength, pt, at, bt, qt, clamped, frac);
        this.x = (float) AutoBezier.get(preA.x, a.x, b.x, postB.x, pt, at, bt, qt, clamped, frac);
        this.y = (float) AutoBezier.get(preA.y, a.y, b.y, postB.y, pt, at, bt, qt, clamped, frac);
        this.z = (float) AutoBezier.get(preA.z, a.z, b.z, postB.z, pt, at, bt, qt, clamped, frac);
        this.turbulence = (float) AutoBezier.get(preA.turbulence, a.turbulence, b.turbulence, postB.turbulence, pt, at, bt, qt, clamped, frac);
        this.turbulenceSpeed = (float) AutoBezier.get(preA.turbulenceSpeed, a.turbulenceSpeed, b.turbulenceSpeed, postB.turbulenceSpeed, pt, at, bt, qt, clamped, frac);
        this.turbulenceScale = (float) AutoBezier.get(preA.turbulenceScale, a.turbulenceScale, b.turbulenceScale, postB.turbulenceScale, pt, at, bt, qt, clamped, frac);
    }

    public WindControl copy()
    {
        WindControl control = new WindControl();

        control.copy(this);

        return control;
    }

    public void copy(WindControl other)
    {
        this.strength = other.strength;
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.turbulence = other.turbulence;
        this.turbulenceSpeed = other.turbulenceSpeed;
        this.turbulenceScale = other.turbulenceScale;
    }

    public boolean isDefault()
    {
        return this.equals(DEFAULT);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (obj instanceof WindControl control)
        {
            return this.strength == control.strength
                && this.x == control.x
                && this.y == control.y
                && this.z == control.z
                && this.turbulence == control.turbulence
                && this.turbulenceSpeed == control.turbulenceSpeed
                && this.turbulenceScale == control.turbulenceScale;
        }

        return false;
    }

    @Override
    public void toData(MapType data)
    {
        data.putDouble("strength", this.strength);
        data.putDouble("x", this.x);
        data.putDouble("y", this.y);
        data.putDouble("z", this.z);
        data.putDouble("turbulence", this.turbulence);
        data.putDouble("turbulence_speed", this.turbulenceSpeed);
        data.putDouble("turbulence_scale", this.turbulenceScale);
    }

    @Override
    public void fromData(MapType data)
    {
        this.strength = (float) data.getDouble("strength", DEFAULT.strength);
        this.x = (float) data.getDouble("x", DEFAULT.x);
        this.y = (float) data.getDouble("y", DEFAULT.y);
        this.z = (float) data.getDouble("z", DEFAULT.z);
        this.turbulence = (float) data.getDouble("turbulence", DEFAULT.turbulence);
        this.turbulenceSpeed = (float) data.getDouble("turbulence_speed", DEFAULT.turbulenceSpeed);
        this.turbulenceScale = (float) data.getDouble("turbulence_scale", DEFAULT.turbulenceScale);
    }
}
