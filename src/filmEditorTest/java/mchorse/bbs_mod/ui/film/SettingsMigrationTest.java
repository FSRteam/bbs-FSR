package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.base.BaseValue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class SettingsMigrationTest
{
    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        legacyCategoriesKeepCurrentValues();
        legacySpaceFillsOnlyAnAbsentRememberedSpace();
        glowMigrationKeepsTheCurrentChoice();
        replaySectionMigrationRevealsOnce();
        registeredSettingsConsumeTheMigratedData();
        System.out.println("SettingsMigrationTest passed");
    }

    private static void legacyCategoriesKeepCurrentValues()
    {
        MapType root = new MapType();
        MapType editor = new MapType();
        editor.putInt("speed", 7);
        editor.putInt("duration", 45);
        editor.putInt("preview_custom_width", 2560);
        root.put("editor", editor);

        MapType dc = new MapType();
        dc.putBool("enabled", false);
        root.put("dc", dc);

        check(BBSSettings.migrateLegacySettings(root), "legacy category migration was not reported");
        check(root.getMap("camera").getInt("speed") == 7, "camera setting did not migrate");
        check(root.getMap("timeline").getInt("duration") == 45, "timeline setting did not migrate");
        check(root.getMap("viewport").getInt("preview_custom_width") == 2560, "viewport setting did not migrate");
        check(!root.getMap("misc").getBool("damage_control"), "misc setting did not migrate");

        root.getMap("camera").putInt("speed", 9);
        BBSSettings.migrateLegacySettings(root);
        check(root.getMap("camera").getInt("speed") == 9, "migration overwrote a current value");
    }

    private static void legacySpaceFillsOnlyAnAbsentRememberedSpace()
    {
        for (boolean local : new boolean[] {false, true})
        {
            for (String category : new String[] {"appearance", "transformation"})
            {
                MapType root = new MapType();
                MapType source = new MapType();
                source.putBool(category.equals("appearance") ? "transform_local_default" : "default_local", local);
                root.put(category, source);
                BBSSettings.migrateLegacySettings(root);
                check(root.getMap("transformation").getInt("transform_space") == (local ? 0 : 3),
                    "the old initial-frame setting did not migrate from " + category);
                check(!BBSSettings.migrateLegacySettings(root), "the same space settings migrated twice");
            }
        }

        MapType root = new MapType();
        MapType transformation = new MapType();
        transformation.putBool("default_local", true);
        transformation.putInt("transform_space", 4);
        root.put("transformation", transformation);
        BBSSettings.migrateLegacySettings(root);
        check(root.getMap("transformation").getInt("transform_space") == 4,
            "legacy local preference replaced an explicit remembered WORLD space");
    }

    private static void glowMigrationKeepsTheCurrentChoice()
    {
        MapType root = new MapType();
        MapType personalization = new MapType();
        personalization.putBool("overlay_gradient_border", false);
        root.put("personalization", personalization);
        BBSSettings.migrateLegacySettings(root);
        check(personalization.has("interface_glow") && !personalization.getBool("interface_glow"),
            "the old disabled panel glow did not become a disabled interface glow");
        personalization.putBool("interface_glow", true);
        check(!BBSSettings.migrateLegacySettings(root) && personalization.getBool("interface_glow"),
            "the old panel glow overwrote a current interface glow choice");
    }

    private static void replaySectionMigrationRevealsOnce()
    {
        MapType root = new MapType();
        MapType appearance = new MapType();
        ListType disabled = new ListType();
        for (String channel : new String[] {"item_head", "swimming", "grounded", "leaning", "roll", "fall", "yaw", "vX", "addon.hidden"})
        {
            disabled.addString(channel);
        }
        appearance.put("disabled_sheets", disabled);
        appearance.putBool("hotbar_filter_migrated", true);
        root.put("appearance", appearance);
        BBSSettings.migrateLegacySettings(root);
        check(disabled.size() == 3 && appearance.getBool("replay_sections_filter_migrated"),
            "curated replay sections were left hidden or unrelated filters were removed");
        check(disabled.getString(0).equals("yaw") && disabled.getString(1).equals("vX")
                && disabled.getString(2).equals("addon.hidden"),
            "replay section migration changed velocity/yaw or addon filters");
        disabled.addString("swimming");
        check(!BBSSettings.migrateLegacySettings(root) && disabled.size() == 4,
            "reloading settings removed a later manual curated-channel filter");
    }

    private static void registeredSettingsConsumeTheMigratedData()
    {
        Map<Field, Object> saved = new HashMap<>();

        try
        {
            for (Field field : BBSSettings.class.getFields())
            {
                if (BaseValue.class.isAssignableFrom(field.getType())) saved.put(field, field.get(null));
            }

            SettingsBuilder builder = new SettingsBuilder(null, "range-settings-test", null);
            BBSSettings.register(builder);
            check(BBSSettings.fov.get() == 70F && BBSSettings.duration.get() == 100
                    && BBSSettings.editorTimelineGrid.get() && BBSSettings.editorPreviewSizeMode.get() == 2
                    && BBSSettings.gizmoHoverTolerance.get() == 4 && BBSSettings.editorOrbitGizmoScale.get() == 0.75F,
                "fresh settings retained defaults replaced in the pinned FS range");
            check(BBSSettings.disabledSheets.get().equals(Set.of("yaw", "vX", "vY", "vZ")),
                "fresh settings still hide curated replay sections");
            check(BBSSettings.interfaceGlow == BBSSettings.overlayGradientBorder
                    && BBSSettings.colorPickerHsvTab == BBSSettings.hsvColorPicker,
                "legacy public settings fields own a different value from the canonical setting");
            check(!BBSSettings.colorPickerHsvTab.isVisible() && !BBSSettings.textureCellSize.isVisible()
                    && !BBSSettings.textureSort.isVisible() && !BBSSettings.recentData.isVisible(),
                "browser or picker memory is still exposed as a settings row");

            MapType root = new MapType();
            MapType transformation = new MapType();
            MapType personalization = new MapType();
            transformation.putBool("default_local", true);
            personalization.putBool("overlay_gradient_border", false);
            root.put("transformation", transformation);
            root.put("personalization", personalization);
            BBSSettings.migrateLegacySettings(root);
            builder.getConfig().fromData(root);
            check(BBSSettings.transformSpace.get() == 0 && !BBSSettings.hasInterfaceGlow()
                    && !BBSSettings.hasOverlayGradientBorder(),
                "registered settings did not read migrated space/glow values");
            MapType savedData = builder.getConfig().toData().asMap();
            check(savedData.getMap("personalization").has("interface_glow")
                    && !savedData.getMap("personalization").getBool("interface_glow"),
                "saving registered settings dropped the canonical glow value");
            BBSSettings.axesKeepScreenSize.set(false);
            float fov = BBSSettings.getFov();
            check(BBSSettings.getAxesDistanceScale(20F, fov) == 1F
                    && BBSSettings.getScreenSizeScale(20F, fov) > BBSSettings.getScreenSizeScale(10F, fov),
                "world point markers still depend on the gizmo fixed-size option");
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
        finally
        {
            for (Map.Entry<Field, Object> entry : saved.entrySet())
            {
                try
                {
                    entry.getKey().set(null, entry.getValue());
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError(exception);
                }
            }
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
