package mchorse.bbs_mod.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
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

    /** Read the configured binding while an editor screen holds vanilla keyboard input. */
    private static boolean isBoundKeyDown(KeyMapping binding)
    {
        InputConstants.Key key = binding.getKey();

        if (key.getValue() == GLFW.GLFW_KEY_UNKNOWN)
        {
            return false;
        }

        if (key.getType() == InputConstants.Type.MOUSE)
        {
            return Window.isMouseButtonPressed(key.getValue());
        }

        return key.getType() == InputConstants.Type.KEYSYM && Window.isKeyPressed(key.getValue());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    public void onTick(boolean slowDown, float slowDownFactor, CallbackInfo info)
    {
        UIBaseMenu menu = UIScreen.getCurrentMenu();

        if (
            menu instanceof UIDashboard dashboard &&
            dashboard.getPanels().panel instanceof UIFilmPanel filmPanel &&
            filmPanel.getController().canControl()
        ) {
            KeyboardInput input = (KeyboardInput) (Object) this;
            Options options = Minecraft.getInstance().options;

            input.up = isBoundKeyDown(options.keyUp);
            input.down = isBoundKeyDown(options.keyDown);
            input.left = isBoundKeyDown(options.keyLeft);
            input.right = isBoundKeyDown(options.keyRight);
            input.forwardImpulse = getMovementImpulse(input.up, input.down);
            input.leftImpulse = getMovementImpulse(input.left, input.right);
            input.jumping = isBoundKeyDown(options.keyJump);
            input.shiftKeyDown = isBoundKeyDown(options.keyShift);
            options.keySprint.setDown(isBoundKeyDown(options.keySprint));

            if (slowDown)
            {
                input.leftImpulse *= slowDownFactor;
                input.forwardImpulse *= slowDownFactor;
            }
        }
    }
}
