package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-light contracts for Film viewport orbit and deferred picking. */
public final class OrbitFilmGestureOwnershipTest
{
    private static final String ORBIT = "src/client/java/mchorse/bbs_mod/ui/film/controller/OrbitFilmCameraController.java";
    private static final String CONTROLLER = "src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java";
    private static final String PREVIEW = "src/client/java/mchorse/bbs_mod/ui/film/UIFilmPreview.java";
    private static final String REPLAYS = "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java";
    private static final String DASHBOARD_ORBIT = "src/client/java/mchorse/bbs_mod/ui/dashboard/utils/UIOrbitCamera.java";

    private OrbitFilmGestureOwnershipTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertInterleavedButtonsKeepTheOriginalOrbitOwner();
        assertOrbitUsesButtonAndGenerationOwnership();
        assertPendingPickUsesTheOrbitGeneration();
        assertReleaseAndModeChangesRetireTheExactOrbit();
        assertPreviewForwardsTerminalToTheSiblingController();
    }

    private static void assertInterleavedButtonsKeepTheOriginalOrbitOwner()
    {
        MouseGestureOwnership ownership = new MouseGestureOwnership();
        long left = ownership.acquireToken(0);

        check(left != 0L, "Film orbit could not acquire its left-button owner");
        check(ownership.acquireToken(2) == 0L && ownership.isOwnedBy(0, left),
            "middle press replaced an active left Film orbit");
        check(!ownership.release(2, left) && ownership.isOwnedBy(0, left),
            "middle release retired an active left Film orbit");
        check(ownership.release(0, left),
            "matching left release did not retire the Film orbit");

        long replacement = ownership.acquireToken(0);

        check(replacement != 0L && replacement != left,
            "replacement Film orbit reused a stale generation");
        check(!ownership.release(0, left) && ownership.isOwnedBy(0, replacement),
            "stale left release retired a replacement Film orbit");
    }

    private static void assertOrbitUsesButtonAndGenerationOwnership()
    {
        String source = readSource(ORBIT);
        String start = method(source, "public long startGesture(UIContext context)", "public boolean wasDragged()");
        String stop = method(source, "public boolean stop(int mouseButton, long generation)", "public long gestureGeneration()");

        check(source.contains("MouseGestureOwnership orbitOwnership")
                && source.contains("private long orbitGeneration;")
                && source.contains("public void start(UIContext context)"),
            "Film orbit has no initiating-button generation owner");
        check(start.contains("this.orbitOwnership.acquireToken(button)")
                && start.contains("if (generation == 0L)")
                && start.contains("this.orbitOwnership.release(button, generation)"),
            "Film orbit does not reject re-entry or roll back a failed start");
        check(stop.indexOf("this.orbitOwnership.release(mouseButton, generation)")
                < stop.indexOf("this.clearOrbitGesture();"),
            "Film orbit clears state before matching the release generation");
    }

    private static void assertPendingPickUsesTheOrbitGeneration()
    {
        String source = readSource(REPLAYS);
        String controller = readSource(CONTROLLER);
        String intent = readSource("src/client/java/mchorse/bbs_mod/ui/film/view/ViewportPickIntent.java");
        String release = method(
            source,
            "public void releaseViewport(UIContext context, boolean dragged, UIFilmController controller, long generation)",
            "public void cancelViewportPick(long generation)"
        );
        String click = method(source, "public boolean clickViewport(UIContext context, Area area, UIFilmController controller)", "public void close()");
        String replayUtils = readSource("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditorUtils.java");
        String startFilmGizmo = method(replayUtils, "public static boolean startFilmGizmo(", "public static void configureFilmHotkeyDrag(");
        String gizmo = readSource("src/client/java/mchorse/bbs_mod/ui/utils/GizmoInteraction.java");
        String gizmoClick = method(gizmo, "public boolean mouseClickedHandle(UIContext context)", "public boolean mouseClickedSphere(UIContext context)");
        String gizmoRelease = method(gizmo, "public boolean mouseReleased(UIContext context)", "public void update(UIContext context)");
        String transform = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UIPropTransform.java");
        String transformDisable = method(transform, "private void disable()", "public void acceptChanges()");

        check(source.contains("private long pendingPickGeneration;")
                && source.contains("private UIFilmController pendingPickOwner;")
                && controller.contains("new ViewportPickIntent.Target(this.preview, this.panel.getData(), this.getReplay(),")
                && intent.contains("this.view == other.view && this.film == other.film"),
            "deferred Film viewport pick has no view, Film and orbit generation owner");
        check(release.contains("this.pendingPickGeneration != generation")
                && release.contains("this.pendingPickOwner != controller")
                && release.contains("controller.releaseViewportPick(context, dragged, generation)")
                && release.indexOf("this.pendingPickGeneration = 0L;")
                    < release.indexOf("controller.releaseViewportPick("),
            "another view or stale Film/orbit release can submit a replacement viewport pick");
        int acquire = click.indexOf("long generation = controller.orbit.startGesture(context);");
        int arm = click.indexOf("this.pendingPickGeneration = generation;");

        check(acquire >= 0 && arm > acquire
                && click.contains("this.pendingPickOwner = controller;")
                && click.contains("controller.deferViewportPick(context, generation);"),
            "viewport pick is armed before the Film orbit owns the press");
        check(controller.contains("this.pendingPickOrbitGeneration != orbitGeneration")
                && controller.contains("this.pendingViewportPick.take(generation)")
                && intent.contains("this.generation != generation || !this.isReady()"),
            "deferred stencil completion lost the initiating orbit and click generations");
        check(click.contains("StencilFormFramebuffer stencil = controller.getStencil();")
                && click.contains("controller.startViewportGizmo(context)")
                && !click.contains("UIReplaysEditorUtils.startFilmGizmo(")
                && startFilmGizmo.contains("context.mouseButton != 0")
                && click.contains("UIReplaysEditorUtils.pickFormWithOffers(context, pair, this::pickFormBone)"),
            "Film viewport bypasses generation-owned Gizmo startup or drops form/bone picking");
        check(source.contains("public boolean clickViewport(UIContext context, Area area)")
                && source.contains("return this.clickViewport(context, area, this.filmPanel.getController());")
                && source.contains("public void releaseViewport(UIContext context, boolean dragged, long generation)"),
            "per-view picking removed an existing addon entry point");
        check(gizmoClick.contains("if (context.mouseButton != 0)")
                && gizmoRelease.contains("context.mouseButton == this.pendingButton")
                && gizmoRelease.contains("this.retireGesture(context.mouseButton, generation)")
                && gizmoRelease.contains("Gizmo.INSTANCE.stop()")
                && transformDisable.contains("this.handler.removeFromParent()"),
            "Gizmo button ownership or transform-handler cleanup is not wired for wheel recovery");
    }

    private static void assertReleaseAndModeChangesRetireTheExactOrbit()
    {
        String source = readSource(CONTROLLER);
        String release = method(source, "protected boolean subMouseReleased(UIContext context)", "protected boolean subKeyPressed(UIContext context)");
        String pov = method(source, "public void setPov(int pov)", "private int getMouseMode()");

        check(release.contains("long orbitGeneration = this.orbit.gestureGeneration();")
                && release.contains("this.orbit.stop(context.mouseButton, orbitGeneration)")
                && release.contains("releaseViewport(context, orbitDragged, this, orbitGeneration)")
                && release.indexOf("this.gizmo.mouseReleased(context)")
                    < release.indexOf("this.orbit.stop(context.mouseButton, orbitGeneration)")
                && release.contains("mergeInputFailure(failure, exception)"),
            "Film controller release is not scoped to the captured orbit generation");
        check(pov.contains("this.cancelOrbitGesture();")
                && source.contains("this.panel.replayEditor.cancelViewportPick(this, generation);"),
            "camera mode/reset can leave an old deferred viewport pick armed");
    }

    private static void assertPreviewForwardsTerminalToTheSiblingController()
    {
        String preview = readSource(PREVIEW);
        String controller = readSource(CONTROLLER);
        String replays = readSource(REPLAYS);
        String dashboardOrbit = readSource(DASHBOARD_ORBIT);
        String cancel = method(
            controller,
            "public void cancelViewportGesture(UIContext context)",
            "protected void subMouseCanceled(UIContext context)"
        );

        check(preview.contains("releaseViewportGesture(context)")
                && preview.contains("cancelViewportGesture(context)"),
            "Film preview does not forward its captured terminal to the sibling controller");
        check(controller.contains("public boolean releaseViewportGesture(UIContext context)")
                && cancel.contains("this.orbit.stop(context.mouseButton, orbitGeneration)")
                && cancel.contains("cancelViewportPick(this, orbitGeneration)")
                && !cancel.contains("releaseViewport(context"),
            "Film viewport cancellation can commit or ignores the initiating orbit owner");
        check(replays.contains("dashboard.orbitUI.startGesture(context)")
                && !replays.contains("dashboard.orbit.start(2")
                && dashboardOrbit.contains("dragOwnership.acquireToken(context.mouseButton)"),
            "Film flight fallback bypasses dashboard orbit gesture ownership");
    }

    private static String method(String source, String startToken, String endToken)
    {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());

        check(start >= 0 && end > start, "Missing source contract: " + startToken);

        return source.substring(start, end);
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

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
