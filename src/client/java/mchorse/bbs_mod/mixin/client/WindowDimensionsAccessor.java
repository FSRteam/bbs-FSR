package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the physical framebuffer dimensions (pixels) without WindowMixin's
 * export-size overrides. Must stay aligned with vanilla
 * {@code Window.getWidth()}/{@code getHeight()} semantics (both are the
 * framebuffer pixel size) so consumers never mix screen-coordinate and pixel
 * dimensions.
 */
@Mixin(Window.class)
public interface WindowDimensionsAccessor
{
    @Accessor("framebufferWidth")
    int bbs$getRawWidth();

    @Accessor("framebufferHeight")
    int bbs$getRawHeight();
}
