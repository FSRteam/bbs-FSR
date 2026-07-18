package mchorse.bbs_mod.client.film.collaboration;

import mchorse.bbs_mod.api.client.film.BBSFilmApplyResult;
import mchorse.bbs_mod.api.client.film.BBSFilmCheckpointReason;
import mchorse.bbs_mod.api.client.film.BBSFilmCheckpointRequired;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationStatus;
import mchorse.bbs_mod.api.client.film.BBSFilmCollaborationLimits;
import mchorse.bbs_mod.api.client.film.BBSFilmEditorKind;
import mchorse.bbs_mod.api.client.film.BBSFilmEditorView;
import mchorse.bbs_mod.api.client.film.BBSFilmKeyframeSelection;
import mchorse.bbs_mod.api.client.film.BBSFilmMutation;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationBatch;
import mchorse.bbs_mod.api.client.film.BBSFilmMutationKind;
import mchorse.bbs_mod.api.client.film.BBSFilmPresence;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceClearRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmPresenceResult;
import mchorse.bbs_mod.api.client.film.BBSFilmRemotePresence;
import mchorse.bbs_mod.api.client.film.BBSFilmRefreshHint;
import mchorse.bbs_mod.api.client.film.BBSFilmServerSequenceObserveRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSession;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshot;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotApplyRequest;
import mchorse.bbs_mod.api.client.film.BBSFilmSnapshotResult;
import mchorse.bbs_mod.data.storage.DataStorage;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueData;
import mchorse.bbs_mod.settings.values.core.ValueList;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIClips;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;

/** Core-owned adapter between Film values and the stable client Addon API. */
public final class BBSFilmCollaborationBridge
{
    static final int MAX_MUTATIONS = BBSFilmCollaborationLimits.MAX_MUTATIONS;
    static final int MAX_PATH_SEGMENTS = BBSFilmCollaborationLimits.MAX_PATH_SEGMENTS;
    static final int MAX_SEGMENT_BYTES = BBSFilmCollaborationLimits.MAX_SEGMENT_UTF8_BYTES;
    static final int MAX_PATH_BYTES = BBSFilmCollaborationLimits.MAX_PATH_UTF8_BYTES;
    static final int MAX_MUTATION_BYTES = BBSFilmCollaborationLimits.MAX_MUTATION_BYTES;
    static final int MAX_BATCH_BYTES = BBSFilmCollaborationLimits.MAX_BATCH_BYTES;
    static final int MAX_SNAPSHOT_BYTES = BBSFilmCollaborationLimits.MAX_SNAPSHOT_BYTES;
    private static final long LOCAL_COALESCE_INTERVAL_NANOS = 100_000_000L;
    private static final long PRESENCE_INTERVAL_NANOS = 1_000_000_000L / 30L;
    private static final int MAX_REMOTE_PRESENCES = 128;

    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-collaboration");
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private static volatile SessionState current;

    private BBSFilmCollaborationBridge()
    {}

    public static void attach(UIFilmPanel panel, Film film)
    {
        if (panel == null || film == null)
        {
            detach(panel);
            return;
        }

        SessionState state = current;

        String filmId = film.getId();
        String documentId = filmId == null ? "" : filmId;

        if (state != null && state.panel == panel && state.film == film && state.documentId.equals(documentId))
        {
            return;
        }

        closeCurrent();

        long sessionId = NEXT_SESSION_ID.updateAndGet((value) -> value == Long.MAX_VALUE ? 1 : value + 1);
        SessionState opened = new SessionState(sessionId, panel, film);

        current = opened;
        BBSFilmCollaborationRegistry.publishSessionOpened(opened.session());
    }

    public static void detach(UIFilmPanel panel)
    {
        SessionState state = current;

        if (state != null && (panel == null || state.panel == panel))
        {
            closeCurrent();
        }
    }

    /** Idempotent disconnect/client-stop cleanup; subscriptions remain reusable. */
    public static void resetSession()
    {
        closeCurrent();
    }

    public static BBSFilmSession currentSession()
    {
        SessionState state = current;

        return state == null ? null : state.session();
    }

    public static void captureCommittedValues(UIFilmPanel panel, Collection<BaseValue> values)
    {
        SessionState state = matching(panel);

        if (state == null || state.remoteApplyDepth > 0 || values == null || values.isEmpty())
        {
            return;
        }

        List<LocalTarget> targets = new ArrayList<>();

        for (BaseValue value : values)
        {
            List<String> path = relativePath(state.film, value);

            if (path != null && path.isEmpty())
            {
                for (BaseValue child : state.film.getAll())
                {
                    targets.add(new LocalTarget(List.of(child.getId()), child));
                }
            }
            else if (path != null)
            {
                targets.add(new LocalTarget(path, value));
            }
        }

        queueLocal(state, normalizeTargets(targets));
    }

    public static void captureCommittedPaths(UIFilmPanel panel, Collection<List<String>> absolutePaths)
    {
        SessionState state = matching(panel);

        if (state == null || state.remoteApplyDepth > 0 || absolutePaths == null || absolutePaths.isEmpty())
        {
            return;
        }

        List<LocalTarget> targets = new ArrayList<>();

        for (List<String> absolutePath : absolutePaths)
        {
            List<String> relative = stripFilmRoot(state.film, absolutePath);
            BaseValue value = relative == null ? null : resolveExact(state.film, relative);

            if (relative != null && relative.isEmpty())
            {
                for (BaseValue child : state.film.getAll())
                {
                    targets.add(new LocalTarget(List.of(child.getId()), child));
                }
            }
            else if (value != null)
            {
                targets.add(new LocalTarget(relative, value));
            }
        }

        queueLocal(state, normalizeTargets(targets));
    }

    /** Force the coalesced local batch out before CAS, save, or teardown. */
    public static void flushPending(UIFilmPanel panel)
    {
        SessionState state = matching(panel);

        if (state != null)
        {
            flushPending(state);
        }
    }

