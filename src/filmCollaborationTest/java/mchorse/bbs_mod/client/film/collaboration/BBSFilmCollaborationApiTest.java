package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationLimits;
import mchorse.bbs_mod.api.client.film.BBSFilmCheckpointReason;
import mchorse.bbs_mod.api.client.film.BBSFilmCheckpointRequired;
import mchorse.bbs_mod.api.client.film.BBSFilmEditorKind;
import mchorse.bbs_mod.api.client.film.BBSFilmEditorView;
import mchorse.bbs_mod.api.client.film.BBSFilmKeyframeSelection;
import mchorse.bbs_mod.api.client.film.BBSFilmMutation;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationBatch;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationKind;
import mchorse.bbs_mod.api.client.film.BBSFilmPresence;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceClearRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmRefreshHint;
import mchorse.bbs_mod.api.client.film.BBSFilmRemotePresence;
import mchorse.bbs_mod.api.client.film.BBSFilmServerSequenceObserveRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSession;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshot;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotApplyRequest;
import mchorse.bbs_mod.data.storage.DataStorage;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.IntType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.factory.IFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

public final class BBSFilmCollaborationApiTest
{
    public static void main(String[] args)
    {
        testAtomicPathSegmentsAndDefensiveCopies();
        testConstructorLimits();
        testLocalRevisionContract();
        testPendingBatchFlushIsIdempotent();
        testEncodedDataPreflight();
        testServerSequenceGapRule();
        testPresenceWatermarkRule();
        testPresenceContextIsolationAndSelectionBounds();
        testLocalCoalescingRateAndAncestorFolding();
        testWholeTreeRollbackIncludesFailingTarget();
        testListBackedClipsStructuralRoundTrip();

        System.out.println("BBSFilmCollaborationApiTest: all tests passed");
    }

    private static void testAtomicPathSegmentsAndDefensiveCopies()
    {
        List<String> mutablePath = new ArrayList<>(List.of("replays", "form/channel.with.dot", "child/with/slash"));
        byte[] mutableData = {1, 2, 3};
        BBSFilmMutation mutation = new BBSFilmMutation(
            BBSFilmMutationKind.SET,
            mutablePath,
            mutableData,
            BBSFilmRefreshHint.REPLAY
        );

        mutablePath.clear();
        mutableData[0] = 9;
        assertEquals(List.of("replays", "form/channel.with.dot", "child/with/slash"), mutation.pathSegments(), "atomic path segments");
        assertEquals((byte) 1, mutation.encodedBbsData()[0], "constructor data copy");

        byte[] accessorCopy = mutation.encodedBbsData();
        accessorCopy[0] = 8;
        assertEquals((byte) 1, mutation.encodedBbsData()[0], "accessor data copy");

        BBSFilmSnapshot snapshot = new BBSFilmSnapshot(1, 4, new byte[] {5});
        byte[] snapshotCopy = snapshot.encodedBbsData();
        snapshotCopy[0] = 0;
        assertEquals((byte) 5, snapshot.encodedBbsData()[0], "snapshot data copy");
    }

