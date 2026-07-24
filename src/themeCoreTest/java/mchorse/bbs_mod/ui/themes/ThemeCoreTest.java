package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.interps.Interpolations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Executable regressions for the theme core: parsing, base chain
 * inheritance, malformed input fallbacks, legacy settings migration and
 * built-in JSON parity with the pre-theme hardcoded constants.
 */
public final class ThemeCoreTest
{
    private ThemeCoreTest()
    {}

    public static void main(String[] args) throws Exception
    {
        testFullParse();
        testMissingKeysInherit();
        testBaseChainMerge();
        testCircularBaseChain();
        testChainDepthLimit();
        testBadValuesFallBack();
        testBadJsonAndFormat();
        testUnderscoreKeysIgnored();
        testJsonNullTextureResets();
        testLegacyMigration();
        testBuiltinJsonParity();
        testSpecExampleTheme();
        testBuiltinPacks();

        System.out.println("ThemeCoreTest: all tests passed");
    }

    /**
     * Every theme shipped in the jar parses, and the bundled example is a
     * byte-for-byte copy of the docs spec sample (docs are the source of
     * truth; sync direction is docs -> resources).
     */
    private static void testBuiltinPacks() throws Exception
    {
        Path themes = Path.of("src/client/resources/assets/bbs/assets/themes");
        UITheme dark = parseFile("dark", themes.resolve("dark/theme.json"), ThemeParser.defaultDark("dark"));

        for (String id : new String[] {"light", "example", "amber"})
        {
            UITheme theme = parseFile(id, themes.resolve(id + "/theme.json"), dark);

            assertTrue(theme != null, "built-in theme " + id + " parses");
        }

        Path docsExample = Path.of("docs/theme-spec/example-theme/theme.json");

        if (Files.isRegularFile(docsExample))
        {
            assertEquals(-1L, Files.mismatch(docsExample, themes.resolve("example/theme.json")), "bundled example matches docs byte for byte");
        }

        /* The demo theme must differ from dark on all three layers */
        UITheme amber = parseFile("amber", themes.resolve("amber/theme.json"), dark);

        assertTrue(amber.accentPrimary != dark.accentPrimary, "amber recolors the accent");
        assertTrue(!amber.bevel && !amber.panelShadow && !amber.textShadow, "amber flattens the style toggles");
        assertTrue(amber.overlay.easing != dark.overlay.easing || amber.overlay.duration != dark.overlay.duration, "amber changes motion");
    }

    private static void testFullParse()
    {
        MapType map = DataToString.mapFromString("""
            {
                "format": 1,
                "name": "Night",
                "author": "Tester",
                "description": "desc",
                "variant": "light",
                "colors": {
                    "surface": { "chrome": "#10131A", "base": "#151926", "raised": "#1B2030", "deep": "#0D101A", "divider": "#2C3450" },
                    "accent": { "primary": "#6C8CFF" },
                    "text": { "primary": "#F2F4FF", "muted": "#9AA3C0" },
                    "state": { "positive": "#59D940", "negative": "#FF4059", "warning": "#FFBB00", "active": "#4A7DFF", "highlight": "#DDE4FF", "cursor": "#80FF0000" }
                },
                "style": { "text_shadow": false, "bevel": false, "panel_shadow": false },
                "textures": { "icons": "assets:themes/night/icons.png", "background": "assets:themes/night/bg.png" },
                "motion": {
                    "enabled": false,
                    "speed": 2.0,
                    "overlay": { "enabled": false, "duration": 300, "easing": "back_out" }
                }
            }
            """);

        UITheme theme = ThemeParser.parse("night", map, ThemeParser.defaultDark("night"));

        assertEquals("night", theme.id, "id");
        assertEquals("Night", theme.name, "name");
        assertEquals("Tester", theme.author, "author");
        assertEquals("desc", theme.description, "description");
        assertTrue(theme.light, "variant light");

        assertEquals(0xff10131a, theme.surfaceChrome, "surface chrome");
        assertEquals(0xff151926, theme.surfaceBase, "surface base");
        assertEquals(0xff1b2030, theme.surfaceRaised, "surface raised");
        assertEquals(0xff0d101a, theme.surfaceDeep, "surface deep");
        assertEquals(0xff2c3450, theme.surfaceDivider, "surface divider");
        assertEquals(0xff6c8cff, theme.accentPrimary, "accent primary");
        assertEquals(0xfff2f4ff, theme.textPrimary, "text primary");
        assertEquals(0xff9aa3c0, theme.textMuted, "text muted");
        assertEquals(0xff4a7dff, theme.stateActive, "state active");

        /* 8-digit colors keep their explicit alpha */
        assertEquals(0x80ff0000, theme.stateCursor, "state cursor with alpha");

        assertTrue(!theme.textShadow && !theme.bevel && !theme.panelShadow, "style toggles off");

        assertEquals(Link.create("assets:themes/night/icons.png"), theme.iconsAtlas, "icons atlas");
        assertEquals(Link.create("assets:themes/night/bg.png"), theme.background, "background");

        assertTrue(!theme.motionEnabled, "motion disabled");
        assertEquals(2F, theme.motionSpeed, "motion speed");
        assertTrue(!theme.overlay.enabled, "overlay disabled");
        assertEquals(300, theme.overlay.duration, "overlay duration");
        assertEquals(Interpolations.BACK_OUT, theme.overlay.easing, "overlay easing");

        /* Entries absent from the document inherit the fallback */
        assertEquals(150, theme.notification.duration, "notification inherited");
    }

