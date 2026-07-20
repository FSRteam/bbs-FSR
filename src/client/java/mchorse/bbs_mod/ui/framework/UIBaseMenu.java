package mchorse.bbs_mod.ui.framework;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.IViewport;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.IViewportStack;
import mchorse.bbs_mod.ui.framework.elements.utils.UIViewportStack;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.renderers.InputRenderer;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for GUI screens using this framework
 */
public abstract class UIBaseMenu
{
    /** F8 toggle for the transform gizmo / axes. Read through {@link #shouldRenderAxes()}, which also
     *  honours the hold-to-hide key, rather than directly. */
    public static boolean renderAxes = true;

    private static InputRenderer inputRenderer = new InputRenderer();

    /**
     * Whether the transform gizmo / axes should be drawn (and pickable) right now: the F8 toggle
     * {@link #renderAxes} is on AND the hold-to-hide key ({@link Keys#TRANSFORMATIONS_HIDE_GIZMO}) is
     * not being held. Gizmo render, its stencil pass and picking all gate on this, so holding the key
     * hides everything at once. The held state is polled (the keybind system only dispatches presses,
     * not holds).
     */
    public static boolean shouldRenderAxes()
    {
        return renderAxes && !isHideGizmoHeld();
    }

    /** Whether the hold-to-hide gizmo key ({@link Keys#TRANSFORMATIONS_HIDE_GIZMO}) is currently held.
     *  Use this to suppress gizmo stencil/picking at sites that aren't otherwise gated by the F8
     *  {@link #renderAxes} flag, so the hold removes them without altering F8's behaviour. */
    public static boolean isHideGizmoHeld()
    {
        return Keys.TRANSFORMATIONS_HIDE_GIZMO.isHeld();
    }

    private UIRootElement root;
    public UIElement main;
    public UIElement overlay;
    public UIContext context;
    public Area viewport = new Area();

    private MouseCapture mouseCapture;
    private final Map<Integer, MouseCapture> mouseCaptures = new LinkedHashMap<>();
    private long nextMouseCaptureGeneration;
    private long inputLifecycleGeneration = 1L;
    private final Deque<Long> inputDispatchLifecycleGenerations = new ArrayDeque<>();
    private int mouseDispatchDepth;
    private boolean releasingCapturedMouseGestures;
    private boolean invalidateAfterMouseRelease;
    private boolean mouseBarrierAdmissionFence;
    private final Deque<MouseBarrierAction> mouseBarrierActions = new ArrayDeque<>();
    /**
     * Owners explicitly allowed to survive a sibling-only hierarchy refresh.
     * The scope is captured onto each queued mutation; it is deliberately not
     * consulted by blocking/lifecycle barriers.
     */
    private final Deque<IUIElement> preservedMouseCaptureOwners = new ArrayDeque<>();
    private int rootMouseX;
    private int rootMouseY;

    public int width;
    public int height;

    public UIBaseMenu()
    {
        this.context = new UIContext(this);

        this.root = new UIRootElement(this.context);
        this.root.markContainer().full(this.viewport);

        this.main = new UIElement();
        this.main.full(this.viewport);
        this.overlay = new UIElement();
        this.overlay.full(this.viewport);
        this.root.add(this.main, this.overlay);

        UIElement popka = new UIElement();

        popka.keys().register(Keys.KEYBINDS, () -> this.context.toggleKeybinds());
        popka.keys().register(Keys.TRANSFORMATIONS_TOGGLE_AXES, () -> renderAxes = !renderAxes);
        popka.keys().register(Keys.UI_SCALE_INC, () -> changeUIScale(0.25F));
        popka.keys().register(Keys.UI_SCALE_DEC, () -> changeUIScale(-0.25F));
        this.root.add(popka);

        this.context.keybinds.relative(this.viewport).wh(0.5F, 1F);
    }

    /** Step the custom UI scale; zero uses Minecraft's effective scale as the base. */
    private static void changeUIScale(float delta)
    {
        float current = BBSSettings.userIntefaceScale.get();

        if (current <= 0F)
        {
            current = BBSModClient.getGUIScale();
        }

        BBSSettings.userIntefaceScale.set(MathUtils.clamp(current + delta, 0.5F, 4F));
    }

