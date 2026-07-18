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
    private static final String REPLAYS = "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java";

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
        String release = method(
            source,
            "public void releaseViewport(UIContext context, boolean dragged, long generation)",
            "public void cancelViewportPick(long generation)"
        );
        String click = method(source, "public boolean clickViewport(UIContext context, Area area)", "public void close()");

        check(source.contains("private long pendingPickGeneration;"),
            "deferred Film viewport pick has no orbit generation");
        check(release.contains("this.pendingPickGeneration != generation")
                && release.indexOf("this.pendingPickGeneration = 0L;")
                    < release.indexOf("UIReplaysEditorUtils.pickFormWithOffers"),
            "stale orbit release can clear or submit a replacement viewport pick");
        check(click.indexOf("long generation = this.filmPanel.getController().orbit.startGesture(context);")
                < click.indexOf("this.pendingPickGeneration = generation;"),
            "viewport pick is armed before the Film orbit owns the press");
    }

    private static void assertReleaseAndModeChangesRetireTheExactOrbit()
    {
        String source = readSource(CONTROLLER);
        String release = method(source, "protected boolean subMouseReleased(UIContext context)", "protected boolean subKeyPressed(UIContext context)");
        String pov = method(source, "public void setPov(int pov)", "private int getMouseMode()");

        check(release.contains("long orbitGeneration = this.orbit.gestureGeneration();")
                && release.contains("this.orbit.stop(context.mouseButton, orbitGeneration)")
                && release.contains("releaseViewport(context, orbitDragged, orbitGeneration)")
                && release.indexOf("this.gizmo.mouseReleased(context)")
                    < release.indexOf("this.orbit.stop(context.mouseButton, orbitGeneration)")
                && release.contains("mergeInputFailure(failure, exception)"),
            "Film controller release is not scoped to the captured orbit generation");
        check(pov.contains("this.cancelOrbitGesture();")
                && source.contains("this.panel.replayEditor.cancelViewportPick(generation);"),
            "camera mode/reset can leave an old deferred viewport pick armed");
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
