package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UIViewportStack;
import mchorse.bbs_mod.ui.utils.Area;
import org.lwjgl.glfw.GLFW;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression coverage for isolated pointer-event viewport frames. */
public final class UIViewportInputCleanupTest
{
    private UIViewportInputCleanupTest()
    {}

    public static void runAll()
    {
        topLevelCallbacksRetainEventStateAndCleanViewport();
        nestedButtonCallbacksRestoreOuterContext();
        nestedWheelDispatchRestoresAxesShiftsAndViewport();
        nestedFailureRestoresOuterContext();
        sameFrameViewportUnderflowIsNotMasked();
    }

    private static void topLevelCallbacksRetainEventStateAndCleanViewport()
    {
        TestMenu menu = menuWith(new HandlingElement());

        check(menu.mouseClicked(10, 11, GLFW.GLFW_MOUSE_BUTTON_LEFT), "top-level click was not handled");
        assertPointer(menu.context, 10, 11, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0D, 0D,
            "top-level click state");
        assertViewportClean(menu.context, "top-level click");

        check(menu.mouseScrolled(12, 13, -0.5D, 1.5D), "top-level scroll was not handled");
        assertPointer(menu.context, 12, 13, GLFW.GLFW_MOUSE_BUTTON_LEFT, -0.5D, 1.5D,
            "top-level scroll state");
        assertViewportClean(menu.context, "top-level scroll");

        check(menu.mouseReleased(14, 15, GLFW.GLFW_MOUSE_BUTTON_RIGHT), "top-level release was not handled");
        assertPointer(menu.context, 14, 15, GLFW.GLFW_MOUSE_BUTTON_RIGHT, -0.5D, 1.5D,
            "top-level release state");
        assertViewportClean(menu.context, "top-level release");

        check(menu.mouseCanceled(16, 17, GLFW.GLFW_MOUSE_BUTTON_MIDDLE), "top-level cancel did not complete");
        assertPointer(menu.context, 16, 17, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, -0.5D, 1.5D,
            "top-level cancel state");
        assertViewportClean(menu.context, "top-level cancel");
    }

    private static void nestedButtonCallbacksRestoreOuterContext()
    {
        for (ButtonEvent event : ButtonEvent.values())
        {
            TestMenu menu = new TestMenu();
            NestedButtonElement element = new NestedButtonElement(menu, event);

            menu.resize(320, 180);
            menu.main.add(element);

            boolean handled = event.dispatch(menu, 5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);

            check(handled, "nested " + event + " dispatch was not handled");
            check(element.visits.get() == 2 && element.outerContextRestored.get(),
                "nested " + event + " dispatch did not restore the outer event context");
            assertPointer(menu.context, 5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0D, 0D,
                "nested " + event + " top-level state");
            assertViewportClean(menu.context, "nested " + event);
        }
    }

