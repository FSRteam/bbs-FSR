package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
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
        mouseButtonsOnlyToggleOnLeftClick();
        collapsingRelayoutsAncestorScrollView();
    }

    private static void mouseButtonsOnlyToggleOnLeftClick()
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

    private static void collapsingRelayoutsAncestorScrollView()
    {
        UIScrollView viewport = new UIScrollView();
        UIElement spacer = new UIElement();
        UISection section = new UISection();
        UIElement field = new UIElement();
        UIElement follower = new UIElement();

        viewport.set(0, 0, 120, 60);
        viewport.column(4).vertical().stretch().scroll();
        spacer.h(80);
        field.h(100);
        follower.h(20);
        section.fields.add(field);
        viewport.add(spacer, section, follower);
        viewport.resize();

        int expandedSize = viewport.scroll.scrollSize;
        int expandedFollowerY = follower.area.y;

        viewport.scroll.setScroll(expandedSize);
        section.setExpanded(false);

        int maximum = Math.max(0, viewport.scroll.scrollSize - viewport.area.h);

        check(viewport.scroll.scrollSize < expandedSize,
            "collapsing a section left the ancestor scroll range at its expanded height");
        check(follower.area.y < expandedFollowerY,
            "collapsing a section left later scroll content at its expanded position");
        check(viewport.scroll.getScroll() <= maximum,
            "collapsing a section left the ancestor scroll position outside its valid range");
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
