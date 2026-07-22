package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.dashboard.panels.RepositorySession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-light repository-state and lifecycle source-shape checks. */
public final class BBSUiLifecycleSourceTest
{
    private BBSUiLifecycleSourceTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertOwnerAwareInputCallSites();
        assertRepositoryRebindsBeforeDataAndPinsAfter();
        assertMorphingSelectionAndDemorphRemainDistinct();
    }

    private static void assertOwnerAwareInputCallSites()
    {
        String activity = readSource("src/client/java/mchorse/bbs_mod/ui/film/FilmEditorUserActivity.java");
        String transform = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UIPropTransform.java");
        String screen = readSource("src/client/java/mchorse/bbs_mod/ui/framework/UIScreen.java");
        String dispatcher = readSource("src/client/java/mchorse/bbs_mod/client/ui/mirror/BBSUiInputDispatcher.java");
        String modelBlocks = readSource("src/client/java/mchorse/bbs_mod/ui/model_blocks/UIModelBlockPanel.java");
        String canvas = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/utils/UICanvas.java");
        String orbit = readSource("src/client/java/mchorse/bbs_mod/ui/dashboard/utils/UIOrbitCamera.java");
        String baseMenu = readSource("src/client/java/mchorse/bbs_mod/ui/framework/UIBaseMenu.java");
        String uiContext = readSource("src/client/java/mchorse/bbs_mod/ui/framework/UIContext.java");
        String uiElement = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/UIElement.java");
        String overlay = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/overlay/UIOverlay.java");
        String pixels = readSource("src/client/java/mchorse/bbs_mod/ui/dashboard/textures/UIPixelsEditor.java");
        String filmRecorder = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIFilmRecorder.java");
        String gizmo = readSource("src/client/java/mchorse/bbs_mod/ui/utils/GizmoInteraction.java");
        String clientLifecycle = readSource("src/client/java/mchorse/bbs_mod/BBSModClient.java");
        String mirrorLifecycle = readSource("src/client/java/mchorse/bbs_mod/client/ui/mirror/BBSUiMirrorRuntime.java");
        String filmPanel = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");
        String collaboration = readSource("src/client/java/mchorse/bbs_mod/client/film/collaboration/BBSFilmCollaborationBridge.java");

        check(activity.contains("Window.isMouseButtonPressed(b)"),
            "film activity bypasses the current input owner for mouse buttons");
        check(activity.contains("Window.isKeyPressed(key)"),
            "film activity bypasses the current input owner for keys");
        check(!activity.contains("GLFW.glfwGetMouseButton") && !activity.contains("InputConstants.isKeyDown"),
            "film activity still reads local GLFW state directly");
        check(transform.contains("if (!remoteInput && rawX <= border)")
                && transform.contains("else if (!remoteInput && rawY >= h - border)"),
            "remote transform dragging can still warp/read the physical cursor at a wrap boundary");
        check(screen.contains("localHeldKeys.putIfAbsent")
                && screen.contains("releaseLocalInputGestures()")
                && screen.contains("this.menu.handleKey(entry.getKey(), key.scanCode, GLFW.GLFW_RELEASE"),
            "UIScreen does not retain and synthesize release for locally delivered held keys");
        check(screen.contains("long renderingSessionId = this.mirrorSessionId;")
                && screen.contains("private boolean isRenderSessionCurrent(long sessionId)")
                && occurrences(screen, "if (!this.isRenderSessionCurrent(renderingSessionId))") >= 4
                && screen.contains("cleanupFailure = runTeardownStep(cleanupFailure, BBSFormPreviewCapture::abortFrame)")
                && screen.contains("cleanupFailure = runTeardownStep(cleanupFailure, BBSUiFrameRecorder::abortFrame)"),
            "UIScreen render teardown does not fence stale screen/session callbacks or abort both frame scopes");
        check(occurrences(screen, "if (this.removed && !this.removing)") >= 4
                && screen.contains("if (failure != exception)"),
            "UIScreen allows completed-screen releases or can abort teardown through self-suppression");
        check(dispatcher.contains("this.screen.releaseLocalInputGestures()"),
            "remote lease acquisition does not release all local input gestures");
        check(modelBlocks.contains("scaleCoordinate(context.mouseX, context.menu.width, w)")
                && modelBlocks.contains("screen.getOwnerFramebufferMouseX()")
                && modelBlocks.contains("screen.getOwnerFramebufferMouseY()"),
            "model-block stencil/ray coordinates do not follow the active input owner");
        check(canvas.contains("beginOwnedDrag(context, dragButton)")
                && canvas.contains("retireOwnedDrag(context.mouseButton)")
                && orbit.contains("dragOwnership.release(mouseButton, generation)")
                && orbit.contains("stopGesture(context.mouseButton, this.dragGeneration)"),
            "canvas/orbit gestures still end on an unrelated mouse-button release");
        check(pixels.contains("this.beginOwnedDrag(context, 0)")
                && pixels.contains("if (!this.isDragOwnedBy(context.mouseButton))")
                && pixels.contains("this.restoreSecondaryEraser();"),
            "pixel editor bypasses UICanvas ownership or finalizes an unrelated release");
        check(baseMenu.contains("runAfterCapturedMouseRelease(Runnable mutation)")
                && overlay.contains("context.menu.runAfterCapturedMouseRelease(() ->")
                && uiContext.contains("this.menu.runAfterCapturedMouseRelease(() ->")
                && filmRecorder.contains("context.menu.runAfterCapturedMouseRelease(() ->"),
            "blocking overlays/context menus do not release the prior press target");
        check(uiElement.contains("this.runHierarchyMutation(() ->")
                && uiElement.contains("this.remove((UIElement) element);")
                && uiElement.contains("if (element.parent == this)"),
            "hierarchy replacement does not preserve ordered add/remove lifecycle ownership");
        check(gizmo.contains("this.retireGesture(context.mouseButton, generation)")
                && gizmo.contains("this.pendingButton = context.mouseButton")
                && gizmo.contains("this.gestureOwnership.acquireToken(context.mouseButton)")
                && gizmo.contains("this.startOwnedGizmo(context, Gizmo.STENCIL_TRACKBALL, ownerButton, generation)"),
            "Gizmo release is not scoped to the initiating button and generation");
        check(clientLifecycle.contains("runClientLifecycleStep(\"reset UI mirror\", () -> BBSUiMirrorRuntime.reset())")
                && clientLifecycle.contains("runClientLifecycleStep(\"reset Film collaboration\", () -> BBSFilmCollaborationBridge.resetSession())")
                && clientLifecycle.contains("runClientLifecycleStep(\"stop resource watchdog\", () -> BBSResources.stopWatchdog())")
                && clientLifecycle.contains("filmPanel.forceSave()"),
            "client teardown still lets one lifecycle callback skip later cleanup");
        check(mirrorLifecycle.contains("runStep(\"reset render surfaces\"")
                && mirrorLifecycle.contains("runStep(\"release UI input\"")
                && mirrorLifecycle.contains("runStep(\"close UI mirror sessions\"")
                && mirrorLifecycle.contains("runStep(\"stop render surfaces\""),
            "UI mirror teardown does not isolate its owned lifecycle components");
        check(filmPanel.contains("Throwable failure = null;")
                && filmPanel.contains("super.forceSave();")
                && filmPanel.contains("failure.addSuppressed(exception)"),
            "Film force-save does not preserve flush errors while attempting repository persistence");
        check(collaboration.contains("if (current == state)")
                && collaboration.contains("current = null;")
                && collaboration.contains("CollectionUtils.getIndex(state.film.replays.getList(), replay)"),
            "Film collaboration teardown/Replay selection does not preserve identity semantics");
        check(clientLifecycle.contains("CollectionUtils.getIndex(panel.getData().replays.getList(), replay)"),
            "recording shortcut still resolves equal Replay values structurally");
    }

    private static void assertRepositoryRebindsBeforeDataAndPinsAfter()
    {
        Object remoteRepository = new Object();
        Object localRepository = new Object();
        AtomicReference<Object> selected = new AtomicReference<>(localRepository);
        AtomicInteger selections = new AtomicInteger();
        RepositorySession<Object> session = new RepositorySession<>(() ->
        {
            selections.incrementAndGet();
            return selected.get();
        });

        check(session.get() == localRepository,
            "pre-handshake repository selection did not start locally");
        check(!session.isPinned(), "name-list selection pinned the repository before data was loaded");

        selected.set(remoteRepository);
        check(session.get() == remoteRepository,
            "remote handshake did not refresh an idle panel repository");
        check(session.pin(remoteRepository) == remoteRepository && session.isPinned(),
            "loading a remote Film did not pin its repository");

        selected.set(localRepository);
        check(session.get() == remoteRepository,
            "handshake reset retargeted an active remote Film to the local library");
        check(selections.get() == 2,
            "repository selector did not refresh only until the data-session pin");

        RepositorySession<Object> nextSession = new RepositorySession<>(selected::get);
        check(nextSession.get() == localRepository,
            "a new panel session did not select the current local repository");
    }

    private static void assertMorphingSelectionAndDemorphRemainDistinct()
    {
        String morphingPanel = readSource("src/client/java/mchorse/bbs_mod/ui/morphing/UIMorphingPanel.java");
        String formPalette = readSource("src/client/java/mchorse/bbs_mod/ui/forms/UIFormPalette.java");
        String client = readSource("src/client/java/mchorse/bbs_mod/BBSModClient.java");
        String demorphButton = sourceSection(morphingPanel, "this.demorph =", "this.demorph.tooltip");
        String setSelected = sourceSection(formPalette, "public void setSelected(Form form)", "@Override");

        check(demorphButton.contains("this.palette.setSelected(null);")
                && demorphButton.contains("this.setForm(null);"),
            "morphing-panel demorph no longer clears the selection and restores the player form");
        check(setSelected.contains("this.list.setSelected(form);")
                && !setSelected.contains("this.accept(form)")
                && !setSelected.contains("this.callback.accept(form)"),
            "UIFormPalette.setSelected unexpectedly notifies the form callback");
        check(client.contains("while (keyDemorph.consumeClick()) ClientNetwork.sendPlayerForm(null);"),
            "the global demorph shortcut no longer restores the player's original form");
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

    private static String sourceSection(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start,
            "Could not locate source section between " + startMarker + " and " + endMarker);

        return source.substring(start, end);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
