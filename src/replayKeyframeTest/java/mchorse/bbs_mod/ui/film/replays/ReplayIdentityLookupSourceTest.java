package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.utils.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReplayIdentityLookupSourceTest
{
    private static final List<String> REPLAY_INDEX_SOURCES = List.of(
        "src/client/java/mchorse/bbs_mod/BBSModClient.java",
        "src/client/java/mchorse/bbs_mod/client/film/collaboration/BBSFilmCollaborationBridge.java",
        "src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java",
        "src/client/java/mchorse/bbs_mod/ui/film/controller/OrbitFilmCameraController.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayList.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayPropertiesPanel.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/overlays/UIReplaysOverlayPanel.java"
    );

    private ReplayIdentityLookupSourceTest()
    {}

    public static void run()
    {
        verifiesIdentityLookupContract();
        verifiesReplayUiCallSites();
        verifiesSoundGuideVisibilityOwnership();
    }

    private static void verifiesSoundGuideVisibilityOwnership()
    {
        Path project = findProjectRoot();
        String interaction = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/forms/renderers/sound/SoundGuideInteraction.java"
        )));
        String renderer = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/forms/renderers/sound/SoundGuideRenderer.java"
        )));

        check(!interaction.contains("FILM_MARK_TTL_MS") && !interaction.contains("FILM_SELECTED"),
            "sound guide visibility still expires with hover/picking time");
        check(interaction.contains("context.timelineProperties==selected.properties"),
            "Film sound guides are not owned by the selected Replay timeline");
        check(interaction.contains("&&(!isReplayEditorActive()||showAllGuides()||isFilmSelected(context,form));"),
            "world sound guides do not honor show_guide outside the Film editor");
        check(interaction.contains("if((previewPick||filmPick)&&form.showGuide.get())"),
            "sound guide picking no longer covers preview and Film handles");
        check(renderer.contains("if(!form.showGuide.get()||isCapturing()){return;}"),
            "sound guides are no longer excluded from capture/export");
    }

    private static void verifiesIdentityLookupContract()
    {
        StructurallyEqualReplay first = new StructurallyEqualReplay("same");
        StructurallyEqualReplay second = new StructurallyEqualReplay("same");
        List<StructurallyEqualReplay> values = List.of(first, second);

        check(first.equals(second), "duplicate replay fixture is not structurally equal");
        check(values.indexOf(second) == 0, "fixture no longer demonstrates structural List.indexOf ambiguity");
        check(CollectionUtils.getIndex(values, second) == 1, "identity lookup selected the first equal replay");
    }

    private static void verifiesReplayUiCallSites()
    {
        Path project = findProjectRoot();

        for (String sourcePath : REPLAY_INDEX_SOURCES)
        {
            String source = read(project.resolve(sourcePath));
            String compact = source.replaceAll("\\s+", "");

            check(!compact.contains(".replays.getList().indexOf("),
                sourcePath + " uses structural equality for a Replay index");
            check(compact.contains("CollectionUtils.getIndex("),
                sourcePath + " no longer uses the shared identity lookup");
        }

        String controller = read(project.resolve("src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java")).replaceAll("\\s+", "");

        check(!controller.contains("list.indexOf(this.getReplay())"),
            "UIFilmController replay switching uses structural equality");

        String replayList = read(project.resolve("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayList.java")).replaceAll("\\s+", "");

        check(!replayList.contains("all.indexOf(ef.replay)") && !replayList.contains("all.indexOf(et.replay)"),
            "UIReplayList drag uses structural Replay equality");
        check(replayList.contains("List<Replay>remaining=film.replays.getList();this.refreshReplayList();this.update();if(remaining.isEmpty()){this.panel.replayEditor.setReplay(null);")
                && replayList.contains("else{intidx=MathUtils.clamp(globalFocus,0,remaining.size()-1);Replaynext=remaining.get(idx);"),
            "UIReplayList does not clear the editor safely when deletion removes the final replay");
        check(replayList.indexOf("this.panel.replayEditor.setReplay(null);")
                < replayList.indexOf("this.updateFilmEditor();", replayList.indexOf("publicvoidremoveReplay()")),
            "UIReplayList refreshes controller/channels while the deleted final replay is still selected");

        String replayEditor = read(project.resolve("src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java")).replaceAll("\\s+", "");

        check(replayEditor.contains("UIKeyframeEditoreditor=this.keyframeEditor;UIKeyframesview=editor.view;ReplayreplayForEditor=this.replay;"),
            "UIReplaysEditor does not bind delayed callbacks to a stable editor/view/replay snapshot");
        check(!replayEditor.contains("renderRuler(context,this.keyframeEditor.view,"),
            "UIReplaysEditor ruler callback still dereferences the mutable keyframe editor");
        check(occurrences(replayEditor, "this.keyframeEditor!=editor||this.replay!=replayForEditor") >= 6,
            "UIReplaysEditor does not fence every delayed replay-mutating callback");

        String filterAction = "if(view.getGraph()instanceofUIKeyframeDopeSheet){menu.action(Icons.FILTER,UIKeys.FILM_REPLAY_FILTER_SHEETS,()->{";
        String filterFence = "if(this.keyframeEditor!=editor||this.replay!=replayForEditor||replayForEditor==null){return;}";
        String disabledRead = "Set<String>disabledSet=BBSSettings.disabledSheets.get();";
        String filterClose = "panel.onClose(e->{";
        String disabledWrite = "BBSSettings.disabledSheets.set(disabledSet);this.updateChannelsList();";
        int filterStart = replayEditor.indexOf(filterAction);
        int filterActionFence = replayEditor.indexOf(filterFence, filterStart);
        int disabledReadIndex = replayEditor.indexOf(disabledRead, filterStart);
        int filterCloseIndex = replayEditor.indexOf(filterClose, disabledReadIndex);
        int filterCloseFence = replayEditor.indexOf(filterFence, filterCloseIndex);
        int disabledWriteIndex = replayEditor.indexOf(disabledWrite, filterCloseFence);

        check(filterStart >= 0
                && filterActionFence > filterStart
                && disabledReadIndex > filterActionFence
                && filterCloseIndex > disabledReadIndex
                && filterCloseFence > filterCloseIndex
                && disabledWriteIndex > filterCloseFence,
            "UIReplaysEditor FILTER callbacks do not fence stale editor/replay state before read/write");

        String updateChannels = section(
            replayEditor,
            "publicvoidupdateChannelsList()",
            "/**All-tracksview"
        );
        String replacement = section(
            replayEditor,
            "privatevoidreplaceKeyframeEditor(",
            "/**All-tracksview"
        );

        check(updateChannels.contains("UIKeyframeEditorpreviousEditor=this.keyframeEditor;")
                && updateChannels.contains("this.keyframeEditorGeneration")
                && updateChannels.contains("booleanresetView=lastEditor==null||this.keyframeEditorResetPending;")
                && updateChannels.contains("this.keyframeEditor=null;"),
            "UIReplaysEditor rebuild does not snapshot and invalidate the previous editor generation");
        check(updateChannels.contains("this.keyframeEditorResetPending=false;")
                && updateChannels.contains("this.keyframeEditorResetPending=resetView;"),
            "UIReplaysEditor rebuild does not propagate and clear the pending initial viewport reset");
        check(updateChannels.contains("this.replaceKeyframeEditor(previousEditor,null,editorGeneration,false);")
                && updateChannels.contains("this.replaceKeyframeEditor(previousEditor,editor,editorGeneration,resetView);"),
            "UIReplaysEditor does not route both empty and populated rebuilds through the atomic replacement");
        check(!updateChannels.contains("this.keyframeEditor.removeFromParent()")
                && !updateChannels.contains("this.add(editor);")
                && !updateChannels.contains("if(editor!=null&&lastEditor==null){editor.view.resetView();}"),
            "UIReplaysEditor still performs an unpaired deferred remove/add during rebuild");
        assertOrdered(replacement,
            "if(previous!=null&&previous.getParent()==this){this.remove(previous);}",
            "if(generation!=this.keyframeEditorGeneration||this.keyframeEditor!=replacement){return;}",
            "for(UIKeyframeEditormounted:newArrayList<>(this.getChildren(UIKeyframeEditor.class)))",
            "if(mounted!=replacement&&mounted.getParent()==this){this.remove(mounted);}",
            "if(replacement!=null&&replacement.getParent()!=this){this.add(replacement);}",
            "this.iconBar.removeFromParent();",
            "this.allToggle.removeFromParent();",
            "this.resize();",
            "if(replacement!=null&&resetView){replacement.view.resetView();if(generation==this.keyframeEditorGeneration&&this.keyframeEditor==replacement){this.keyframeEditorResetPending=false;}}",
            "context.menu.runAfterHierarchyMutation"
        );
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(REPLAY_INDEX_SOURCES.get(0))))
            {
                return current;
            }

            Path nestedProject = current.resolve("new");

            if (Files.isRegularFile(nestedProject.resolve(REPLAY_INDEX_SOURCES.get(0))))
            {
                return nestedProject;
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate the new project source tree");
    }

    private static String read(Path path)
    {
        try
        {
            return Files.readString(path);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static int occurrences(String source, String value)
    {
        int count = 0;
        int index = 0;

        while ((index = source.indexOf(value, index)) >= 0)
        {
            count += 1;
            index += value.length();
        }

        return count;
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int index = source.indexOf(marker);

            check(index > previous, "missing or out-of-order source marker: " + marker);
            previous = index;
        }
    }

    private static String section(String source, String start, String end)
    {
        int begin = source.indexOf(start);
        int finish = begin < 0 ? -1 : source.indexOf(end, begin + start.length());

        check(begin >= 0 && finish > begin, "missing source section: " + start);

        return source.substring(begin, finish);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private record StructurallyEqualReplay(String label)
    {}
}
