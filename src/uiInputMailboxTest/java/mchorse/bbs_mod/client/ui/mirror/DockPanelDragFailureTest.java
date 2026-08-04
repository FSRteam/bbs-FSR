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
        assertSharedDockFinishesSafely();
        assertFilmUsesSharedDockLayout();
        assertDetachedPropertyPanelsAreRemoved();
        assertLayoutSettledAndStrictUndoContracts();
        assertClipSitesUseWidthAndHeight();
        assertWrapperClearsDragStateAfterDropFailure(
            "src/client/java/mchorse/bbs_mod/ui/particles/UIParticleSchemePanel.java",
            "UIParticleSchemePanel"
        );
    }

    private static void assertDetachedPropertyPanelsAreRemoved()
    {
        String clips = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIClipsPanel.java");
        String keyframes = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor.java");

        check(hasRemovalSequence(clips, "this.panel.removeFromParent();"),
            "UIClipsPanel must remove its externally parented property panel");
        check(hasRemovalSequence(keyframes, "this.editor.removeFromParent();"),
            "UIKeyframeEditor must remove its externally parented property panel");
    }

    private static boolean hasRemovalSequence(String source, String removal)
    {
        int start = source.indexOf("public void removeFromParent()");
        int end = source.indexOf("\n    }", start);
        int superRemove = source.indexOf("super.removeFromParent();", start);
        int panelRemove = source.indexOf(removal, start);

        return start >= 0 && end > start && superRemove > start && panelRemove > superRemove && panelRemove < end;
    }

    private static void assertLayoutSettledAndStrictUndoContracts()
    {
        String dock = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/layout/UIDockLayout.java");
        String film = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanel.java");
        String filmUndo = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPanelUndoKeys.java");
        String formUndo = readSource("src/client/java/mchorse/bbs_mod/ui/forms/editors/UIFormEditorUndoKeys.java");
        String modelUndo = readSource("src/client/java/mchorse/bbs_mod/ui/model_editor/UIModelEditorUndoKeys.java");

        check(dock.contains("onLayoutSettled(Runnable onLayoutSettled)")
                && dock.contains("this.onLayoutSettled.run();")
                && film.contains(".onLayoutSettled(() -> this.applyPreviewSizeToBBS(\"layoutSettled\"))"),
            "film preview must follow the shared dock's settled-layout callback");
        check(strictUndo(filmUndo) && strictUndo(formUndo) && strictUndo(modelUndo),
            "editor undo overlays must use strict modifier matching");
    }

    private static boolean strictUndo(String source)
    {
        return source.contains("register(Keys.UNDO") && source.contains("register(Keys.REDO")
            && source.contains(".strict()");
    }

    private static void assertClipSitesUseWidthAndHeight()
    {
        String clips = readSource("src/client/java/mchorse/bbs_mod/ui/film/UIClips.java");
        String replays = readSource("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java");
        String dopeSheet = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeDopeSheet.java");
        String ruler = readSource("src/client/java/mchorse/bbs_mod/ui/utils/renderers/TimelineRulerRenderer.java");

        check(clips.contains("clip(this.vertical.area.x, contentTop, this.vertical.area.w, contentHeight, context)")
                && replays.contains("clip(area.x, area.y, area.w, rulerBottom - area.y, context)")
                && dopeSheet.contains("clip(area.x, contentTop, area.w, area.ey() - contentTop, context)")
                && dopeSheet.contains("clip(area.x, area.y, w, area.h, context)")
                && ruler.contains("clip(area.x, top, area.w, area.ey() - top, context)"),
            "seven audited clip sites must pass widths and heights, not right/bottom coordinates");
    }

    private static void assertSharedDockFinishesSafely()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/ui/framework/elements/layout/UIDockLayout.java");
        int start = source.indexOf("private void finishPanelDrag()");
        int end = source.indexOf("/** Kills the in-flight drag", start);

        check(start >= 0 && end > start, "UIDockLayout completion method could not be inspected");

        String method = source.substring(start, end);
        int gate = method.indexOf("!this.panelDragCancelled && this.draggingPanelId != null");
        int apply = method.indexOf("this.applyPanelDropResult(this.draggingPanelId, this.dropTargetPanelId, this.dropTargetZone);");
        int clear = method.indexOf("this.clearPanelDragState();", apply);

        check(gate >= 0 && apply > gate && clear > apply,
            "UIDockLayout must gate drops before applying them and clear drag state afterwards");
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
