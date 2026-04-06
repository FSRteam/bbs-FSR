package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intentionally no-op.
 * M09 moved scroll handling to client input/screen events; this mixin stays dormant
 * to prevent duplicate Window scroll writes when legacy entries are reintroduced.
 */
@Mixin(MouseHandler.class)
public class MouseMixin
{
    @Inject(method = "onScroll", at = @At("HEAD"))
    public void mouseScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        /* no-op: event path owns scroll propagation */
    }
}
