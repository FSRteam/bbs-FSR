package mchorse.bbs_mod.ui.forms.editors;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeLoop;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.List;

/** A playback take must undo as one channel snapshot, including edits to existing keys. */
public final class AutoKeyframeUndoTest
{
    public static void runAll()
    {
        UIBaseMenu menu = new UIBaseMenu() {};
        TestHandler handler = new TestHandler(menu.main);
        ValueGroup document = new ValueGroup("film");
        KeyframeChannel<Float> channel = new KeyframeChannel<>("position", KeyframeFactories.FLOAT);
        document.add(channel);
        channel.insert(0F, 1F);
        BaseType before = channel.toData();
        document.preCallback(handler::handlePreValues);

        channel.preNotify(IValueListener.FLAG_BATCH | IValueListener.FLAG_UNMERGEABLE);
        channel.get(0).setValue(3F);
        handler.submitUndo();
        require(handler.getUndoManager().getTotalUndos() == 0, "First sample committed before take ended");
        for (int tick = 1; tick <= 5; tick++)
        {
            channel.preNotify(IValueListener.FLAG_BATCH | IValueListener.FLAG_UNMERGEABLE);
            channel.insert(tick, (float) tick + 3F);
            handler.submitUndo();
        }

        BaseType after = channel.toData();
        handler.submitUndo(true);
        require(handler.getUndoManager().getTotalUndos() == 1, "A take became multiple undo entries");
        require(handler.getUndoManager().undo(document) && before.equals(channel.toData()), "Undo failed to restore the pre-take channel");
        require(handler.getUndoManager().redo(document) && after.equals(channel.toData()), "Redo lost recorded keys");

        channel.preNotify(IValueListener.FLAG_BATCH | IValueListener.FLAG_UNMERGEABLE);
        handler.submitUndo(true);
        require(handler.getUndoManager().getTotalUndos() == 1, "Unchanged recording consumed history");

        Keyframe<Float> existing = channel.get(0);
        existing.preNotify(IValueListener.FLAG_BATCH | IValueListener.FLAG_UNMERGEABLE);
        existing.setValue(20F);
        channel.insert(9F, 30F);
        handler.expireBatch();
        handler.submitUndo();
        require(handler.getUndoManager().getTotalUndos() == 2, "Idle boundary did not seal the take");
        require(handler.getUndoManager().undo(document) && after.equals(channel.toData()), "Child edit was cached by index instead of owning channel");
        handler.reset();
        handler.submitUndo(true);
        require(handler.getUndoManager().getTotalUndos() == 0, "Reset retained pending channel snapshots");
        insertionPreservesSelection();
        loopRecordingReusesTheSourceKey();
        System.out.println("AutoKeyframeUndoTest passed");
    }

    private static void insertionPreservesSelection()
    {
        KeyframeChannel<Float> channel = new KeyframeChannel<>("position", KeyframeFactories.FLOAT);
        Keyframe<Float> first = channel.get(channel.insert(10F, 1F));
        Keyframe<Float> last = channel.get(channel.insert(20F, 2F));
        UIKeyframeSheet sheet = new UIKeyframeSheet(0xffffff, false, channel, null);
        sheet.selection.add(first);
        sheet.selection.add(last);

        Keyframe<Float> before = UIReplaysEditorUtils.ensureKeyframe(sheet, 5F);
        require(sheet.selection.getSelected().equals(List.of(first, last)),
            "Recording before selected keys moved selection onto their neighbours");
        require(UIReplaysEditorUtils.<Float>ensureKeyframe(sheet, 5F) == before
            && sheet.selection.getSelected().equals(List.of(first, last)),
            "Reusing an existing recording key shifted selection again");
        UIReplaysEditorUtils.ensureKeyframe(sheet, 15F);
        require(sheet.selection.getSelected().equals(List.of(first, last)),
            "Recording between selected keys lost the later selected key");
        sheet.selection.removeSelected();
        require(channel.getKeyframes().size() == 2 && channel.get(0) == before && channel.get(1).getTick() == 15F,
            "Deleting after recording removed inserted keys instead of the original selection");
    }

    private static void loopRecordingReusesTheSourceKey()
    {
        KeyframeChannel<Float> channel = new KeyframeChannel<>("position", KeyframeFactories.FLOAT);
        channel.insert(0F, 0F);
        Keyframe<Float> middle = channel.get(channel.insert(5F, 5F));
        Keyframe<Float> last = channel.get(channel.insert(10F, 10F));
        channel.putLoop(new KeyframeLoop("recording-loop", 0F, 10F, 30F));
        UIKeyframeSheet sheet = new UIKeyframeSheet(0xffffff, false, channel, null);
        sheet.selection.add(middle);
        sheet.selection.add(last);
        BaseType before = channel.toData();

        require(sheet.<Float>ensureKeyframe(15F) == middle && before.equals(channel.toData())
                && sheet.selection.getSelected().equals(List.of(middle, last)),
            "Recording on a repeated key overwrote the source key or shifted selection without inserting");
        Keyframe<Float> inserted = sheet.ensureKeyframe(17.5F);
        require(inserted.getTick() == 7.5F && inserted.getValue() == 7.5F
                && sheet.selection.getSelected().equals(List.of(middle, last)),
            "Recording in a loop did not map insertion and selection to the source interval");
        sheet.selection.removeSelected();
        require(channel.getKeyframes().size() == 2 && channel.get(1) == inserted,
            "Deleting after loop recording removed the new source key instead of the original selection");
    }

    private static final class TestHandler extends UIFormUndoHandler
    {
        TestHandler(UIElement element)
        {
            super(element);
        }

        void expireBatch()
        {
            this.batchTimer.time = 0L;
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
