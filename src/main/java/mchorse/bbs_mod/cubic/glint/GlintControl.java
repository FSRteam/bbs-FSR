package mchorse.bbs_mod.cubic.glint;

import mchorse.bbs_mod.data.IMapSerializable;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;

/** The four animatable fields of one bone's enchantment layer. */
public class GlintControl implements IMapSerializable
{
    public static final GlintControl DEFAULT = new GlintControl();

    public float mode = PoseTransform.GLINT_OFF;
    public final Color color = new Color().set(Colors.WHITE);
    public float speed = PoseTransform.DEFAULT_GLINT_SPEED;
    public final Transform transform = new Transform();

    public void lerp(GlintControl preA, GlintControl a, GlintControl b, GlintControl postB, IInterp interpolation, float x)
    {
        this.mode = x < 0.5F ? a.mode : b.mode;
        this.color.set(
            (float) MathUtils.clamp(interpolation.interpolate(IInterp.context.set(preA.color.r, a.color.r, b.color.r, postB.color.r, x)), 0F, 1F),
            (float) MathUtils.clamp(interpolation.interpolate(IInterp.context.set(preA.color.g, a.color.g, b.color.g, postB.color.g, x)), 0F, 1F),
            (float) MathUtils.clamp(interpolation.interpolate(IInterp.context.set(preA.color.b, a.color.b, b.color.b, postB.color.b, x)), 0F, 1F),
            (float) MathUtils.clamp(interpolation.interpolate(IInterp.context.set(preA.color.a, a.color.a, b.color.a, postB.color.a, x)), 0F, 1F)
        );
        this.speed = (float) interpolation.interpolate(IInterp.context.set(preA.speed, a.speed, b.speed, postB.speed, x));
        this.transform.lerp(preA.transform, a.transform, b.transform, postB.transform, interpolation, x);
    }

    public GlintControl copy()
    {
        GlintControl control = new GlintControl();

        control.copy(this);

        return control;
    }

    public void copy(GlintControl other)
    {
        this.mode = other.mode;
        this.color.copy(other.color);
        this.speed = other.speed;
        this.transform.copy(other.transform);
    }

    public void copy(PoseTransform pose)
    {
        this.mode = pose.glintMode;
        this.color.copy(pose.glintColor);
        this.speed = pose.glintSpeed;
        this.transform.copy(pose.glintTransform);
    }

    public void apply(PoseTransform pose)
    {
        pose.glintMode = this.mode;
        pose.glintColor.copy(this.color);
        pose.glintSpeed = this.speed;
        pose.glintTransform.copy(this.transform);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (obj instanceof GlintControl control)
        {
            return this.mode == control.mode
                && this.color.equals(control.color)
                && this.speed == control.speed
                && this.transform.equals(control.transform);
        }

        return false;
    }

    @Override
    public void toData(MapType data)
    {
        data.putFloat("mode", this.mode);
        data.putInt("color", this.color.getARGBColor());
        data.putFloat("speed", this.speed);
        data.put("transform", this.transform.toData());
    }

    @Override
    public void fromData(MapType data)
    {
        this.mode = data.getFloat("mode", PoseTransform.GLINT_OFF);
        this.color.set(data.getInt("color", Colors.WHITE));
        this.speed = data.getFloat("speed", PoseTransform.DEFAULT_GLINT_SPEED);
        this.transform.fromData(data.getMap("transform"));
    }
}
