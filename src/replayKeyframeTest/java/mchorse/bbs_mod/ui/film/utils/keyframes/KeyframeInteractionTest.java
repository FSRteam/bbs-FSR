package mchorse.bbs_mod.ui.film.utils.keyframes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.BooleanKeyframeFactory;

/** Regression coverage for replay/camera keyframe seek and gesture contracts. */
public final class KeyframeInteractionTest
{
    private static final Path KEYFRAMES = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframes.java"
    );
    private static final Path FILM_KEYFRAMES = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/film/utils/keyframes/UIFilmKeyframes.java"
    );
    private static final Path CLIPS = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/film/UIClips.java"
    );
    private static final Path KEYFRAME_EDITOR = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor.java"
    );
    private static final Path KEYFRAME_SHEET = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeSheet.java"
    );
    private static final Path DOPE_SHEET = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeDopeSheet.java"
    );
    private static final Path GRAPH = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/graphs/UIKeyframeGraph.java"
    );
    private static final Path REPLAYS_UTILS = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/film/replays/UIReplaysEditorUtils.java"
    );
    private static final Path BOOLEAN_FACTORY = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UIBooleanKeyframeFactory.java"
    );
    private static final Path NUMERIC_FACTORY = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/keyframes/factories/UINumericKeyframeFactory.java"
    );
    private static final Path CEM = Path.of(
        "src/main/java/mchorse/bbs_mod/cubic/jem/CemAnimation.java"
    );
    private static final Path VANILLA_POSE = Path.of(
        "src/client/java/mchorse/bbs_mod/mob/VanillaPose.java"
    );
    private static final Path TEXTBOX = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/text/UIBaseTextbox.java"
    );
    private static final Path TEXTAREA = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/text/UITextarea.java"
    );
    private static final Path TRACKPAD = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/UITrackpad.java"
    );

    private KeyframeInteractionTest()
    {}

    public static void run()
    {
        testCursorConversion();
        testLegacySteppedInterpolation();
        testFractionalTickOrdering();
        testOccupiedTickOverwrite();
        verifyInputSourceContract();
        verifyKeyframeFindingContracts();
    }

    /**
     * Distinct ticks less than one apart used to compare equal, because the tick difference was
     * truncated to an int before comparing. Moving a key by a fraction of a tick therefore left the
     * list out of order, and segment lookup assumes it is sorted.
     */
    private static void testFractionalTickOrdering()
    {
        KeyframeChannel<Boolean> channel = new KeyframeChannel<>("value", new BooleanKeyframeFactory());
        Keyframe<Boolean> early = channel.get(channel.insert(1.1F, false));
        Keyframe<Boolean> late = channel.get(channel.insert(1.8F, true));

        /* Move the first key just past the second by 0.2 of a tick: the truncating comparator
         * answered "equal" for exactly this pair and left the order alone. */
        early.setTick(2F, false);
        channel.sort();

        check(channel.get(0) == late && channel.get(1) == early,
            "fractional ticks less than one apart were not reordered by the sort comparator");
        check(channel.get(0).getTick() == 1.8F && channel.get(1).getTick() == 2F,
            "the sorted channel does not hold the expected fractional ticks");
    }

    /**
     * A key dropped onto a tick another key already holds replaces it: both keys used to survive at
     * the same tick, which breaks segment lookup and interpolation.
     */
    private static void testOccupiedTickOverwrite()
    {
        KeyframeChannel<Boolean> channel = new KeyframeChannel<>("value", new BooleanKeyframeFactory());
        Keyframe<Boolean> first = channel.get(channel.insert(0F, false));
        Keyframe<Boolean> moved = channel.get(channel.insert(5F, false));
        Keyframe<Boolean> occupant = channel.get(channel.insert(10F, true));

        moved.setTick(10F, false);
        channel.sort(java.util.List.of(moved));

        check(channel.getKeyframes().size() == 2, "the destination key was not replaced by the moved key");
        check(!channel.getKeyframes().contains(occupant), "the key that already held the tick survived the overwrite");
        check(channel.get(0) == first && channel.get(1) == moved, "the moved key did not take over the occupied tick");
        check(moved.getTick() == 10F && !moved.getValue() && !first.getValue(),
            "the overwrite changed the moved key's own value or tick");
    }

    private static void testLegacySteppedInterpolation()
    {
        BooleanKeyframeFactory factory = new BooleanKeyframeFactory();
        Keyframe<Boolean> created = new Keyframe<>("value", factory);

        check(created.getInterpolation().getInterp() == Interpolations.CONST,
            "new stepped keyframe did not default to constant interpolation");

        MapType legacy = new MapType();
        legacy.putBool("value", true);
        created.fromData(legacy);

        check(created.getInterpolation().getInterp() == Interpolations.LINEAR,
            "legacy stepped keyframe without interp did not retain its linear default");
    }

    private static void testCursorConversion()
    {
        check(UIFilmKeyframes.resolveCursorTick(17F, 0L) == 17,
            "absolute replay keyframe did not retain its tick");
        check(UIFilmKeyframes.resolveCursorTick(7F, 40L) == 47,
            "camera clip keyframe did not include its clip offset");
        check(UIFilmKeyframes.resolveCursorTick(-3F, 0L) == 0,
            "negative keyframe cursor was not clamped");
    }

    private static void verifyInputSourceContract()
    {
        Path root = findProjectRoot();
        String keyframes = compact(read(root.resolve(KEYFRAMES)));
        String filmKeyframes = compact(read(root.resolve(FILM_KEYFRAMES)));
        String clips = compact(read(root.resolve(CLIPS)));
        String keyframeEditor = compact(read(root.resolve(KEYFRAME_EDITOR)));
        String pickGesture = section(
            keyframes,
            "privatevoidpickOrStartSelectingKeyframes(UIContextcontext)",
            "@OverrideprotectedbooleansubMouseReleased(UIContextcontext)"
        );
        String pickCallback = section(
            keyframes,
            "publicvoidpickKeyframe(Keyframekeyframe)",
            "privatevoidnotifyKeyframePicked(Keyframekeyframe)"
        );
        String release = section(
            keyframes,
            "@OverrideprotectedbooleansubMouseReleased(UIContextcontext)",
            "@OverrideprotectedvoidsubMouseCanceled(UIContextcontext)"
        );
        String rollback = section(
            keyframes,
            "privatevoidrollbackEditingGesture(intbutton,longgeneration)",
            "privatevoidremoveOrCreateKeyframe(UIContextcontext)"
        );
        String cancellation = section(
            keyframes,
            "privatevoidcancelEditingGesture(UIContextcontext)",
            "privateMap<UIKeyframeSheet,List<Integer>>captureGestureSelection()"
        );
        String selectionRestore = section(
            keyframes,
            "privatevoidrestoreGestureSelection()",
            "privatestaticThrowablerunEditingReleaseStep"
        );

        assertOrdered(keyframes,
            "this.pickKeyframe(found);",
            "this.onKeyframePicked(picked);");
        assertOrdered(pickGesture,
            "this.gestureSelected=this.currentGraph.getSelected();",
            "this.gestureSelection=this.captureGestureSelection();",
            "this.currentGraph.clearSelection();",
            "sheet.selection.add(found);",
            "this.pickKeyframe(found);");
        assertOrdered(pickCallback,
            "this.getGraph().onCallback(keyframe);",
            "if(this.deferPickCallback)",
            "this.hasDeferredPick=true;",
            "this.deferredPick=keyframe;",
            "this.notifyKeyframePicked(keyframe);");
        assertOrdered(release,
            "this.editingOwnership.release(context.mouseButton,generation)",
            "this.editingGeneration=0L;",
            "failure=runEditingReleaseStep(failure,this::flushDeferredPick);");
        check(release.indexOf("this::flushDeferredPick")
                == release.lastIndexOf("this::flushDeferredPick"),
            "keyframe release flushes the deferred property callback more than once");
        assertOrdered(rollback,
            "this.discardDeferredPick();",
            "this.restoreGestureSelection();");
        check(cancellation.indexOf("this.gestureSelection=null;") < 0
                || cancellation.indexOf("this.gestureSelection=null;") > cancellation.indexOf("this::restoreGestureSelection"),
            "cancel path clears the selection snapshot before restoring it");
        assertOrdered(cancellation,
            "this::restoreGestureSelection",
            "this.currentGraph.mouseReleased(context)");
        check(!cancellation.contains("pickKeyframe(")
                && !cancellation.contains("pickSelected()")
                && cancellation.contains("this.discardDeferredPick();"),
            "cancel path invokes a property-panel selection callback");
        assertOrdered(selectionRestore,
            "sheet.selection.clear();",
            "sheet.selection.add(index);",
            "this.currentGraph.onCallback(selected);");
        check(!selectionRestore.contains("pickKeyframe(")
                && !selectionRestore.contains("pickSelected()")
                && !selectionRestore.contains("notifyKeyframePicked("),
            "selection restore invokes a property-panel selection callback");
        check(keyframes.contains("this.moveNoKeyframes(context);"),
            "blank keyframe timeline click does not seek immediately");
        check(keyframes.contains("elseif(this.dragging==0&&mouseHasMoved){this.dragging=1;}if(this.dragging==1)"),
            "first moved frame does not enter keyframe drag immediately");
        check(cancellation.contains("this::restoreKeyframes")
                && keyframes.contains("this.restoreSheetKeyframes(sheet,pair.a);"),
            "cancel path does not restore transient keyframe edits");
        check(keyframes.contains("this.editingOwnership.release(context.mouseButton,generation)"),
            "keyframe release lost its initiating-button generation guard");
        check(filmKeyframes.contains("protectedvoidonKeyframePicked(Keyframekeyframe)")
                && filmKeyframes.contains("this.seekToKeyframe(keyframe)"),
            "film keyframe view does not seek when a keyframe is picked");
        check(clips.contains("this.scrubbing=true;")
                && clips.contains("this.delegate.stopPlaybackOnScrub();")
                && clips.contains("this.delegate.setCursor(Math.max(0F,this.fromGraphCursor(mouseX)));"),
            "camera clips timeline no longer seeks on its initial click");

        String replacement = section(
            keyframeEditor,
            "privatevoidpickKeyframe(Keyframekeyframe)",
            "privatevoidreplaceEditor("
        );
        String commit = section(
            keyframeEditor,
            "privatevoidreplaceEditor(",
            "publicvoidsetTimelineVisible(booleanvisible)"
        );

        check(keyframeEditor.contains("privatelongeditorGeneration;"),
            "keyframe editor does not retain a replacement generation");
        check(!replacement.contains("previous.removeFromParent()")
                && !replacement.contains("this.add(replacement)"),
            "pickKeyframe still performs an unpaired deferred remove/add");
        assertOrdered(commit,
            "if(previous!=null&&previous.getParent()==this){this.remove(previous);}",
            "if(generation!=this.editorGeneration||this.editor!=replacement){return;}",
            "for(UIKeyframeFactorymounted:newArrayList<>(this.getChildren(UIKeyframeFactory.class)))",
            "if(mounted!=replacement&&mounted.getParent()==this){this.remove(mounted);}",
            "if(replacement!=null&&replacement.getParent()!=this){this.add(replacement);this.moveToFront(this.splitter);}",
            "this.target.resize();",
            "this.resize();",
            "replacement.restoreScroll();",
            "context.menu.runAfterHierarchyMutation"
        );
        check(commit.contains("generation==this.editorGeneration&&this.editor==replacement"),
            "stale replacement callback can restore a newer property panel");
    }

    /**
     * The source contracts for the keyframe review fixes that need a live editor to observe as
     * behavior. Each marker is the exact statement the fix added or changed, so removing the fix
     * fails this check rather than only the manual matrix.
     */
    private static void verifyKeyframeFindingContracts()
    {
        Path root = findProjectRoot();
        String keyframes = compact(read(root.resolve(KEYFRAMES)));
        String sheet = compact(read(root.resolve(KEYFRAME_SHEET)));
        String dopeSheet = compact(read(root.resolve(DOPE_SHEET)));
        String graph = compact(read(root.resolve(GRAPH)));
        String replaysUtils = compact(read(root.resolve(REPLAYS_UTILS)));
        String booleanFactory = compact(read(root.resolve(BOOLEAN_FACTORY)));
        String numericFactory = compact(read(root.resolve(NUMERIC_FACTORY)));

        /* Occupied-tick overwrite: the move submits an overwrite sort, and the sheet reselects by
         * keyframe so a replaced neighbour cannot shift the selection. */
        check(sheet.contains("publicList<Integer>sort(booleanoverwrite)")
                && sheet.contains("this.channel.sort(selected);")
                && sheet.contains("this.selection.add(keyframe);"),
            "the keyframe sheet cannot overwrite an occupied tick or reselect by keyframe");
        check(keyframes.contains("this.submitKeyframes(true)")
                && keyframes.contains("privatevoidsubmitKeyframes(booleanoverwrite)"),
            "the keyframe drag release does not submit an overwrite sort");

        /* Alt-click column selection, plus the two hover previews asking the same question. */
        check(keyframes.contains("publicbooleanisDuplicatingKeyframes(UIContextcontext)")
                && keyframes.contains("&&(this.isDuplicatingAtPlayhead()||this.currentGraph.findKeyframe(context.mouseX,context.mouseY)==null);"),
            "duplication no longer depends on the pointer sitting on an existing keyframe");
        check(graph.contains("Window.isAltPressed()&&this.keyframes.isDuplicatingKeyframes(context)")
                && dopeSheet.contains("Window.isAltPressed()&&this.keyframes.isDuplicatingKeyframes(context)"),
            "an alt-hover preview still advertises duplication over an existing keyframe");

        /* Boolean editor transform-key shortcut. */
        check(booleanFactory.contains("this.keys().register(Keys.TRANSFORMATIONS_TRANSLATE")
                && booleanFactory.contains("this.setValue(!this.keyframe.getValue());")
                && booleanFactory.contains("this.toggle.setValue(this.keyframe.getValue());"),
            "the boolean keyframe editor does not toggle through the transform-key shortcut");

        /* Folded tracks: bulk operations reach every sheet, and a pick reveals the row it selected. */
        String selectionOps = section(dopeSheet, "publicvoidclearSelection()", "privatevoidflatten(");
        check(!selectionOps.contains("getInteractiveSheets()")
                && selectionOps.contains("for(UIKeyframeSheetsheet:this.sheets)"),
            "folded sheets are still excluded from clear/select/remove operations");
        check(selectionOps.contains("publicvoidrevealSheet(UIKeyframeSheetsheet)"),
            "the dope sheet cannot reveal a row hidden by a fold");
        check(replaysUtils.contains("revealSheet(graph.getSheet(graph.getSelected()))")
                && replaysUtils.contains("revealSheet(currentSheet)")
                && occurrences(replaysUtils, "getDopeSheet().revealSheet(") >= 3,
            "viewport picking does not reveal the sheet it selected");

        /* Numeric edit transaction: one snapshot, an overlay that owns accept/reject, and no
         * compensating write-back on cancel. */
        check(numericFactory.contains("privatefinalUIElementeditingOverlay=newAcceptRejectOverlay();")
                && numericFactory.contains("this.editor.cacheKeyframes();")
                && numericFactory.contains("this.editor.submitKeyframes();")
                && numericFactory.contains("this.editor.cancelCachedKeyframes();")
                && numericFactory.contains("protectedvoidonRemove(UIElementparent)"),
            "the numeric keyframe editor has no single accept/cancel gesture lifecycle");
        check(keyframes.contains("publicvoidcancelCachedKeyframes()")
                && keyframes.contains("this.restoreKeyframes();"),
            "cancelling a numeric edit does not restore the captured keyframes silently");
        check(numericFactory.contains("privatevoidapplyEditingValue(doublevalue)")
                && numericFactory.contains("setValue(this.keyframe.getValue(),false);")
                && !numericFactory.contains("editingInitialValue"),
            "a numeric drag still notifies per sample or writes the initial value back on cancel");

        /* CEM/vanilla yaw wrap uses the target platform math, not Fabric's helper. */
        String cem = compact(read(root.resolve(CEM)));
        String vanillaPose = compact(read(root.resolve(VANILLA_POSE)));
        check(cem.contains("Mth.rotLerp(transition,target.getPrevHeadYaw(),target.getHeadYaw())")
                && cem.contains("Mth.rotLerp(transition,target.getPrevBodyYaw(),target.getBodyYaw())")
                && cem.contains("this.parser.setValue(\"head_yaw\",Mth.wrapDegrees(headYaw-bodyYaw));")
                && !cem.contains("MathHelper"),
            "CEM yaw does not interpolate and wrap on the short angular path");
        check(vanillaPose.contains("Mth.wrapDegrees(headYaw-bodyYaw)"),
            "vanilla pose does not wrap the relative yaw");

        /* Focused text inputs gate the I-beam on pointer bounds, not on keyboard focus. */
        String textbox = compact(read(root.resolve(TEXTBOX)));
        String textarea = compact(read(root.resolve(TEXTAREA)));
        String trackpad = compact(read(root.resolve(TRACKPAD)));
        check(textbox.contains("if(this.isEnabled()&&this.area.isInside(context))")
                && textarea.contains("if(this.isEnabled()&&this.area.isInside(context))")
                && trackpad.contains("if(this.isEnabled()&&this.area.isInside(context)&&(this.textbox.isFocused()||!dragging))"),
            "a focused text input still requests the text cursor outside its bounds");
    }

    private static int occurrences(String source, String marker)
    {
        int count = 0;
        int index = source.indexOf(marker);

        while (index >= 0)
        {
            count++;
            index = source.indexOf(marker, index + marker.length());
        }

        return count;
    }

    private static Path findProjectRoot()
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            if (Files.isRegularFile(current.resolve(KEYFRAMES)))
            {
                return current;
            }

            Path nested = current.resolve("new");

            if (Files.isRegularFile(nested.resolve(KEYFRAMES)))
            {
                return nested;
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
        catch (IOException exception)
        {
            throw new AssertionError("could not read " + path, exception);
        }
    }

    private static String compact(String source)
    {
        return source.replaceAll("\\s+", "");
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
}
