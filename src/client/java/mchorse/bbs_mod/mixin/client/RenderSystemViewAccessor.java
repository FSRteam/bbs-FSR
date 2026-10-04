package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Swap the whole stack so a failed optional renderer cannot leak stack depth. */
@Mixin(RenderSystem.class)
public interface RenderSystemViewAccessor
{
    @Accessor("modelViewStack")
    @Mutable
    static void bbs$setModelViewStack(Matrix4fStack stack)
    {
        throw new AssertionError("RenderSystem accessor was not applied");
    }
}
