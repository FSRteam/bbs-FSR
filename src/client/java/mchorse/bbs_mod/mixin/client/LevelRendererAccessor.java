package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the per-frame cull/occlusion bookkeeping fields that
 * {@link LevelRenderer#renderLevel} mutates. The secondary off-screen pass
 * re-enters renderLevel with a different camera, which would otherwise leave
 * these fields pointing at the secondary camera and corrupt the next frame's
 * main-view frustum culling (Immersive Portals issue #271). Saving and
 * restoring them around the secondary pass keeps the main view stable.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor
{
    @Accessor("prevCamX")
    double bbs$getPrevCamX();

    @Accessor("prevCamX")
    void bbs$setPrevCamX(double value);

    @Accessor("prevCamY")
    double bbs$getPrevCamY();

    @Accessor("prevCamY")
    void bbs$setPrevCamY(double value);

    @Accessor("prevCamZ")
    double bbs$getPrevCamZ();

    @Accessor("prevCamZ")
    void bbs$setPrevCamZ(double value);

    @Accessor("prevCamRotX")
    double bbs$getPrevCamRotX();

    @Accessor("prevCamRotX")
    void bbs$setPrevCamRotX(double value);

    @Accessor("prevCamRotY")
    double bbs$getPrevCamRotY();

    @Accessor("prevCamRotY")
    void bbs$setPrevCamRotY(double value);

    @Accessor("lastCameraSectionX")
    int bbs$getLastCameraSectionX();

    @Accessor("lastCameraSectionX")
    void bbs$setLastCameraSectionX(int value);

    @Accessor("lastCameraSectionY")
    int bbs$getLastCameraSectionY();

    @Accessor("lastCameraSectionY")
    void bbs$setLastCameraSectionY(int value);

    @Accessor("lastCameraSectionZ")
    int bbs$getLastCameraSectionZ();

    @Accessor("lastCameraSectionZ")
    void bbs$setLastCameraSectionZ(int value);
}
