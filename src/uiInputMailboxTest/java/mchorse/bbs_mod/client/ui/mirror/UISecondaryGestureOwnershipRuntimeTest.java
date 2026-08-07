package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.input.UIOrder;
import mchorse.bbs_mod.ui.framework.elements.input.UITransform;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.film.clips.modules.UIPointsModule;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIClickable;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.UIKeybind;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPicker;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.input.text.utils.Textbox;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSection;
import mchorse.bbs_mod.ui.particles.utils.UICurve;
import mchorse.bbs_mod.ui.particles.utils.UIGradientEditor;
import mchorse.bbs_mod.ui.utility.audio.UIAudioEditor;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.UIChalkboard;
import mchorse.bbs_mod.ui.utils.context.ContextAction;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Real control regressions for interleaved physical mouse-button releases. */
public final class UISecondaryGestureOwnershipRuntimeTest
{
    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int MIDDLE = 2;

    private UISecondaryGestureOwnershipRuntimeTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("UISecondaryGestureOwnershipRuntimeTest: all tests passed");
    }

    public static void runAll()
    {
        assertCurveKeepsLeftEditUntilLeftRelease();
        assertCurveKeepsMiddlePanUntilMiddleRelease();
        assertCurveRollsBackFailedStart();
        assertGradientCommitsOnlyForOwnerAndPreservesReentry();
        assertAudioKeepsScrubUntilLeftRelease();
        assertTextareaKeepsSelectionUntilLeftRelease();
        assertColorPickerRejectsRightDragAndKeepsLeftOwner();
        assertIndependentGestureCancellationClearsWithoutCommit();
        assertLegacyMouseStateCancellationClearsWithoutCommit();
        assertListCommitsOnlyOnLeftRelease();
        assertContextMenuRunsOnlyForOwnerRelease();
        assertContextMenuCancellationDoesNotRunDestructiveAction();
        assertClickableKeepsPressedUntilOwnerRelease();
        assertProgrammaticClickableIsAnInstantaneousGesture();
        assertProgrammaticClickableCleansUpAfterFailureAndRemoval();
        assertProgrammaticClickablePreservesPhysicalOwner();
        assertProgrammaticClickablePreservesVirtualDispatch();
    }

    private static void assertCurveKeepsLeftEditUntilLeftRelease()
    {
        UICurve curve = allocate(UICurve.class);
        MouseGestureOwnership ownership = arm(curve, "gestureOwnership", "gestureGeneration", LEFT);
        UIContext context = new UIContext(null);

        setBoolean(curve, "dragging", true);
        release(UICurve.class, curve, context, RIGHT);
        check(booleanField(curve, "dragging") && ownership.isOwnedBy(LEFT),
            "curve ended a left edit on right-button release");

        release(UICurve.class, curve, context, LEFT);
        check(!booleanField(curve, "dragging") && !ownership.isActive(),
            "curve did not end a left edit on matching release");
    }

    private static void assertCurveKeepsMiddlePanUntilMiddleRelease()
    {
        UICurve curve = allocate(UICurve.class);
        MouseGestureOwnership ownership = arm(curve, "gestureOwnership", "gestureGeneration", MIDDLE);
        UIContext context = new UIContext(null);

        setBoolean(curve, "panning", true);
        release(UICurve.class, curve, context, LEFT);
        check(booleanField(curve, "panning") && ownership.isOwnedBy(MIDDLE),
            "curve ended a middle pan on left-button release");

        release(UICurve.class, curve, context, MIDDLE);
        check(!booleanField(curve, "panning") && !ownership.isActive(),
            "curve did not end a middle pan on matching release");
    }

    private static void assertCurveRollsBackFailedStart()
    {
        UICurve curve = allocate(UICurve.class);
        MouseGestureOwnership ownership = new MouseGestureOwnership();

        setField(curve, "gestureOwnership", ownership);

        try
        {
            invokeCurveStart(curve, () ->
            {
                throw new IllegalStateException("expected start failure");
            });
            throw new AssertionError("curve failing starter did not propagate its exception");
        }
        catch (IllegalStateException exception)
        {
            check("expected start failure".equals(exception.getMessage()),
                "curve failing starter propagated the wrong exception");
        }

        check(!ownership.isActive() && longField(curve, "gestureGeneration") == 0L,
            "curve retained ownership after a failing gesture starter");
        check(invokeCurveStart(curve, () -> {}) && ownership.isOwnedBy(LEFT),
            "curve could not reacquire after rolling back a failing starter");
        ownership.cancel();
    }

    private static void assertGradientCommitsOnlyForOwnerAndPreservesReentry()
    {
        UIGradientEditor gradient = allocate(UIGradientEditor.class);
        MouseGestureOwnership ownership = arm(gradient, "dragOwnership", "dragGeneration", LEFT);
        TestParticleSection section = allocate(TestParticleSection.class);
        UIContext context = new UIContext(null);

        section.editor = gradient;
        setField(gradient, "section", section);
        setInt(gradient, "dragging", 1);

        release(UIGradientEditor.class, gradient, context, RIGHT);
        check(intField(gradient, "dragging") == 1 && section.dirtyCount == 0
                && ownership.isOwnedBy(LEFT),
            "gradient committed or cleared a left drag on right-button release");

        release(UIGradientEditor.class, gradient, context, LEFT);
        check(section.dirtyCount == 1 && section.replacementGeneration != 0L,
            "gradient did not commit after retiring its matching owner");
        check(ownership.isOwnedBy(LEFT, section.replacementGeneration)
                && intField(gradient, "dragging") == 0,
            "gradient old release cleared a reentrant replacement drag");
    }

    private static void assertAudioKeepsScrubUntilLeftRelease()
    {
        UIAudioEditor audio = allocate(UIAudioEditor.class);
        MouseGestureOwnership ownership = arm(audio, "gestureOwnership", "gestureGeneration", LEFT);
        UIContext context = new UIContext(null);

        setInt(audio, "dragging", -1);
        release(UIAudioEditor.class, audio, context, MIDDLE);
        check(intField(audio, "dragging") == -1 && ownership.isOwnedBy(LEFT),
            "audio editor ended a left scrub on middle-button release");

        release(UIAudioEditor.class, audio, context, LEFT);
        check(intField(audio, "dragging") == -2 && !ownership.isActive(),
            "audio editor did not end a left scrub on matching release");
    }

    private static void assertTextareaKeepsSelectionUntilLeftRelease()
    {
        UITextarea<?> textarea = allocate(UITextarea.class);
        MouseGestureOwnership ownership = arm(textarea, "dragOwnership", "dragGeneration", LEFT);
        UIContext context = new UIContext(null);

        textarea.horizontal = new Scroll(new Area());
        textarea.vertical = new Scroll(new Area());
        setInt(textarea, "dragging", 2);

        release(UITextarea.class, textarea, context, RIGHT);
        check(intField(textarea, "dragging") == 2 && ownership.isOwnedBy(LEFT),
            "textarea ended left selection on right-button release");

        release(UITextarea.class, textarea, context, LEFT);
        check(intField(textarea, "dragging") == 0 && !ownership.isActive(),
            "textarea did not end left selection on matching release");
    }

    private static void assertColorPickerRejectsRightDragAndKeepsLeftOwner()
    {
        UIColorPicker picker = allocate(UIColorPicker.class);
        MouseGestureOwnership ownership = arm(picker, "dragOwnership", "dragGeneration", LEFT);
        UIContext context = new UIContext(null);

        setInt(picker, "dragging", 1);
        context.setMouse(5, 5, RIGHT);
        check(!invokeBoolean(UIColorPicker.class, picker, "beginDragging", context),
            "color picker accepted a right-button slider drag");

        release(UIColorPicker.class, picker, context, RIGHT);
        check(intField(picker, "dragging") == 1 && ownership.isOwnedBy(LEFT),
            "color picker ended a left drag on right-button release");

        release(UIColorPicker.class, picker, context, LEFT);
        check(intField(picker, "dragging") == -1 && !ownership.isActive(),
            "color picker did not end a left drag on matching release");
    }

    private static void assertIndependentGestureCancellationClearsWithoutCommit()
    {
        UIContext context = new UIContext(null);

        UICurve curve = allocate(UICurve.class);
        MouseGestureOwnership curveOwnership = arm(curve, "gestureOwnership", "gestureGeneration", LEFT);

        setBoolean(curve, "dragging", true);
        setBoolean(curve, "moving", true);
        setBoolean(curve, "panning", true);
        setBoolean(curve, "chainDraggingCPOut", true);
        setBoolean(curve, "chainDraggingCPIn", true);
        cancel(UICurve.class, curve, context, LEFT);
        check(!curveOwnership.isActive() && longField(curve, "gestureGeneration") == 0L
                && !booleanField(curve, "dragging") && !booleanField(curve, "moving")
                && !booleanField(curve, "panning") && !booleanField(curve, "chainDraggingCPOut")
                && !booleanField(curve, "chainDraggingCPIn"),
            "curve cancellation retained gesture ownership or editing state");

        UIGradientEditor gradient = allocate(UIGradientEditor.class);
        MouseGestureOwnership gradientOwnership = arm(gradient, "dragOwnership", "dragGeneration", LEFT);
        TestParticleSection section = allocate(TestParticleSection.class);

        section.editor = gradient;
        setField(gradient, "section", section);
        setInt(gradient, "dragging", 1);
        cancel(UIGradientEditor.class, gradient, context, LEFT);
        check(!gradientOwnership.isActive() && longField(gradient, "dragGeneration") == 0L
                && intField(gradient, "dragging") == -1 && section.dirtyCount == 0,
            "gradient cancellation committed or retained its drag");

        UIAudioEditor audio = allocate(UIAudioEditor.class);
        MouseGestureOwnership audioOwnership = arm(audio, "gestureOwnership", "gestureGeneration", MIDDLE);

        setBoolean(audio, "navigating", true);
        setInt(audio, "dragging", -1);
        cancel(UIAudioEditor.class, audio, context, MIDDLE);
        check(!audioOwnership.isActive() && longField(audio, "gestureGeneration") == 0L
                && !booleanField(audio, "navigating") && intField(audio, "dragging") == -2,
            "audio cancellation retained navigation or drag state");

        UITextarea<?> textarea = allocate(UITextarea.class);
        MouseGestureOwnership textareaOwnership = arm(textarea, "dragOwnership", "dragGeneration", LEFT);

        textarea.horizontal = new Scroll(new Area());
        textarea.vertical = new Scroll(new Area());
        textarea.horizontal.beginDragging(LEFT);
        textarea.vertical.beginDragging(LEFT);
        setInt(textarea, "dragging", 2);
        cancel(UITextarea.class, textarea, context, LEFT);
        check(!textareaOwnership.isActive() && longField(textarea, "dragGeneration") == 0L
                && intField(textarea, "dragging") == 0
                && !textarea.horizontal.dragging && !textarea.vertical.dragging,
            "textarea cancellation retained selection or scrollbar drag state");

        UIColorPicker picker = allocate(UIColorPicker.class);
        MouseGestureOwnership pickerOwnership = arm(picker, "dragOwnership", "dragGeneration", LEFT);

        setInt(picker, "dragging", 1);
        cancel(UIColorPicker.class, picker, context, LEFT);
        check(!pickerOwnership.isActive() && longField(picker, "dragGeneration") == 0L
                && intField(picker, "dragging") == -1,
            "color picker cancellation retained its slider drag");

        int[] callbacks = {0};
        UIKeybind keybind = new UIKeybind((combo) -> callbacks[0] += 1).mouse();
        MouseGestureOwnership keybindOwnership = arm(keybind, "mouseOwnership", "mouseGeneration", LEFT);

        keybind.reading = true;
        setBoolean(keybind, "first", true);
        cancel(UIKeybind.class, keybind, context, LEFT);
        check(!keybindOwnership.isActive() && longField(keybind, "mouseGeneration") == 0L
                && !keybind.reading && !booleanField(keybind, "first") && callbacks[0] == 0,
            "keybind cancellation committed or retained its capture state");

        UIPointsModule points = allocate(UIPointsModule.class);
        Scroll pointsScroll = new Scroll(new Area());

        points.scroll = pointsScroll;
        pointsScroll.beginDragging(MIDDLE);
        cancel(UIPointsModule.class, points, context, MIDDLE);
        check(!pointsScroll.dragging,
            "points module cancellation retained its scrolling gesture");
    }

    private static void assertLegacyMouseStateCancellationClearsWithoutCommit()
    {
        UIContext context = new UIContext(null);

        UIChalkboard chalkboard = allocate(UIChalkboard.class);

        setBoolean(chalkboard, "drawing", true);
        cancel(UIChalkboard.class, chalkboard, context, RIGHT);
        check(booleanField(chalkboard, "drawing"),
            "chalkboard cleared a left drawing on foreign-button cancellation");
        cancel(UIChalkboard.class, chalkboard, context, LEFT);
        check(!booleanField(chalkboard, "drawing"),
            "chalkboard retained drawing after matching cancellation");

        UIOrder order = allocate(UIOrder.class);

        setInt(order, "dragging", 2);
        cancel(UIOrder.class, order, context, RIGHT);
        check(intField(order, "dragging") == 2,
            "order cleared a left reorder on foreign-button cancellation");
        cancel(UIOrder.class, order, context, LEFT);
        check(intField(order, "dragging") == -1,
            "order retained reorder state after matching cancellation");

        TestTransform transform = allocate(TestTransform.class);

        setBoolean(transform, "uniformDrag", true);
        cancel(UITransform.class, transform, context, LEFT);
        check(booleanField(transform, "uniformDrag"),
            "transform cleared uniform drag on foreign-button cancellation");
        cancel(UITransform.class, transform, context, RIGHT);
        check(!booleanField(transform, "uniformDrag"),
            "transform retained uniform drag after matching cancellation");

        Textbox textbox = new Textbox(null);

        setBoolean(textbox, "holding", true);
        textbox.mouseCanceled(RIGHT);
        check(booleanField(textbox, "holding"),
            "textbox cleared a left selection on foreign-button cancellation");
        textbox.mouseCanceled(LEFT);
        check(!booleanField(textbox, "holding"),
            "textbox retained selection holding after matching cancellation");

        UITextbox textboxElement = allocate(UITextbox.class);

        setField(textboxElement, "textbox", textbox);
        setBoolean(textbox, "holding", true);
        cancel(UITextbox.class, textboxElement, context, RIGHT);
        check(booleanField(textbox, "holding"),
            "UITextbox cleared a left selection on foreign-button cancellation");
        cancel(UITextbox.class, textboxElement, context, LEFT);
        check(!booleanField(textbox, "holding"),
            "UITextbox retained selection holding after matching cancellation");
    }

    private static void assertListCommitsOnlyOnLeftRelease()
    {
        TestList list = new TestList();
        MouseGestureOwnership ownership = arm(list, "dragOwnership", "dragGeneration", LEFT);
        UIContext context = new UIContext(null);

        list.area.set(0, 0, 100, 60);
        list.sorting = true;
        list.setList(new ArrayList<>(List.of("a", "b", "c")));
        setInt(list, "dragging", 0);
        setLong(list, "dragTime", 0L);

        context.setMouse(10, 45, RIGHT);
        release(UIList.class, list, context, RIGHT);
        check(list.getDraggingIndex() == 0 && list.swaps == 0 && ownership.isOwnedBy(LEFT),
            "list committed or cleared left sorting on right-button release");

        release(UIList.class, list, context, LEFT);
        check(list.getDraggingIndex() == -1 && list.swaps == 1 && list.retiredBeforeSwap,
            "list did not retire its owner before the matching sort commit");
        check(list.getList().equals(List.of("b", "c", "a")),
            "list matching release swapped using cleared mutable drag state");
    }

    private static void assertContextMenuRunsOnlyForOwnerRelease()
    {
        UISimpleContextMenu menu = new UISimpleContextMenu();
        MouseGestureOwnership ownership = arm(menu, "actionOwnership", "actionGeneration", LEFT);
        UIContext context = new UIContext(null);
        boolean[] ran = {false};
        boolean[] retiredBeforeRun = {false};
        ContextAction action = new ContextAction(null, null, () ->
        {
            ran[0] = true;
            retiredBeforeRun[0] = !ownership.isActive()
                && getField(menu, "action") == null
                && longField(menu, "actionGeneration") == 0L;
        });

        setField(menu, "action", action);
        release(UISimpleContextMenu.class, menu, context, RIGHT);
        check(!ran[0] && getField(menu, "action") == action && ownership.isOwnedBy(LEFT),
            "context menu ran or cleared a pending action on foreign release");

        release(UISimpleContextMenu.class, menu, context, LEFT);
        check(ran[0] && retiredBeforeRun[0],
            "context menu did not retire its pending owner before running the action");
    }

    private static void assertContextMenuCancellationDoesNotRunDestructiveAction()
    {
        int[] deletes = {0};
        UISimpleContextMenu canceled = new UISimpleContextMenu();
        MouseGestureOwnership ownership = arm(canceled, "actionOwnership", "actionGeneration", LEFT);
        UIContext context = new UIContext(null);
        ContextAction action = new ContextAction(null, null, () -> deletes[0] += 1);

        setField(canceled, "action", action);
        context.setMouse(5, 5, LEFT);
        canceled.mouseCanceled(context);

        check(deletes[0] == 0 && !ownership.isActive() && getField(canceled, "action") == null,
            "canceling a context menu executed or retained its destructive pending action");
        release(UISimpleContextMenu.class, canceled, context, LEFT);
        check(deletes[0] == 0,
            "release after context cancellation executed the retired destructive action");
    }

    private static void assertClickableKeepsPressedUntilOwnerRelease()
    {
        TestClickable clickable = allocate(TestClickable.class);
        MouseGestureOwnership ownership = arm(clickable, "pressOwnership", "pressGeneration", LEFT);
        UIContext context = new UIContext(null);

        setBoolean(clickable, "pressed", true);
        release(UIClickable.class, clickable, context, RIGHT);
        check(booleanField(clickable, "pressed") && ownership.isOwnedBy(LEFT),
            "clickable cleared its left press on right-button release");

        release(UIClickable.class, clickable, context, LEFT);
        check(!booleanField(clickable, "pressed") && !ownership.isActive(),
            "clickable did not clear its press on matching release");
    }

    private static void assertProgrammaticClickableIsAnInstantaneousGesture()
    {
        int[] clicks = {0};
        TestClickable clickable = new TestClickable();
        UIContext context = new UIContext(null);

        clickable.area.set(0, 0, 20, 20);
        clickable.callback = (ignored) -> clicks[0] += 1;
        clickable.clickItself(context);
        clickable.clickItself(context);

        MouseGestureOwnership ownership = (MouseGestureOwnership) getField(clickable, "pressOwnership");

        check(clicks[0] == 2, "consecutive programmatic clicks did not invoke the callback exactly once each");
        check(!ownership.isActive() && !clickable.pressed() && longField(clickable, "pressGeneration") == 0L,
            "programmatic click retained pressed state or ownership");
    }

    private static void assertProgrammaticClickableCleansUpAfterFailureAndRemoval()
    {
        TestClickable failing = new TestClickable();
        UIContext context = new UIContext(null);

        failing.area.set(0, 0, 20, 20);
        context.setMouse(41, 42, RIGHT);
        failing.callback = (ignored) ->
        {
            throw new IllegalStateException("expected click failure");
        };

        try
        {
            failing.clickItself(context);
            throw new AssertionError("programmatic click did not propagate its callback failure");
        }
        catch (IllegalStateException exception)
        {
            check("expected click failure".equals(exception.getMessage()),
                "programmatic click propagated the wrong callback failure");
        }

        MouseGestureOwnership failingOwnership = (MouseGestureOwnership) getField(failing, "pressOwnership");

        check(!failingOwnership.isActive() && !failing.pressed(),
            "failing programmatic click retained pressed state or ownership");
        check(context.mouseX == 41 && context.mouseY == 42 && context.mouseButton == RIGHT,
            "failing programmatic click did not restore the caller's mouse context");

        UIElement parent = new UIElement();
        TestClickable removing = new TestClickable();
        int[] clicks = {0};

        removing.area.set(0, 0, 20, 20);
        parent.add(removing);
        removing.callback = (ignored) ->
        {
            clicks[0] += 1;
            removing.removeFromParent();
        };
        removing.clickItself(context);

        MouseGestureOwnership removingOwnership = (MouseGestureOwnership) getField(removing, "pressOwnership");

        check(clicks[0] == 1 && !removing.hasParent(),
            "programmatic removal callback did not run exactly once");
        check(!removingOwnership.isActive() && !removing.pressed(),
            "removed clickable retained programmatic pressed state or ownership");
    }

    private static void assertProgrammaticClickablePreservesPhysicalOwner()
    {
        TestClickable clickable = new TestClickable();
        MouseGestureOwnership ownership = arm(clickable, "pressOwnership", "pressGeneration", LEFT);
        long generation = ownership.generation();
        UIContext context = new UIContext(null);
        int[] clicks = {0};

        clickable.area.set(0, 0, 20, 20);
        setBoolean(clickable, "pressed", true);
        clickable.callback = (ignored) -> clicks[0] += 1;
        clickable.clickItself(context);

        check(clicks[0] == 0 && ownership.isOwnedBy(LEFT, generation) && clickable.pressed(),
            "programmatic click invoked a callback or released an existing physical owner");

        release(UIClickable.class, clickable, context, LEFT);
    }

    private static void assertProgrammaticClickablePreservesVirtualDispatch()
    {
        DispatchingClickable clickable = new DispatchingClickable();
        UIContext context = new UIContext(null);

        clickable.clickItself(context);

        check(clickable.dispatched == 1,
            "programmatic click bypassed a clickable subclass's mouse dispatch override");
    }

    private static MouseGestureOwnership arm(Object target, String ownershipName, String generationName, int button)
    {
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        long generation = ownership.acquireToken(button);

        check(generation != 0L, "test could not acquire gesture generation");
        setField(target, ownershipName, ownership);
        setLong(target, generationName, generation);

        return ownership;
    }

    private static void release(Class<?> owner, Object target, UIContext context, int button)
    {
        context.setMouse(context.mouseX, context.mouseY, button);

        try
        {
            Method method = owner.getDeclaredMethod("subMouseReleased", UIContext.class);

            method.setAccessible(true);
            method.invoke(target, context);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke " + owner.getSimpleName() + " release", exception);
        }
    }

    private static void cancel(Class<?> owner, Object target, UIContext context, int button)
    {
        context.setMouse(context.mouseX, context.mouseY, button);

        try
        {
            Method method = owner.getDeclaredMethod("subMouseCanceled", UIContext.class);

            method.setAccessible(true);
            method.invoke(target, context);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke " + owner.getSimpleName() + " cancellation", exception);
        }
    }

    private static boolean invokeBoolean(Class<?> owner, Object target, String name, UIContext context)
    {
        try
        {
            Method method = owner.getDeclaredMethod(name, UIContext.class);

            method.setAccessible(true);

            return (boolean) method.invoke(target, context);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke " + owner.getSimpleName() + "." + name, exception);
        }
    }

    private static boolean invokeCurveStart(UICurve curve, Runnable starter)
    {
        try
        {
            Method method = UICurve.class.getDeclaredMethod("beginGesture", int.class, Runnable.class);

            method.setAccessible(true);

            return (boolean) method.invoke(curve, LEFT, starter);
        }
        catch (InvocationTargetException exception)
        {
            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }

            if (cause instanceof Error error)
            {
                throw error;
            }

            throw new AssertionError("Curve starter failed with a checked exception", cause);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke UICurve gesture starter", exception);
        }
    }

    private static <T> T allocate(Class<T> type)
    {
        try
        {
            return type.cast(UnsafeHolder.INSTANCE.allocateInstance(type));
        }
        catch (InstantiationException exception)
        {
            throw new AssertionError("Could not allocate " + type.getName(), exception);
        }
    }

    private static void setField(Object target, String name, Object value)
    {
        try
        {
            declaredField(target.getClass(), name).set(target, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not set field " + name, exception);
        }
    }

    private static void setBoolean(Object target, String name, boolean value)
    {
        try
        {
            declaredField(target.getClass(), name).setBoolean(target, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not set boolean field " + name, exception);
        }
    }

    private static boolean booleanField(Object target, String name)
    {
        try
        {
            return declaredField(target.getClass(), name).getBoolean(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect boolean field " + name, exception);
        }
    }

    private static void setInt(Object target, String name, int value)
    {
        try
        {
            declaredField(target.getClass(), name).setInt(target, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not set int field " + name, exception);
        }
    }

    private static int intField(Object target, String name)
    {
        try
        {
            return declaredField(target.getClass(), name).getInt(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect int field " + name, exception);
        }
    }

    private static void setLong(Object target, String name, long value)
    {
        try
        {
            declaredField(target.getClass(), name).setLong(target, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not set long field " + name, exception);
        }
    }

    private static long longField(Object target, String name)
    {
        try
        {
            return declaredField(target.getClass(), name).getLong(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect long field " + name, exception);
        }
    }

    private static Field declaredField(Class<?> type, String name) throws ReflectiveOperationException
    {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
        {
            try
            {
                Field field = current.getDeclaredField(name);

                field.setAccessible(true);

                return field;
            }
            catch (NoSuchFieldException ignored)
            {}
        }

        throw new NoSuchFieldException(name);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class UnsafeHolder
    {
        private static final Unsafe INSTANCE;

        static
        {
            try
            {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");

                field.setAccessible(true);
                INSTANCE = (Unsafe) field.get(null);
            }
            catch (ReflectiveOperationException exception)
            {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    private static final class TestTransform extends UITransform
    {
        @Override
        public void setT(mchorse.bbs_mod.utils.Axis axis, double x, double y, double z)
        {}

        @Override
        public void setS(mchorse.bbs_mod.utils.Axis axis, double x, double y, double z)
        {}

        @Override
        public void setR(mchorse.bbs_mod.utils.Axis axis, double x, double y, double z)
        {}
    }

    private static final class TestParticleSection extends UIParticleSchemeSection
    {
        private UIGradientEditor editor;
        private int dirtyCount;
        private long replacementGeneration;

        private TestParticleSection()
        {
            super(null);
        }

        @Override
        public void dirty()
        {
            this.dirtyCount += 1;

            MouseGestureOwnership ownership = (MouseGestureOwnership) getField(this.editor, "dragOwnership");

            this.replacementGeneration = ownership.acquireToken(LEFT);

            if (this.replacementGeneration != 0L)
            {
                setLong(this.editor, "dragGeneration", this.replacementGeneration);
                setInt(this.editor, "dragging", 0);
            }
        }

        @Override
        public IKey getTitle()
        {
            return null;
        }
    }

    private static final class TestList extends UIList<String>
    {
        private int swaps;
        private boolean retiredBeforeSwap;

        private TestList()
        {
            super(null);
        }

        @Override
        protected void handleSwap(int from, int to)
        {
            this.swaps += 1;
            this.retiredBeforeSwap = this.getDraggingIndex() == -1;
            super.handleSwap(from, to);
        }
    }

    private static final class TestClickable extends UIClickable<Object>
    {
        private TestClickable()
        {
            super(null);
        }

        @Override
        protected Object get()
        {
            return this;
        }

        @Override
        protected void playClickSound()
        {}

        @Override
        protected void renderSkin(UIContext context)
        {}

        private boolean pressed()
        {
            return this.pressed;
        }
    }

    private static final class DispatchingClickable extends UIClickable<Object>
    {
        private int dispatched;

        private DispatchingClickable()
        {
            super(null);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            this.dispatched += 1;

            return true;
        }

        @Override
        protected Object get()
        {
            return this;
        }

        @Override
        protected void playClickSound()
        {}

        @Override
        protected void renderSkin(UIContext context)
        {}
    }

    private static Object getField(Object target, String name)
    {
        try
        {
            return declaredField(target.getClass(), name).get(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect field " + name, exception);
        }
    }
}