    private static void nestedWheelDispatchRestoresAxesShiftsAndViewport()
    {
        TestMenu menu = new TestMenu();
        AtomicInteger visits = new AtomicInteger();
        AtomicBoolean outerContextRestored = new AtomicBoolean();
        UIElement wheelOwner = new UIElement()
        {
            @Override
            protected boolean subMouseScrolled(UIContext context)
            {
                int visit = visits.incrementAndGet();

                if (visit == 1)
                {
                    assertPointer(context, 5, 6, 0, -0.5D, 1.5D, "outer wheel event");

                    context.viewportStack.pushViewport(new Area(0, 0, 100, 100));
                    context.viewportStack.shiftX(7);
                    context.viewportStack.shiftY(9);
                    context.mouseX += 7;
                    context.mouseY += 9;

                    UIViewportStack outerStack = context.viewportStack;
                    Area outerViewport = context.getViewport();

                    check(menu.mouseScrolled(15, 16, 2.5D, -3.5D),
                        "nested wheel owner did not consume its event");
                    outerContextRestored.set(
                        context.viewportStack == outerStack
                            && context.getViewport() == outerViewport
                            && context.viewportStack.getShiftX() == 7
                            && context.viewportStack.getShiftY() == 9
                            && context.mouseX == 12
                            && context.mouseY == 15
                            && context.mouseWheelHorizontal == -0.5D
                            && context.mouseWheel == 1.5D
                    );

                    context.mouseX -= 7;
                    context.mouseY -= 9;
                    context.viewportStack.shiftX(-7);
                    context.viewportStack.shiftY(-9);
                    context.viewportStack.popViewport();
                }
                else
                {
                    check(visit == 2, "nested wheel dispatch recursed more than once");
                    assertPointer(context, 15, 16, 0, 2.5D, -3.5D, "nested wheel event");
                }

                return true;
            }
        };

        menu.resize(320, 180);
        menu.main.add(wheelOwner);

        check(menu.mouseScrolled(5, 6, -0.5D, 1.5D), "outer wheel event was not handled");
        check(visits.get() == 2 && outerContextRestored.get(),
            "nested wheel dispatch did not restore axes, shifts, and viewport ownership");
        assertPointer(menu.context, 5, 6, 0, -0.5D, 1.5D, "top-level wheel state");
        assertViewportClean(menu.context, "nested wheel dispatch");
    }

    private static void nestedFailureRestoresOuterContext()
    {
        TestMenu menu = new TestMenu();
        AtomicInteger visits = new AtomicInteger();
        AtomicBoolean restored = new AtomicBoolean();
        UIElement element = new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (visits.incrementAndGet() == 2)
                {
                    throw new TestFailure();
                }

                context.viewportStack.pushViewport(new Area(0, 0, 80, 80));
                context.viewportStack.shiftX(3);
                context.viewportStack.shiftY(4);
                context.mouseX += 3;
                context.mouseY += 4;

                UIViewportStack outerStack = context.viewportStack;
                Area outerViewport = context.getViewport();

                try
                {
                    menu.mouseClicked(50, 60, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                    throw new AssertionError("nested pointer failure did not propagate");
                }
                catch (TestFailure expected)
                {
                    restored.set(
                        context.viewportStack == outerStack
                            && context.getViewport() == outerViewport
                            && context.viewportStack.getShiftX() == 3
                            && context.viewportStack.getShiftY() == 4
                            && context.mouseX == 10
                            && context.mouseY == 12
                            && context.mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    );
                }

                context.mouseX -= 3;
                context.mouseY -= 4;
                context.viewportStack.shiftX(-3);
                context.viewportStack.shiftY(-4);
                context.viewportStack.popViewport();

                return true;
            }
        };

        menu.resize(320, 180);
        menu.main.add(element);

