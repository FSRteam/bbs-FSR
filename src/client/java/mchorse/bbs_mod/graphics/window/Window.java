package mchorse.bbs_mod.graphics.window;

import com.mojang.blaze3d.platform.InputConstants;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Window
{
    public static final int MOD_NONE = 0;
    public static final int MOD_SHIFT = 1;
    public static final int MOD_CTRL = 2;
    public static final int MOD_ALT = 3;

    private static int verticalScroll;
    private static long lastScroll;
    private static final Map<Integer, Long> standardCursors = new HashMap<>();
    private static int currentCursorShape = -1;

    private static long lastLocalShiftPress;
    private static long lastLocalCtrlPress;
    private static long lastLocalAltPress;
    private static long lastRemoteShiftPress;
    private static long lastRemoteCtrlPress;
    private static long lastRemoteAltPress;

    public static long getWindow()
    {
        return Minecraft.getInstance().getWindow().getWindow();
    }

    public static void setVerticalScroll(int scroll)
    {
        verticalScroll = scroll;
        lastScroll = System.currentTimeMillis();
    }

    public static int getVerticalScroll()
    {
        if (lastScroll + 5 < System.currentTimeMillis())
        {
            return 0;
        }

        return verticalScroll;
    }

    public static boolean isMouseButtonPressed(int mouse)
    {
        return BBSUiRemoteHeldState.resolveMouseButtonPressed(
            mouse,
            () -> GLFW.glfwGetMouseButton(getWindow(), mouse) == GLFW.GLFW_PRESS
        );
    }

    public static boolean isCtrlPressed()
    {
        return BBSUiRemoteHeldState.resolveModifierPressed(
            BBSUiRemoteInputState.MOD_CONTROL,
            Screen::hasControlDown
        );
    }

    public static boolean isShiftPressed()
    {
        return BBSUiRemoteHeldState.resolveModifierPressed(
            BBSUiRemoteInputState.MOD_SHIFT,
            Screen::hasShiftDown
        );
    }

    public static boolean isAltPressed()
    {
        return BBSUiRemoteHeldState.resolveModifierPressed(
            BBSUiRemoteInputState.MOD_ALT,
            Screen::hasAltDown
        );
    }

    public static void noteModifierKeyEvent(int key, int action)
    {
        if (action != GLFW.GLFW_PRESS)
        {
            return;
        }

        long now = System.nanoTime();
        boolean remote = BBSUiRemoteHeldState.isActive();

        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT)
        {
            if (remote) lastRemoteShiftPress = now;
            else lastLocalShiftPress = now;
        }
        else if (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL)
        {
            if (remote) lastRemoteCtrlPress = now;
            else lastLocalCtrlPress = now;
        }
        else if (key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT)
        {
            if (remote) lastRemoteAltPress = now;
            else lastLocalAltPress = now;
        }
    }

    public static int getLastModifier()
    {
        boolean shift = isShiftPressed();
        boolean ctrl = isCtrlPressed();
        boolean alt = isAltPressed();

        boolean remote = BBSUiRemoteHeldState.isActive();
        long shiftTime = shift ? (remote ? lastRemoteShiftPress : lastLocalShiftPress) : Long.MIN_VALUE;
        long ctrlTime = ctrl ? (remote ? lastRemoteCtrlPress : lastLocalCtrlPress) : Long.MIN_VALUE;
        long altTime = alt ? (remote ? lastRemoteAltPress : lastLocalAltPress) : Long.MIN_VALUE;

        if (shiftTime == Long.MIN_VALUE && ctrlTime == Long.MIN_VALUE && altTime == Long.MIN_VALUE)
        {
            return MOD_NONE;
        }

        if (shiftTime >= ctrlTime && shiftTime >= altTime) return MOD_SHIFT;
        if (ctrlTime >= altTime) return MOD_CTRL;

        return MOD_ALT;
    }

    public static boolean isKeyPressed(int key)
    {
        return BBSUiRemoteHeldState.resolveKeyPressed(key, () -> InputConstants.isKeyDown(getWindow(), key));
    }

    public static String getClipboard()
    {
        try
        {
            String string = GLFW.glfwGetClipboardString(getWindow());

            return string == null ? "" : string;
        }
        catch (Exception e)
        {}

        return "";
    }

    public static MapType getClipboardMap()
    {
        return DataToString.mapFromString(getClipboard());
    }

    /**
     * Get a data map from clipboard with verification key.
     */
    public static MapType getClipboardMap(String verificationKey)
    {
        MapType data = DataToString.mapFromString(getClipboard());

        return data != null && data.getBool(verificationKey) ? data : null;
    }

    public static ListType getClipboardList()
    {
        return DataToString.listFromString(getClipboard());
    }

    public static void setClipboard(String string)
    {
        if (string.length() > 1024)
        {
            byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length + 1);

            buffer.put(bytes);
            buffer.put((byte) 0);
            buffer.flip();

            GLFW.glfwSetClipboardString(getWindow(), buffer);

            MemoryUtil.memFree(buffer);
        }
        else
        {
            GLFW.glfwSetClipboardString(getWindow(), string);
        }
    }

    public static void setClipboard(BaseType data)
    {
        if (data != null)
        {
            setClipboard(DataToString.toString(data, true));
        }
    }

    /**
     * Save given data to clipboard with a verification key that could be
     * used in {@link #getClipboardMap(String)} to decode data.
     */
    public static void setClipboard(MapType data, String verificationKey)
    {
        if (data != null)
        {
            data.putBool(verificationKey, true);
        }

        setClipboard(data);
    }

    public static void moveCursor(int x, int y)
    {
        GLFW.glfwSetCursorPos(getWindow(), x, y);
    }

    public static void setStandardCursor(int shape)
    {
        long window = getWindow();

        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED)
        {
            currentCursorShape = -1;

            return;
        }

        if (currentCursorShape == shape)
        {
            return;
        }

        long cursor = standardCursors.computeIfAbsent(shape, GLFW::glfwCreateStandardCursor);

        GLFW.glfwSetCursor(window, cursor);
        currentCursorShape = shape;
    }

    public static void resetCursor()
    {
        setStandardCursor(GLFW.GLFW_ARROW_CURSOR);
    }
}
