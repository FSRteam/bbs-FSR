package mchorse.bbs_mod.client.ui.mirror;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source contracts for exception-safe panel-dock completion wrappers. */
public final class DockPanelDragFailureTest
{
    private DockPanelDragFailureTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertWrapperClearsDragStateAfterDropFailure(
            "src/client/java/mchorse/bbs_mod/ui/framework/elements/layout/UIDockLayout.java",
            "UIDockLayout"
        );
        assertFilmUsesSharedDockLayout();
        assertWrapperClearsDragStateAfterDropFailure(
            "src/client/java/mchorse/bbs_mod/ui/particles/UIParticleSchemePanel.java",
            "UIParticleSchemePanel"
        );
    }

    private static void assertFilmUsesSharedDockLayout()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");

        check(source.contains("private final UIDockLayout dock;")
                && source.contains("this.dock = new UIDockLayout();")
                && !source.contains("private UIDraggable createPanelDragHandle(String panelId)"),
            "UIFilmPanel does not delegate panel dragging to the shared UIDockLayout");
    }

    private static void assertWrapperClearsDragStateAfterDropFailure(String path, String name)
    {
        String source = readSource(path);
        int start = source.indexOf("private UIDraggable createPanelDragHandle(String panelId)");
        int end = source.indexOf("private void renderPanelDragHandle", start);

        check(start >= 0 && end > start, name + " panel-drag wrapper could not be inspected");

        String wrapper = source.substring(start, end);
        int snapshot = wrapper.indexOf("String dragId = this.draggingPanelId;");
        int attempt = wrapper.indexOf("try", snapshot);
        int apply = wrapper.indexOf("this.applyPanelDropResult(dragId, targetId, targetZone);", attempt);
        int cleanup = wrapper.indexOf("finally", apply);
        int clear = wrapper.indexOf("this.clearPanelDragState();", cleanup);

        check(snapshot >= 0
                && wrapper.indexOf("String targetId = this.dropTargetPanelId;", snapshot) >= 0
                && wrapper.indexOf("int targetZone = this.dropTargetZone;", snapshot) >= 0,
            name + " does not snapshot the completed panel drop before cleanup");
        check(snapshot < attempt && attempt < apply && apply < cleanup && cleanup < clear,
            name + " can retain stale panel-drag state when its drop callback throws");
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
