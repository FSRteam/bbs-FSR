package mchorse.bbs_mod.utils.pose;

import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Lerps;

public class PoseTransform extends Transform
{
    private static PoseTransform DEFAULT = new PoseTransform();

    public float fix;
    public final Color color = new Color().set(Colors.WHITE);
    public float lighting;

    /* Enchantment glint applied to this bone alone: off, full, edge or vanilla.
     * Kept as a float so it rides the same keyframe machinery as every other transform
     * field, but it's a discrete state — interpolation steps between values rather than
     * sweeping through them, since there's no meaningful "half way to edge glint". */
    public float glintMode;
    /* Glint tint, with alpha doubling as the effect's opacity. */
    public final Color glintColor = new Color().set(Colors.WHITE);
    public float glintSpeed = DEFAULT_GLINT_SPEED;
    public final Transform glintTransform = new Transform();

    public static final float GLINT_OFF = 0F;
    public static final float GLINT_FULL = 1F;
    public static final float GLINT_EDGE = 2F;
    public static final float GLINT_VANILLA = 3F;

    public static final float DEFAULT_GLINT_SPEED = 1F;

    @Override
    public void identity()
    {
        super.identity();

        this.fix = 0F;
        this.color.set(Colors.WHITE);
        this.lighting = 0F;

        this.glintMode = GLINT_OFF;
        this.glintColor.set(Colors.WHITE);
        this.glintSpeed = DEFAULT_GLINT_SPEED;
        this.glintTransform.identity();
    }

    @Override
    public void lerp(Transform transform, float a)
    {
        if (transform instanceof PoseTransform pose)
        {
            this.fix = Lerps.lerp(this.fix, pose.fix, a);

            this.color.r = Lerps.lerp(this.color.r, pose.color.r, a);
            this.color.g = Lerps.lerp(this.color.g, pose.color.g, a);
            this.color.b = Lerps.lerp(this.color.b, pose.color.b, a);
            this.color.a = Lerps.lerp(this.color.a, pose.color.a, a);

            this.lighting = Lerps.lerp(this.lighting, pose.lighting, a);

            /* Discrete — snap at the midpoint instead of passing through the modes in
             * between, which would flash the wrong effect mid-blend. */
            this.glintMode = a < 0.5F ? this.glintMode : pose.glintMode;

            this.glintColor.r = Lerps.lerp(this.glintColor.r, pose.glintColor.r, a);
            this.glintColor.g = Lerps.lerp(this.glintColor.g, pose.glintColor.g, a);
            this.glintColor.b = Lerps.lerp(this.glintColor.b, pose.glintColor.b, a);
            this.glintColor.a = Lerps.lerp(this.glintColor.a, pose.glintColor.a, a);

            this.glintSpeed = Lerps.lerp(this.glintSpeed, pose.glintSpeed, a);
            this.glintTransform.lerp(pose.glintTransform, a);
        }

        super.lerp(transform, a);
    }

