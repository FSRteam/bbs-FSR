package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.framework.elements.utils.UIModelRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Real wrapper regressions for interleaved physical mouse buttons. */
public final class UIWrapperGestureOwnershipTest
{
    private static final long SESSION = 0x57524150504552L;
    private static final BBSUiRemoteInputState IDLE_STATE = new BBSUiRemoteInputState(
        0D,
        0D,
        0,
        Set.of(),
        0
    );

    private UIWrapperGestureOwnershipTest()
    {}

    public static void main(String[] args)
    {
        runAll();
        System.out.println("UIWrapperGestureOwnershipTest: all tests passed");
    }

    public static void runAll()
    {
        long leaseId = BBSUiRemoteHeldState.install(SESSION, IDLE_STATE);

        try
        {
            assertPickableRendererDoesNotStopLeftGizmoOnRightRelease();
            assertKeyframesKeepLeftDragUntilLeftRelease();
            assertKeyframesRollbackAfterCallbackFailures();
            assertModelRendererKeepsLeftDragUntilLeftRelease();
            assertClipsKeepLeftScrubUntilLeftRelease();
            assertReplayListCommitsOnlyOnLeftRelease();
        }
        finally
        {
            BBSUiRemoteHeldState.clear(leaseId);
        }
    }

    private static void assertPickableRendererDoesNotStopLeftGizmoOnRightRelease()
    {
        try
        {
            UIPickableFormRenderer renderer = allocate(UIPickableFormRenderer.class);
            GizmoInteraction interaction = new GizmoInteraction(null);
            MouseGestureOwnership ownership = (MouseGestureOwnership) field(interaction, "gestureOwnership");
            Field active = declaredField(GizmoInteraction.class, "gizmoActive");
            UIContext context = new UIContext(null);

            setField(renderer, "gizmoInteraction", interaction);
            setField(renderer, "dragOwnership", new MouseGestureOwnership());
            long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

            check(generation != 0L,
                "pickable renderer test could not arm its left Gizmo owner");
            setField(interaction, "gestureGeneration", generation);
            active.setBoolean(interaction, true);

            context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            invokeMouseRelease(UIPickableFormRenderer.class, renderer, context);
            check(ownership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT) && active.getBoolean(interaction),
                "UIPickableFormRenderer stopped a left-owned Gizmo on right release");

            context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            invokeMouseRelease(UIPickableFormRenderer.class, renderer, context);
            check(!ownership.isActive() && !active.getBoolean(interaction),
                "UIPickableFormRenderer did not stop the Gizmo on its matching left release");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect UIPickableFormRenderer ownership", exception);
        }
    }

    private static void assertKeyframesKeepLeftDragUntilLeftRelease()
    {
        UIKeyframes keyframes = allocate(UIKeyframes.class);
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        UIContext context = new UIContext(null);

        long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        setField(keyframes, "editingOwnership", ownership);
        setField(keyframes, "editingGeneration", generation);
        setField(keyframes, "currentGraph", emptyKeyframeGraph());
        setField(keyframes, "navigating", true);
        setField(keyframes, "dragging", -1);
        check(booleanField(keyframes, "navigating"),
            "UIKeyframes test did not arm its middle-owned navigation");

        context.setMouse(20, 20, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        invokeMouseRelease(UIKeyframes.class, keyframes, context);
        check(booleanField(keyframes, "navigating"),
            "UIKeyframes cleared middle-owned navigation on right release");

        context.setMouse(20, 20, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
        invokeMouseRelease(UIKeyframes.class, keyframes, context);
        check(!booleanField(keyframes, "navigating"),
            "UIKeyframes did not clear navigation on matching middle release");
    }

    private static void assertKeyframesRollbackAfterCallbackFailures()
    {
        UIContext context = new UIContext(null);
        UIKeyframes clickFailure = allocate(UIKeyframes.class);
        MouseGestureOwnership clickOwnership = new MouseGestureOwnership();
        Area graphArea = new Area();

        graphArea.set(0, 0, 100, 40);
        setField(clickFailure, "editingOwnership", clickOwnership);
        setField(clickFailure, "editingGeneration", 0L);
        setField(clickFailure, "currentGraph", throwingKeyframeGraph("findKeyframe"));
        setField(clickFailure, "graphArea", graphArea);
        setField(clickFailure, "dragging", -1);
        context.setMouse(20, 20, GLFW.GLFW_MOUSE_BUTTON_LEFT);

        Throwable clickError = invokeMouseFailure(UIKeyframes.class, "subMouseClicked", clickFailure, context);

        check(clickError instanceof IllegalStateException
                && "findKeyframe failure".equals(clickError.getMessage()),
            "UIKeyframes click propagated the wrong callback failure");
        check(!clickOwnership.isActive() && longField(clickFailure, "editingGeneration") == 0L,
            "UIKeyframes retained its owner after a throwing click callback");
        check(clickOwnership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT) != 0L,
            "UIKeyframes could not reacquire after a throwing click callback");
        clickOwnership.cancel();

        UIKeyframes releaseFailure = allocate(UIKeyframes.class);
        MouseGestureOwnership releaseOwnership = new MouseGestureOwnership();
        long releaseGeneration = releaseOwnership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        setField(releaseFailure, "editingOwnership", releaseOwnership);
        setField(releaseFailure, "editingGeneration", releaseGeneration);
        setField(releaseFailure, "currentGraph", throwingKeyframeGraph("mouseReleased"));
        setField(releaseFailure, "navigating", true);
        setField(releaseFailure, "dragging", -1);
        context.setMouse(20, 20, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);

        Throwable releaseError = invokeMouseFailure(UIKeyframes.class, "subMouseReleased", releaseFailure, context);

        check(releaseError instanceof IllegalStateException
                && "mouseReleased failure".equals(releaseError.getMessage()),
            "UIKeyframes release propagated the wrong graph failure");
        check(!releaseOwnership.isActive()
                && !booleanField(releaseFailure, "navigating")
                && longField(releaseFailure, "editingGeneration") == 0L,
            "UIKeyframes retained gesture state after a throwing release callback");
        check(releaseOwnership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) != 0L,
            "UIKeyframes could not reacquire after a throwing release callback");
        releaseOwnership.cancel();
    }

    private static void assertModelRendererKeepsLeftDragUntilLeftRelease()
    {
        TestModelRenderer renderer = allocate(TestModelRenderer.class);
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        UIContext context = new UIContext(null);

        long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        setField(renderer, "dragOwnership", ownership);
        setField(renderer, "dragGeneration", generation);
        setField(renderer, "dragging", 1);
        check(renderer.isDragging(), "UIModelRenderer did not begin its left-owned drag");

        context.setMouse(50, 50, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        invokeMouseRelease(UIModelRenderer.class, renderer, context);
        check(renderer.isDragging(), "UIModelRenderer cleared a left-owned drag on right release");

        context.setMouse(50, 50, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        invokeMouseRelease(UIModelRenderer.class, renderer, context);
        check(!renderer.isDragging(), "UIModelRenderer did not clear its drag on matching left release");
    }

    private static void assertClipsKeepLeftScrubUntilLeftRelease()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIClips.java");
        int click = source.indexOf("private boolean handleLeftClick(UIContext context");
        int release = source.indexOf("public boolean subMouseReleased(UIContext context)", click);
        String clickHandler = source.substring(click, release);
        int capture = clickHandler.indexOf("this.captureGestureState();");
        int firstPick = clickHandler.indexOf("context.menu.runWithPreservedMouseCapture");

        check(source.contains("this.gestureGeneration = this.gestureOwnership.acquireToken(button)")
                || source.contains("long generation = this.gestureOwnership.acquireToken(button)"),
            "UIClips does not acquire an initiating-button generation");
        check(clickHandler.contains(
                "context.menu.runWithPreservedMouseCapture(this, () -> this.delegate.pickClip(last))")
                && clickHandler.contains(
                    "context.menu.runWithPreservedMouseCapture(this, () -> this.delegate.pickClip(clip))"),
            "UIClips selection rebuild does not preserve its first-press drag owner");
        check(capture >= 0 && firstPick > capture,
            "UIClips does not snapshot its previous selection before the first-press callback");
        int snapshotMethod = source.indexOf("private void captureGestureState()");
        int restoreMethod = source.indexOf("private void restoreGestureState()", snapshotMethod);
        String snapshotPath = source.substring(snapshotMethod, restoreMethod);
        check(snapshotPath.contains("for (Clip clip : this.clips.get())")
                && snapshotPath.contains("new ClipGestureSnapshot(clip, clip.toData().copy())")
                && source.indexOf("this.captureGestureState();", click)
                    < source.indexOf("context.menu.runWithPreservedMouseCapture", click),
            "UIClips does not snapshot the complete pre-callback clip state");
        check(source.contains("if (this.area.isInside(context) && this.gestureOwnership.isActive())")
                && source.contains("return true;"),
            "UIClips can cancel and roll back its active owner on an unrelated press");
        check(source.contains("if (!this.gestureOwnership.release(context.mouseButton, generation))")
                && source.indexOf("this.gestureGeneration = 0L;")
                    < source.indexOf("this.pickLastSelectedClip();", release),
            "UIClips release is not button/generation scoped before its selection callback");
        int cancel = source.indexOf("private void cancelGesture()");
        int restore = source.indexOf("private void restoreGestureState()", cancel);
        int finish = source.indexOf("private void finishGesture(boolean finishSelection)");
        int finishGuard = source.indexOf("if (finishSelection && wasSelecting)", finish);
        int finishPick = source.indexOf("this.pickLastSelectedClip();", finish);
        int finishEnd = source.indexOf("protected boolean subKeyPressed(UIContext context)", finish);
        int setClipData = source.indexOf("private void setClipData(Clip clip, int newTick, int newLayer, int newDuration)");
        int setClipDataEnd = source.indexOf("private void captureSelection(Area area)", setClipData);
        int dragClips = source.indexOf("private void dragClips(int mouseX, int mouseY)");
        int dragClipsEnd = source.indexOf("private void moveClips(List<Clip> others, int dx, int dy)", dragClips);
        String cancelPath = source.substring(cancel, finish);
        String releasePath = source.substring(release, cancel);
        String finishPath = source.substring(finish, finishEnd);
        String setClipDataPath = source.substring(setClipData, setClipDataEnd);
        String dragClipsPath = source.substring(dragClips, dragClipsEnd);
        int releaseOwnerRetire = releasePath.indexOf("this.gestureOwnership.release(context.mouseButton, generation)");
        int releaseFinish = releasePath.indexOf("this.finishGesture(true);");
        check(cancelPath.indexOf("this.restoreGestureState();")
                    < cancelPath.indexOf("this.finishGesture(false);")
                && restore > cancel,
            "UIClips cancellation clears gesture state before rolling it back");
        check(cancelPath.contains("snapshot.clip().fromData(snapshot.data().copy());")
                && cancelPath.contains("this.delegate.pickClip(this.gestureClip);")
                && cancelPath.contains("for (Clip clip : this.gestureSelection)"),
            "UIClips cancellation does not restore clip data, property target, and selection");
        check(releasePath.contains("this.finishGesture(true);")
                && !releasePath.contains("this.restoreGestureState();"),
            "UIClips physical release unexpectedly rolls back committed drag edits");
        check(setClipDataPath.contains("clip.tick.set(newTick);")
                && setClipDataPath.contains("clip.duration.set(newDuration);")
                && setClipDataPath.contains("clip.layer.set(newLayer);")
                && releaseOwnerRetire >= 0 && releaseFinish > releaseOwnerRetire,
            "UIClips physical release does not commit the final tick, duration, and layer before retiring the owner");
        check(dragClipsPath.contains("context.menu.runWithPreservedMouseCapture(this, this.delegate::fillData)"),
            "UIClips drag refresh can cancel its press owner while rebuilding the clip property hierarchy");
        check(finishPath.contains("this.gestureGeneration = 0L;")
                && finishPath.contains("this.grabbing = false;")
                && finishPath.contains("this.scrubbing = false;")
                && finishPath.contains("this.scrolling = false;")
                && finishPath.contains("this.selecting = false;"),
            "UIClips terminal path leaves a gesture generation or drag mode active");
        check(source.contains("this.finishGesture(false);")
                && finish >= 0 && finishGuard > finish && finishPick > finishGuard,
            "UIClips cancellation can still commit its pending selection");
        check(cancelPath.indexOf("this.gestureOwnership.cancel();")
                    < cancelPath.indexOf("this.restoreGestureState();")
                && finishPath.contains("this.gestureSelection = Collections.emptyList();")
                && finishPath.contains("this.gestureClip = null;")
                && finishPath.contains("this.gestureClips = null;"),
            "UIClips cancellation or terminal cleanup can retain stale selection ownership");
    }

    private static void assertReplayListCommitsOnlyOnLeftRelease()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayList.java");
        int release = source.indexOf("public boolean subMouseReleased(UIContext context)");
        int retire = source.indexOf("this.replayDragOwnership.release(context.mouseButton, generation)", release);
        int snapshot = source.indexOf("int draggedIndex = this.dragging;", retire);
        int clear = source.indexOf("this.dragging = -1;", snapshot);
        int swap = source.indexOf("this.handleSwap(draggedIndex, index);", clear);

        check(source.contains("this.replayDragOwnership.acquireToken(context.mouseButton)")
                && release >= 0 && retire > release,
            "UIReplayList does not scope sort release to its initiating button generation");
        check(source.contains(
                "context.menu.runWithPreservedMouseCapture(this, () -> this.callback.accept(this.getCurrent()))"),
            "UIReplayList selection rebuild does not preserve its first-press drag owner");
        check(snapshot > retire && clear > snapshot && swap > clear,
            "UIReplayList does not retire the old drag before its drop/swap callback");
        int cancelDrag = source.indexOf("private void cancelReplayDrag()");
        int cancelEnd = source.indexOf("/** Drag a replay row", cancelDrag);
        String cancelBody = cancelDrag >= 0 && cancelEnd > cancelDrag
            ? source.substring(cancelDrag, cancelEnd)
            : "";
        check(cancelBody.contains("this.replayDragOwnership.cancel();")
                && cancelBody.contains("this.dragging = -1;")
                && !cancelBody.contains("handleSwap")
                && !cancelBody.contains("dropReplaysOntoCategory"),
            "UIReplayList cancellation does not retire its drag without dropping or swapping");
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException
    {
        Field field = declaredField(target.getClass(), name);

        return field.get(target);
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

    private static void setField(Object target, String name, int value)
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

    private static void setField(Object target, String name, long value)
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

    private static void invokeMouseRelease(Class<?> owner, Object target, UIContext context)
    {
        try
        {
            var method = owner.getDeclaredMethod("subMouseReleased", UIContext.class);

            method.setAccessible(true);
            method.invoke(target, context);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke " + owner.getSimpleName() + " release", exception);
        }
    }

    private static Throwable invokeMouseFailure(Class<?> owner, String name, Object target, UIContext context)
    {
        try
        {
            var method = owner.getDeclaredMethod(name, UIContext.class);

            method.setAccessible(true);
            method.invoke(target, context);

            throw new AssertionError(owner.getSimpleName() + "." + name + " did not throw");
        }
        catch (InvocationTargetException exception)
        {
            return exception.getCause();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not invoke " + owner.getSimpleName() + "." + name, exception);
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

    private static String readSource(String path)
    {
        try
        {
            return Files.readString(Path.of(path));
        }
        catch (Exception exception)
        {
            throw new AssertionError("Could not read " + path, exception);
        }
    }

    private static IUIKeyframeGraph emptyKeyframeGraph()
    {
        return (IUIKeyframeGraph) Proxy.newProxyInstance(
            UIWrapperGestureOwnershipTest.class.getClassLoader(),
            new Class<?>[] {IUIKeyframeGraph.class},
            (proxy, method, args) ->
            {
                Class<?> type = method.getReturnType();

                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == float.class) return 0F;
                if (type == double.class) return 0D;
                if (List.class.isAssignableFrom(type)) return List.of();

                return null;
            }
        );
    }

    private static IUIKeyframeGraph throwingKeyframeGraph(String failingMethod)
    {
        return (IUIKeyframeGraph) Proxy.newProxyInstance(
            UIWrapperGestureOwnershipTest.class.getClassLoader(),
            new Class<?>[] {IUIKeyframeGraph.class},
            (proxy, method, args) ->
            {
                if (failingMethod.equals(method.getName()))
                {
                    throw new IllegalStateException(failingMethod + " failure");
                }

                Class<?> type = method.getReturnType();

                if (type == boolean.class) return false;
                if (type == int.class) return 0;
                if (type == float.class) return 0F;
                if (type == double.class) return 0D;
                if (List.class.isAssignableFrom(type)) return List.of();

                return null;
            }
        );
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

    private static final class TestModelRenderer extends UIModelRenderer
    {
        @Override
        protected void renderUserModel(UIContext context)
        {}
    }

}