    private static void testConstructorLimits()
    {
        BBSFilmMutation valid = mutation(List.of("replays", "a/b.c"), new byte[] {1});

        expectIllegal(() -> new BBSFilmSession(0, "film", 0), "non-positive session");
        expectIllegal(() -> new BBSFilmSession(1, "film", -1), "negative revision");
        expectIllegal(() -> mutation(List.of(), new byte[] {1}), "empty path");
        expectIllegal(() -> mutation(List.of(""), new byte[] {1}), "empty segment");
        expectIllegal(() -> mutation(List.of("界".repeat(342)), new byte[] {1}), "UTF-8 segment limit");
        expectIllegal(() -> mutation(repeatedSegments(BBSFilmCollaborationLimits.MAX_PATH_SEGMENTS + 1), new byte[] {1}), "path segment count");
        expectIllegal(() -> mutation(List.of("value"), new byte[0]), "empty mutation data");
        expectIllegal(() -> mutation(List.of("value"), new byte[BBSFilmCollaborationLimits.MAX_MUTATION_BYTES + 1]), "mutation data limit");
        expectIllegal(() -> new BBSFilmMutationBatch(1, 0, 0, -1, List.of()), "empty mutation batch");
        expectIllegal(() -> new BBSFilmMutationBatch(1, -1, 0, -1, List.of(valid)), "negative base revision");
        expectIllegal(() -> new BBSFilmMutationBatch(1, 0, -1, -1, List.of(valid)), "negative local op id");
        expectIllegal(() -> new BBSFilmMutationBatch(1, 0, 0, -2, List.of(valid)), "invalid server sequence");

        List<BBSFilmMutation> tooMany = new ArrayList<>();

        for (int i = 0; i <= BBSFilmCollaborationLimits.MAX_MUTATIONS; i++)
        {
            tooMany.add(valid);
        }

        expectIllegal(() -> new BBSFilmMutationBatch(1, 0, 0, -1, tooMany), "mutation count limit");
        expectIllegal(() -> new BBSFilmSnapshot(1, 0, new byte[0]), "empty snapshot data");
        expectIllegal(() -> new BBSFilmSnapshot(1, 0, new byte[BBSFilmCollaborationLimits.MAX_SNAPSHOT_BYTES + 1]), "snapshot data limit");
        expectIllegal(() -> new BBSFilmSnapshotApplyRequest(1, -1, 0, new byte[] {1}), "snapshot expected revision");
        expectIllegal(() -> new BBSFilmSnapshotApplyRequest(1, 0, -2, new byte[] {1}), "snapshot server sequence");
        expectIllegal(() -> new BBSFilmServerSequenceObserveRequest(1, 0, -1), "observed server sequence");
        expectIllegal(() -> new BBSFilmCheckpointRequired(1, 0, -1, BBSFilmCheckpointReason.ENCODE_FAILED), "checkpoint operation id");
    }

    private static void testLocalRevisionContract()
    {
        BBSFilmMutation first = mutation(List.of("replays", "0", "enabled"), new byte[] {1});
        BBSFilmMutation second = mutation(List.of("camera"), new byte[] {2});
        BBSFilmMutationBatch batch = new BBSFilmMutationBatch(9, 50, 12, 3, List.of(first, second));

        assertEquals(51L, BBSFilmCoreRevision.next(batch.baseRevision()), "one local CAS advance per batch");
        assertEquals(3L, batch.serverSeq(), "server ordering remains independent");

        BBSFilmSnapshotApplyRequest snapshot = new BBSFilmSnapshotApplyRequest(9, 50, 3, new byte[] {1});

        assertEquals(51L, BBSFilmCoreRevision.next(snapshot.expectedRevision()), "snapshot advances local CAS once");

        BBSFilmPresence presence = new BBSFilmPresence(
            9,
            51,
            BBSFilmEditorKind.REPLAY,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            List.of(2)
        );

        assertEquals(51L, presence.revision(), "revision-scoped presence");
    }

    private static void testPendingBatchFlushIsIdempotent()
    {
        ValueGroup film = new ValueGroup("film");
        ValueInt value = new ValueInt("value", 1);
        TestUndoHandler handler = new TestUndoHandler(new UIElement());

        film.add(value);
        film.preCallback(handler::handlePreValues);
        value.set(2);
        assertEquals(0, handler.committedBatches, "pending edit stays batched before close flush");

        handler.submitUndo();
        assertEquals(1, handler.committedBatches, "close flush commits final batch");

        handler.submitUndo();
        assertEquals(1, handler.committedBatches, "repeated close flush is idempotent");

        assertEquals(1, handler.getUndoManager().getTotalUndos(), "local undo exists before subtree invalidation");
        handler.reset();
        assertEquals(0, handler.getUndoManager().getTotalUndos(), "subtree refresh clears stale numeric-path undo");
    }

    private static void testEncodedDataPreflight()
    {
        try
        {
            ByteArrayOutputStream validOutput = new ByteArrayOutputStream();

            DataStorage.writeToStream(validOutput, new IntType(4));

            byte[] valid = validOutput.toByteArray();
            assertEquals(true, BBSFilmEncodedDataValidator.isValid(valid), "valid BBS1 data");

            byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
            assertEquals(false, BBSFilmEncodedDataValidator.isValid(trailing), "trailing BBS1 bytes");

            ByteArrayOutputStream maliciousOutput = new ByteArrayOutputStream();
            DataOutputStream malicious = new DataOutputStream(maliciousOutput);

            malicious.writeBytes("BBS1");
            malicious.writeByte(0);
            malicious.writeByte(0);
            malicious.writeByte(BaseType.TYPE_BYTE_ARRAY);
            malicious.writeInt(Integer.MAX_VALUE);

            assertEquals(false, BBSFilmEncodedDataValidator.isValid(maliciousOutput.toByteArray()), "oversized declared array");

            ByteArrayOutputStream keyBombOutput = new ByteArrayOutputStream();
            DataOutputStream keyBomb = new DataOutputStream(keyBombOutput);

            keyBomb.writeBytes("BBS1");
            keyBomb.writeByte(2);
            keyBomb.writeInt(1_000_000);

            assertEquals(false, BBSFilmEncodedDataValidator.isValid(keyBombOutput.toByteArray()), "truncated key table rejected before allocation");
        }
        catch (IOException e)
        {
            throw new AssertionError("Could not create BBS1 fixtures", e);
        }
    }

