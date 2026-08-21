package mchorse.bbs_mod.api.client.dashboard;

import java.util.Objects;

/** Stable semantic ids for host-owned Dashboard controls. */
public final class BBSDashboardAnchors
{
    public static final String SETTINGS = "dashboard.settings";
    public static final String SELECTORS = "dashboard.selectors";
    public static final String MORPHING_PALETTE = "morphing.palette";
    public static final String MORPHING_DEMORPH = "morphing.demorph";
    public static final String MORPHING_FROM_MOB = "morphing.from_mob";

    private BBSDashboardAnchors() {}

    public static String panelButton(String panelId)
    {
        return "dashboard.panel_button." + requirePanelId(panelId);
    }

    public static String panelContent(String panelId)
    {
        return "dashboard.panel_content." + requirePanelId(panelId);
    }

    private static String requirePanelId(String panelId)
    {
        String id = Objects.requireNonNull(panelId, "panelId").trim();

        if (id.isEmpty())
        {
            throw new IllegalArgumentException("Dashboard panel id is blank");
        }

        return id;
    }
}
