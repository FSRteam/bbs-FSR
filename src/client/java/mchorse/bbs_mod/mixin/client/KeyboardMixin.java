package mchorse.bbs_mod.mixin.client;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intentionally no-op.
 * M09 migrated keyboard handling to the event pipeline; keeping this class avoids
 * remap churn while guaranteeing no duplicate dispatch if re-enabled accidentally.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin
{
    @Inject(method = "keyPress", at = @At("HEAD"))
    public void onOnKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        /* no-op: InputEvent.Key updates lastAction */
    }

    @Inject(method = "keyPress", at = @At("TAIL"))
    public void onOnEndKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        /* no-op: InputEvent.Key is the primary onEndKey path */
    }
}