    private static void testServerSequenceGapRule()
    {
        assertEquals(true, BBSFilmServerSequence.accepts(-1, 10), "snapshot establishes server watermark");
        assertEquals(true, BBSFilmServerSequence.accepts(10, 11), "next server sequence");
        assertEquals(false, BBSFilmServerSequence.accepts(10, 10), "duplicate server sequence");
        assertEquals(false, BBSFilmServerSequence.accepts(10, 12), "server sequence gap");
        assertEquals(true, BBSFilmServerSequence.acceptsSnapshot(10, 10), "same-watermark authoritative snapshot repair");
        assertEquals(true, BBSFilmServerSequence.acceptsSnapshot(10, 15), "snapshot establishes a newer watermark after a gap");
        assertEquals(false, BBSFilmServerSequence.acceptsSnapshot(10, 9), "stale snapshot watermark");
    }

    private static void testPresenceWatermarkRule()
    {
        BBSFilmPresence presence = new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            List.of(2)
        );
        BBSFilmRemotePresence remote = new BBSFilmRemotePresence("player-a", "Alice", 0xff44aaff, 10, presence);
        BBSFilmPresenceClearRequest clear = new BBSFilmPresenceClearRequest(9, 50, 10, "player-a");

        assertEquals(10L, remote.serverSeq(), "remote presence watermark");
        assertEquals("player-a", clear.participantId(), "presence clear participant");
        assertEquals(true, BBSFilmPresenceSequence.accepts(-1, 10), "first presence watermark");
        assertEquals(true, BBSFilmPresenceSequence.accepts(10, 10), "same-watermark 30 Hz presence update");
        assertEquals(false, BBSFilmPresenceSequence.accepts(10, 9), "stale presence watermark");
        assertEquals(false, BBSFilmPresenceSequence.acceptsAfterClear(10, 10), "cleared participant cannot be resurrected by same watermark");
        assertEquals(true, BBSFilmPresenceSequence.acceptsAfterClear(10, 11), "newer presence can rejoin after clear");
        assertEquals(true, BBSFilmServerSequence.accepts(10, 11), "presence does not consume mutation sequence");
        expectIllegal(() -> new BBSFilmRemotePresence("", "Alice", 0, 10, presence), "blank participant id");
        expectIllegal(() -> new BBSFilmPresenceClearRequest(9, 50, -1, "player-a"), "negative presence watermark");
        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            repeatedIntegers(BBSFilmCollaborationLimits.MAX_SELECTED_REPLAYS + 1)
        ), "presence selection allocation bound");
        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            2,
            30,
            OptionalInt.of(-1),
            OptionalInt.empty(),
            List.of(2)
        ), "negative semantic cursor");
        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            List.of(2, 2)
        ), "duplicate replay selection");

        ValueGroup film = new ValueGroup("film");
        ValueInt value = new ValueInt("value", 1);
        TestUndoHandler pending = new TestUndoHandler(new UIElement());

        film.add(value);
        film.preCallback(pending::handlePreValues);
        value.set(2);
        assertEquals(0, pending.committedBatches, "pending local edit does not force presence flush");
        assertEquals(50L, remote.presence().revision(), "remote presence keeps caller's current core revision");
    }

    private static void testPresenceContextIsolationAndSelectionBounds()
    {
        List<BBSFilmKeyframeSelection> mutableKeyframes = new ArrayList<>(List.of(
            new BBSFilmKeyframeSelection("form/body/pose", 2),
            new BBSFilmKeyframeSelection("form/body/pose", 5),
            new BBSFilmKeyframeSelection("transform/x", 1)
        ));
        BBSFilmPresence presence = new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            2,
            30,
            OptionalInt.of(28),
            OptionalInt.of(4),
            "form/body/pose",
            List.of(2, 7),
            mutableKeyframes
        );

        mutableKeyframes.clear();
        assertEquals(3, presence.selectedKeyframes().size(), "keyframe selection defensive copy");
        assertEquals("form/body/pose", presence.semanticCursorSheetId(), "stable full-sheet cursor identity");
        assertEquals(true, BBSFilmCollaborationBridge.matchesTimelineScope(
            presence,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            2
        ), "matching editor/view/replay context");
        assertEquals(false, BBSFilmCollaborationBridge.matchesTimelineScope(
            presence,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_GRAPH,
            2
        ), "sub-editor context isolation");
        assertEquals(false, BBSFilmCollaborationBridge.matchesTimelineScope(
            presence,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            3
        ), "Replay context isolation");
        assertEquals(true, BBSFilmCollaborationBridge.presenceMatchesRevision(50, 50),
            "current revision presence visibility");
        assertEquals(false, BBSFilmCollaborationBridge.presenceMatchesRevision(49, 50),
            "old revision presence visibility");

        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            "",
            List.of(),
            List.of(
                new BBSFilmKeyframeSelection("sheet", 1),
                new BBSFilmKeyframeSelection("sheet", 1)
            )
        ), "duplicate full-sheet keyframe selection");
        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.of(BBSFilmCollaborationLimits.MAX_PRESENCE_CURSOR_ROW + 1),
            "",
            List.of(),
            List.of()
        ), "semantic cursor row bound");
        expectIllegal(() -> new BBSFilmPresence(
            9,
            50,
            BBSFilmEditorKind.REPLAY,
            BBSFilmEditorView.KEYFRAME_DOPE_SHEET,
            2,
            30,
            OptionalInt.empty(),
            OptionalInt.empty(),
            "",
            repeatedIntegers(128),
            repeatedKeyframeSelections(129)
        ), "combined Replay/keyframe selection allocation bound");
    }

    private static void testLocalCoalescingRateAndAncestorFolding()
    {
        BBSFilmLocalBatchBuffer<Integer> buffer = new BBSFilmLocalBatchBuffer<>(
            BBSFilmCollaborationLimits.MAX_MUTATIONS,
            100_000_000L
        );
        long frameNanos = 1_000_000_000L / 240L;
        int batches = 0;
        int finalValue = -1;

        for (int i = 0; i < 240; i++)
        {
            long now = frameNanos * i;

            buffer.offer(List.of("camera", "0", "tick"), i, now);

            if (buffer.isDue(now))
            {
                BBSFilmLocalBatchBuffer.Batch<Integer> batch = buffer.drain();

                batches += 1;
                finalValue = batch.targets().get(0).value();
            }
        }

        if (!buffer.isEmpty())
        {
            BBSFilmLocalBatchBuffer.Batch<Integer> batch = buffer.drain();

            batches += 1;
            finalValue = batch.targets().get(0).value();
        }

        assertEquals(true, batches >= 9 && batches <= 11, "240 Hz edits coalesce to about 10 batches per second");
        assertEquals(239, finalValue, "coalescing keeps the final drag value");

        buffer.offer(List.of("replays", "0", "enabled"), 1, 0L);
        buffer.offer(List.of("replays"), 2, 1L);
        buffer.offer(List.of("replays", "1", "enabled"), 3, 2L);

        BBSFilmLocalBatchBuffer.Batch<Integer> folded = buffer.drain();

        assertEquals(1, folded.targets().size(), "ancestor removes queued descendants");
        assertEquals(List.of("replays"), folded.targets().get(0).path(), "stable ancestor path");
        assertEquals(2, folded.targets().get(0).value(), "ancestor payload remains authoritative");

        BBSFilmLocalBatchBuffer<Integer> bounded = new BBSFilmLocalBatchBuffer<>(1, 100_000_000L);

        bounded.offer(List.of("camera"), 1, 0L);
        bounded.offer(List.of("replays"), 2, 1L);
        assertEquals(true, bounded.drain().overflowed(), "entry overflow requires a checkpoint instead of silent loss");
    }

    private static void testWholeTreeRollbackIncludesFailingTarget()
    {
        ValueGroup film = new ValueGroup("film");
        ValueGroup nested = new ValueGroup("nested");
        ValueInt early = new ValueInt("early", 1);
        ThrowOnceValueInt late = new ThrowOnceValueInt("late", 2);

        nested.add(early);
        nested.add(late);
        film.add(nested);

        MapType backup = film.toData().asMap();
        MapType incoming = new MapType();

        incoming.putInt("early", 10);
        incoming.putInt("late", 20);

        BBSFilmAtomicApply.Result result = BBSFilmAtomicApply.run(film, backup, () -> nested.fromData(incoming));

        assertEquals(false, result.applied(), "partially failing group is not reported as applied");
        assertEquals(true, result.restored(), "whole tree rollback succeeds");
        assertEquals(1, early.get(), "early child mutated before failure is restored");
        assertEquals(2, late.get(), "failing child is restored too");
    }

    private static void testListBackedClipsStructuralRoundTrip()
    {
        ValueInt previousShape = BBSSettings.keyframeDefaultShape;
        ValueInt previousDuration = BBSSettings.duration;

        try
        {
            BBSSettings.keyframeDefaultShape = new ValueInt("keyframe_default_shape", 0);
            BBSSettings.duration = new ValueInt("duration", 30);

            IFactory<Clip, ClipFactoryData> factory = testClipFactory();
            Clips sourceCamera = new Clips("camera", factory);
            Clips targetCamera = new Clips("camera", factory);
            Clips sourceActions = new Clips("actions", factory);
            Clips targetActions = new Clips("actions", factory);

            sourceCamera.addClip(new IdleClip());
            sourceActions.addClip(new IdleClip());
            sourceActions.addClip(new IdleClip());

            assertClipsRoundTrip(sourceCamera, targetCamera, 1, "camera Clips list mutation");
            assertClipsRoundTrip(sourceActions, targetActions, 2, "action Clips list mutation");
        }
        finally
        {
            BBSSettings.keyframeDefaultShape = previousShape;
            BBSSettings.duration = previousDuration;
        }
    }

    private static void assertClipsRoundTrip(Clips source, Clips target, int expected, String label)
    {
        BaseType incoming = source.toData();
        BaseType current = target.toData();

        assertEquals(true, incoming instanceof ListType, label + " wire shape");
        assertEquals(true, BBSFilmCollaborationBridge.validTargetData(target, current, incoming), label + " validation");
        target.fromData(incoming);
        assertEquals(expected, target.get().size(), label + " round-trip");
    }

    private static IFactory<Clip, ClipFactoryData> testClipFactory()
    {
        return new IFactory<>()
        {
            private final Link idle = Link.bbs("idle");

            @Override
            public Link getType(Clip object)
            {
                return this.idle;
            }

            @Override
            public Clip create(Link type)
            {
                return new IdleClip();
            }

            @Override
            public ClipFactoryData getData(Clip object)
            {
                return null;
            }

            @Override
            public ClipFactoryData getData(Link type)
            {
                return null;
            }

            @Override
            public Collection<Link> getKeys()
            {
                return List.of(this.idle);
            }
        };
    }

    private static BBSFilmMutation mutation(List<String> path, byte[] data)
    {
        return new BBSFilmMutation(BBSFilmMutationKind.SET, path, data, BBSFilmRefreshHint.VALUE);
    }

    private static List<String> repeatedSegments(int count)
    {
        String[] values = new String[count];

        Arrays.fill(values, "x");

        return List.of(values);
    }

    private static List<Integer> repeatedIntegers(int count)
    {
        Integer[] values = new Integer[count];

        for (int i = 0; i < count; i++)
        {
            values[i] = i;
        }

        return List.of(values);
    }

    private static List<BBSFilmKeyframeSelection> repeatedKeyframeSelections(int count)
    {
        List<BBSFilmKeyframeSelection> values = new ArrayList<>(count);

        for (int i = 0; i < count; i++)
        {
            values.add(new BBSFilmKeyframeSelection("sheet", i));
        }

        return values;
    }

    private static void expectIllegal(Runnable runnable, String label)
    {
        try
        {
            runnable.run();
        }
        catch (IllegalArgumentException e)
        {
            return;
        }

        throw new AssertionError("Expected IllegalArgumentException: " + label);
    }

    private static void assertEquals(Object expected, Object actual, String label)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static final class TestUndoHandler extends UIFormUndoHandler
    {
        private int committedBatches;

        private TestUndoHandler(UIElement element)
        {
            super(element);
        }

        @Override
        protected void handleCommittedValues(List<BaseValue> values)
        {
            this.committedBatches += 1;
        }
    }

    private static final class ThrowOnceValueInt extends ValueInt
    {
        private boolean fail = true;

        private ThrowOnceValueInt(String id, int defaultValue)
        {
            super(id, defaultValue);
        }

        @Override
        public void fromData(BaseType data)
        {
            if (this.fail)
            {
                this.fail = false;
                throw new IllegalStateException("intentional partial apply failure");
            }

            super.fromData(data);
        }
    }
}
