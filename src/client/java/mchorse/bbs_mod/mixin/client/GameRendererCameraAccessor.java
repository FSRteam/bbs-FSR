package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Allows the secondary world pass to use an isolated Camera instance. */
@Mixin(GameRenderer.class)
public interface GameRendererCameraAccessor
{
    @Accessor("mainCamera")
    Camera bbs$getMainCamera();

    @Accessor("mainCamera")
    @Mutable
    void bbs$setMainCamera(Camera camera);
}
