package mchorse.bbs_mod.api.client.dashboard;

import java.util.List;

/** Stable public ids for host-owned Dashboard panels. */
public final class BBSDashboardPanelIds
{
    public static final String MORPHING = "morphing";
    public static final String FILM = "film";
    public static final String MODEL_BLOCKS = "model_blocks";
    public static final String PARTICLES = "particles";
    public static final String MODEL_EDITOR = "model_editor";
    public static final String TEXTURES = "textures";
    public static final String AUDIO = "audio";
    public static final String GRAPH = "graph";
    public static final String PLUGINS = "plugins";

    /** Ordered exactly like the built-in Dashboard taskbar. */
    public static final List<String> BUILT_IN = List.of(
        MORPHING,
        FILM,
        MODEL_BLOCKS,
        PARTICLES,
        MODEL_EDITOR,
        TEXTURES,
        AUDIO,
        GRAPH,
        PLUGINS
    );

    private BBSDashboardPanelIds() {}

    public static boolean isBuiltIn(String id)
    {
        return BUILT_IN.contains(id);
    }
}
