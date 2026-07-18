package mchorse.bbs_mod.api.client.film;

/** Stable keyframe selection identity; sheet ids are native full-sheet ids, not visible row numbers. */
public record BBSFilmKeyframeSelection(String sheetId, int keyframeIndex)
{
    public BBSFilmKeyframeSelection
    {
        sheetId = BBSFilmCollaborationLimits.requireText(
            sheetId,
            BBSFilmCollaborationLimits.MAX_PRESENCE_SHEET_ID_UTF8_BYTES,
            "sheetId"
        );

        if (keyframeIndex < 0)
        {
            throw new IllegalArgumentException("keyframeIndex must be non-negative");
        }
    }
}
