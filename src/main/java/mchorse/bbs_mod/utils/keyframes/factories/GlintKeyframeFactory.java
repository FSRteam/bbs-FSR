package mchorse.bbs_mod.utils.keyframes.factories;

import mchorse.bbs_mod.cubic.glint.GlintControl;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.utils.interps.IInterp;

import java.util.LinkedHashSet;
import java.util.Set;

public class GlintKeyframeFactory implements IKeyframeFactory<GlintControls>
{
    private final GlintControls interpolated = new GlintControls();
    private final Set<String> bones = new LinkedHashSet<>();

    @Override
    public GlintControls fromData(BaseType data)
    {
        GlintControls controls = new GlintControls();

        if (data.isMap())
        {
            controls.fromData(data.asMap());
        }

        return controls;
    }

    @Override
    public BaseType toData(GlintControls value)
    {
        return value.toData();
    }

    @Override
    public GlintControls createEmpty()
    {
        return new GlintControls();
    }

    @Override
    public GlintControls copy(GlintControls value)
    {
        return value.copy();
    }

    @Override
    public GlintControls interpolate(GlintControls preA, GlintControls a, GlintControls b, GlintControls postB, IInterp interpolation, float x)
    {
        this.bones.clear();
        this.bones.addAll(preA.controls.keySet());
        this.bones.addAll(a.controls.keySet());
        this.bones.addAll(b.controls.keySet());
        this.bones.addAll(postB.controls.keySet());
        this.interpolated.controls.clear();

        for (String bone : this.bones)
        {
            GlintControl control = new GlintControl();

            control.lerp(
                preA.controls.getOrDefault(bone, GlintControl.DEFAULT),
                a.controls.getOrDefault(bone, GlintControl.DEFAULT),
                b.controls.getOrDefault(bone, GlintControl.DEFAULT),
                postB.controls.getOrDefault(bone, GlintControl.DEFAULT),
                interpolation,
                x
            );
            this.interpolated.controls.put(bone, control);
        }

        return this.interpolated;
    }
}