    public UIRootElement getRoot()
    {
        return this.root;
    }

    public boolean canHideHUD()
    {
        return true;
    }

    public boolean canPause()
    {
        return true;
    }

    public boolean canRefresh()
    {
        return true;
    }

    public void onOpen(UIBaseMenu oldMenu)
    {}

    public void onClose(UIBaseMenu nextMenu)
    {}

    public void update()
    {
        this.context.update();
    }

    public void resize(int width, int height)
    {
        this.width = width;
        this.height = height;

        this.viewport.set(0, 0, this.width, this.height);
        this.viewportSet();

        this.context.pushViewport(this.viewport);
        this.root.resize();
        this.context.popViewport();
    }

    protected void viewportSet()
    {}

    public boolean mouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        this.rememberRootMouse(mouseX, mouseY);

        if (!this.isInputDispatchLifecycleCurrent()
            || this.releasingCapturedMouseGestures || this.mouseBarrierAdmissionFence
            || this.mouseCaptures.containsKey(mouseButton))
        {
            return true;
        }

        MouseCapture capture = null;

        if (mouseButton >= 0 && mouseButton <= GLFW.GLFW_MOUSE_BUTTON_LAST)
        {
            capture = new MouseCapture(mouseButton, this.advanceMouseCaptureGeneration());
            this.mouseCaptures.put(mouseButton, capture);
            this.updateMouseCaptureAlias();
        }

