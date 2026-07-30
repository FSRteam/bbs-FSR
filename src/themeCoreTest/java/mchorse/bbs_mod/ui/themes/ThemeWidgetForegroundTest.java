package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.renderers.InputRenderer;
import mchorse.bbs_mod.utils.colors.Colors;

import java.lang.reflect.Method;

final class ThemeWidgetForegroundTest
{
    private ThemeWidgetForegroundTest()
    {}

    static void run() throws Exception
    {
        Method buttonColor = method(UIButton.class, "resolveLabelColor", int.class, int.class);

        assertEquals(Colors.WHITE, buttonColor.invoke(null, Colors.WHITE, Colors.WHITE), "default button text stays theme white");
        assertEquals(0xff123456, buttonColor.invoke(null, 0xff123456, Colors.WHITE), "explicit button text color stays explicit");

        Method textColor = method(Batcher2D.class, "resolveTextColor", int.class, boolean.class, boolean.class);

        assertEquals(Colors.A100, textColor.invoke(null, Colors.WHITE, true, false), "ordinary white text keeps legacy light-theme conversion");
        assertEquals(Colors.WHITE, textColor.invoke(null, Colors.WHITE, true, true), "exact white text bypasses light-theme conversion");

        Method keyCapColor = method(InputRenderer.class, "keyCapLabelColor", boolean.class);

        assertEquals(Colors.A100, keyCapColor.invoke(null, false), "white key cap uses black text");
        assertEquals(Colors.WHITE, keyCapColor.invoke(null, true), "light-theme black key cap uses white text");

        System.out.println("ThemeWidgetForegroundTest: all tests passed");
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws Exception
    {
        Method method = type.getDeclaredMethod(name, parameterTypes);

        method.setAccessible(true);

        return method;
    }

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
