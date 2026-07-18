package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Tracks whether the user is actively editing (not AFK) for the film's {@code time_spent_active} counter.
 * AFK = no keyboard/mouse input for {@link #AFK_IDLE_MS} while the game window is focused.
 */
public final class FilmEditorUserActivity
{
    /**
     * Idle time after which the user is considered AFK (no time added to the active counter).
     */
    private static final long AFK_IDLE_MS = 120_000L;

    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private long lastActivityMs;

    public void reset()
    {
        this.lastMouseX = Integer.MIN_VALUE;
        this.lastMouseY = Integer.MIN_VALUE;
        this.lastActivityMs = 0L;
    }

    public void onFilmOpened()
    {
        this.lastMouseX = Integer.MIN_VALUE;
        this.lastMouseY = Integer.MIN_VALUE;
        this.lastActivityMs = System.currentTimeMillis();
    }

    /**
     * @return {@code true} if the non-AFK timer should accumulate the elapsed real-time delta for this frame.
     */
    public boolean shouldAccumulateActiveTime(Minecraft mc, UIContext context, long nowMs)
    {
        if (!mc.isWindowActive() || mc.isPaused())
        {
            return false;
        }

        if (this.detectActivity(context))
        {
            this.lastActivityMs = nowMs;
        }

        return nowMs - this.lastActivityMs < AFK_IDLE_MS;
    }

    private boolean detectActivity(UIContext context)
    {
        if (context.mouseX != this.lastMouseX || context.mouseY != this.lastMouseY)
        {
            this.lastMouseX = context.mouseX;
            this.lastMouseY = context.mouseY;
            return true;
        }

        if (context.mouseWheel != 0D || context.mouseWheelHorizontal != 0D)
        {
            return true;
        }

        for (int b = 0; b <= GLFW.GLFW_MOUSE_BUTTON_LAST; b++)
        {
            if (Window.isMouseButtonPressed(b))
            {
                return true;
            }
        }

        if (context.getKeyAction() != KeyAction.RELEASED && context.getKeyCode() != GLFW.GLFW_KEY_UNKNOWN)
        {
            return true;
        }

        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++)
        {
            if (key == GLFW.GLFW_KEY_UNKNOWN)
            {
                continue;
            }

            try
            {
                if (Window.isKeyPressed(key))
                {
                    return true;
                }
            }
            catch (IndexOutOfBoundsException ignored)
            {
                /* PojavLauncher (Android) uses a GLFW implementation with a smaller
                   key-state buffer than GLFW_KEY_LAST; once an index is out of range,
                   all higher key codes will fail too, so stop polling. */
                break;
            }
        }

        return false;
    }
}