    private static void testMissingKeysInherit()
    {
        MapType map = DataToString.mapFromString("{ \"format\": 1, \"colors\": { \"accent\": { \"primary\": \"#123456\" } } }");
        UITheme fallback = ThemeParser.defaultDark("base");
        UITheme theme = ThemeParser.parse("mini", map, fallback);

        assertEquals(0xff123456, theme.accentPrimary, "overridden accent");
        assertEquals(fallback.surfaceChrome, theme.surfaceChrome, "inherited chrome");
        assertEquals(fallback.textPrimary, theme.textPrimary, "inherited text");
        assertEquals(fallback.overlay, theme.overlay, "inherited overlay entry");
        assertEquals("mini", theme.name, "name defaults to id, not inherited");
        assertEquals("", theme.author, "author defaults to empty");
    }

    private static void testBaseChainMerge()
    {
        Map<String, MapType> themes = new HashMap<>();

        themes.put("child", DataToString.mapFromString("{ \"format\": 1, \"base\": \"parent\", \"colors\": { \"accent\": { \"primary\": \"#111111\" } } }"));
        themes.put("parent", DataToString.mapFromString("{ \"format\": 1, \"variant\": \"light\", \"colors\": { \"surface\": { \"chrome\": \"#222222\" } } }"));

        UITheme theme = ThemeParser.resolveChain("child", loader(themes));

        assertEquals("child", theme.id, "chain id");
        assertEquals(0xff111111, theme.accentPrimary, "child override");
        assertEquals(0xff222222, theme.surfaceChrome, "parent override");
        assertTrue(theme.light, "variant inherited from parent");
        assertEquals(ThemeParser.defaultDark("x").surfaceBase, theme.surfaceBase, "defaults terminate the chain");
    }

    private static void testCircularBaseChain()
    {
        Map<String, MapType> themes = new HashMap<>();

        themes.put("a", DataToString.mapFromString("{ \"format\": 1, \"base\": \"b\", \"colors\": { \"accent\": { \"primary\": \"#0000AA\" } } }"));
        themes.put("b", DataToString.mapFromString("{ \"format\": 1, \"base\": \"a\" }"));

        UITheme theme = ThemeParser.resolveChain("a", loader(themes));

        assertTrue(theme != null, "circular chain still resolves");
        assertEquals(0xff0000aa, theme.accentPrimary, "circular chain keeps own values");
    }

    private static void testChainDepthLimit()
    {
        Map<String, MapType> themes = new HashMap<>();

        for (int i = 0; i < 8; i++)
        {
            String base = i == 7 ? "" : ("t" + (i + 1));

            themes.put("t" + i, DataToString.mapFromString("{ \"format\": 1, \"base\": \"" + base + "\" }"));
        }

        UITheme theme = ThemeParser.resolveChain("t0", loader(themes));

        assertTrue(theme != null, "deep chain resolves via truncation");
    }

    private static void testBadValuesFallBack()
    {
        MapType map = DataToString.mapFromString("""
            {
                "format": 1,
                "colors": { "accent": { "primary": "not-a-color" }, "surface": { "chrome": "#12" } },
                "motion": { "overlay": { "easing": "warp-speed", "duration": 999999 } }
            }
            """);
        UITheme fallback = ThemeParser.defaultDark("d");
        UITheme theme = ThemeParser.parse("bad", map, fallback);

        assertEquals(fallback.accentPrimary, theme.accentPrimary, "bad color keeps fallback");
        assertEquals(fallback.surfaceChrome, theme.surfaceChrome, "short color keeps fallback");
        assertEquals(Interpolations.SINE_OUT, theme.overlay.easing, "unknown easing becomes sine_out");
        assertEquals(10000, theme.overlay.duration, "duration clamped");
    }

