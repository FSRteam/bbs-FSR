package mchorse.bbs_mod.mixin.client;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.player.KeyboardInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin
{
    /*
     * Fallback path kept for M09:
     * MovementInputUpdateEvent does not expose slowDown/slowDownFactor parity,
     * so tick(...) override stays as the temporary compatibility layer.
     */
    private static float getMovementImpulse(boolean positive, boolean negative)
    {
        return positive == negative ? 0F : (positive ? 1F : -1F);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(boolean slowDown, float slowDownFactor, CallbackInfo info)
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (
            menu instanceof UIDashboard dashboard &&
            dashboard.getPanels().panel instanceof UIFilmPanel filmPanel &&
            filmPanel.getController().isControlling()
        ) {
            KeyboardInput input = (KeyboardInput) (Object) this;

            input.up = Window.isKeyPressed(GLFW.GLFW_KEY_W);
            input.down = Window.isKeyPressed(GLFW.GLFW_KEY_S);
            input.left = Window.isKeyPressed(GLFW.GLFW_KEY_A);
            input.right = Window.isKeyPressed(GLFW.GLFW_KEY_D);
            input.forwardImpulse = getMovementImpulse(input.up, input.down);
            input.leftImpulse = getMovementImpulse(input.left, input.right);
            input.jumping = Window.isKeyPressed(GLFW.GLFW_KEY_SPACE);
            input.shiftKeyDown = Window.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT);

            if (slowDown)
            {
                input.leftImpulse *= slowDownFactor;
                input.forwardImpulse *= slowDownFactor;
            }
        }
    }
}
