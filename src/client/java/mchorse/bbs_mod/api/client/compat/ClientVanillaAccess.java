package mchorse.bbs_mod.api.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import mchorse.bbs_mod.mixin.client.MinecraftAccessor;
import net.minecraft.client.Minecraft;

/** Framebuffer ownership for addons rendering their own scene or export target. */
public final class ClientVanillaAccess
{
    private ClientVanillaAccess()
    {}

    public static RenderTarget getFramebuffer(Minecraft client)
    {
        return client.getMainRenderTarget();
    }

    public static void setFramebuffer(Minecraft client, RenderTarget framebuffer)
    {
        ((MinecraftAccessor) client).bbs$setMainRenderTarget(framebuffer);
    }
}
