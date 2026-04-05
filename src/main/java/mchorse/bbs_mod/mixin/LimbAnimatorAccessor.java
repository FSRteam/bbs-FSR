package mchorse.bbs_mod.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface LimbAnimatorAccessor
{
    @Accessor("speedOld")
    public float getSpeedOld();

    @Accessor("speedOld")
    public void setSpeedOld(float v);

    @Accessor("speed")
    public float getSpeed();

    @Accessor("speed")
    public void setSpeed(float v);

    @Accessor("position")
    public float getPosition();

    @Accessor("position")
    public void setPosition(float v);
}
