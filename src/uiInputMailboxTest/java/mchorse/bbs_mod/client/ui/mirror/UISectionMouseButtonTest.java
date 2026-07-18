package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import org.lwjgl.glfw.GLFW;

/** Real button-routing regression for collapsible section headers. */
public final class UISectionMouseButtonTest
{
    private UISectionMouseButtonTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        UISection section = new UISection();
        UIContext context = new UIContext(null);

        section.title.area.set(20, 30, 120, 10);

        check(section.isExpanded(), "section did not start expanded");
        check(!click(section, context, GLFW.GLFW_MOUSE_BUTTON_RIGHT) && section.isExpanded(),
            "right click toggled or consumed a section header");
        check(!click(section, context, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) && section.isExpanded(),
            "middle click toggled or consumed a section header");
        check(click(section, context, GLFW.GLFW_MOUSE_BUTTON_LEFT) && !section.isExpanded(),
            "left click did not collapse the section header");
        check(click(section, context, GLFW.GLFW_MOUSE_BUTTON_LEFT) && section.isExpanded(),
            "second left click did not expand the section header");
    }

    private static boolean click(UISection section, UIContext context, int button)
    {
        context.setMouse(25, 35, button);

        return section.subMouseClicked(context);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