    private static void testBadJsonAndFormat()
    {
        assertTrue(DataToString.mapFromString("{ not json !!") == null, "broken JSON parses to null");

        Map<String, MapType> themes = new HashMap<>();

        themes.put("future", DataToString.mapFromString("{ \"format\": 99 }"));
        themes.put("missing-format", DataToString.mapFromString("{ \"name\": \"x\" }"));

        assertTrue(ThemeParser.resolveChain("future", loader(themes)) == null, "unsupported format is rejected");
        assertTrue(ThemeParser.resolveChain("missing-format", loader(themes)) == null, "missing format is rejected");
        assertTrue(ThemeParser.resolveChain("nope", loader(themes)) == null, "unknown theme resolves to null");

        /* A broken base doesn't take the whole theme down */
        themes.put("okay", DataToString.mapFromString("{ \"format\": 1, \"base\": \"future\", \"colors\": { \"accent\": { \"primary\": \"#ABCDEF\" } } }"));

        UITheme theme = ThemeParser.resolveChain("okay", loader(themes));

        assertTrue(theme != null, "broken base falls back to defaults");
        assertEquals(0xffabcdef, theme.accentPrimary, "own values survive a broken base");
    }

    private static void testUnderscoreKeysIgnored()
    {
        MapType map = DataToString.mapFromString("{ \"format\": 1, \"_doc\": \"comment\", \"colors\": { \"_note\": \"hi\", \"accent\": { \"primary\": \"#654321\" } } }");
        UITheme theme = ThemeParser.parse("c", map, ThemeParser.defaultDark("c"));

        assertEquals(0xff654321, theme.accentPrimary, "underscore keys don't break parsing");
    }

    private static void testJsonNullTextureResets()
    {
        MapType map = DataToString.mapFromString("{ \"format\": 1, \"textures\": { \"icons\": null } }");
        UITheme withIcons = new UITheme.Builder("f", ThemeParser.defaultDark("f")).build();
        UITheme.Builder builder = new UITheme.Builder("f", withIcons);

        builder.iconsAtlas = Link.assets("some/icons.png");

        UITheme theme = ThemeParser.parse("f", map, builder.build());

        assertTrue(theme.iconsAtlas == null, "explicit JSON null resets an inherited texture");
    }

    private static void testLegacyMigration()
    {
        /* theme: 0 (light) becomes theme_id "light" */
        MapType root = DataToString.mapFromString("{ \"personalization\": { \"theme\": 0 } }");

        assertTrue(BBSSettings.migrateLegacySettings(root), "light migration reports a change");
        assertEquals("light", root.getMap("skins").getString("theme_id"), "theme 0 becomes light");
        assertTrue(!root.getMap("personalization").has("theme"), "legacy theme key is removed");

        /* theme: 1 (dark) becomes theme_id "dark" */
        root = DataToString.mapFromString("{ \"personalization\": { \"theme\": 1 } }");
        BBSSettings.migrateLegacySettings(root);
        assertEquals("dark", root.getMap("skins").getString("theme_id"), "theme 1 becomes dark");

        /* A customized primary color keeps working by pinning the accent */
        root = DataToString.mapFromString("{ \"personalization\": { \"primary_color\": 43690 } }");
        BBSSettings.migrateLegacySettings(root);
        assertTrue(!root.getMap("skins").getBool("accent_follows_theme", true), "custom accent unpins from theme");

        /* The default primary color keeps following the theme */
        root = DataToString.mapFromString("{ \"personalization\": { \"primary_color\": " + 0xff3242 + " } }");
        BBSSettings.migrateLegacySettings(root);
        assertTrue(!root.getMap("skins").has("accent_follows_theme"), "default accent keeps following the theme");

        /* Migration doesn't run twice */
        root = DataToString.mapFromString("{ \"personalization\": { \"theme\": 0 }, \"skins\": { \"theme_id\": \"custom\" } }");
        BBSSettings.migrateLegacySettings(root);
        assertEquals("custom", root.getMap("skins").getString("theme_id"), "existing theme_id wins");
    }