    /** Sample native editor presence; changed samples are coalesced to at most 30 Hz. */
    public static void samplePresence(UIFilmPanel panel, UIContext context)
    {
        SessionState state = matching(panel);

        if (state == null)
        {
            return;
        }

        flushPendingIfDue(state, System.nanoTime());

        if (!BBSFilmCollaborationRegistry.hasSubscriptions())
        {
            return;
        }

        BBSFilmEditorKind editorKind = editorKind(panel);
        TimelineProjection timeline = timelineProjection(panel, editorKind);
        BBSFilmEditorView editorView = timeline == null
            ? fallbackEditorView(editorKind)
            : timeline.editorView;
        OptionalInt semanticCursorTick = OptionalInt.empty();
        OptionalInt semanticCursorRow = OptionalInt.empty();
        String semanticCursorSheetId = "";

        if (context != null && timeline != null && timeline.area.isInside(context))
        {
            semanticCursorTick = OptionalInt.of(Math.max(0, timeline.fromX.applyAsInt(context.mouseX)));
            int row = timeline.rowAt(context.mouseY);

            if (row >= 0)
            {
                semanticCursorRow = OptionalInt.of(row);
            }

            semanticCursorSheetId = timeline.sheetAt(context.mouseY);
        }

        int replayIndex = editorKind == BBSFilmEditorKind.NONE
            ? -1
            : panel.replayEditor.replaysList.replays.getGlobalReplayIndex();
        List<Integer> selection = new ArrayList<>();

        for (var replay : editorKind == BBSFilmEditorKind.NONE
            ? List.<Replay>of()
            : panel.replayEditor.replaysList.replays.getSelectedReplays())
        {
            int index = CollectionUtils.getIndex(state.film.replays.getList(), replay);

            if (index >= 0)
            {
                if (selection.size() < BBSFilmCollaborationLimits.MAX_SELECTED_REPLAYS)
                {
                    selection.add(index);
                }
            }
        }

        selection.sort(Integer::compareTo);
        List<BBSFilmKeyframeSelection> keyframeSelection = keyframeSelection(
            timeline,
            BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTIONS - selection.size()
        );
        BBSFilmPresence presence = new BBSFilmPresence(
            state.sessionId,
            state.revision,
            editorKind,
            editorView,
            replayIndex,
            panel.getCursor(),
            semanticCursorTick,
            semanticCursorRow,
            semanticCursorSheetId,
            selection,
            keyframeSelection
        );

        if (presence.equals(state.lastPresence))
        {
            return;
        }

        long now = System.nanoTime();

        if (state.lastPresence != null && now - state.lastPresenceNanos < PRESENCE_INTERVAL_NANOS)
        {
            return;
        }

        state.lastPresence = presence;
        state.lastPresenceNanos = now;
        BBSFilmCollaborationRegistry.publishPresence(presence);
    }

    public static void renderRemotePresence(UIFilmPanel panel, UIContext context)
    {
        SessionState state = matching(panel);

        if (state == null || state.remotePresence.isEmpty())
        {
            return;
        }

        List<BBSFilmRemotePresence> visible = state.remotePresence.values().stream()
            .filter((remote) -> presenceMatchesRevision(remote.presence().revision(), state.revision))
            .sorted(Comparator.comparing(BBSFilmRemotePresence::participantId))
            .limit(8)
            .toList();
        BBSFilmEditorKind localEditor = editorKind(panel);
        int localReplay = panel.replayEditor.replaysList.replays.getGlobalReplayIndex();
        TimelineProjection timeline = timelineProjection(panel, localEditor);

        if (timeline != null)
        {
            context.batcher.clip(timeline.area, context);

            for (BBSFilmRemotePresence remote : visible)
            {
                BBSFilmPresence presence = remote.presence();

                if (!matchesTimelineScope(presence, localEditor, timeline.editorView, localReplay))
                {
                    continue;
                }

                int color = remote.argbColor() | 0xff000000;

                renderKeyframeSelection(context, timeline, presence, color);

                if (presence.semanticCursorTick().isEmpty())
                {
                    continue;
                }

                int cursorX = timeline.toX.applyAsInt(presence.semanticCursorTick().getAsInt());

                if (cursorX >= timeline.area.x && cursorX < timeline.area.ex())
                {
                    RowProjection row = timeline.projectRow(
                        presence.semanticCursorRow(),
                        presence.semanticCursorSheetId()
                    );

                    if (row != null)
                    {
                        context.batcher.box(
                            timeline.area.x,
                            row.y,
                            timeline.area.ex(),
                            row.y + row.height,
                            Colors.setA(color, 0.16F)
                        );
                        int cursorY = row.y + row.height / 2;

                        context.batcher.box(cursorX - 3, cursorY - 3, cursorX + 4, cursorY + 4, color);
                    }

                    context.batcher.box(cursorX, timeline.area.y, cursorX + 2, timeline.area.ey(), color);
                }
            }

            context.batcher.unclip(context);
        }

        if (localEditor == BBSFilmEditorKind.REPLAY)
        {
            renderReplaySelections(state, panel, context, visible);
        }

        int x = panel.editor.area.x + 8;
        int y = panel.editor.area.y + 8;
        int lineHeight = context.batcher.getFont().getHeight() + 7;

        for (BBSFilmRemotePresence remote : visible)
        {
            BBSFilmPresence presence = remote.presence();
            int color = remote.argbColor() | 0xff000000;
            String label = truncate(remote.displayName(), 48)
                + " · " + presence.editorKind().name().toLowerCase(Locale.ROOT)
                + "/" + presence.editorView().name().toLowerCase(Locale.ROOT)
                + " · t" + presence.playheadTick()
                + (presence.replayIndex() < 0 ? "" : " · r" + presence.replayIndex())
                + selectionSummary(presence.selectedReplayIndices(), presence.selectedKeyframes());

            context.batcher.box(x, y + 2, x + 6, y + 9, color);
            context.batcher.textCard(label, x + 10, y, color, Colors.A75);
            y += lineHeight;
        }
    }