        try
        {
            IUIElement owner = this.dispatchMouseThroughRootElement(
                mouseX,
                mouseY,
                mouseButton,
                false,
                false,
                this.currentInputDispatchLifecycleGeneration()
            );

            if (capture != null)
            {
                capture.owner = owner;
                capture.releasePath = this.findMouseReleasePath(owner);
            }

            this.drainMouseBarrierActions();

            return owner != null;
        }
        catch (RuntimeException | Error exception)
        {
            long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();
            this.removeMouseCapture(capture);

            if (capture != null && !this.mouseCaptures.containsKey(mouseButton))
            {
                try
                {
                    this.dispatchMouseCancellationThroughRoot(
                        mouseX,
                        mouseY,
                        mouseButton,
                        false,
                        lifecycleGeneration
                    );
                }
                catch (RuntimeException | Error cancellationException)
                {
                    if (cancellationException != exception)
                    {
                        exception.addSuppressed(cancellationException);
                    }
                }
            }

            this.discardMouseBarrierActions();

            throw exception;
        }
    }

    public boolean mouseScrolled(int x, int y, double h, double v)
    {
        this.rememberRootMouse(x, y);

        if (!this.isInputDispatchLifecycleCurrent()
            || this.releasingCapturedMouseGestures || this.mouseBarrierAdmissionFence)
        {
            return true;
        }

        try
        {
            boolean handled = this.dispatchMouseScrolledThroughRoot(x, y, h, v);

            this.drainMouseBarrierActions();

            return handled;
        }
        catch (RuntimeException | Error exception)
        {
            this.discardMouseBarrierActions();

            throw exception;
        }
    }

    public boolean mouseReleased(int mouseX, int mouseY, int mouseButton)
    {
        this.rememberRootMouse(mouseX, mouseY);

        if (this.releasingCapturedMouseGestures || this.mouseBarrierAdmissionFence)
        {
            return true;
        }

        MouseCapture capture = this.mouseCaptures.get(mouseButton);

        if (capture == null)
        {
            return true;
        }

        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        this.removeMouseCapture(capture);

        try
        {
            IUIElement owner = capture.owner;
            boolean handled = owner == null
                ? this.dispatchMouseThroughRoot(mouseX, mouseY, mouseButton, true, false)
                : this.dispatchMouseToCapturedPath(capture.releasePath, mouseX, mouseY, mouseButton, false) != null;

            this.drainMouseBarrierActions();

            return handled;
        }
        catch (RuntimeException | Error exception)
        {
            /* A release owner can throw before retiring its local drag state.
             * If no reentrant replacement captured this button, deliver the
             * non-committing terminal so the old owner cannot remain stuck.
             * A replacement capture wins and must never be canceled here. */
            if (!this.mouseCaptures.containsKey(mouseButton))
            {
                try
                {
                    this.dispatchMouseCancellationThroughRoot(
                        mouseX,
                        mouseY,
                        mouseButton,
                        false,
                        lifecycleGeneration
                    );
                }
                catch (RuntimeException | Error cancellationException)
                {
                    if (cancellationException != exception)
                    {
                        exception.addSuppressed(cancellationException);
                    }
                }
            }

            this.discardMouseBarrierActions();

            throw exception;
        }
    }

    /** Cancel one captured button without synthesizing a physical release. */
    public boolean mouseCanceled(int mouseX, int mouseY, int mouseButton)
    {
        this.rememberRootMouse(mouseX, mouseY);

        if (this.releasingCapturedMouseGestures)
        {
            return true;
        }

        MouseCapture capture = this.mouseCaptures.get(mouseButton);

        if (capture == null)
        {
            return true;
        }

        this.removeMouseCapture(capture);

        try
        {
            this.dispatchMouseCancellationThroughRoot(
                mouseX,
                mouseY,
                mouseButton,
                false,
                this.currentInputDispatchLifecycleGeneration()
            );
            this.drainMouseBarrierActions();

            return true;
        }
        catch (RuntimeException | Error exception)
        {
            this.discardMouseBarrierActions();

            throw exception;
        }
    }

    /**
     * Release the initiating button before a blocking overlay/context menu or
     * hierarchy mutation. Dispatch still follows the normal root/viewport
     * path. Admission stays fenced until every queued mutation is complete.
     */
    public void releaseCapturedMouseGestures()
    {
        this.cancelCapturedMouseGestures();
    }

    /** Cancel every currently captured button without committing release actions. */
    public void cancelCapturedMouseGestures()
    {
        if (this.mouseCaptures.isEmpty())
        {
            return;
        }

        this.runAfterCapturedMouseCancellation(() -> {});
    }

    /**
     * Run a blocking UI or hierarchy mutation only after the current mouse
     * dispatch has fully unwound and the initiating button has been released.
     */
    public void runAfterCapturedMouseRelease(Runnable mutation)
    {
        this.runAfterCapturedMouseCancellation(mutation);
    }

    /** Run a blocking mutation after canceling every captured mouse gesture. */
    public void runAfterCapturedMouseCancellation(Runnable mutation)
    {
        if (mutation == null)
        {
            throw new IllegalArgumentException("Mouse barrier mutation cannot be null");
        }

        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        if (lifecycleGeneration != this.inputLifecycleGeneration)
        {
            return;
        }

        if (this.mouseCaptures.isEmpty() && this.mouseDispatchDepth == 0)
        {
            this.runInInputDispatchLifecycle(lifecycleGeneration, mutation);

            return;
        }

        this.mouseBarrierActions.addLast(new MouseBarrierAction(
            mutation,
            this.snapshotMouseCaptures(),
            lifecycleGeneration,
            false,
            null,
            List.of()
        ));
        this.mouseBarrierAdmissionFence = true;
        this.drainMouseBarrierActions();
    }

    /**
     * Run a narrowly scoped sibling refresh while retaining one initiating
     * press owner. Any hierarchy mutation that removes that owner (or an
     * ancestor containing it) still cancels the capture.
     */
    public void runWithPreservedMouseCapture(IUIElement owner, Runnable mutation)
    {
        if (owner == null)
        {
            throw new IllegalArgumentException("Preserved mouse capture owner cannot be null");
        }
        if (mutation == null)
        {
            throw new IllegalArgumentException("Preserved mouse capture mutation cannot be null");
        }

        this.preservedMouseCaptureOwners.push(owner);

        try
        {
            mutation.run();
        }
        finally
        {
            this.preservedMouseCaptureOwners.pop();
        }
    }

    /**
     * Queue structural edits in call order and relayout once after owner cancellation.
     *
     * Keep this overload for callers compiled against the pre-capture API.
     */
    public void runAfterHierarchyMutation(Runnable mutation)
    {
        this.runAfterHierarchyMutation(mutation, new IUIElement[0]);
    }

    /** Queue structural edits in call order and relayout once after owner cancellation. */
    public void runAfterHierarchyMutation(Runnable mutation, IUIElement... detachedRoots)
    {
        if (mutation == null)
        {
            throw new IllegalArgumentException("Hierarchy mutation cannot be null");
        }

        List<IUIElement> detached = List.of();

        if (detachedRoots != null && detachedRoots.length > 0)
        {
            List<IUIElement> copiedRoots = new ArrayList<>(detachedRoots.length);

            for (IUIElement detachedRoot : detachedRoots)
            {
                copiedRoots.add(detachedRoot);
            }

            detached = List.copyOf(copiedRoots);
        }

        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        if (lifecycleGeneration != this.inputLifecycleGeneration)
        {
            return;
        }

        if (this.mouseCaptures.isEmpty() && this.mouseDispatchDepth == 0)
        {
            this.runInInputDispatchLifecycle(lifecycleGeneration, mutation);

            return;
        }

        this.mouseBarrierActions.addLast(new MouseBarrierAction(
            mutation,
            this.snapshotMouseCaptures(),
            lifecycleGeneration,
            true,
            this.preservedMouseCaptureOwners.peek(),
            detached
        ));
        this.mouseBarrierAdmissionFence = true;
        this.drainMouseBarrierActions();
    }

    /**
     * Reissue removal-only cleanup after {@link #invalidateInputState()} moved
     * the screen to a new lifecycle while the old dispatch is still unwinding.
     */
    void runAfterHierarchyCleanup(Runnable mutation)
    {
        if (mutation == null)
        {
            throw new IllegalArgumentException("Hierarchy cleanup cannot be null");
        }

        long lifecycleGeneration = this.inputLifecycleGeneration;

        if (this.mouseCaptures.isEmpty() && this.mouseDispatchDepth == 0)
        {
            this.runInInputDispatchLifecycle(lifecycleGeneration, mutation);

            return;
        }

        this.mouseBarrierActions.addLast(new MouseBarrierAction(
            mutation,
            this.snapshotMouseCaptures(),
            lifecycleGeneration,
            false,
            null,
            List.of()
        ));
        this.mouseBarrierAdmissionFence = true;
        this.drainMouseBarrierActions();
    }

    private void drainMouseBarrierActions()
    {
        if (this.mouseDispatchDepth > 0 || this.releasingCapturedMouseGestures || this.mouseBarrierActions.isEmpty())
        {
            return;
        }

        this.releasingCapturedMouseGestures = true;

        try
        {
            boolean resizeHierarchy = false;
            long resizeLifecycleGeneration = 0L;

            while (true)
            {
                while (!this.mouseBarrierActions.isEmpty())
                {
                    MouseBarrierAction action = this.mouseBarrierActions.removeFirst();

                    if (action.lifecycleGeneration != this.inputLifecycleGeneration)
                    {
                        continue;
                    }

                    this.cancelCapturedMouseGesturesNow(
                        action.captures,
                        action.lifecycleGeneration,
                        action.preservedOwner,
                        action.detachedRoots
                    );

                    if (action.lifecycleGeneration != this.inputLifecycleGeneration)
                    {
                        continue;
                    }

                    this.runInInputDispatchLifecycle(action.lifecycleGeneration, action.mutation);

                    if (action.lifecycleGeneration != this.inputLifecycleGeneration)
                    {
                        continue;
                    }

                    if (action.resizeHierarchy)
                    {
                        resizeHierarchy = true;
                        resizeLifecycleGeneration = action.lifecycleGeneration;
                    }
                }

                if (!resizeHierarchy)
                {
                    break;
                }

                long lifecycleGeneration = resizeLifecycleGeneration;

                resizeHierarchy = false;
                resizeLifecycleGeneration = 0L;

                if (lifecycleGeneration == this.inputLifecycleGeneration)
                {
                    this.runInInputDispatchLifecycle(lifecycleGeneration, this.root::resize);
                }
            }

            this.mouseBarrierAdmissionFence = false;
        }
        catch (RuntimeException | Error exception)
        {
            this.discardMouseBarrierActions();

            throw exception;
        }
        finally
        {
            if (this.invalidateAfterMouseRelease)
            {
                this.mouseCaptures.clear();
                this.updateMouseCaptureAlias();
                this.invalidateAfterMouseRelease = false;
            }

            this.releasingCapturedMouseGestures = false;
        }
    }

    private void discardMouseBarrierActions()
    {
        this.mouseBarrierActions.clear();
        this.mouseBarrierAdmissionFence = false;
    }

    private void cancelCapturedMouseGesturesNow(List<MouseCapture> captures, long lifecycleGeneration)
    {
        this.cancelCapturedMouseGesturesNow(captures, lifecycleGeneration, null, List.of());
    }

    private void cancelCapturedMouseGesturesNow(
        List<MouseCapture> captures,
        long lifecycleGeneration,
        IUIElement preservedOwner,
        List<IUIElement> detachedRoots
    )
    {
        Throwable failure = null;

        for (MouseCapture capture : captures)
        {
            if (this.mouseCaptures.get(capture.button) != capture)
            {
                continue;
            }

            if (this.shouldPreserveMouseCapture(capture, preservedOwner, detachedRoots))
            {
                continue;
            }

            this.removeMouseCapture(capture);
            this.advanceMouseCaptureGeneration();

            try
            {
                this.dispatchMouseCancellationThroughRoot(
                    this.rootMouseX,
                    this.rootMouseY,
                    capture.button,
                    true,
                    lifecycleGeneration
                );
            }
            catch (RuntimeException | Error exception)
            {
                if (failure == null)
                {
                    failure = exception;
                }
                else if (failure != exception)
                {
                    failure.addSuppressed(exception);
                }
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private boolean shouldPreserveMouseCapture(
        MouseCapture capture,
        IUIElement preservedOwner,
        List<IUIElement> detachedRoots
    )
    {
        if (preservedOwner == null || capture.owner != preservedOwner)
        {
            return false;
        }

        for (IUIElement detachedRoot : detachedRoots)
        {
            if (this.containsElement(detachedRoot, preservedOwner))
            {
                return false;
            }
        }

        return true;
    }

    private boolean containsElement(IUIElement root, IUIElement target)
    {
        if (root == target)
        {
            return true;
        }

        if (root instanceof UIElement element)
        {
            for (IUIElement child : element.getChildren())
            {
                if (this.containsElement(child, target))
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Drop every input token and deferred mutation owned by a detached screen. */
    public void invalidateInputState()
    {
        Throwable failure = null;
        boolean cancelingHere = !this.releasingCapturedMouseGestures && !this.mouseCaptures.isEmpty();

        if (cancelingHere)
        {
            this.releasingCapturedMouseGestures = true;

            try
            {
                this.cancelCapturedMouseGesturesNow(
                    this.snapshotMouseCaptures(),
                    this.currentInputDispatchLifecycleGeneration()
                );
            }
            catch (RuntimeException | Error exception)
            {
                failure = exception;
            }
            finally
            {
                this.releasingCapturedMouseGestures = false;
            }
        }

        this.inputLifecycleGeneration = this.inputLifecycleGeneration == Long.MAX_VALUE
            ? 1L
            : this.inputLifecycleGeneration + 1L;
        this.advanceMouseCaptureGeneration();
        if (this.releasingCapturedMouseGestures)
        {
            this.invalidateAfterMouseRelease = true;
        }
        else
        {
            this.mouseCaptures.clear();
            this.updateMouseCaptureAlias();
        }
        this.discardMouseBarrierActions();
        try
        {
            this.context.invalidateContextMenus();
        }
        catch (RuntimeException | Error exception)
        {
            if (failure == null)
            {
                failure = exception;
            }
            else if (failure != exception)
            {
                failure.addSuppressed(exception);
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private List<MouseCapture> snapshotMouseCaptures()
    {
        return List.copyOf(this.mouseCaptures.values());
    }

    private void removeMouseCapture(MouseCapture capture)
    {
        if (capture != null && this.mouseCaptures.get(capture.button) == capture)
        {
            this.mouseCaptures.remove(capture.button);
            this.updateMouseCaptureAlias();
        }
    }

    private void updateMouseCaptureAlias()
    {
        this.mouseCapture = this.mouseCaptures.isEmpty()
            ? null
            : this.mouseCaptures.values().iterator().next();
    }

    private void rememberRootMouse(int mouseX, int mouseY)
    {
        this.rootMouseX = mouseX;
        this.rootMouseY = mouseY;
    }

    private long advanceMouseCaptureGeneration()
    {
        this.nextMouseCaptureGeneration = this.nextMouseCaptureGeneration == Long.MAX_VALUE
            ? 1L
            : this.nextMouseCaptureGeneration + 1L;

        return this.nextMouseCaptureGeneration;
    }

    private long currentInputDispatchLifecycleGeneration()
    {
        return this.inputDispatchLifecycleGenerations.isEmpty()
            ? this.inputLifecycleGeneration
            : this.inputDispatchLifecycleGenerations.peek();
    }

    private boolean isInputDispatchLifecycleCurrent()
    {
        return this.currentInputDispatchLifecycleGeneration() == this.inputLifecycleGeneration;
    }

    private void runInInputDispatchLifecycle(long lifecycleGeneration, Runnable action)
    {
        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);

        try
        {
            action.run();
        }
        finally
        {
            this.inputDispatchLifecycleGenerations.pop();
        }
    }

    private boolean dispatchMouseThroughRoot(
        int mouseX,
        int mouseY,
        int mouseButton,
        boolean release,
        boolean restoreMouse
    )
    {
        return this.dispatchMouseThroughRootElement(
            mouseX,
            mouseY,
            mouseButton,
            release,
            restoreMouse,
            this.currentInputDispatchLifecycleGeneration()
        ) != null;
    }

    private IUIElement dispatchMouseToCapturedPath(
        List<IUIElement> path,
        int mouseX,
        int mouseY,
        int mouseButton,
        boolean restoreMouse
    )
    {
        int previousMouseX = this.context.mouseX;
        int previousMouseY = this.context.mouseY;
        int previousMouseButton = this.context.mouseButton;
        UIViewportStack previousViewportStack = this.context.viewportStack;
        boolean nested = this.mouseDispatchDepth > 0;
        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        this.context.viewportStack = new UIViewportStack();
        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;

        try
        {
            this.context.setMouse(mouseX, mouseY, mouseButton);
            this.context.pushViewport(this.viewport);

            try
            {
                return path == null || path.isEmpty()
                    ? null
                    : path.get(0).mouseReleasedCaptured(this.context, path, 0);
            }
            finally
            {
                this.context.popViewport();
            }
        }
        finally
        {
            this.mouseDispatchDepth -= 1;
            this.inputDispatchLifecycleGenerations.pop();
            this.context.viewportStack = previousViewportStack;

            if (restoreMouse || nested)
            {
                this.context.mouseX = previousMouseX;
                this.context.mouseY = previousMouseY;
                this.context.mouseButton = previousMouseButton;
            }
        }
    }

    private List<IUIElement> findMouseReleasePath(IUIElement owner)
    {
        if (owner == null)
        {
            return List.of();
        }

        List<IUIElement> path = new ArrayList<>();

        return this.findMouseReleasePath(this.root, owner, path) ? List.copyOf(path) : List.of(owner);
    }

    private boolean findMouseReleasePath(IUIElement current, IUIElement owner, List<IUIElement> path)
    {
        path.add(current);

        if (current == owner)
        {
            return true;
        }

        if (current instanceof UIElement element)
        {
            for (IUIElement child : element.getChildren())
            {
                if (this.findMouseReleasePath(child, owner, path))
                {
                    return true;
                }
            }
        }

        path.remove(path.size() - 1);

        return false;
    }

    private void dispatchMouseCancellationThroughRoot(
        int mouseX,
        int mouseY,
        int mouseButton,
        boolean restoreMouse,
        long lifecycleGeneration
    )
    {
        int previousMouseX = this.context.mouseX;
        int previousMouseY = this.context.mouseY;
        int previousMouseButton = this.context.mouseButton;
        UIViewportStack previousViewportStack = this.context.viewportStack;
        boolean nested = this.mouseDispatchDepth > 0;

        this.context.viewportStack = new UIViewportStack();
        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;

        try
        {
            this.context.setMouse(mouseX, mouseY, mouseButton);
            this.context.pushViewport(this.viewport);

            try
            {
                this.root.mouseCanceled(this.context);
            }
            finally
            {
                this.context.popViewport();
            }
        }
        finally
        {
            this.mouseDispatchDepth -= 1;
            this.inputDispatchLifecycleGenerations.pop();
            this.context.viewportStack = previousViewportStack;

            if (restoreMouse || nested)
            {
                this.context.mouseX = previousMouseX;
                this.context.mouseY = previousMouseY;
                this.context.mouseButton = previousMouseButton;
            }
        }
    }

    private IUIElement dispatchMouseThroughRootElement(
        int mouseX,
        int mouseY,
        int mouseButton,
        boolean release,
        boolean restoreMouse,
        long lifecycleGeneration
    )
    {
        int previousMouseX = this.context.mouseX;
        int previousMouseY = this.context.mouseY;
        int previousMouseButton = this.context.mouseButton;
        UIViewportStack previousViewportStack = this.context.viewportStack;
        boolean nested = this.mouseDispatchDepth > 0;

        this.context.viewportStack = new UIViewportStack();
        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;

        try
        {
            this.context.setMouse(mouseX, mouseY, mouseButton);

            if (!this.root.isEnabled())
            {
                return null;
            }

            this.context.pushViewport(this.viewport);

            try
            {
                IUIElement element = release
                    ? this.root.mouseReleased(this.context)
                    : this.root.mouseClicked(this.context);

                return element;
            }
            finally
            {
                this.context.popViewport();
            }
        }
        finally
        {
            this.mouseDispatchDepth -= 1;
            this.inputDispatchLifecycleGenerations.pop();
            this.context.viewportStack = previousViewportStack;

            if (restoreMouse || nested)
            {
                this.context.mouseX = previousMouseX;
                this.context.mouseY = previousMouseY;
                this.context.mouseButton = previousMouseButton;
            }
        }
    }

    private boolean dispatchMouseScrolledThroughRoot(int mouseX, int mouseY, double horizontal, double vertical)
    {
        int previousMouseX = this.context.mouseX;
        int previousMouseY = this.context.mouseY;
        double previousMouseWheel = this.context.mouseWheel;
        double previousMouseWheelHorizontal = this.context.mouseWheelHorizontal;
        UIViewportStack previousViewportStack = this.context.viewportStack;
        boolean nested = this.mouseDispatchDepth > 0;
        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        this.context.viewportStack = new UIViewportStack();
        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;

        try
        {
            this.context.setMouseWheel(mouseX, mouseY, vertical, horizontal);

            if (!this.root.isEnabled())
            {
                return false;
            }

            this.context.pushViewport(this.viewport);

            try
            {
                return this.root.mouseScrolled(this.context) != null;
            }
            finally
            {
                this.context.popViewport();
            }
        }
        finally
        {
            this.mouseDispatchDepth -= 1;
            this.inputDispatchLifecycleGenerations.pop();
            this.context.viewportStack = previousViewportStack;

            if (nested)
            {
                this.context.mouseX = previousMouseX;
                this.context.mouseY = previousMouseY;
                this.context.mouseWheel = previousMouseWheel;
                this.context.mouseWheelHorizontal = previousMouseWheelHorizontal;
            }
        }
    }

    public boolean handleKey(int key, int scanCode, int action, int mods)
    {
        if ((!this.isInputDispatchLifecycleCurrent()
            || this.releasingCapturedMouseGestures || this.mouseBarrierAdmissionFence)
            && action != GLFW.GLFW_RELEASE)
        {
            return true;
        }
        if (!this.root.isEnabled() && action != GLFW.GLFW_RELEASE)
        {
            return false;
        }

        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;
        boolean completed = false;

        try
        {
            if (action == GLFW.GLFW_PRESS)
            {
                inputRenderer.keyPressed(this.context, key);
            }

            this.context.setKeyEvent(key, scanCode, action);

            IUIElement element = this.root.keyPressed(this.context);

            if (this.root.isEnabled() && element != null)
            {
                completed = true;

                return true;
            }

            if (this.context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                this.closeMenu();
                completed = true;

                return true;
            }

            completed = true;

            return false;
        }
        catch (RuntimeException | Error exception)
        {
            this.discardMouseBarrierActions();

            throw exception;
        }
        finally
        {
            this.mouseDispatchDepth -= 1;

            try
            {
                if (completed)
                {
                    this.drainMouseBarrierActions();
                }
            }
            finally
            {
                this.inputDispatchLifecycleGenerations.pop();
            }
        }
    }

    public void handleTextInput(int key)
    {
        if (!this.isInputDispatchLifecycleCurrent()
            || this.releasingCapturedMouseGestures || this.mouseBarrierAdmissionFence)
        {
            return;
        }

        long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

        this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
        this.mouseDispatchDepth += 1;
        boolean completed = false;

        try
        {
            this.context.setKeyTyped((char) key);

            if (this.root.isEnabled())
            {
                this.root.textInput(this.context);
            }

            completed = true;
        }
        catch (RuntimeException | Error exception)
        {
            this.discardMouseBarrierActions();

            throw exception;
        }
        finally
        {
            this.mouseDispatchDepth -= 1;

            try
            {
                if (completed)
                {
                    this.drainMouseBarrierActions();
                }
            }
            finally
            {
                this.inputDispatchLifecycleGenerations.pop();
            }
        }
    }

    /**
     * This method is called when this screen is about to get closed
     */
    protected void closeMenu()
    {
        Minecraft.getInstance().setScreen(null);
    }

    public void closeThisMenu()
    {
        this.closeMenu();
    }

    public void renderDefaultBackground()
    {
        this.context.batcher.box(0, 0, this.width, this.height, Colors.A50);
    }

    public void renderMenu(UIRenderingContext context, int mouseX, int mouseY)
    {
        RenderSystem.depthFunc(GL11.GL_ALWAYS);

        this.rememberRootMouse(mouseX, mouseY);
        this.context.resetMatrix();
        this.context.setMouse(mouseX, mouseY);
        this.context.resetCursor();

        this.preRenderMenu(context);

        if (this.root.isVisible())
        {
            this.context.reset();
            long lifecycleGeneration = this.currentInputDispatchLifecycleGeneration();

            this.inputDispatchLifecycleGenerations.push(lifecycleGeneration);
            this.mouseDispatchDepth += 1;
            boolean rendered = false;

            try
            {
                this.context.pushViewport(this.viewport);

                try
                {
                    this.root.render(this.context);
                }
                finally
                {
                    this.context.popViewport();
                }

                rendered = true;
            }
            catch (RuntimeException | Error exception)
            {
                this.discardMouseBarrierActions();

                throw exception;
            }
            finally
            {
                this.mouseDispatchDepth -= 1;

                try
                {
                    if (rendered)
                    {
                        this.drainMouseBarrierActions();
                    }
                }
                finally
                {
                    this.inputDispatchLifecycleGenerations.pop();
                }
            }

            this.context.postRender();
        }

        if (this.main.isVisible())
        {
            inputRenderer.render(this, mouseX, mouseY);
        }

        this.context.applyCursor();

        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    protected void preRenderMenu(UIRenderingContext context)
    {}

    public void startRenderFrame(float tickDelta)
    {}

    public void renderInWorld(IBbsWorldRenderContext context)
    {}

    public static class UIRootElement extends UIElement implements IViewport
    {
        private UIContext context;

        public UIRootElement(UIContext context)
        {
            super();

            this.context = context;

            this.markContainer();
        }

        public UIContext getContext()
        {
            return this.context;
        }

        @Override
        public void apply(IViewportStack stack)
        {
            stack.pushViewport(this.area);
        }

        @Override
        public void unapply(IViewportStack stack)
        {
            stack.popViewport();
        }
    }

    private static final class MouseCapture
    {
        private final int button;
        private final long generation;
        private IUIElement owner;
        private List<IUIElement> releasePath = List.of();

        private MouseCapture(int button, long generation)
        {
            this.button = button;
            this.generation = generation;
        }
    }

    private record MouseBarrierAction(
        Runnable mutation,
        List<MouseCapture> captures,
        long lifecycleGeneration,
        boolean resizeHierarchy,
        IUIElement preservedOwner,
        List<IUIElement> detachedRoots
    )
    {}
}
