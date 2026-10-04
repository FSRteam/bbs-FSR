package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.utils.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ReplayIdentityLookupSourceTest
{
    /**
     * Sources that still look a replay up inside the film's replay list. A Replay is structurally
     * comparable, so a positional or equality-based lookup can select an actor other than the one
     * the caller is holding; each of these must go through the shared identity lookup instead.
     */
    private static final List<String> REPLAY_INDEX_SOURCES = List.of(
        "src/client/java/mchorse/bbs_mod/BBSModClient.java",
        "src/client/java/mchorse/bbs_mod/client/film/collaboration/BBSFilmCollaborationBridge.java",
        "src/client/java/mchorse/bbs_mod/ui/film/controller/UIFilmController.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayList.java",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java"
    );

    /**
     * Sources that used to look a replay up by position and no longer look one up at all, because
     * what they hand out is the replay's stable id. Here the list lookup is not merely optional —
     * putting one back would put the bug back — so it has to be absent, and the stable-id
     * replacement is asserted in its place rather than assumed from the lookup's disappearance.
     */
    private static final Map<String, String> STABLE_ID_REPLACEMENTS = Map.of(
        "src/client/java/mchorse/bbs_mod/ui/film/controller/OrbitFilmCameraController.java",
        "this.controller.getEntities().get(replay.getId())",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayPropertiesPanel.java",
        "UIAnchorKeyframeFactory.displayAttachments(filmPanel,replay.getId(),",
        "src/client/java/mchorse/bbs_mod/ui/film/replays/overlays/UIReplaysOverlayPanel.java",
        "UIAnchorKeyframeFactory.displayAttachments(filmPanel,replay.getId(),"
    );

    private ReplayIdentityLookupSourceTest()
    {}

    public static void run()
    {
        verifiesIdentityLookupContract();
        verifiesReplayUiCallSites();
        verifiesReplayMutationHasNoReferenceRewriting();
        verifiesAllTracksCollectorContract();
        verifiesSoundGuideVisibilityOwnership();
        verifiesSoundLoopIntervalUiContract();
    }

    /**
     * Strict pins on the two UI entry points that used to run the index remapper, taken over the
     * code with comments stripped.
     *
     * <p>These are <em>text contracts</em> (source pins), not behavioural coverage, and they are
     * deliberately strict: the assertion is that the whole compressed run of code from the call
     * that mutates the replay list to the {@code postNotify} that closes the edit contains nothing
     * but the mutation itself. A looser pin already exists above for the tail of {@code removeReplay}
     * ({@code List<Replay>remaining=...}), but its anchor sits <em>after</em> the old call site, so
     * pasting {@code remapReplayReferences(film, previousOrder);} back where it used to be would
     * leave it satisfied. This one cannot be satisfied by insertion: any statement added inside the
     * pinned run changes it.</p>
     *
     * <p>The cost is stated plainly: any legitimate edit inside these two runs also reddens the
     * test. That is acceptable here — both runs are a deliberate deletion site, and the only edit
     * either of them should ever receive is the one that would undo the stable-id work, which is
     * worth forcing someone to look at.</p>
     */
    private static void verifiesReplayMutationHasNoReferenceRewriting()
    {
        Path project = findProjectRoot();
        String replayList = compactCode(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplayList.java"
        )));

        check(replayList.contains(START_REMOVE_REPLAY + END_REMOVE_REPLAY),
            "UIReplayList.removeReplay no longer deletes the selected replays and closes the edit as the "
                + "stable-id rewrite left it: something was inserted into, removed from, or reordered inside "
                + "the deletion transaction (the index remapper belongs there again)");

        check(replayList.contains(START_HANDLE_SWAP + END_HANDLE_SWAP),
            "UIReplayList.handleSwap no longer swaps the two replays and closes the edit as the stable-id "
                + "rewrite left it: something was inserted into, removed from, or reordered inside the swap "
                + "transaction (the index remapper belongs there again)");

        check(occurrences(replayList, START_REMOVE_REPLAY) == 1 && occurrences(replayList, START_HANDLE_SWAP) == 1,
            "UIReplayList declares removeReplay or handleSwap more than once, so the pins above may not "
                + "describe the live implementation");
    }

    /*
     * The pinned runs, compacted with comments stripped. Kept as two halves per pin so the failure
     * message can point at the delete/notify boundary rather than dumping the whole run.
     */
    private static final String START_REMOVE_REPLAY =
        "publicvoidremoveReplay(){"
            + "if(!this.hasReplaySelection()){return;}"
            + "Filmfilm=this.panel.getData();"
            + "List<Replay>removing=newArrayList<>(this.getSelectedReplays());"
            + "Replayfocus=removing.get(0);"
            + "intglobalFocus=CollectionUtils.getIndex(film.replays.getList(),focus);"
            + "film.preNotify(IValueListener.FLAG_UNMERGEABLE);"
            + "for(Replayreplay:removing){film.replays.remove(replay);}";

    private static final String END_REMOVE_REPLAY = "film.postNotify(IValueListener.FLAG_UNMERGEABLE);";

    private static final String START_HANDLE_SWAP =
        "replays.remove(value);replays.add(globalTo,value);";

    private static final String END_HANDLE_SWAP = "data.postNotify(IValueListener.FLAG_UNMERGEABLE);";

    private static void verifiesAllTracksCollectorContract()
    {
        Path project = findProjectRoot();
        String replayEditor = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditor.java"
        )));
        String curated = section(
            replayEditor,
            "privatevoidcollectCuratedSheets(",
            "privatevoidcollectFormPropertySheets("
        );
        String properties = section(
            replayEditor,
            "privatevoidcollectFormPropertySheets(",
            "/**IKtracksliveintheirowncategory"
        );
        String ik = section(
            replayEditor,
            "privatevoidcollectIKSheets(List<UIKeyframeSheet>sheets){",
            "/**Physicstracksliveintheirowncategory"
        );
        String physics = section(
            replayEditor,
            "privatevoidcollectPhysicsSheets(List<UIKeyframeSheet>sheets){",
            "/**ShowtheIKtab"
        );
        String flush = section(
            replayEditor,
            "privatevoidflushForm(",
            "privatevoidsavePoseTabState("
        );

        for (String collector : List.of(curated, properties, ik, physics, flush))
        {
            check(!collector.contains("showAllTracks()") && !collector.contains("this.category"),
                "a replay track collector filters by category before the all-tracks view can see it");
        }

        String update = section(
            replayEditor,
            "publicvoidupdateChannelsList()",
            "privatevoidreplaceKeyframeEditor("
        );

        assertOrdered(update,
            "this.collectCuratedSheets(sheets)",
            "this.collectFormPropertySheets(sheets,poseTabs,poseTabDepths)",
            "this.collectIKSheets(sheets)",
            "this.collectPhysicsSheets(sheets)",
            "sheets.removeIf",
            "shouldShowTrack(v,this.category,this.showAllTracks())");
        check(replayEditor.contains("returnallTracks||categoryOf(sheet)==category;"),
            "all-tracks mode no longer bypasses the centralized category filter");
    }

    private static void verifiesSoundLoopIntervalUiContract()
    {
        Path project = findProjectRoot();
        String formPanel = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/ui/forms/editors/panels/UIAbstractSoundFormPanel.java"
        )));
        String keyframeFactory = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UISoundKeyframeFactory.java"
        )));
        String uiKeys = compact(read(project.resolve(
            "src/client/java/mchorse/bbs_mod/ui/UIKeys.java"
        )));

        check(formPanel.contains("this.looping,UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_LOOP_INTERVAL,this.loopInterval),this.startOffset"),
            "sound Form panel does not place loop interval immediately after looping");
        check(keyframeFactory.contains("looping,UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_LOOP_INTERVAL,loopInterval),UI.labelRow(UIKeys.FORMS_EDITORS_SOUND_START_OFFSET,startOffset)"),
            "sound keyframe panel does not place loop interval immediately after looping");
        check(uiKeys.contains("FORMS_EDITORS_SOUND_LOOP_INTERVAL=L10n.lang(\"bbs.ui.forms.editors.sound.loop_interval\")"),
            "sound loop interval UI key is missing or points to the wrong localization key");
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

        List<StructurallyEqualReplay> remaining = List.of(second);

        check(remaining.contains(first), "fixture no longer demonstrates structural List.contains ambiguity");
        check(CollectionUtils.getIndex(remaining, first) == -1,
            "a removed replay must stay absent when an equal duplicate remains");
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
            check(!compact.contains(".replays.getList().contains("),
                sourcePath + " uses structural equality to keep a removed Replay alive");
            check(compact.contains("CollectionUtils.getIndex("),
                sourcePath + " no longer uses the shared identity lookup");
        }

        for (Map.Entry<String, String> entry : STABLE_ID_REPLACEMENTS.entrySet())
        {
            String sourcePath = entry.getKey();
            String compact = read(project.resolve(sourcePath)).replaceAll("\\s+", "");

            check(!compact.contains(".replays.getList().indexOf("),
                sourcePath + " uses structural equality for a Replay index");
            check(!compact.contains(".replays.getList().contains("),
                sourcePath + " uses structural equality to keep a removed Replay alive");
            check(!compact.contains("CollectionUtils.getIndex("),
                sourcePath + " looks a Replay up by identity in the list again, although the value it "
                    + "needs is the stable id it already holds");
            check(compact.contains(entry.getValue()),
                sourcePath + " no longer addresses a replay by its stable id: expected to find \""
                    + entry.getValue() + "\"");
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

    /**
     * Compact code with comments removed. The ordinary {@link #compact(String)} keeps comments, so a
     * pin taken with it also pins the prose around the code — rewriting a comment would redden a
     * test that is supposed to be about code. Stripping comments first keeps the pin strict about
     * statements while leaving the explanation above them free to be improved.
     */
    private static String compactCode(String source)
    {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", "");
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