    static BBSFilmPresenceResult applyRemotePresence(String addonId, BBSFilmRemotePresence remote)
    {
        if (remote == null)
        {
            return presenceResult(null, BBSFilmCollaborationStatus.INVALID_REQUEST, "remote presence is null");
        }

        SessionState state = current;

        BBSFilmPresenceResult failure = validatePresenceSession(
            state,
            remote.presence().sessionId(),
            remote.presence().revision(),
            remote.serverSeq(),
            remote.participantId()
        );

        if (failure != null)
        {
            return failure;
        }

        PresenceKey key = new PresenceKey(addonId, remote.participantId());
        long lastPresenceSeq = state.presenceWatermarks.getOrDefault(key, -1L);

        boolean acceptedWatermark = state.clearedPresence.contains(key)
            ? BBSFilmPresenceSequence.acceptsAfterClear(lastPresenceSeq, remote.serverSeq())
            : BBSFilmPresenceSequence.accepts(lastPresenceSeq, remote.serverSeq());

        if (!acceptedWatermark)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, remote.participantId(), remote.serverSeq(), "remote presence watermark is stale");
        }

        if (!state.presenceWatermarks.containsKey(key) && state.presenceWatermarks.size() >= MAX_REMOTE_PRESENCES)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, remote.participantId(), remote.serverSeq(), "too many remote Film participants");
        }

        state.presenceWatermarks.put(key, remote.serverSeq());
        state.clearedPresence.remove(key);
        state.remotePresence.put(key, remote);

        return presenceResult(state, BBSFilmCollaborationStatus.OK, remote.participantId(), remote.serverSeq(), "");
    }

    static BBSFilmPresenceResult clearRemotePresence(String addonId, BBSFilmPresenceClearRequest request)
    {
        if (request == null)
        {
            return presenceResult(null, BBSFilmCollaborationStatus.INVALID_REQUEST, "presence clear request is null");
        }

        SessionState state = current;

        BBSFilmPresenceResult failure = validatePresenceSession(
            state,
            request.sessionId(),
            request.expectedRevision(),
            request.serverSeq(),
            request.participantId()
        );

        if (failure != null)
        {
            return failure;
        }

        PresenceKey key = new PresenceKey(addonId, request.participantId());
        long lastPresenceSeq = state.presenceWatermarks.getOrDefault(key, -1L);

        if (!BBSFilmPresenceSequence.accepts(lastPresenceSeq, request.serverSeq()))
        {
            return presenceResult(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, request.participantId(), request.serverSeq(), "presence clear watermark is stale");
        }

        if (!state.presenceWatermarks.containsKey(key) && state.presenceWatermarks.size() >= MAX_REMOTE_PRESENCES)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, request.participantId(), request.serverSeq(), "too many remote Film participants");
        }

        state.presenceWatermarks.put(key, request.serverSeq());
        state.clearedPresence.add(key);
        state.remotePresence.remove(key);

        return presenceResult(state, BBSFilmCollaborationStatus.OK, request.participantId(), request.serverSeq(), "");
    }

    static void clearAddonPresence(String addonId)
    {
        SessionState state = current;

        if (state != null)
        {
            clearAddonPresence(state, addonId);
            state.serverSequences.remove(addonId);
        }
    }

    static BBSFilmPresenceResult clearAddonPresence(String addonId, long sessionId)
    {
        SessionState state = current;

        if (state == null)
        {
            return new BBSFilmPresenceResult(
                BBSFilmCollaborationStatus.NO_ACTIVE_SESSION,
                sessionId,
                -1,
                "",
                BBSFilmMutationBatch.NO_SERVER_SEQUENCE,
                "no active Film editor session"
            );
        }

        if (state.sessionId != sessionId)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.SESSION_MISMATCH, "Film session changed");
        }

        clearAddonPresence(state, addonId);

        return presenceResult(state, BBSFilmCollaborationStatus.OK, "");
    }

    private static void clearAddonPresence(SessionState state, String addonId)
    {
        state.remotePresence.keySet().removeIf((key) -> key.addonId.equals(addonId));
        state.presenceWatermarks.keySet().removeIf((key) -> key.addonId.equals(addonId));
        state.clearedPresence.removeIf((key) -> key.addonId.equals(addonId));
    }

    private static BBSFilmEditorKind editorKind(UIFilmPanel panel)
    {
        return switch (panel.getPanelIndex())
        {
            case 0 -> BBSFilmEditorKind.CAMERA;
            case 1 -> BBSFilmEditorKind.REPLAY;
            case 2 -> BBSFilmEditorKind.ACTION;
            default -> BBSFilmEditorKind.NONE;
        };
    }

    private static TimelineProjection timelineProjection(UIFilmPanel panel, BBSFilmEditorKind editorKind)
    {
        if (editorKind == BBSFilmEditorKind.CAMERA || editorKind == BBSFilmEditorKind.ACTION)
        {
            UIClips clips = editorKind == BBSFilmEditorKind.CAMERA
                ? panel.cameraEditor.clips
                : panel.actionEditor.clips;

            if (clips == null || !clips.isVisible())
            {
                return null;
            }

            return new TimelineProjection(
                clips.area,
                clips::fromGraphX,
                clips::toGraphX,
                BBSFilmEditorView.CLIP_TIMELINE,
                clips,
                null
            );
        }

        if (editorKind == BBSFilmEditorKind.REPLAY
            && panel.replayEditor.keyframeEditor != null
            && panel.replayEditor.keyframeEditor.view != null)
        {
            UIKeyframes keyframes = panel.replayEditor.keyframeEditor.view;

            if (!keyframes.isVisible())
            {
                return null;
            }

            BBSFilmEditorView editorView = keyframes.getGraph() == keyframes.getDopeSheet()
                ? BBSFilmEditorView.KEYFRAME_DOPE_SHEET
                : BBSFilmEditorView.KEYFRAME_GRAPH;

            return new TimelineProjection(
                keyframes.graphArea,
                (x) -> (int) Math.round(keyframes.fromGraphX(x)),
                keyframes::toGraphX,
                editorView,
                null,
                keyframes
            );
        }

        return null;
    }

    private static BBSFilmEditorView fallbackEditorView(BBSFilmEditorKind editorKind)
    {
        return switch (editorKind)
        {
            case NONE -> BBSFilmEditorView.NONE;
            case CAMERA, ACTION -> BBSFilmEditorView.CLIP_TIMELINE;
            case REPLAY -> BBSFilmEditorView.REPLAY_LIST;
        };
    }

    private static List<BBSFilmKeyframeSelection> keyframeSelection(
        TimelineProjection timeline,
        int maximum
    )
    {
        if (timeline == null || timeline.keyframes == null || maximum <= 0)
        {
            return List.of();
        }

        List<BBSFilmKeyframeSelection> selection = new ArrayList<>();
        Set<BBSFilmKeyframeSelection> unique = new HashSet<>();
        Set<String> sheets = new HashSet<>();
        int sheetBytes = 0;

        for (UIKeyframeSheet sheet : timeline.keyframes.getGraph().getSheets())
        {
            String sheetId = sheet.id;
            int encodedBytes = validSheetId(sheetId)
                ? sheetId.getBytes(StandardCharsets.UTF_8).length
                : -1;

            if (encodedBytes < 0 || !sheet.selection.hasAny())
            {
                continue;
            }

            if (!sheets.contains(sheetId))
            {
                if (sheets.size() >= BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTION_SHEETS
                    || sheetBytes + encodedBytes > BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTION_SHEET_UTF8_BYTES)
                {
                    continue;
                }

                sheets.add(sheetId);
                sheetBytes += encodedBytes;
            }

            for (Integer keyframeIndex : sheet.selection.getIndices())
            {
                if (keyframeIndex == null || keyframeIndex < 0 || keyframeIndex >= sheet.channel.getKeyframes().size())
                {
                    continue;
                }

                BBSFilmKeyframeSelection selected = new BBSFilmKeyframeSelection(sheetId, keyframeIndex);

                if (unique.add(selected))
                {
                    selection.add(selected);

                    if (selection.size() >= maximum)
                    {
                        break;
                    }
                }
            }

            if (selection.size() >= maximum)
            {
                break;
            }
        }

        selection.sort(Comparator
            .comparing(BBSFilmKeyframeSelection::sheetId)
            .thenComparingInt(BBSFilmKeyframeSelection::keyframeIndex));

        return List.copyOf(selection);
    }

    private static boolean validSheetId(String sheetId)
    {
        return sheetId != null && !sheetId.isBlank()
            && sheetId.getBytes(StandardCharsets.UTF_8).length
                <= BBSFilmCollaborationLimits.MAX_PRESENCE_SHEET_ID_UTF8_BYTES
            && sheetId.codePoints().noneMatch(Character::isISOControl);
    }

    private static void renderKeyframeSelection(
        UIContext context,
        TimelineProjection timeline,
        BBSFilmPresence presence,
        int color
    )
    {
        if (timeline.keyframes == null || presence.selectedKeyframes().isEmpty())
        {
            return;
        }

        IUIKeyframeGraph graph = timeline.keyframes.getGraph();

        for (BBSFilmKeyframeSelection selected : presence.selectedKeyframes())
        {
            UIKeyframeSheet sheet = graph.getSheet(selected.sheetId());

            if (sheet == null || selected.keyframeIndex() >= sheet.channel.getKeyframes().size())
            {
                continue;
            }

            Keyframe keyframe = sheet.channel.get(selected.keyframeIndex());

            if (keyframe == null)
            {
                continue;
            }

            int x = timeline.toX.applyAsInt(Math.max(0, Math.round(keyframe.getTick())));

            if (x < timeline.area.x || x >= timeline.area.ex())
            {
                continue;
            }

            RowProjection row = timeline.projectRow(
                OptionalInt.of(graph.getSheets().indexOf(sheet)),
                sheet.id
            );

            if (row == null)
            {
                continue;
            }

            int y = timeline.editorView == BBSFilmEditorView.KEYFRAME_DOPE_SHEET
                ? row.y + row.height / 2
                : timeline.area.y + 5;

            context.batcher.box(x - 4, y - 4, x + 5, y + 5, Colors.setA(color, 0.34F));
            context.batcher.box(x - 2, y - 2, x + 3, y + 3, color);
        }
    }

    private static void renderReplaySelections(
        SessionState state,
        UIFilmPanel panel,
        UIContext context,
        List<BBSFilmRemotePresence> visible
    )
    {
        var replayList = panel.replayEditor.replaysList.replays;

        if (replayList == null || !replayList.isVisible())
        {
            return;
        }

        context.batcher.clip(replayList.area, context);

        for (BBSFilmRemotePresence remote : visible)
        {
            BBSFilmPresence presence = remote.presence();

            if (presence.editorKind() != BBSFilmEditorKind.REPLAY)
            {
                continue;
            }

            int color = remote.argbColor() | 0xff000000;

            for (Integer replayIndex : presence.selectedReplayIndices())
            {
                if (replayIndex == null || replayIndex < 0 || replayIndex >= state.film.replays.getList().size())
                {
                    continue;
                }

                Replay replay = state.film.replays.getList().get(replayIndex);
                int y = replayList.getReplayRowY(replay);

                if (y == Integer.MIN_VALUE || y >= replayList.area.ey()
                    || y + replayList.scroll.scrollItemSize <= replayList.area.y)
                {
                    continue;
                }

                context.batcher.box(
                    replayList.area.x,
                    y,
                    replayList.area.ex(),
                    y + replayList.scroll.scrollItemSize,
                    Colors.setA(color, 0.18F)
                );
                context.batcher.box(replayList.area.x, y, replayList.area.x + 3,
                    y + replayList.scroll.scrollItemSize, color);
            }
        }

        context.batcher.unclip(context);
    }

    static boolean matchesTimelineScope(
        BBSFilmPresence presence,
        BBSFilmEditorKind localEditor,
        BBSFilmEditorView localView,
        int localReplay
    )
    {
        if (presence.editorKind() != localEditor || presence.editorView() != localView)
        {
            return false;
        }

        return localEditor != BBSFilmEditorKind.REPLAY || presence.replayIndex() == localReplay;
    }

    static boolean presenceMatchesRevision(long presenceRevision, long currentRevision)
    {
        return presenceRevision == currentRevision;
    }

    private static String selectionSummary(
        List<Integer> replaySelection,
        List<BBSFilmKeyframeSelection> keyframeSelection
    )
    {
        if (replaySelection.isEmpty() && keyframeSelection.isEmpty())
        {
            return "";
        }

        StringBuilder builder = new StringBuilder(" · sel[");
        int shown = Math.min(replaySelection.size(), 3);

        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                builder.append(',');
            }

            builder.append('r').append(replaySelection.get(i));
        }

        int remaining = replaySelection.size() - shown;

        if (!keyframeSelection.isEmpty())
        {
            if (shown > 0)
            {
                builder.append(',');
            }

            BBSFilmKeyframeSelection first = keyframeSelection.get(0);

            builder.append('k').append(first.keyframeIndex());
            remaining += keyframeSelection.size() - 1;
        }

        if (remaining > 0)
        {
            builder.append(",+").append(remaining);
        }

        return builder.append(']').toString();
    }

    private static String truncate(String text, int maximumCodePoints)
    {
        int count = text.codePointCount(0, text.length());

        if (count <= maximumCodePoints)
        {
            return text;
        }

        return text.substring(0, text.offsetByCodePoints(0, maximumCodePoints - 1)) + "…";
    }

    static BBSFilmSnapshotResult requestSnapshot(long sessionId)
    {
        SessionState state = current;

        if (state == null)
        {
            return new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.NO_ACTIVE_SESSION, null, "no active Film editor session");
        }

        if (state.sessionId != sessionId)
        {
            return new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.SESSION_MISMATCH, null, "Film session changed");
        }

        state.panel.flushFilmCollaborationEdits();
        state = current;

        if (state == null || state.sessionId != sessionId)
        {
            return new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.SESSION_MISMATCH, null, "Film session changed while flushing local edits");
        }

        byte[] encoded = encode(state.film.toData());

        if (encoded == null)
        {
            return new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.INTERNAL_ERROR, null, "could not encode Film snapshot");
        }

        if (encoded.length > MAX_SNAPSHOT_BYTES)
        {
            return new BBSFilmSnapshotResult(BBSFilmCollaborationStatus.LIMIT_EXCEEDED, null, "Film snapshot exceeds the API size limit");
        }

        return new BBSFilmSnapshotResult(
            BBSFilmCollaborationStatus.OK,
            new BBSFilmSnapshot(state.sessionId, state.revision, encoded),
            ""
        );
    }

    static BBSFilmApplyResult applyRemote(String addonId, BBSFilmMutationBatch batch)
    {
        SessionState state = current;

        if (state != null)
        {
            /* Local values change before the render-time undo batch is published.
             * Flush first so the incoming baseRevision cannot overwrite an edit
             * that is already visible in the same Film instance. */
            state.panel.flushFilmCollaborationEdits();
            state = current;
        }

        BBSFilmApplyResult sessionFailure = validateSession(state, addonId, batch == null ? 0 : batch.sessionId(), batch == null ? -1 : batch.baseRevision(), batch == null ? -1 : batch.serverSeq());

        if (sessionFailure != null)
        {
            return sessionFailure;
        }

        if (batch == null || batch.localOpId() < 0 || batch.mutations().isEmpty())
        {
            return result(state, BBSFilmCollaborationStatus.INVALID_REQUEST, 0, batch == null ? -1 : batch.serverSeq(), "mutation batch is empty or has an invalid operation id");
        }

        if (batch.mutations().size() > MAX_MUTATIONS)
        {
            return result(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, 0, batch.serverSeq(), "too many mutations in one batch");
        }

        List<DecodedMutation> decoded = new ArrayList<>(batch.mutations().size());
        List<List<String>> paths = new ArrayList<>(batch.mutations().size());
        long totalBytes = 0;
        boolean invalidateUndo = false;
        BBSFilmRefreshHint refresh = BBSFilmRefreshHint.NONE;

        for (BBSFilmMutation mutation : batch.mutations())
        {
            String pathError = validatePath(mutation == null ? null : mutation.pathSegments());

            if (pathError != null)
            {
                return result(state, BBSFilmCollaborationStatus.INVALID_PATH, 0, batch.serverSeq(), pathError);
            }

            byte[] bytes = mutation.encodedBbsData();

            if (bytes.length == 0 || bytes.length > MAX_MUTATION_BYTES)
            {
                return result(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, 0, batch.serverSeq(), "mutation data is empty or too large");
            }

            totalBytes += bytes.length;

            if (totalBytes > MAX_BATCH_BYTES)
            {
                return result(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, 0, batch.serverSeq(), "mutation batch is too large");
            }

            List<String> path = mutation.pathSegments();
            BaseValue target = resolveExact(state.film, path);

            if (target == null)
            {
                return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, batch.serverSeq(), "mutation path no longer exists");
            }

            boolean subtree = target instanceof BaseValueGroup;

            if ((mutation.kind() == BBSFilmMutationKind.REPLACE_SUBTREE) != subtree)
            {
                return result(state, BBSFilmCollaborationStatus.INVALID_PATH, 0, batch.serverSeq(), "mutation kind does not match its target");
            }

            BaseType value = decode(bytes);

            BaseType currentData = target.toData();

            if (value == null || !validTargetData(target, currentData, value))
            {
                return result(state, BBSFilmCollaborationStatus.INVALID_DATA, 0, batch.serverSeq(), "mutation contains invalid BBS data");
            }

            BBSFilmRefreshHint safeRefresh = stronger(mutation.refreshHint(), refreshHint(path, target));

            decoded.add(new DecodedMutation(target, value, safeRefresh));
            paths.add(path);
            refresh = stronger(refresh, safeRefresh);
            invalidateUndo = invalidateUndo || mutation.kind() == BBSFilmMutationKind.REPLACE_SUBTREE;
        }

        if (hasOverlappingPaths(paths))
        {
            return result(state, BBSFilmCollaborationStatus.INVALID_PATH, 0, batch.serverSeq(), "mutation paths overlap or are duplicated");
        }

        MapType filmBackup;

        try
        {
            filmBackup = state.film.toData().asMap();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] failed to capture an atomic Film backup", e);
            return result(state, BBSFilmCollaborationStatus.INTERNAL_ERROR, 0, batch.serverSeq(), "could not capture Film rollback state");
        }

        Film film = state.film;
        BBSFilmAtomicApply.Result atomic;

        state.remoteApplyDepth += 1;

        try
        {
            atomic = BBSFilmAtomicApply.run(film, filmBackup, () ->
            {
                for (DecodedMutation mutation : decoded)
                {
                    mutation.target.fromData(mutation.value);
                }
            });
        }
        finally
        {
            state.remoteApplyDepth -= 1;
        }

        if (!atomic.applied())
        {
            LOGGER.error("[bbs-film-collaboration] failed to apply a validated mutation batch", atomic.applyFailure());

            if (!atomic.restored())
            {
                LOGGER.error("[bbs-film-collaboration] failed to restore Film after mutation failure", atomic.restoreFailure());
                invalidateSession(state);

                return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, batch.serverSeq(), "mutation apply and rollback failed; Film session was invalidated");
            }

            state.panel.refreshFilmCollaboration(BBSFilmRefreshHint.STRUCTURE, true, true, List.of());

            return result(state, BBSFilmCollaborationStatus.INTERNAL_ERROR, 0, batch.serverSeq(), "mutation apply failed and was rolled back");
        }

        state.revision = BBSFilmCoreRevision.next(state.revision);
        clearPresenceForRevisionChange(state);
        state.serverSequences.put(addonId, batch.serverSeq());
        state.panel.refreshFilmCollaboration(refresh, false, invalidateUndo, paths);

        return result(state, BBSFilmCollaborationStatus.OK, decoded.size(), batch.serverSeq(), "");
    }

    static BBSFilmApplyResult applySnapshot(String addonId, BBSFilmSnapshotApplyRequest request)
    {
        SessionState state = current;

        if (state != null)
        {
            state.panel.flushFilmCollaborationEdits();
            state = current;
        }

        BBSFilmApplyResult sessionFailure = validateSnapshotSession(state, addonId, request == null ? 0 : request.sessionId(), request == null ? -1 : request.expectedRevision(), request == null ? -1 : request.serverSeq());

        if (sessionFailure != null)
        {
            return sessionFailure;
        }

        byte[] bytes = request.encodedBbsData();

        if (bytes.length == 0 || bytes.length > MAX_SNAPSHOT_BYTES)
        {
            return result(state, BBSFilmCollaborationStatus.LIMIT_EXCEEDED, 0, request.serverSeq(), "snapshot data is empty or too large");
        }

        BaseType decoded = decode(bytes);

        if (!(decoded instanceof MapType map))
        {
            return result(state, BBSFilmCollaborationStatus.INVALID_DATA, 0, request.serverSeq(), "snapshot is not encoded Film map data");
        }

        MapType backup;

        try
        {
            backup = state.film.toData().asMap();
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.error("[bbs-film-collaboration] failed to capture a snapshot rollback state", e);
            return result(state, BBSFilmCollaborationStatus.INTERNAL_ERROR, 0, request.serverSeq(), "could not capture Film rollback state");
        }

        Film film = state.film;
        BBSFilmAtomicApply.Result atomic;

        state.remoteApplyDepth += 1;

        try
        {
            atomic = BBSFilmAtomicApply.run(film, backup, () -> film.fromData(map));
        }
        finally
        {
            state.remoteApplyDepth -= 1;
        }

        if (!atomic.applied())
        {
            LOGGER.error("[bbs-film-collaboration] failed to apply a validated Film snapshot", atomic.applyFailure());

            if (!atomic.restored())
            {
                LOGGER.error("[bbs-film-collaboration] failed to restore Film after snapshot failure", atomic.restoreFailure());
                invalidateSession(state);

                return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, request.serverSeq(), "snapshot apply and rollback failed; Film session was invalidated");
            }

            state.panel.refreshFilmCollaboration(BBSFilmRefreshHint.STRUCTURE, true, true, List.of());

            return result(state, BBSFilmCollaborationStatus.INTERNAL_ERROR, 0, request.serverSeq(), "snapshot apply failed and was rolled back");
        }

        state.revision = BBSFilmCoreRevision.next(state.revision);
        state.remotePresence.clear();
        state.presenceWatermarks.clear();
        state.clearedPresence.clear();
        state.serverSequences.put(addonId, request.serverSeq());
        state.panel.refreshFilmCollaboration(BBSFilmRefreshHint.STRUCTURE, true, true, List.of());

        return result(state, BBSFilmCollaborationStatus.OK, 1, request.serverSeq(), "");
    }

    static BBSFilmApplyResult observeServerSequence(String addonId, BBSFilmServerSequenceObserveRequest request)
    {
        SessionState state = current;

        if (state != null)
        {
            state.panel.flushFilmCollaborationEdits();
            state = current;
        }

        BBSFilmApplyResult sessionFailure = validateSession(
            state,
            addonId,
            request == null ? 0 : request.sessionId(),
            request == null ? -1 : request.expectedRevision(),
            request == null ? -1 : request.serverSeq()
        );

        if (sessionFailure != null)
        {
            return sessionFailure;
        }

        state.serverSequences.put(addonId, request.serverSeq());

        return result(state, BBSFilmCollaborationStatus.OK, 0, request.serverSeq(), "");
    }

    static String validatePath(List<String> path)
    {
        if (path == null)
        {
            return "mutation path is null";
        }

        if (path.isEmpty() || path.size() > MAX_PATH_SEGMENTS)
        {
            return "mutation path must contain 1.." + MAX_PATH_SEGMENTS + " segments";
        }

        int total = 0;

        for (String segment : path)
        {
            if (segment == null || segment.isEmpty())
            {
                return "mutation path contains an empty segment";
            }

            int bytes = segment.getBytes(StandardCharsets.UTF_8).length;

            if (bytes > MAX_SEGMENT_BYTES)
            {
                return "mutation path segment is too large";
            }

            total += bytes;

            if (total > MAX_PATH_BYTES)
            {
                return "mutation path is too large";
            }
        }

        return null;
    }

    static boolean isPrefix(List<String> ancestor, List<String> child)
    {
        if (ancestor.size() > child.size())
        {
            return false;
        }

        for (int i = 0; i < ancestor.size(); i++)
        {
            if (!ancestor.get(i).equals(child.get(i)))
            {
                return false;
            }
        }

        return true;
    }

    private static void queueLocal(SessionState state, List<LocalTarget> targets)
    {
        if (targets.isEmpty() || current != state || state.remoteApplyDepth > 0)
        {
            return;
        }

        long now = System.nanoTime();

        for (LocalTarget target : targets)
        {
            state.pendingLocal.offer(target.path, target.value, now);
        }
    }

    private static void flushPendingIfDue(SessionState state, long nowNanos)
    {
        if (state.pendingLocal.isDue(nowNanos))
        {
            flushPending(state);
        }
    }

    private static void flushPending(SessionState state)
    {
        if (current != state || state.remoteApplyDepth > 0 || state.pendingLocal.isEmpty())
        {
            return;
        }

        BBSFilmLocalBatchBuffer.Batch<BaseValue> pending = state.pendingLocal.drain();
        List<LocalTarget> targets = new ArrayList<>(pending.targets().size());

        for (BBSFilmLocalBatchBuffer.Target<BaseValue> target : pending.targets())
        {
            targets.add(new LocalTarget(target.path(), target.value()));
        }

        publishLocal(state, targets, pending.overflowed());
    }

    private static void publishLocal(
        SessionState state,
        List<LocalTarget> targets,
        boolean overflowed
    )
    {
        if ((targets.isEmpty() && !overflowed) || current != state || state.remoteApplyDepth > 0)
        {
            return;
        }

        long baseRevision = state.revision;
        long localOpId = ++state.localOpId;

        state.revision = BBSFilmCoreRevision.next(state.revision);
        clearPresenceForRevisionChange(state);

        if (!BBSFilmCollaborationRegistry.hasSubscriptions())
        {
            return;
        }

        if (overflowed || targets.size() > MAX_MUTATIONS)
        {
            requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.TOO_MANY_MUTATIONS, "local Film batch has too many mutations");
            return;
        }

        List<BBSFilmMutation> mutations = new ArrayList<>(targets.size());
        long total = 0;

        for (LocalTarget target : targets)
        {
            if (validatePath(target.path) != null)
            {
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.PATH_LIMIT_EXCEEDED, "local Film value path exceeds API limits");
                return;
            }

            BaseValue liveTarget = resolveExact(state.film, target.path);

            if (liveTarget == null)
            {
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.ENCODE_FAILED, "local Film value path became stale before flush");
                return;
            }

            BaseType data;

            try
            {
                data = liveTarget.toData();
            }
            catch (Exception | LinkageError e)
            {
                LOGGER.error("[bbs-film-collaboration] failed to serialize a local Film value", e);
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.ENCODE_FAILED, "local Film value serialization failed");
                return;
            }

            byte[] encoded = data == null ? null : encode(data);

            if (encoded == null || encoded.length == 0)
            {
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.ENCODE_FAILED, "local Film value could not be encoded");
                return;
            }

            if (encoded.length > MAX_MUTATION_BYTES)
            {
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.VALUE_TOO_LARGE, "local Film value exceeds the mutation limit");
                return;
            }

            total += encoded.length;

            if (total > MAX_BATCH_BYTES)
            {
                requireCheckpoint(state, localOpId, BBSFilmCheckpointReason.BATCH_TOO_LARGE, "local Film batch exceeds the mutation limit");
                return;
            }

            BBSFilmMutationKind kind = liveTarget instanceof BaseValueGroup
                ? BBSFilmMutationKind.REPLACE_SUBTREE
                : BBSFilmMutationKind.SET;

            mutations.add(new BBSFilmMutation(kind, target.path, encoded, refreshHint(target.path, liveTarget)));
        }

        BBSFilmCollaborationRegistry.publishLocalMutations(new BBSFilmMutationBatch(
            state.sessionId,
            baseRevision,
            localOpId,
            BBSFilmMutationBatch.NO_SERVER_SEQUENCE,
            mutations
        ));
    }

    private static void requireCheckpoint(
        SessionState state,
        long localOpId,
        BBSFilmCheckpointReason reason,
        String diagnostic
    )
    {
        LOGGER.warn("[bbs-film-collaboration] {}; checkpoint required at revision {}", diagnostic, state.revision);
        BBSFilmCollaborationRegistry.publishCheckpointRequired(new BBSFilmCheckpointRequired(
            state.sessionId,
            state.revision,
            localOpId,
            reason
        ));
    }

    private static List<LocalTarget> normalizeTargets(List<LocalTarget> input)
    {
        if (input.isEmpty())
        {
            return List.of();
        }

        input.sort(Comparator.comparingInt((LocalTarget target) -> target.path.size()).thenComparing(LocalTarget::path, BBSFilmCollaborationBridge::comparePaths));
        List<LocalTarget> output = new ArrayList<>();
        Set<List<String>> exact = new HashSet<>();

        for (LocalTarget target : input)
        {
            List<String> path = List.copyOf(target.path);

            if (!exact.add(path))
            {
                continue;
            }

            boolean covered = false;

            for (LocalTarget ancestor : output)
            {
                if (isPrefix(ancestor.path, path))
                {
                    covered = true;
                    break;
                }
            }

            if (!covered)
            {
                output.add(new LocalTarget(path, target.value));
            }
        }

        return output;
    }

    private static int comparePaths(List<String> first, List<String> second)
    {
        int count = Math.min(first.size(), second.size());

        for (int i = 0; i < count; i++)
        {
            int compared = first.get(i).compareTo(second.get(i));

            if (compared != 0)
            {
                return compared;
            }
        }

        return Integer.compare(first.size(), second.size());
    }

    private static List<String> relativePath(Film film, BaseValue value)
    {
        if (value == null)
        {
            return null;
        }

        List<String> reversed = new ArrayList<>();
        BaseValue currentValue = value;

        while (currentValue != null && currentValue != film)
        {
            String id = currentValue.getId();

            if (id == null || id.isEmpty())
            {
                return null;
            }

            reversed.add(id);
            currentValue = currentValue.getParent();
        }

        if (currentValue != film)
        {
            return null;
        }

        List<String> path = new ArrayList<>(reversed.size());

        for (int i = reversed.size() - 1; i >= 0; i--)
        {
            path.add(reversed.get(i));
        }

        return path;
    }

    private static List<String> stripFilmRoot(Film film, List<String> absolutePath)
    {
        if (absolutePath == null)
        {
            return null;
        }

        String rootId = film.getId();

        if (rootId == null || rootId.isEmpty())
        {
            return List.copyOf(absolutePath);
        }

        if (absolutePath.isEmpty() || !rootId.equals(absolutePath.get(0)))
        {
            return null;
        }

        return List.copyOf(absolutePath.subList(1, absolutePath.size()));
    }

    private static BaseValue resolveExact(Film film, List<String> relativePath)
    {
        BaseValue currentValue = film;

        for (String segment : relativePath)
        {
            if (!(currentValue instanceof BaseValueGroup group))
            {
                return null;
            }

            currentValue = group.get(segment);

            if (currentValue == null)
            {
                return null;
            }
        }

        return currentValue;
    }

    private static BBSFilmRefreshHint refreshHint(List<String> path, BaseValue value)
    {
        if (value instanceof ValueList)
        {
            return BBSFilmRefreshHint.STRUCTURE;
        }

        if (!path.isEmpty() && path.get(0).equals("replays"))
        {
            return value instanceof BaseValueGroup ? BBSFilmRefreshHint.STRUCTURE : BBSFilmRefreshHint.REPLAY;
        }

        if (!path.isEmpty() && path.get(0).equals("camera"))
        {
            return BBSFilmRefreshHint.TIMELINE;
        }

        return value instanceof BaseValueGroup ? BBSFilmRefreshHint.STRUCTURE : BBSFilmRefreshHint.VALUE;
    }

    private static BBSFilmRefreshHint stronger(BBSFilmRefreshHint first, BBSFilmRefreshHint second)
    {
        return refreshRank(first) >= refreshRank(second) ? first : second;
    }

    private static int refreshRank(BBSFilmRefreshHint hint)
    {
        return switch (hint)
        {
            case NONE -> 0;
            case VALUE -> 1;
            case TIMELINE -> 2;
            case REPLAY -> 3;
            case STRUCTURE -> 4;
        };
    }

    static boolean validTargetData(BaseValue target, BaseType currentData, BaseType value)
    {
        return target instanceof ValueData
            || currentData == null
            || currentData.getTypeId() == value.getTypeId();
    }

    private static boolean hasOverlappingPaths(List<List<String>> paths)
    {
        for (int i = 0; i < paths.size(); i++)
        {
            for (int j = i + 1; j < paths.size(); j++)
            {
                if (isPrefix(paths.get(i), paths.get(j)) || isPrefix(paths.get(j), paths.get(i)))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static byte[] encode(BaseType type)
    {
        try
        {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            DataStorage.writeToStream(output, type);

            return output.toByteArray();
        }
        catch (Exception e)
        {
            LOGGER.error("[bbs-film-collaboration] failed to encode BBS data", e);
            return null;
        }
    }

    private static BaseType decode(byte[] bytes)
    {
        if (!BBSFilmEncodedDataValidator.isValid(bytes))
        {
            return null;
        }

        try
        {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            BaseType decoded = DataStorage.readFromStream(input);

            return input.available() == 0 ? decoded : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static BBSFilmApplyResult validateSession(SessionState state, String addonId, long sessionId, long expectedRevision, long serverSeq)
    {
        BBSFilmApplyResult failure = validateSessionIdentity(state, sessionId, expectedRevision, serverSeq);

        if (failure != null)
        {
            return failure;
        }

        if (!BBSFilmServerSequence.accepts(state.serverSequences.getOrDefault(addonId, -1L), serverSeq))
        {
            return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, serverSeq, "server sequence is stale, duplicated or has a gap");
        }

        return null;
    }

    private static BBSFilmApplyResult validateSnapshotSession(SessionState state, String addonId, long sessionId, long expectedRevision, long serverSeq)
    {
        BBSFilmApplyResult failure = validateSessionIdentity(state, sessionId, expectedRevision, serverSeq);

        if (failure != null)
        {
            return failure;
        }

        if (!BBSFilmServerSequence.acceptsSnapshot(state.serverSequences.getOrDefault(addonId, -1L), serverSeq))
        {
            return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, serverSeq, "snapshot server sequence is stale");
        }

        return null;
    }

    private static BBSFilmApplyResult validateSessionIdentity(SessionState state, long sessionId, long expectedRevision, long serverSeq)
    {
        if (state == null)
        {
            return new BBSFilmApplyResult(BBSFilmCollaborationStatus.NO_ACTIVE_SESSION, sessionId, -1, 0, serverSeq, "no active Film editor session");
        }

        if (state.sessionId != sessionId)
        {
            return result(state, BBSFilmCollaborationStatus.SESSION_MISMATCH, 0, serverSeq, "Film session changed");
        }

        if (expectedRevision != state.revision)
        {
            return result(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, 0, serverSeq, "Film revision changed");
        }

        return null;
    }

    private static BBSFilmPresenceResult validatePresenceSession(
        SessionState state,
        long sessionId,
        long expectedRevision,
        long serverSeq,
        String participantId
    )
    {
        if (state == null)
        {
            return new BBSFilmPresenceResult(
                BBSFilmCollaborationStatus.NO_ACTIVE_SESSION,
                sessionId,
                -1,
                participantId,
                serverSeq,
                "no active Film editor session"
            );
        }

        if (state.sessionId != sessionId)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.SESSION_MISMATCH, participantId, serverSeq, "Film session changed");
        }

        if (state.revision != expectedRevision)
        {
            return presenceResult(state, BBSFilmCollaborationStatus.RESYNC_REQUIRED, participantId, serverSeq, "Film revision changed");
        }

        return null;
    }

    private static void clearPresenceForRevisionChange(SessionState state)
    {
        /* Never manufacture a current-revision overlay from an older semantic
         * projection. The next bounded 30 Hz presence sample can repopulate it
         * after the collaboration revision has caught up. */
        state.remotePresence.clear();
    }

    private static BBSFilmApplyResult result(SessionState state, BBSFilmCollaborationStatus status, int applied, long serverSeq, String message)
    {
        return new BBSFilmApplyResult(status, state.sessionId, state.revision, applied, serverSeq, message);
    }

    private static BBSFilmPresenceResult presenceResult(SessionState state, BBSFilmCollaborationStatus status, String message)
    {
        return presenceResult(state, status, "", BBSFilmMutationBatch.NO_SERVER_SEQUENCE, message);
    }

    private static BBSFilmPresenceResult presenceResult(
        SessionState state,
        BBSFilmCollaborationStatus status,
        String participantId,
        long serverSeq,
        String message
    )
    {
        return new BBSFilmPresenceResult(
            status,
            state == null ? 0 : state.sessionId,
            state == null ? -1 : state.revision,
            participantId,
            serverSeq,
            message
        );
    }

    private static SessionState matching(UIFilmPanel panel)
    {
        SessionState state = current;

        return state != null && state.panel == panel && state.film == panel.getData() ? state : null;
    }

    private static void closeCurrent()
    {
        SessionState state = current;

        if (state == null)
        {
            return;
        }

        Throwable failure = null;

        try
        {
            flushPending(state);
        }
        catch (RuntimeException | Error exception)
        {
            failure = exception;
        }

        try
        {
            BBSFilmCollaborationRegistry.publishSessionClosing(state.session());
        }
        catch (RuntimeException | Error exception)
        {
            failure = appendFailure(failure, exception);
        }
        finally
        {
            /* A throwing flush/listener must never retain a disconnected Film
             * identity. Do not erase a newer session installed re-entrantly. */
            if (current == state)
            {
                current = null;
            }
        }

        try
        {
            BBSFilmCollaborationRegistry.publishSessionClosed(state.sessionId);
        }
        catch (RuntimeException | Error exception)
        {
            failure = appendFailure(failure, exception);
        }

        rethrowUnchecked(failure);
    }

    private static Throwable appendFailure(Throwable failure, Throwable next)
    {
        if (failure == null)
        {
            return next;
        }

        if (failure != next)
        {
            failure.addSuppressed(next);
        }

        return failure;
    }

    private static void rethrowUnchecked(Throwable failure)
    {
        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        else if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static void invalidateSession(SessionState state)
    {
        if (current == state)
        {
            current = null;
            BBSFilmCollaborationRegistry.publishSessionClosed(state.sessionId);
        }
    }

    private record LocalTarget(List<String> path, BaseValue value)
    {}

    private record DecodedMutation(BaseValue target, BaseType value, BBSFilmRefreshHint refreshHint)
    {}

    private record PresenceKey(String addonId, String participantId)
    {}

    private record RowProjection(int y, int height)
    {}

    private record TimelineProjection(
        Area area,
        IntUnaryOperator fromX,
        IntUnaryOperator toX,
        BBSFilmEditorView editorView,
        UIClips clips,
        UIKeyframes keyframes
    )
    {
        private int rowAt(int mouseY)
        {
            if (this.clips != null)
            {
                return this.clips.fromLayerY(mouseY);
            }

            if (this.keyframes != null)
            {
                IUIKeyframeGraph graph = this.keyframes.getGraph();
                UIKeyframeSheet sheet = graph.getSheet(mouseY);

                if (sheet == null && this.editorView == BBSFilmEditorView.KEYFRAME_GRAPH)
                {
                    sheet = graph.getLastSheet();
                }

                return graph.getSheets().indexOf(sheet);
            }

            return -1;
        }

        private String sheetAt(int mouseY)
        {
            if (this.keyframes == null)
            {
                return "";
            }

            IUIKeyframeGraph graph = this.keyframes.getGraph();
            UIKeyframeSheet sheet = graph.getSheet(mouseY);

            if (sheet == null && this.editorView == BBSFilmEditorView.KEYFRAME_GRAPH)
            {
                sheet = graph.getLastSheet();
            }

            return sheet != null && validSheetId(sheet.id) ? sheet.id : "";
        }

        private RowProjection projectRow(OptionalInt semanticRow, String sheetId)
        {
            if (this.clips != null)
            {
                if (semanticRow.isEmpty())
                {
                    return null;
                }

                int row = semanticRow.getAsInt();
                int y = this.clips.toLayerY(row);
                int height = Math.max(1, y - this.clips.toLayerY(row + 1));

                return new RowProjection(y, height);
            }

            if (this.keyframes == null)
            {
                return null;
            }

            IUIKeyframeGraph graph = this.keyframes.getGraph();
            boolean hasStableSheetId = sheetId != null && !sheetId.isEmpty();
            UIKeyframeSheet sheet = hasStableSheetId ? graph.getSheet(sheetId) : null;

            if (sheet == null && !hasStableSheetId && semanticRow.isPresent())
            {
                int row = semanticRow.getAsInt();

                if (row >= 0 && row < graph.getSheets().size())
                {
                    sheet = graph.getSheets().get(row);
                }
            }

            if (sheet == null)
            {
                return null;
            }

            if (graph == this.keyframes.getDopeSheet())
            {
                UIKeyframeDopeSheet dopeSheet = this.keyframes.getDopeSheet();

                return new RowProjection(
                    dopeSheet.getDopeSheetY(sheet),
                    Math.max(1, (int) Math.ceil(dopeSheet.getTrackHeight()))
                );
            }

            return new RowProjection(this.area.y, Math.max(1, this.area.h));
        }
    }

    private static final class SessionState
    {
        private final long sessionId;
        private final UIFilmPanel panel;
        private final Film film;
        private final String documentId;
        private volatile long revision;
        private long localOpId;
        private final Map<String, Long> serverSequences = new LinkedHashMap<>();
        private int remoteApplyDepth;
        private final BBSFilmLocalBatchBuffer<BaseValue> pendingLocal = new BBSFilmLocalBatchBuffer<>(MAX_MUTATIONS, LOCAL_COALESCE_INTERVAL_NANOS);
        private BBSFilmPresence lastPresence;
        private long lastPresenceNanos;
        private final Map<PresenceKey, BBSFilmRemotePresence> remotePresence = new LinkedHashMap<>();
        private final Map<PresenceKey, Long> presenceWatermarks = new LinkedHashMap<>();
        private final Set<PresenceKey> clearedPresence = new HashSet<>();

        private SessionState(long sessionId, UIFilmPanel panel, Film film)
        {
            this.sessionId = sessionId;
            this.panel = panel;
            this.film = film;
            String id = film.getId();

            this.documentId = id == null ? "" : id;
        }

        private BBSFilmSession session()
        {
            return new BBSFilmSession(this.sessionId, this.documentId, this.revision);
        }
    }
}
