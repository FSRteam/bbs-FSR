package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.colors.Oklab;

import java.lang.reflect.Field;

/** Secondary-color rendering and legacy split-count migration exercise production code. */
final class SecondarySurfaceTest
{
    private SecondarySurfaceTest()
    {}

    static void run() throws ReflectiveOperationException
    {
        ValueInt previousColor = BBSSettings.secondaryColor;
        ValueFloat previousBrightness = BBSSettings.backgroundBrightness;
        Field current = ThemeManager.class.getDeclaredField("current");
        Field currentId = ThemeManager.class.getDeclaredField("currentId");
        current.setAccessible(true);
        currentId.setAccessible(true);
        Object previousTheme = current.get(null);
        Object previousId = currentId.get(null);
        String selectedId = BBSSettings.themeId == null ? "dark" : BBSSettings.themeId.get();
        if (selectedId == null || selectedId.isEmpty()) selectedId = "dark";
        current.set(null, ThemeParser.defaultDark(selectedId));
        currentId.set(null, selectedId);

        try
        {
            BBSSettings.secondaryColor = new ValueInt("secondary", 0x171b22);
            BBSSettings.backgroundBrightness = new ValueFloat("brightness", 1F);
            UITheme theme = ThemeManager.current();

            check(BBSSettings.baseSurface() == theme.surfaceBase && BBSSettings.chromeSurface() == theme.surfaceChrome
                    && BBSSettings.textColor() == theme.textPrimary && BBSSettings.isLightTheme() == theme.light,
                "default secondary color changed the selected FSR theme");
            BBSSettings.secondaryColor.set(0x183d66);
            Oklab lab = new Oklab();
            float sunken = lab.set(BBSSettings.sunkenSurface()).l;
            float deep = lab.set(BBSSettings.deepSurface()).l;
            float chrome = lab.set(BBSSettings.chromeSurface()).l;
            float base = lab.set(BBSSettings.baseSurface()).l;
            float raised = lab.set(BBSSettings.raisedSurface()).l;
            float divider = lab.set(BBSSettings.dividerColor()).l;

            check(sunken < deep && deep < chrome && chrome < base && base < raised && raised < divider,
                "a custom tint lost the upstream perceptual surface ordering");
            check((BBSSettings.baseSurface() & Colors.RGB) == 0x183d66,
                "the user's secondary color is not the actual base surface");
            BBSSettings.secondaryColor.set(0xf0e7d4);
            check(BBSSettings.isLightTheme(), "a light custom surface did not switch white icon conversion");
            check((BBSSettings.textColor() & Colors.RGB) < 0x808080 && !BBSSettings.textShadow(),
                "a light custom surface kept an unreadable light foreground");
            BBSSettings.secondaryColor.set(0x171b22);
            check(BBSSettings.baseSurface() == theme.surfaceBase && BBSSettings.textColor() == theme.textPrimary,
                "resetting secondary color did not restore the selected theme");
            assertOverlayCountMigration();
            assertPerformanceCategoryMigration();
        }
        finally
        {
            BBSSettings.secondaryColor = previousColor;
            BBSSettings.backgroundBrightness = previousBrightness;
            current.set(null, previousTheme);
            currentId.set(null, previousId);
        }

        System.out.println("SecondarySurfaceTest: all tests passed");
    }

    private static void assertOverlayCountMigration()
    {
        MapType root = new MapType();
        MapType recording = new MapType();

        recording.putInt("pose_transform_overlays", 3);
        recording.putInt("transform_overlays", 5);
        root.put("recording", recording);
        BBSSettings.migrateLegacySettings(root);
        check(root.getMap("recording").getInt("pose_overlays") == 3
                && root.getMap("recording").getInt("transform_overlays") == 5,
            "legacy combined overlay count did not fill missing split count or overwrote an explicit one");
    }

    private static void assertPerformanceCategoryMigration()
    {
        MapType root = new MapType();
        MapType viewport = new MapType();
        MapType appearance = new MapType();
        MapType misc = new MapType();
        MapType performance = new MapType();

        viewport.putBool("profiler_overlay", true);
        viewport.putBool("frame_pose_cache", false);
        appearance.putBool("freeze_models", true);
        appearance.putBool("list_model_preview", false);
        misc.putBool("translucency_queue", true);
        misc.putBool("multiskin_multithreaded", false);
        misc.putBool("frustum_culling", false);
        performance.putBool("profiler_overlay", false);
        root.put("viewport", viewport);
        root.put("appearance", appearance);
        root.put("misc", misc);
        root.put("performance", performance);

        BBSSettings.migrateLegacySettings(root);
        MapType migrated = root.getMap("performance");

        check(!migrated.getBool("profiler_overlay") && !migrated.getBool("frame_pose_cache")
                && migrated.getBool("freeze_models") && !migrated.getBool("list_model_preview")
                && migrated.getBool("translucency_queue") && !migrated.getBool("multiskin_multithreaded")
                && !migrated.getBool("frustum_culling"),
            "performance-category migration lost an old choice or overwrote an explicit current value");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
