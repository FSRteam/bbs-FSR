package mchorse.bbs_mod.settings.values.ui;

import mchorse.bbs_mod.data.types.MapType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Regression checks for editor-owned fallback and persisted FSR layout extensions. */
public final class EditorLayoutPersistenceTest
{
    private EditorLayoutPersistenceTest()
    {}

    public static void runAll()
    {
        unreadableBranchesKeepTheirEditorOwnership();
        freshFilmLayoutKeepsTheInspectorInItsOwnColumn();
        splitterSizesReadBothFormatsAndResetOnReload();
        customLayoutsAndMultiviewSettingsRoundTrip();

        System.out.println("EditorLayoutPersistenceTest: all checks passed");
    }

    private static void unreadableBranchesKeepTheirEditorOwnership()
    {
        check(EditorLayoutNode.fromData(null) == null, "missing data must let the owner choose its default");
        check(EditorLayoutNode.fromData(new MapType()) == null, "unknown data must not become a film layout");

        MapType split = new MapType();

        split.putString("type", EditorLayoutNode.TYPE_SPLITTER);
        split.put("first", new MapType());
        split.put("second", new EditorLayoutNode.StackNode(Arrays.asList("events", "curves"), "curves").toData());

        EditorLayoutNode restored = EditorLayoutNode.fromData(split);

        check(restored instanceof EditorLayoutNode.StackNode, "a broken side must collapse to the usable stack");
        check(((EditorLayoutNode.StackNode) restored).getActivePanelId().equals("curves"),
            "collapsing a broken side lost the active particle tab");

        split.put("second", new MapType());
        check(EditorLayoutNode.fromData(split) == null, "two broken sides must return control to the owning editor");

        MapType settingsData = new MapType();
        ValueEditorLayout settings = new ValueEditorLayout("layout");

        settingsData.putString("particle_layout", "invalid");
        settings.fromData(settingsData);

        Set<String> particleIds = bounds(settings.getParticleLayoutRoot()).keySet();

        check(particleIds.containsAll(Arrays.asList("preview", "file", "emitter", "motion", "appearance", "time", "events", "curves")),
            "unreadable particle data no longer falls back to the registered FSR particle panels");
        check(!particleIds.contains("main") && !particleIds.contains("replaysList"),
            "unreadable particle data injected film panels into the particle dock");

        check(EditorLayoutNode.copyWithReplacedLeaf(restored, "absent", new EditorLayoutNode.PanelNode("preview")) == restored,
            "the legacy leaf adapter must leave a nonmatching stack intact");
    }

    private static void freshFilmLayoutKeepsTheInspectorInItsOwnColumn()
    {
        ValueEditorLayout settings = new ValueEditorLayout("layout");
        Map<String, float[]> initial = bounds(settings.getFilmLayoutRoot());
        float[] edit = initial.get("editArea");
        float[] preview = initial.get("preview");
        float[] timeline = initial.get("main");
        float[] properties = initial.get("replayProps");
        float[] replays = initial.get("replaysList");

        near(edit[0], 0.7916667F, "the inspector must occupy the right column");
        near(edit[1], 0F, "the inspector must start at the top");
        near(edit[3], 1F, "the inspector must span preview and timeline height");
        near(preview[0], timeline[0], "preview and timeline must share a column");
        near(preview[2], timeline[2], "preview and timeline must share their width");
        near(preview[1] + preview[3], timeline[1], "timeline must follow the preview");
        near(properties[0], replays[0], "replay properties and list must share the left column");
        near(properties[1] + properties[3], replays[1], "the replay list must follow its properties");

        settings.fromData(new MapType());
        near(bounds(settings.getFilmLayoutRoot()).get("editArea")[0], edit[0],
            "an empty settings map must not apply legacy ratios to the fresh default");
    }

