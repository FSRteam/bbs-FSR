package mchorse.bbs_mod.ui.film.utils.keyframes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private KeyframeInteractionTest()
    {}

    public static void run()
    {
        testCursorConversion();
        verifyInputSourceContract();
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
        check(clips.contains("this.scrubbing=true;this.delegate.setCursor(this.fromGraphX(mouseX));"),
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
            "if(replacement!=null&&replacement.getParent()!=this){this.add(replacement);}",
            "this.target.resize();",
            "this.resize();",
            "replacement.restoreScroll();",
            "context.menu.runAfterHierarchyMutation"
        );
        check(commit.contains("generation==this.editorGeneration&&this.editor==replacement"),
            "stale replacement callback can restore a newer property panel");
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
