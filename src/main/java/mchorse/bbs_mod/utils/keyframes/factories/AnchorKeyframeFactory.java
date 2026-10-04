package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;

public class AnchorKeyframeFactory implements IKeyframeFactory<Anchor>
{
    private final TransformKeyframeFactory transform = new TransformKeyframeFactory();

    @Override
    public Anchor fromData(BaseType data)
    {
        Anchor anchor = new Anchor();

        anchor.fromData(data.asMap());

        return anchor;
    }

    @Override
    public BaseType toData(Anchor value)
    {
        return value.toData();
    }

    @Override
    public Anchor createEmpty()
    {
        return new Anchor();
    }

    @Override
    public Anchor copy(Anchor value)
    {
        Anchor anchor = value.copy();

        anchor.previous = value.previous == null ? null : value.previous.copy();
        anchor.x = value.x;

        return anchor;
    }

    @Override
    public Anchor interpolate(Keyframe<Anchor> preA, Keyframe<Anchor> a, Keyframe<Anchor> b, Keyframe<Anchor> postB, IInterp interpolation, float x)
    {
        if (a.getValue().hasSameTarget(b.getValue())
            && (interpolation.has(Interpolations.AUTO) || interpolation.has(Interpolations.AUTO_CLAMPED)))
        {
            /* Offsets belonging to another target are in a different coordinate system, so they
             * must not shape this segment's tangents; fall back to the segment's own endpoints. */
            if (!preA.getValue().hasSameTarget(a.getValue())) preA = a;
            if (!postB.getValue().hasSameTarget(b.getValue())) postB = b;

            Anchor anchor = b.getValue().copy();

            anchor.transform.autoLerp(
                preA.getValue().transform, a.getValue().transform, b.getValue().transform, postB.getValue().transform,
                preA.getTick(), a.getTick(), b.getTick(), postB.getTick(),
                interpolation.has(Interpolations.AUTO_CLAMPED), x
            );

            return anchor;
        }

        return IKeyframeFactory.super.interpolate(preA, a, b, postB, interpolation, x);
    }

    @Override
    public Anchor interpolate(Anchor preA, Anchor a, Anchor b, Anchor postB, IInterp interpolation, float x)
    {
        if (a.hasSameTarget(b))
        {
            if (!preA.hasSameTarget(a)) preA = a;
            if (!postB.hasSameTarget(b)) postB = b;

            Anchor anchor = b.copy();

            anchor.transform.copy(this.transform.interpolate(preA.transform, a.transform, b.transform, postB.transform, interpolation, x));

            return anchor;
        }

        Anchor anchor = b.copy();

        anchor.previous = a.copy();
        anchor.x = interpolation.interpolate(0F, 1F, x);

        return anchor;
    }
}
