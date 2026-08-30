package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link EntityRenderDispatcher}'s camera-relative state. The secondary
 * off-screen pass calls {@code prepare} with the secondary camera, mutating the
 * shared dispatcher's {@code camera} and {@code cameraOrientation}; if the main
 * render path (or anything else) reads these between frames they describe the
 * wrong camera, making entities jump to incorrect positions. Save and restore
 * them around the secondary pass.
 */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor
{
    @Accessor("camera")
    Camera bbs$getCamera();

    @Accessor("camera")
    void bbs$setCamera(Camera camera);

    @Accessor("cameraOrientation")
    Quaternionf bbs$getCameraOrientation();

    @Accessor("cameraOrientation")
    void bbs$setCameraOrientation(Quaternionf orientation);
}
