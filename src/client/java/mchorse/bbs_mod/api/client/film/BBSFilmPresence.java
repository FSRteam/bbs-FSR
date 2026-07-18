package mchorse.bbs_mod.api.client.film;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Revision-scoped native editor presence. Cursor columns use Film-global
 * ticks; rows are semantic clip layers or keyframe-sheet rows. The full sheet
 * id remains authoritative when a receiver's visible row layout differs.
 */
public record BBSFilmPresence(
    long sessionId,
    long revision,
    BBSFilmEditorKind editorKind,
    BBSFilmEditorView editorView,
    int replayIndex,
    int playheadTick,
    OptionalInt semanticCursorTick,
    OptionalInt semanticCursorRow,
    String semanticCursorSheetId,
    List<Integer> selectedReplayIndices,
    List<BBSFilmKeyframeSelection> selectedKeyframes
)
{
    /** Source-compatible projection for addons compiled before semantic sheet presence was added. */
    public BBSFilmPresence(
        long sessionId,
        long revision,
        BBSFilmEditorKind editorKind,
        int replayIndex,
        int playheadTick,
        OptionalInt semanticCursorTick,
        OptionalInt semanticCursorRow,
        List<Integer> selectedReplayIndices
    )
    {
        this(
            sessionId,
            revision,
            editorKind,
            defaultView(editorKind),
            replayIndex,
            playheadTick,
            semanticCursorTick,
            semanticCursorRow,
            "",
            selectedReplayIndices,
            List.of()
        );
    }

    public BBSFilmPresence
    {
        BBSFilmCollaborationLimits.requireSession(sessionId);
        BBSFilmCollaborationLimits.requireRevision(revision, "revision");
        editorKind = Objects.requireNonNull(editorKind, "editorKind");
        editorView = Objects.requireNonNull(editorView, "editorView");

        if (replayIndex < -1)
        {
            throw new IllegalArgumentException("replayIndex must be -1 or non-negative");
        }

        if (playheadTick < 0)
        {
            throw new IllegalArgumentException("playheadTick must be non-negative");
        }

        semanticCursorTick = Objects.requireNonNull(semanticCursorTick, "semanticCursorTick");
        semanticCursorRow = Objects.requireNonNull(semanticCursorRow, "semanticCursorRow");
        semanticCursorSheetId = BBSFilmCollaborationLimits.requireOptionalText(
            semanticCursorSheetId,
            BBSFilmCollaborationLimits.MAX_PRESENCE_SHEET_ID_UTF8_BYTES,
            "semanticCursorSheetId"
        );
        List<Integer> checkedSelection = Objects.requireNonNull(selectedReplayIndices, "selectedReplayIndices");
        List<BBSFilmKeyframeSelection> checkedKeyframes = Objects.requireNonNull(selectedKeyframes, "selectedKeyframes");

        if (semanticCursorTick.isPresent() && semanticCursorTick.getAsInt() < 0)
        {
            throw new IllegalArgumentException("semanticCursorTick must be non-negative when present");
        }

        if (semanticCursorRow.isPresent() && (semanticCursorRow.getAsInt() < 0
            || semanticCursorRow.getAsInt() > BBSFilmCollaborationLimits.MAX_PRESENCE_CURSOR_ROW))
        {
            throw new IllegalArgumentException("semanticCursorRow is outside its semantic row limit");
        }

        if (checkedSelection.size() > BBSFilmCollaborationLimits.MAX_SELECTED_REPLAYS
            || checkedKeyframes.size() > BBSFilmCollaborationLimits.MAX_SELECTED_KEYFRAMES
            || checkedSelection.size() + checkedKeyframes.size() > BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTIONS)
        {
            throw new IllegalArgumentException("presence selection exceeds its entry limit");
        }

        HashSet<Integer> unique = new HashSet<>(checkedSelection.size());

        for (Integer selected : checkedSelection)
        {
            if (selected == null || selected < 0 || !unique.add(selected))
            {
                throw new IllegalArgumentException("selectedReplayIndices must be unique and non-negative");
            }
        }

        selectedReplayIndices = List.copyOf(checkedSelection);

        Set<BBSFilmKeyframeSelection> uniqueKeyframes = new HashSet<>(checkedKeyframes.size());
        Set<String> sheetIds = new HashSet<>();
        int sheetUtf8Bytes = 0;

        for (BBSFilmKeyframeSelection selected : checkedKeyframes)
        {
            if (selected == null || !uniqueKeyframes.add(selected))
            {
                throw new IllegalArgumentException("selectedKeyframes must be unique and non-null");
            }

            if (sheetIds.add(selected.sheetId()))
            {
                sheetUtf8Bytes += selected.sheetId().getBytes(StandardCharsets.UTF_8).length;
            }
        }

        if (sheetIds.size() > BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTION_SHEETS
            || sheetUtf8Bytes > BBSFilmCollaborationLimits.MAX_PRESENCE_SELECTION_SHEET_UTF8_BYTES)
        {
            throw new IllegalArgumentException("selectedKeyframes sheet dictionary exceeds its limit");
        }

        if (editorKind == BBSFilmEditorKind.NONE && (editorView != BBSFilmEditorView.NONE
            || replayIndex != -1 || semanticCursorTick.isPresent() || semanticCursorRow.isPresent()
            || !semanticCursorSheetId.isEmpty() || !selectedReplayIndices.isEmpty() || !checkedKeyframes.isEmpty()))
        {
            throw new IllegalArgumentException("NONE editor presence must be an empty projection");
        }

        if (!semanticCursorSheetId.isEmpty()
            && editorView != BBSFilmEditorView.KEYFRAME_DOPE_SHEET
            && editorView != BBSFilmEditorView.KEYFRAME_GRAPH)
        {
            throw new IllegalArgumentException("semanticCursorSheetId requires a keyframe editor view");
        }

        if (!checkedKeyframes.isEmpty()
            && editorView != BBSFilmEditorView.KEYFRAME_DOPE_SHEET
            && editorView != BBSFilmEditorView.KEYFRAME_GRAPH)
        {
            throw new IllegalArgumentException("selectedKeyframes requires a keyframe editor view");
        }

        selectedKeyframes = List.copyOf(checkedKeyframes);
    }

    private static BBSFilmEditorView defaultView(BBSFilmEditorKind editorKind)
    {
        return switch (Objects.requireNonNull(editorKind, "editorKind"))
        {
            case NONE -> BBSFilmEditorView.NONE;
            case CAMERA, ACTION -> BBSFilmEditorView.CLIP_TIMELINE;
            case REPLAY -> BBSFilmEditorView.REPLAY_LIST;
        };
    }
}
