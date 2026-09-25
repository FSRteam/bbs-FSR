package mchorse.bbs_mod.api.client.dashboard;

import java.util.Objects;

/** Stable semantic ids for host-owned Dashboard controls. */
public final class BBSDashboardAnchors
{
    public static final String SETTINGS = "dashboard.settings";
    public static final String SELECTORS = "dashboard.selectors";
    public static final String MORPHING_PALETTE = "morphing.palette";
    public static final String MORPHING_PALETTE_LIST = "morphing.palette_list";
    public static final String MORPHING_PALETTE_EDITOR = "morphing.palette_editor";
    public static final String MORPHING_DEMORPH = "morphing.demorph";
    public static final String MORPHING_FROM_MOB = "morphing.from_mob";

    public static final String FILM_SELECTION = "film.selection";
    public static final String FILM_RECORDER = "film.recorder";
    public static final String FILM_PREVIEW = "film.preview";
    public static final String FILM_DUPLICATE = "film.duplicate";
    public static final String FILM_OPEN_MENU = "film.open_menu";
    public static final String FILM_OPEN_CAMERA_EDITOR = "film.open_camera_editor";
    public static final String FILM_OPEN_REPLAY_EDITOR = "film.open_replay_editor";
    public static final String FILM_OPEN_ACTION_EDITOR = "film.open_action_editor";
    public static final String FILM_CAMERA_EDITOR = "film.camera_editor";
    public static final String FILM_REPLAY_EDITOR = "film.replay_editor";
    public static final String FILM_ACTION_EDITOR = "film.action_editor";
    public static final String FILM_CAMERA_TIMELINE = "film.camera_timeline";
    public static final String FILM_REPLAY_TIMELINE = "film.replay_timeline";
    public static final String FILM_ACTION_TIMELINE = "film.action_timeline";

    public static final String MODEL_BLOCKS_LIST = "model_blocks.list";
    public static final String MODEL_BLOCKS_PICK_EDIT = "model_blocks.pick_edit";
    public static final String MODEL_BLOCKS_TOGGLE_ENABLED = "model_blocks.toggle_enabled";
    public static final String MODEL_BLOCKS_TOGGLE_SHADOW = "model_blocks.toggle_shadow";
    public static final String MODEL_BLOCKS_TOGGLE_GLOBAL = "model_blocks.toggle_global";
    public static final String MODEL_BLOCKS_TOGGLE_LOOK_AT = "model_blocks.toggle_look_at";

    public static final String PARTICLES_RENDERER = "particles.renderer";
    public static final String PARTICLES_SELECTION = "particles.selection";
    public static final String PARTICLES_DOCK = "particles.dock";
    public static final String PARTICLES_LOCK_LAYOUT = "particles.lock_layout";
    public static final String PARTICLES_LAYOUT_PRESETS = "particles.layout_presets";
    public static final String PARTICLES_PLAY_PAUSE = "particles.play_pause";

    public static final String MODEL_EDITOR_GENERAL = "model_editor.general";
    public static final String MODEL_EDITOR_RENDERER = "model_editor.renderer";
    public static final String MODEL_EDITOR_SPLITTER = "model_editor.splitter";
    public static final String TEXTURES_PICKER = "textures.picker";

    public static final String AUDIO_PICK = "audio.pick";
    public static final String AUDIO_PLAY_PAUSE = "audio.play_pause";
    public static final String AUDIO_SAVE_COLORS = "audio.save_colors";
    public static final String AUDIO_EDITOR = "audio.editor";

    public static final String GRAPH_CANVAS = "graph.canvas";
    public static final String GRAPH_EXPRESSION = "graph.expression";
    public static final String GRAPH_HELP = "graph.help";

    public static final String PLUGINS_LIST = "plugins.list";
    public static final String PLUGINS_RESCAN = "plugins.rescan";
    public static final String PLUGINS_OPEN_FOLDER = "plugins.open_folder";
    public static final String PLUGINS_INSTALL = "plugins.install";
    public static final String PLUGINS_AUTO_APPLY = "plugins.auto_apply";

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