    private static void splitterSizesReadBothFormatsAndResetOnReload()
    {
        ValueEditorLayout settings = new ValueEditorLayout("layout");
        MapType saved = new MapType();
        MapType sizes = new MapType();

        sizes.putFloat("fraction", 0.37F);
        sizes.putInt("pixels", 312);
        sizes.putString("invalid", "wide");
        saved.put("splits", sizes);
        settings.fromData(saved);

        near(settings.getSplitSize("fraction", 0F), 0.37F, "fractional split size was lost");
        near(settings.getSplitSize("pixels", 0F), 312F, "pixel split size was lost");
        near(settings.getSplitSize("invalid", 17F), 17F, "a nonnumeric split size replaced its fallback");
        settings.setSplitSize("changed", 196F);

        MapType roundTrip = settings.toData().asMap();

        near(roundTrip.getMap("splits").getFloat("fraction"), 0.37F,
            "split sizes are not written under the FS persistence key");
        near(roundTrip.getMap("splits").getFloat("changed"), 196F,
            "a splitter's edited size did not reach persistence");

        MapType legacy = new MapType();

        legacy.put("split_sizes", sizes);
        settings.fromData(legacy);
        near(settings.getSplitSize("pixels", 0F), 312F, "legacy FSR split sizes must still load");

        settings.fromData(null);
        near(settings.getSplitSize("pixels", 29F), 29F, "reloading unreadable data kept old split sizes");
    }

    private static void customLayoutsAndMultiviewSettingsRoundTrip()
    {
        ValueEditorLayout settings = new ValueEditorLayout("layout");
        EditorLayoutNode film = new EditorLayoutNode.SplitterNode(false, 0.7F,
            new EditorLayoutNode.StackNode(Arrays.asList("preview", "preview2", "preview3", "preview4"), "preview3"),
            new EditorLayoutNode.PanelNode("main"));
        EditorLayoutNode particle = new EditorLayoutNode.StackNode(Arrays.asList("file", "events", "curves"), "events");
        MapType views = new MapType();
        MapType view = new MapType();

        view.putString("camera", "camera-a");
        views.put("film-a", view);
        settings.setFilmLayoutRoot(film);
        settings.setFilmLayoutRoot(ValueEditorLayout.FilmEditor.REPLAY, new EditorLayoutNode.PanelNode("replayProps"));
        settings.setFilmLayoutBound(ValueEditorLayout.FilmEditor.REPLAY, true);
        settings.setParticleLayoutRoot(particle);
        settings.setHiddenPanels(ValueEditorLayout.FILM, new HashSet<>(Arrays.asList("preview4")));
        settings.setDockUnlocked(ValueEditorLayout.PARTICLE, true);
        settings.setFilmViewSettings(views);

        ValueEditorLayout restored = new ValueEditorLayout("layout");

        restored.fromData(settings.toData());
        check(restored.getFilmLayoutRoot().toData().equals(film.toData()), "custom multiview dock layout changed during reload");
        check(restored.getParticleLayoutRoot().toData().equals(particle.toData()), "legacy particle panel ids changed during reload");
        check(restored.isFilmLayoutBound(ValueEditorLayout.FilmEditor.REPLAY)
                && bounds(restored.getFilmLayoutRoot(ValueEditorLayout.FilmEditor.REPLAY)).containsKey("replayProps"),
            "the bound replay layout was lost");
        check(restored.getHiddenPanels(ValueEditorLayout.FILM).contains("preview4"), "hidden auxiliary preview was lost");
        check(restored.isDockUnlocked(ValueEditorLayout.PARTICLE), "particle dock unlock state was lost");
        check(restored.getFilmViewSettings().equals(views), "per-film view settings were lost");
    }

    private static Map<String, float[]> bounds(EditorLayoutNode node)
    {
        Map<String, float[]> result = new HashMap<>();

        node.computeBounds(0F, 0F, 1F, 1F, result);

        return result;
    }

    private static void near(float actual, float expected, String message)
    {
        check(Math.abs(actual - expected) < 0.00001F, message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
