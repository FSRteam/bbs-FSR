package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IFocusedUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.events.UIRemovedEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Regression checks for exception-safe hierarchy removal ownership. */
final class UIElementRemovalOwnershipTest
{
    private UIElementRemovalOwnershipTest()
    {}

    static void runAll()
    {
        removeAllContinuesAfterSharedFailure();
        sameParentReentryKeepsNewAttachment();
        unfocusFailureStillDetachesAndClearsFocus();
    }

    private static void removeAllContinuesAfterSharedFailure()
    {
        UIElement parent = new UIElement();
        UIElement first = new UIElement();
        UIElement second = new UIElement();
        UIElement last = new UIElement();
        IllegalStateException sharedFailure = new IllegalStateException("shared removal failure");
        AtomicBoolean secondVisited = new AtomicBoolean();
        AtomicBoolean lastVisited = new AtomicBoolean();

        first.getEvents().register(UIRemovedEvent.class, (event) ->
        {
            throw sharedFailure;
        });
        second.getEvents().register(UIRemovedEvent.class, (event) ->
        {
            secondVisited.set(true);
            throw sharedFailure;
        });
        last.getEvents().register(UIRemovedEvent.class, (event) -> lastVisited.set(true));
        parent.add(first, second, last);

        try
        {
            parent.removeAll();
            throw new AssertionError("removeAll did not propagate its first removal failure");
        }
        catch (IllegalStateException exception)
        {
            check(exception == sharedFailure,
                "removeAll replaced the first failure while aggregating the same exception identity");
        }

        check(secondVisited.get() && lastVisited.get(),
            "one removal failure prevented later children from receiving removal callbacks");
        check(parent.getChildren().isEmpty(),
            "removeAll retained children after a removal callback failure");
        check(first.getParent() == null && second.getParent() == null && last.getParent() == null,
            "removeAll left a detached child pointing at its old parent");
    }

    private static void sameParentReentryKeepsNewAttachment()
    {
        UIElement parent = new UIElement();
        UIElement child = new UIElement();
        AtomicBoolean reentered = new AtomicBoolean();

        child.getEvents().register(UIRemovedEvent.class, (event) ->
        {
            if (reentered.compareAndSet(false, true))
            {
                parent.add(child);
            }
        });
        parent.add(child);
        parent.remove(child);

        check(reentered.get(), "removal callback did not exercise same-parent reentry");
        check(child.getParent() == parent,
            "old removal cleanup cleared the callback's new same-parent attachment");
        check(parent.getChildren().size() == 1 && parent.getChildren().get(0) == child,
            "same-parent reentry left duplicate or orphaned hierarchy state");
    }

    private static void unfocusFailureStillDetachesAndClearsFocus()
    {
        TestMenu menu = new TestMenu();
        ThrowingFocusedElement focused = new ThrowingFocusedElement();

        menu.main.add(focused);
        menu.context.focus(focused);

        try
        {
            menu.main.remove(focused);
            throw new AssertionError("throwing unfocus did not propagate from hierarchy removal");
        }
        catch (IllegalStateException exception)
        {
            check("unfocus failure".equals(exception.getMessage()),
                "hierarchy removal propagated the wrong unfocus failure");
        }

        check(menu.context.activeElement == null,
            "throwing unfocus retained a detached active-element owner");
        check(focused.getParent() == null && !menu.main.getChildren().contains(focused),
            "throwing unfocus left inconsistent child/parent ownership");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestMenu extends UIBaseMenu
    {}

    private static final class ThrowingFocusedElement extends UIElement implements IFocusedUIElement
    {
        @Override
        public boolean isFocused()
        {
            return false;
        }

        @Override
        public void focus(UIContext context)
        {}

        @Override
        public void unfocus(UIContext context)
        {
            throw new IllegalStateException("unfocus failure");
        }

        @Override
        public void selectAll(UIContext context)
        {}

        @Override
        public void unselect(UIContext context)
        {}
    }
}