        check(menu.mouseClicked(7, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT), "outer click did not recover from nested failure");
        check(visits.get() == 2 && restored.get(), "nested exception did not restore outer pointer state");
        assertPointer(menu.context, 7, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0D, 0D,
            "top-level click after nested failure");
        assertViewportClean(menu.context, "nested pointer failure");
    }

    private static void sameFrameViewportUnderflowIsNotMasked()
    {
        TestMenu menu = menuWith(new UIElement()
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                context.popViewport();

                return true;
            }
        });

        expectThrows(NoSuchElementException.class,
            () -> menu.mouseClicked(20, 21, GLFW.GLFW_MOUSE_BUTTON_LEFT),
            "same-frame extra viewport pop was silently ignored");
        assertPointer(menu.context, 20, 21, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0D, 0D,
            "top-level state after viewport underflow");
        assertViewportClean(menu.context, "same-frame viewport underflow");
    }

    private static TestMenu menuWith(UIElement element)
    {
        TestMenu menu = new TestMenu();

        menu.resize(320, 180);
        menu.main.add(element);

        return menu;
    }

    private static void assertPointer(
        UIContext context,
        int mouseX,
        int mouseY,
        int mouseButton,
        double horizontal,
        double vertical,
        String label
    )
    {
        check(context.mouseX == mouseX && context.mouseY == mouseY,
            label + " coordinates changed");
        check(context.mouseButton == mouseButton, label + " button changed");
        check(context.mouseWheelHorizontal == horizontal && context.mouseWheel == vertical,
            label + " wheel axes changed");
    }

    private static void assertViewportClean(UIContext context, String label)
    {
        check(context.getViewport() == null, label + " left a viewport behind");
        check(context.viewportStack.getShiftX() == 0 && context.viewportStack.getShiftY() == 0,
            label + " left viewport shifts behind");
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, Runnable action, String message)
    {
        try
        {
            action.run();
        }
        catch (Throwable error)
        {
            if (type.isInstance(error))
            {
                return;
            }

            throw new AssertionError(message + ": wrong exception " + error.getClass().getName(), error);
        }

        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private enum ButtonEvent
    {
        CLICK
        {
            @Override
            boolean dispatch(TestMenu menu, int x, int y, int button)
            {
                return menu.mouseClicked(x, y, button);
            }
        },
        RELEASE
        {
            @Override
            boolean dispatch(TestMenu menu, int x, int y, int button)
            {
                return menu.mouseReleased(x, y, button);
            }
        },
        CANCEL
        {
            @Override
            boolean dispatch(TestMenu menu, int x, int y, int button)
            {
                return menu.mouseCanceled(x, y, button);
            }
        };

        abstract boolean dispatch(TestMenu menu, int x, int y, int button);
    }

    private static final class NestedButtonElement extends UIElement
    {
        private final TestMenu menu;
        private final ButtonEvent event;
        private final AtomicInteger visits = new AtomicInteger();
        private final AtomicBoolean outerContextRestored = new AtomicBoolean();

        private NestedButtonElement(TestMenu menu, ButtonEvent event)
        {
            this.menu = menu;
            this.event = event;
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            return this.event == ButtonEvent.CLICK && this.dispatch(context);
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            return this.event == ButtonEvent.RELEASE && this.dispatch(context);
        }

        @Override
        protected void subMouseCanceled(UIContext context)
        {
            if (this.event == ButtonEvent.CANCEL)
            {
                this.dispatch(context);
            }
        }

        private boolean dispatch(UIContext context)
        {
            int visit = this.visits.incrementAndGet();

            if (visit == 1)
            {
                UIViewportStack outerStack = context.viewportStack;
                Area outerViewport = context.getViewport();

                check(this.event.dispatch(this.menu, 15, 16, GLFW.GLFW_MOUSE_BUTTON_RIGHT),
                    "nested " + this.event + " event was not handled");
                check(context.viewportStack == outerStack,
                    "nested " + this.event + " event replaced the outer viewport stack");
                Area restoredViewport = context.getViewport();

                check(restoredViewport != null,
                    "nested " + this.event + " event cleared the outer viewport");
                check(restoredViewport.equals(outerViewport),
                    "nested " + this.event + " event changed the outer viewport from "
                        + outerViewport + " to " + restoredViewport);
                check(context.mouseX == 5 && context.mouseY == 6,
                    "nested " + this.event + " event changed outer coordinates to "
                        + context.mouseX + "," + context.mouseY);
                check(context.mouseButton == GLFW.GLFW_MOUSE_BUTTON_LEFT,
                    "nested " + this.event + " event changed outer button to " + context.mouseButton);
                this.outerContextRestored.set(true);
            }
            else
            {
                check(visit == 2, "nested " + this.event + " dispatch recursed more than once");
                check(context.mouseX == 15 && context.mouseY == 16
                        && context.mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT,
                    "nested " + this.event + " event inherited outer pointer state");
            }

            return true;
        }
    }

    private static final class HandlingElement extends UIElement
    {
        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            return true;
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            return true;
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            return true;
        }
    }

    private static final class TestMenu extends UIBaseMenu
    {}

    private static final class TestFailure extends RuntimeException
    {}
}
