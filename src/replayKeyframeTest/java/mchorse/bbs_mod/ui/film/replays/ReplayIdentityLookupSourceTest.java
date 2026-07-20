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
        check(occurrences(replayEditor, "this.keyframeEditor!=editor||this.replay!=replayForEditor") >= 3,
            "UIReplaysEditor does not fence every delayed replay-mutating callback");
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