    /**
     * The built-in JSON documents must match the constants that used to be
     * hardcoded in BBSSettings/Colors, byte for byte. Values are written out
     * here on purpose: a "quick recolor" of the built-ins should fail this.
     */
    private static void testBuiltinJsonParity() throws Exception
    {
        UITheme dark = parseFile("dark", Path.of("src/client/resources/assets/bbs/assets/themes/dark/theme.json"), ThemeParser.defaultDark("dark"));

        assertTrue(!dark.light, "dark variant");
        assertEquals(0xff111316, dark.surfaceChrome, "dark chrome == DARK_CHROME_SURFACE");
        assertEquals(0xff171a1f, dark.surfaceBase, "dark base == DARK_BASE_SURFACE");
        assertEquals(0xff1d2127, dark.surfaceRaised, "dark raised == DARK_RAISED_SURFACE");
        assertEquals(0xff0f1217, dark.surfaceDeep, "dark deep == DARK_DEEP_SURFACE");
        assertEquals(0xff30353d, dark.surfaceDivider, "dark divider == DARK_DIVIDER_COLOR");
        assertEquals(0xffff3242, dark.accentPrimary, "dark accent == DEFAULT_PRIMARY_COLOR");
        assertEquals(0xffffffff, dark.textPrimary, "dark text == Colors.WHITE");
        assertEquals(0xffaaaaaa, dark.textMuted, "dark muted == Colors.LIGHTER_GRAY");
        assertEquals(0xff59d940, dark.statePositive, "dark positive == Colors.GREEN opaque");
        assertEquals(0xffff4059, dark.stateNegative, "dark negative == Colors.RED opaque");
        assertEquals(0xffffbb00, dark.stateWarning, "dark warning == Colors.INACTIVE opaque");
        assertEquals(0xff0088ff, dark.stateActive, "dark active == Colors.ACTIVE opaque");
        assertEquals(0xffddddff, dark.stateHighlight, "dark highlight == Colors.HIGHLIGHT opaque");
        assertEquals(0xff57f52a, dark.stateCursor, "dark cursor == Colors.CURSOR");
        assertTrue(dark.textShadow && dark.bevel && dark.panelShadow, "dark style toggles on");
        assertTrue(dark.iconsAtlas == null && dark.background == null, "dark has no texture overrides");
        assertTrue(dark.motionEnabled, "dark motion enabled");

        /* The dark JSON must equal the code-level defaults field by field,
         * so the fallback of last resort renders identically */
        UITheme codeDark = ThemeParser.defaultDark("dark");

        assertEquals(codeDark.surfaceChrome, dark.surfaceChrome, "code default chrome parity");
        assertEquals(codeDark.stateCursor, dark.stateCursor, "code default cursor parity");
        assertEquals(codeDark.overlay.duration, dark.overlay.duration, "code default overlay parity");
        assertEquals(codeDark.scrollbar.easing, dark.scrollbar.easing, "code default scrollbar easing parity");

        UITheme light = parseFile("light", Path.of("src/client/resources/assets/bbs/assets/themes/light/theme.json"), dark);

        assertTrue(light.light, "light variant");
        assertTrue(!light.textShadow, "light disables text shadow (mirrors the old hardcoded light branch)");
        assertEquals(0xffe6e9ef, light.surfaceChrome, "light chrome == LIGHT_CHROME_SURFACE");
        assertEquals(0xfff1f4f8, light.surfaceBase, "light base == LIGHT_BASE_SURFACE");
        assertEquals(0xfff8fafd, light.surfaceRaised, "light raised == LIGHT_RAISED_SURFACE");
        assertEquals(0xffdee4ed, light.surfaceDeep, "light deep == LIGHT_DEEP_SURFACE");
        assertEquals(0xffc2cbd8, light.surfaceDivider, "light divider == LIGHT_DIVIDER_COLOR");
        assertEquals(dark.accentPrimary, light.accentPrimary, "light inherits dark accent (status quo)");
        assertEquals(dark.textPrimary, light.textPrimary, "light inherits dark text (status quo)");
    }

    /** The spec's example theme must stay parseable against this parser. */
    private static void testSpecExampleTheme() throws Exception
    {
        Path path = Path.of("docs/theme-spec/example-theme/theme.json");

        if (!Files.isRegularFile(path))
        {
            System.out.println("ThemeCoreTest: docs/theme-spec example not found, skipping");

            return;
        }

        UITheme theme = parseFile("example-theme", path, ThemeParser.defaultDark("example-theme"));

        assertEquals(0xff6c8cff, theme.accentPrimary, "example theme accent");
        assertTrue(!theme.light, "example theme is a dark variant");
        assertEquals(Interpolations.BACK_OUT, theme.notification.easing, "example theme notification easing");
        assertTrue(theme.iconsAtlas == null, "example theme ships no textures");
    }

    private static UITheme parseFile(String id, Path path, UITheme fallback) throws Exception
    {
        MapType map = DataToString.mapFromString(Files.readString(path));

        assertTrue(map != null, "JSON at " + path + " parses");
        assertEquals(1, map.getInt("format", -1), "format of " + path);

        return ThemeParser.parse(id, map, fallback);
    }

    private static Function<String, MapType> loader(Map<String, MapType> themes)
    {
        return themes::get;
    }

    private static void assertEquals(Object expected, Object actual, String message)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            String expectedText = expected instanceof Integer i ? "0x" + Integer.toHexString(i) : String.valueOf(expected);
            String actualText = actual instanceof Integer i ? "0x" + Integer.toHexString(i) : String.valueOf(actual);

            throw new AssertionError(message + ": expected " + expectedText + ", got " + actualText);
        }
    }

    private static void assertTrue(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