    @Override
    public void lerp(Transform preA, Transform a, Transform b, Transform postB, IInterp interp, float x)
    {
        super.lerp(preA, a, b, postB, interp, x);

        if (preA instanceof PoseTransform preA1)
        {
            PoseTransform a1 = (PoseTransform) a;
            PoseTransform b1 = (PoseTransform) b;
            PoseTransform postB1 = (PoseTransform) postB;

            this.fix = (float) interp.interpolate(IInterp.context.set(preA1.fix, a1.fix, b1.fix, postB1.fix, x));

            this.color.set(
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.color.r, a1.color.r, b1.color.r, postB1.color.r, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.color.g, a1.color.g, b1.color.g, postB1.color.g, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.color.b, a1.color.b, b1.color.b, postB1.color.b, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.color.a, a1.color.a, b1.color.a, postB1.color.a, x)), 0F, 1F)
            );

            this.lighting = (float) interp.interpolate(IInterp.context.set(preA1.lighting, a1.lighting, b1.lighting, postB1.lighting, x));

            /* Discrete, so the easing curve doesn't apply — the mode simply switches at the
             * halfway point of the segment. */
            this.glintMode = x < 0.5F ? a1.glintMode : b1.glintMode;

            this.glintColor.set(
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.glintColor.r, a1.glintColor.r, b1.glintColor.r, postB1.glintColor.r, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.glintColor.g, a1.glintColor.g, b1.glintColor.g, postB1.glintColor.g, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.glintColor.b, a1.glintColor.b, b1.glintColor.b, postB1.glintColor.b, x)), 0F, 1F),
                (float) MathUtils.clamp(interp.interpolate(IInterp.context.set(preA1.glintColor.a, a1.glintColor.a, b1.glintColor.a, postB1.glintColor.a, x)), 0F, 1F)
            );

            this.glintSpeed = (float) interp.interpolate(IInterp.context.set(preA1.glintSpeed, a1.glintSpeed, b1.glintSpeed, postB1.glintSpeed, x));
            this.glintTransform.lerp(preA1.glintTransform, a1.glintTransform, b1.glintTransform, postB1.glintTransform, interp, x);
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        boolean result = super.equals(obj);

        if (obj instanceof PoseTransform poseTransform)
        {
            result = result && this.fix == poseTransform.fix;
            result = result && this.color.equals(poseTransform.color);
            result = result && this.lighting == poseTransform.lighting;
            result = result && this.glintMode == poseTransform.glintMode;
            result = result && this.glintColor.equals(poseTransform.glintColor);
            result = result && this.glintSpeed == poseTransform.glintSpeed;
            result = result && this.glintTransform.equals(poseTransform.glintTransform);
        }

        return result;
    }

    @Override
    public Transform copy()
    {
        PoseTransform transform = new PoseTransform();

        transform.copy(this);

        return transform;
    }

    @Override
    public void copy(Transform transform)
    {
        if (transform instanceof PoseTransform poseTransform)
        {
            this.fix = poseTransform.fix;
            this.color.copy(poseTransform.color);
            this.lighting = poseTransform.lighting;
            this.glintMode = poseTransform.glintMode;
            this.glintColor.copy(poseTransform.glintColor);
            this.glintSpeed = poseTransform.glintSpeed;
            this.glintTransform.copy(poseTransform.glintTransform);
        }

        super.copy(transform);
    }

    @Override
    public void add(Transform transform)
    {
        super.add(transform);

        if (transform instanceof PoseTransform pose)
        {
            this.fix += pose.fix;
            this.color.mul(pose.color);
            this.lighting += pose.lighting;

            /* Overlays stack additively like lighting does, clamped so the sum stays a
             * valid mode — effectively the stronger effect wins. */
            this.glintMode = MathUtils.clamp(this.glintMode + pose.glintMode, GLINT_OFF, GLINT_VANILLA);
            this.glintColor.mul(pose.glintColor);
            /* Speed stacks by how far the overlay departs from the resting value, since
             * that value is 1 rather than 0. Adding it outright would let an overlay that
             * never touched the glint — every per-bone animation track carries a full
             * transform — speed the effect up on its own. */
            this.glintSpeed += pose.glintSpeed - DEFAULT_GLINT_SPEED;
            this.glintTransform.add(pose.glintTransform);
        }
    }

    @Override
    public void toData(MapType data)
    {
        super.toData(data);

        data.putFloat("fix", this.fix);
        data.putInt("color", this.color.getARGBColor());
        data.putFloat("lighting", this.lighting);
        data.putFloat("glint", this.glintMode);
        data.putInt("glint_color", this.glintColor.getARGBColor());
        data.putFloat("glint_speed", this.glintSpeed);
        data.put("glint_transform", this.glintTransform.toData());
    }

    @Override
    public void fromData(MapType data)
    {
        super.fromData(data);

        this.fix = data.getFloat("fix");
        this.color.set(data.getInt("color", Colors.WHITE));
        this.lighting = data.getFloat("lighting");
        this.glintMode = data.getFloat("glint", GLINT_OFF);
        this.glintColor.set(data.getInt("glint_color", Colors.WHITE));
        /* Defaults to 1 rather than 0 — a speed of zero would freeze the animation, so
         * poses saved before glint existed must not read back as stopped. */
        this.glintSpeed = data.getFloat("glint_speed", DEFAULT_GLINT_SPEED);
        this.glintTransform.fromData(data.getMap("glint_transform"));
    }

    @Override
    public boolean isDefault()
    {
        return this.equals(DEFAULT);
    }
}
