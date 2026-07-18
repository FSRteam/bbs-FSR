package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-light checks for the second group of continuous UI gestures. */
public final class UISecondaryGestureOwnershipTest
{
    private static final String CURVE = "src/client/java/mchorse/bbs_mod/ui/particles/utils/UICurve.java";
    private static final String GRADIENT = "src/client/java/mchorse/bbs_mod/ui/particles/utils/UIGradientEditor.java";
    private static final String AUDIO = "src/client/java/mchorse/bbs_mod/ui/utility/audio/UIAudioEditor.java";
    private static final String TEXTAREA = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/text/UITextarea.java";
    private static final String COLOR_PICKER = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/color/UIColorPicker.java";
    private static final String LIST = "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/list/UIList.java";
    private static final String CONTEXT_MENU = "src/client/java/mchorse/bbs_mod/ui/framework/elements/context/UISimpleContextMenu.java";
    private static final String CLICKABLE = "src/client/java/mchorse/bbs_mod/ui/framework/elements/buttons/UIClickable.java";

    private UISecondaryGestureOwnershipTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertGenerationSemantics();
        assertCurveOwnership();
        assertGradientOwnership();
        assertAudioOwnership();
        assertTextareaOwnership();
        assertColorPickerOwnership();
        assertListOwnership();
        assertContextMenuOwnership();
        assertClickableOwnership();
    }

    private static void assertGenerationSemantics()
    {
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        long first = ownership.acquireToken(0);

        check(first != 0L, "left-button gesture did not acquire a generation");
        check(!ownership.release(2, first) && ownership.isOwnedBy(0, first),
            "middle-button release retired the left-button gesture");
        check(ownership.release(0, first) && !ownership.isActive(),
            "initiating left-button release did not retire its gesture");

        long replacement = ownership.acquireToken(0);

        check(replacement != 0L && replacement != first,
            "replacement gesture reused the retired generation");
        check(!ownership.release(0, first) && ownership.isOwnedBy(0, replacement),
            "stale release retired a replacement generation");
    }

    private static void assertCurveOwnership()
    {
        String source = readSource(CURVE);
        String clicked = method(source, "public boolean subMouseClicked(UIContext context)");
        String bezier = method(source, "private boolean bezierMouseClicked(UIContext context, boolean ctrl)");
        String chain = method(source, "private boolean bezierChainMouseClicked(UIContext context, boolean ctrl)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "curve");
        check(occurrences(clicked, "this.beginGesture(context.mouseButton,") == 2
                && occurrences(bezier, "this.beginGesture(context.mouseButton,") == 1
                && occurrences(chain, "this.beginGesture(context.mouseButton,") == 3,
            "curve does not acquire both left-edit and middle-pan gestures");
        check(source.contains("boolean started = false;")
                && source.contains("if (!started && this.gestureOwnership.release(button, generation))"),
            "curve does not roll back a failed gesture starter");
        assertReleaseOrder(released, "gestureOwnership", "gestureGeneration", "this.clearGestureState();", "curve");
        check(source.contains("this.panning = false;")
                && source.contains("this.chainDraggingCPOut = false;")
                && source.contains("this.chainDraggingCPIn = false;"),
            "curve owner release does not clear every gesture mode");
    }

    private static void assertGradientOwnership()
    {
        String source = readSource(GRADIENT);
        String clicked = method(source, "public boolean subMouseClicked(UIContext context)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "gradient editor");
        check(clicked.contains("outside.isInside(context) && context.mouseButton == 0")
                && clicked.contains("dragOwnership.acquireToken(context.mouseButton)"),
            "gradient handle drag is not restricted to and owned by left click");
        check(clicked.contains("if (!started && this.dragOwnership.release(context.mouseButton, generation))"),
            "gradient editor does not roll back a failed drag starter");
        assertReleaseOrder(released, "dragOwnership", "dragGeneration", "this.dragging = -1;", "gradient editor");
        check(indexOf(released, "this.section.dirty();") > indexOf(released, "this.dragging = -1;"),
            "gradient editor commits before retiring and clearing the old drag");
    }

    private static void assertAudioOwnership()
    {
        String source = readSource(AUDIO);
        String clicked = method(source, "protected boolean subMouseClicked(UIContext context)");
        String released = method(source, "protected boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "audio editor");
        check(clicked.contains("context.mouseButton == 0 || context.mouseButton == 2")
                && clicked.contains("this.beginGesture(context.mouseButton, () -> this.startGesture(context))"),
            "audio edit/scrub and middle navigation do not share one physical-button owner");
        check(source.contains("if (!started && this.gestureOwnership.release(button, generation))"),
            "audio editor does not roll back a failed gesture starter");
        assertReleaseOrder(released, "gestureOwnership", "gestureGeneration", "this.dragged = null;", "audio editor");
        check(indexOf(released, "this.navigating = false;") > indexOf(released, "gestureOwnership.release"),
            "audio editor ends navigation on an unrelated release");
    }

    private static void assertTextareaOwnership()
    {
        String source = readSource(TEXTAREA);
        String clicked = method(source, "public boolean subMouseClicked(UIContext context)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "textarea");
        check(clicked.contains("this.dragOwnership.isActive()")
                && occurrences(clicked, "this.beginGesture(context.mouseButton") == 2
                && source.contains("this.dragOwnership.acquireToken(button)"),
            "textarea allows a foreign press to replace text selection or middle navigation");
        check(source.contains("if (!started && this.dragOwnership.release(button, generation))"),
            "textarea does not roll back a failed selection/navigation starter");
        assertReleaseOrder(released, "dragOwnership", "dragGeneration", "this.dragging = 0;", "textarea");
    }

    private static void assertColorPickerOwnership()
    {
        String source = readSource(COLOR_PICKER);
        String begin = method(source, "private boolean beginDragging(UIContext context)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "color picker");
        check(indexOf(begin, "if (context.mouseButton != 0)") < indexOf(begin, "dragOwnership.acquireToken"),
            "color picker can start slider dragging from a non-left press");
        check(begin.contains("if (!started)")
                && begin.contains("this.dragOwnership.release(context.mouseButton, generation);"),
            "color picker retains ownership when a left press misses every slider");
        assertReleaseOrder(released, "dragOwnership", "dragGeneration", "this.dragging = -1;", "color picker");
    }

    private static void assertListOwnership()
    {
        String source = readSource(LIST);
        String clicked = method(source, "public boolean subMouseClicked(UIContext context)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");
        String swap = method(source, "protected void handleSwap(int from, int to)");

        assertCommonOwnership(source, "list sorting");
        check(clicked.contains("this.dragOwnership.isActive()")
                && clicked.contains("this.dragOwnership.acquireToken(context.mouseButton)"),
            "list sorting allows a second press to replace the active drag");
        assertReleaseOrder(released, "dragOwnership", "dragGeneration", "this.dragging = -1;", "list sorting");
        check(indexOf(released, "this.handleSwap(draggedIndex, index);")
                > indexOf(released, "this.dragging = -1;"),
            "list sorting swaps before retiring and clearing its old drag");
        check(swap.contains("this.list.remove(from)") && !swap.contains("this.list.remove(this.dragging)"),
            "list swap still reads cleared mutable drag state instead of its snapshot");
        check(source.contains("this.rollbackDragStart(context.mouseButton, startedGeneration)"),
            "list sorting does not roll back ownership when its selection callback fails");
    }

    private static void assertContextMenuOwnership()
    {
        String source = readSource(CONTEXT_MENU);
        String released = method(source, "public boolean subMouseReleased(UIContext context)");

        assertCommonOwnership(source, "simple context menu");
        check(source.contains("actionOwnership.acquireToken(context.mouseButton)"),
            "context menu pending action does not retain its press owner");
        assertReleaseOrder(released, "actionOwnership", "actionGeneration", "this.action = null;", "simple context menu");
        check(indexOf(released, "action.runnable.run();") > indexOf(released, "this.action = null;"),
            "context menu runs its pending action before retiring the old intent");
    }

    private static void assertClickableOwnership()
    {
        String source = readSource(CLICKABLE);
        String clicked = method(source, "public boolean subMouseClicked(UIContext context)");
        String released = method(source, "public boolean subMouseReleased(UIContext context)");
        String started = method(source, "private long startPress(int mouseButton)");
        String finished = method(source, "private boolean finishPress(int mouseButton, long generation)");
        String programmatic = method(source, "public void clickItself(UIContext context, int mouseButton)");

        check(source.contains("MouseGestureOwnership")
                && source.contains("acquireToken(mouseButton)")
                && source.contains(".release(mouseButton, generation)"),
            "clickable does not retain and release one initiating owner generation");
        check(clicked.contains("this.startPress(context.mouseButton);"),
            "clickable physical press bypasses the owner-aware start helper");
        check(indexOf(started, "pressOwnership.acquireToken(mouseButton)")
                < indexOf(started, "this.pressed = true;"),
            "clickable marks itself pressed before acquiring its physical owner");
        check(started.contains("if (!clicked)")
                && started.contains("this.finishPress(mouseButton, generation);"),
            "clickable does not roll back ownership when its press callback fails");
        check(released.contains("this.finishPress(context.mouseButton, generation);"),
            "clickable physical release bypasses the owner-aware finish helper");
        check(indexOf(finished, "pressOwnership.release(mouseButton, generation)")
                < indexOf(finished, "this.pressGeneration = 0L;")
                && indexOf(finished, "this.pressGeneration = 0L;")
                < indexOf(finished, "this.pressed = false;"),
            "clickable clears state before retiring the initiating owner generation");
        check(programmatic.contains("super.clickItself(context, mouseButton);")
                && programmatic.contains("this.finishPress(mouseButton, current.generation);"),
            "programmatic clickable is not a complete instantaneous owner-scoped gesture");
    }

    private static void assertCommonOwnership(String source, String name)
    {
        check(source.contains("MouseGestureOwnership"), name + " has no gesture owner");
        check(source.contains("acquireToken("), name + " does not retain an initiating generation");
        check(source.contains(".release(context.mouseButton, generation)"),
            name + " does not require the initiating button and generation on release");
    }

    private static void assertReleaseOrder(
        String released,
        String ownership,
        String generation,
        String stateClear,
        String name
    )
    {
        int release = indexOf(released, ownership + ".release(context.mouseButton, generation)");
        int generationClear = indexOf(released, "this." + generation + " = 0L;");
        int state = indexOf(released, stateClear);

        check(release < generationClear && generationClear < state,
            name + " clears gesture state before retiring the matching generation");
    }

    private static String method(String source, String signature)
    {
        int start = indexOf(source, signature);
        int end = source.indexOf("\n    @Override", start + signature.length());

        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    private static String readSource(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (IOException exception)
        {
            throw new AssertionError("Could not read regression source " + path, exception);
        }
    }

    private static int occurrences(String source, String value)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(value, index)) >= 0)
        {
            count++;
            index += value.length();
        }

        return count;
    }

    private static int indexOf(String source, String value)
    {
        int index = source.indexOf(value);

        check(index >= 0, "Missing source contract: " + value);

        return index;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
