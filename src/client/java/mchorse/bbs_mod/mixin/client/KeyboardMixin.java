package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In MC 1.21.1 NeoForge, {@code ClientHooks.onKeyInput()} (which fires
 * {@code InputEvent.Key}) runs AFTER {@code screen.keyPressed()} in
 * {@link KeyboardHandler#keyPress}.  If we rely solely on the event to set
 * {@code BBSRendering.lastAction}, the screen reads a stale value.
 *
 * Setting {@code lastAction} here at HEAD guarantees the correct action is
 * visible when UIScreen.keyPressed() delegates to
 * {@link mchorse.bbs_mod.ui.framework.UIBaseMenu#handleKey}.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin
{
    @Inject(method = "keyPress", at = @At("HEAD"))
    public void onOnKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        BBSRendering.lastAction = action;
    }

    @Inject(method = "keyPress", at = @At("TAIL"))
    public void onOnEndKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo info)
    {
        /* no-op */
    }
}
