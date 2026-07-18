package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIKeybind;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Real UIKeybind regressions for interleaved mouse buttons and callback failure. */
public final class UIKeybindGestureOwnershipTest
{
    private UIKeybindGestureOwnershipTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertForeignReleaseDoesNotAdvanceOrFinishCapture();
        assertCallbackFailureLeavesTheKeybindReusable();
    }

    private static void assertForeignReleaseDoesNotAdvanceOrFinishCapture()
    {
        AtomicInteger callbacks = new AtomicInteger();
        UIKeybind keybind = new UIKeybind((combo) -> callbacks.incrementAndGet()).mouse();
        UIContext context = new UIContext(null);

        keybind.area.set(0, 0, 100, 20);

        click(keybind, context, 5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        release(keybind, context, 5, 5, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        check(keybind.reading && callbacks.get() == 0,
            "foreign release consumed the keybind activation press");

        release(keybind, context, 5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(keybind.reading && callbacks.get() == 0,
            "activation release prematurely completed mouse capture");

        click(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        click(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(keybind.combo.keys.size() == 1 && keybind.combo.keys.get(0) == -GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
            "foreign press replaced or extended the captured mouse-button owner");

        release(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        check(keybind.reading && callbacks.get() == 0,
            "foreign release prematurely completed mouse capture");

        release(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        check(!keybind.reading && callbacks.get() == 1,
            "matching captured-button release did not complete exactly once");
    }

    private static void assertCallbackFailureLeavesTheKeybindReusable()
    {
        RuntimeException expected = new RuntimeException("expected keybind callback failure");
        UIKeybind keybind = new UIKeybind((combo) ->
        {
            throw expected;
        }).mouse();
        UIContext context = new UIContext(null);

        keybind.area.set(0, 0, 100, 20);
        armMouseCapture(keybind, context, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        try
        {
            release(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
            throw new AssertionError("throwing keybind callback was swallowed");
        }
        catch (RuntimeException exception)
        {
            check(exception == expected, "keybind callback failure identity changed");
        }

        check(!keybind.reading, "throwing keybind callback retained reading state");

        AtomicInteger callbacks = new AtomicInteger();

        keybind.callback = (combo) -> callbacks.incrementAndGet();
        armMouseCapture(keybind, context, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        release(keybind, context, 150, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        check(!keybind.reading && callbacks.get() == 1,
            "keybind could not reacquire after a callback failure");
    }

    private static void armMouseCapture(UIKeybind keybind, UIContext context, int button)
    {
        click(keybind, context, 5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        release(keybind, context, 5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        click(keybind, context, 150, 5, button);
    }

    private static void click(UIKeybind keybind, UIContext context, int x, int y, int button)
    {
        context.setMouse(x, y, button);
        keybind.subMouseClicked(context);
    }

    private static void release(UIKeybind keybind, UIContext context, int x, int y, int button)
    {
        context.setMouse(x, y, button);

        try
        {
            Method method = UIKeybind.class.getDeclaredMethod("subMouseReleased", UIContext.class);

            method.setAccessible(true);
            method.invoke(keybind, context);
        }
        catch (InvocationTargetException exception)
        {
            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }
            else if (cause instanceof Error error)
            {
                throw error;
            }

            throw new AssertionError("Unexpected checked keybind release failure", cause);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke UIKeybind release", exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
