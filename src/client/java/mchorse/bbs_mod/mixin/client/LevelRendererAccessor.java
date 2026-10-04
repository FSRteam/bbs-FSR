package mchorse.bbs_mod.mixin.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.SortedSet;

/**
 * Exposes per-camera targets and culling state for optional world passes.
 * Targets and the previous frustum are restored together; camera markers are
 * invalidated so the next pass rebuilds visibility for its actual camera.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor
{
    /**
     * The per-block mining cracks vanilla steps while a player breaks it. The
     * model block renderer reads them to paint the crack decal onto the body's
     * hitbox (the block itself renders INVISIBLE, so vanilla's crumbling pass
     * has nothing to draw on).
     */
    @Accessor("destructionProgress")
    Long2ObjectMap<SortedSet<BlockDestructionProgress>> bbs$getDestructionProgress();

    @Accessor("cullingFrustum")
    Frustum bbs$getCullingFrustum();

    @Accessor("cullingFrustum")
    void bbs$setCullingFrustum(Frustum frustum);

    @Accessor("entityEffect")
    PostChain bbs$getEntityEffect();

    @Accessor("entityEffect")
    void bbs$setEntityEffect(PostChain chain);

    @Accessor("transparencyChain")
    PostChain bbs$getTransparencyChain();

    @Accessor("transparencyChain")
    void bbs$setTransparencyChain(PostChain chain);

    @Accessor("entityTarget")
    void bbs$setEntityTarget(RenderTarget target);

    @Accessor("translucentTarget")
    void bbs$setTranslucentTarget(RenderTarget target);

    @Accessor("itemEntityTarget")
    void bbs$setItemEntityTarget(RenderTarget target);

    @Accessor("particlesTarget")
    void bbs$setParticlesTarget(RenderTarget target);

    @Accessor("weatherTarget")
    void bbs$setWeatherTarget(RenderTarget target);

    @Accessor("cloudsTarget")
    void bbs$setCloudsTarget(RenderTarget target);

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
