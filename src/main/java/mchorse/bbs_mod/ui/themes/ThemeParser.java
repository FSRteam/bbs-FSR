package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Parses theme documents ({@link MapType}) into immutable {@link UITheme}s.
 *
 * <p>Parsing never throws to the caller: any broken key keeps its inherited
 * value with a log warning, and a theme whose document can't be loaded at all
 * resolves to null (the manager falls back to the built-in dark theme).
 * Unknown keys are ignored, which makes {@code _} prefixed keys usable as
 * comments. The format contract lives in {@code docs/theme-spec/README.md}.
 */
public class ThemeParser
{
    public static final int FORMAT = 1;
    public static final int MAX_CHAIN = 4;

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeParser.class);

    private ThemeParser()
    {}

    /**
     * Resolve a theme by walking its base chain (deepest base first). The
     * loader returns the raw document for a theme id, or null when it can't
     * be loaded. Every chain implicitly terminates on the code-level dark
     * defaults, so a resolved theme is never partially initialized.
     *
     * @return the resolved theme, or null when the requested theme itself
     *         can't be loaded or has an unsupported format
     */
    public static UITheme resolveChain(String id, Function<String, MapType> loader)
    {
        List<String> ids = new ArrayList<>();
        List<MapType> maps = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = id;

        while (current != null)
        {
            if (!visited.add(current))
            {
                LOGGER.warn("Theme \"{}\": circular base chain at \"{}\", ignoring the rest of the chain", id, current);

                break;
            }

            if (ids.size() >= MAX_CHAIN)
            {
                LOGGER.warn("Theme \"{}\": base chain is deeper than {}, ignoring the rest of the chain", id, MAX_CHAIN);

                break;
            }

            MapType map = loader.apply(current);

            if (map == null || !hasSupportedFormat(current, map))
            {
                if (ids.isEmpty())
                {
                    return null;
                }

                LOGGER.warn("Theme \"{}\": base theme \"{}\" can't be loaded, continuing with defaults", id, current);

                break;
            }

            ids.add(current);
            maps.add(map);

            String base = map.getString("base", "");

            current = base.isEmpty() ? null : base;
        }

        if (ids.isEmpty())
        {
            return null;
        }

        UITheme theme = defaultDark(id);

        for (int i = ids.size() - 1; i >= 0; i--)
        {
            theme = parse(i == 0 ? id : ids.get(i), maps.get(i), theme);
        }

        return theme;
    }

    private static boolean hasSupportedFormat(String id, MapType map)
    {
        int format = map.getInt("format", -1);

        if (format != FORMAT)
        {
            LOGGER.warn("Theme \"{}\": unsupported format {} (this version supports format {})", id, format, FORMAT);

            return false;
        }

        return true;
    }

    /**
     * Parse a single theme document on top of a fully resolved fallback
     * theme. Missing keys keep the fallback's values; meta keys (name,
     * author, description) aren't inherited and use fixed defaults instead.
     */
    public static UITheme parse(String id, MapType map, UITheme fallback)
    {
        UITheme.Builder builder = new UITheme.Builder(id, fallback);

        builder.name = map.getString("name", id);
        builder.author = map.getString("author", "");
        builder.description = map.getString("description", "");

        if (map.has("variant"))
        {
            builder.light = "light".equals(map.getString("variant"));
        }

        MapType colors = map.getMap("colors");
        MapType surface = colors.getMap("surface");
        MapType accent = colors.getMap("accent");
        MapType text = colors.getMap("text");
        MapType state = colors.getMap("state");

        builder.surfaceChrome = color(id, surface, "chrome", fallback.surfaceChrome);
        builder.surfaceBase = color(id, surface, "base", fallback.surfaceBase);
        builder.surfaceRaised = color(id, surface, "raised", fallback.surfaceRaised);
        builder.surfaceDeep = color(id, surface, "deep", fallback.surfaceDeep);
        builder.surfaceDivider = color(id, surface, "divider", fallback.surfaceDivider);
        builder.accentPrimary = color(id, accent, "primary", fallback.accentPrimary);
        builder.textPrimary = color(id, text, "primary", fallback.textPrimary);
        builder.textMuted = color(id, text, "muted", fallback.textMuted);
        builder.statePositive = color(id, state, "positive", fallback.statePositive);
        builder.stateNegative = color(id, state, "negative", fallback.stateNegative);
        builder.stateWarning = color(id, state, "warning", fallback.stateWarning);
        builder.stateActive = color(id, state, "active", fallback.stateActive);
        builder.stateHighlight = color(id, state, "highlight", fallback.stateHighlight);
        builder.stateCursor = color(id, state, "cursor", fallback.stateCursor);
        builder.fieldFill = color(id, colors, "field_fill", fallback.fieldFill);
        builder.fieldBorder = color(id, colors, "field_border", fallback.fieldBorder);
        builder.tabActiveLine = color(id, colors, "tab_active_line", fallback.tabActiveLine);
        builder.tabActiveGradient = color(id, colors, "tab_active_gradient", fallback.tabActiveGradient);
        builder.areaTint = color(id, colors, "area_tint", fallback.areaTint);
        builder.areaTintLight = color(id, colors, "area_tint_light", fallback.areaTintLight);
        builder.dropFill = color(id, colors, "drop_fill", fallback.dropFill);
        builder.dropBorder = color(id, colors, "drop_border", fallback.dropBorder);
        builder.splitterActive = color(id, colors, "splitter_active", fallback.splitterActive);
        builder.splitterIdle = color(id, colors, "splitter_idle", fallback.splitterIdle);
        builder.shadowMuted = color(id, colors, "shadow_muted", fallback.shadowMuted);
        builder.trackpadScrub = color(id, colors, "trackpad_scrub", fallback.trackpadScrub);
        builder.notificationFill = color(id, colors, "notification_fill", fallback.notificationFill);
        builder.notificationText = color(id, colors, "notification_text", fallback.notificationText);
        builder.selectionFill = color(id, colors, "selection_fill", fallback.selectionFill);
        builder.selectionOutline = color(id, colors, "selection_outline", fallback.selectionOutline);
        builder.iconPressed = color(id, colors, "icon_pressed", fallback.iconPressed);
        builder.iconDisabled = color(id, colors, "icon_disabled", fallback.iconDisabled);
        builder.scrollbarShadow = color(id, colors, "scrollbar_shadow", fallback.scrollbarShadow);

        MapType style = map.getMap("style");
        MapType cornerRadius = style.getMap("corner_radius");

        builder.textShadow = style.getBool("text_shadow", fallback.textShadow);
        builder.bevel = style.getBool("bevel", fallback.bevel);
        builder.panelShadow = style.getBool("panel_shadow", fallback.panelShadow);
        builder.cornerChrome = cornerRadius(cornerRadius, "chrome", fallback.cornerChrome);
        builder.cornerPanel = cornerRadius(cornerRadius, "panel", fallback.cornerPanel);
        builder.cornerWidget = cornerRadius(cornerRadius, "widget", fallback.cornerWidget);

        MapType textures = map.getMap("textures");

        builder.iconsAtlas = link(textures, "icons", fallback.iconsAtlas);
        builder.background = link(textures, "background", fallback.background);
        builder.backgroundMode = backgroundMode(id, textures, fallback.backgroundMode);

        if (textures.has("background_dim"))
        {
            float dim = textures.getFloat("background_dim", Float.NaN);

            if (Float.isNaN(dim) || Float.isInfinite(dim))
            {
                LOGGER.warn("Theme \"{}\": background_dim has an invalid value, keeping {}", id, fallback.backgroundDim);
            }
            else
            {
                builder.backgroundDim = MathUtils.clamp(dim, 0F, 1F);
            }
        }

        if (map.has("decorations"))
        {
            builder.decorations = decorations(id, map.getList("decorations"));
        }

        MapType motion = map.getMap("motion");

        builder.motionEnabled = motion.getBool("enabled", fallback.motionEnabled);
        builder.motionSpeed = MathUtils.clamp(motion.getFloat("speed", fallback.motionSpeed), 0.1F, 10F);
        builder.overlay = motionEntry(id, motion, "overlay", fallback.overlay);
        builder.panelSwitch = motionEntry(id, motion, "panel_switch", fallback.panelSwitch);
        builder.hover = motionEntry(id, motion, "hover", fallback.hover);
        builder.notification = motionEntry(id, motion, "notification", fallback.notification);
        builder.contextMenu = motionEntry(id, motion, "context_menu", fallback.contextMenu);
        builder.scrollbar = motionEntry(id, motion, "scrollbar", fallback.scrollbar);
        builder.scrollSmooth = motionEntry(id, motion, "scroll_smooth", fallback.scrollSmooth);
        builder.hoverScale = motionEntry(id, motion, "hover_scale", fallback.hoverScale);
        builder.press = motionEntry(id, motion, "press", fallback.press);
        builder.layout = motionEntry(id, motion, "layout", fallback.layout);
        builder.toggle = motionEntry(id, motion, "toggle", fallback.toggle);
        builder.taskbarHide = motionEntry(id, motion, "taskbar_hide", fallback.taskbarHide);

        return builder.build();
    }

    /**
     * Parse a color string. {@code #RRGGBB} colors are normalized to opaque
     * ({@code 0xFF} alpha); consumers that need a different alpha compose it
     * via {@code BBSSettings.color(color, alpha)}.
     */
    private static int color(String id, MapType map, String key, int fallback)
    {
        if (!map.has(key))
        {
            return fallback;
        }

        String raw = map.getString(key);

        try
        {
            int color = Colors.parseWithException(raw);
            String hex = raw.startsWith("#") ? raw.substring(1) : raw;

            return hex.length() == 6 ? Colors.A100 | color : color;
        }
        catch (Exception e)
        {
            LOGGER.warn("Theme \"{}\": color \"{}\" has invalid value \"{}\", keeping the inherited color", id, key, raw);

            return fallback;
        }
    }

    private static Link link(MapType map, String key, Link fallback)
    {
        if (!map.has(key))
        {
            return fallback;
        }

        /* A JSON null (or any non-string) reads as "", i.e. an explicit reset
         * to the built-in texture */
        String raw = map.getString(key);

        return raw.isEmpty() ? null : Link.create(raw);
    }

    private static int cornerRadius(MapType map, String key, int fallback)
    {
        return MathUtils.clamp(map.getInt(key, fallback), 0, 16);
    }

    private static UITheme.BackgroundMode backgroundMode(String id, MapType textures, UITheme.BackgroundMode fallback)
    {
        if (!textures.has("background_mode"))
        {
            return fallback;
        }

        String raw = textures.getString("background_mode");

        switch (raw)
        {
            case "stretch": return UITheme.BackgroundMode.STRETCH;
            case "cover": return UITheme.BackgroundMode.COVER;
            case "tile": return UITheme.BackgroundMode.TILE;
        }

        LOGGER.warn("Theme \"{}\": unknown background_mode \"{}\", keeping {}", id, raw, fallback);

        return fallback;
    }

    /** Maximum number of decorations a theme may declare. */
    public static final int MAX_DECORATIONS = 16;

    /**
     * Parse the decoration list. Broken entries (missing texture, unknown
     * anchor) are dropped with a warning rather than guessed at; the list is
     * capped at {@link #MAX_DECORATIONS} entries.
     */
    private static List<UIThemeDecoration> decorations(String id, ListType list)
    {
        List<UIThemeDecoration> result = new ArrayList<>();

        for (int i = 0; i < list.size(); i++)
        {
            if (result.size() >= MAX_DECORATIONS)
            {
                LOGGER.warn("Theme \"{}\": more than {} decorations, ignoring the rest", id, MAX_DECORATIONS);

                break;
            }

            MapType entry = list.getMap(i);
            String texture = entry.getString("texture", "");

            if (texture.isEmpty())
            {
                LOGGER.warn("Theme \"{}\": decoration {} has no texture, dropping it", id, i);

                continue;
            }

            String anchorRaw = entry.getString("anchor", "top_left");
            UIThemeDecoration.Anchor anchor = null;

            for (UIThemeDecoration.Anchor candidate : UIThemeDecoration.Anchor.values())
            {
                if (candidate.name().toLowerCase().equals(anchorRaw))
                {
                    anchor = candidate;

                    break;
                }
            }

            if (anchor == null)
            {
                LOGGER.warn("Theme \"{}\": decoration {} has unknown anchor \"{}\", dropping it", id, i, anchorRaw);

                continue;
            }

            ListType offset = entry.getList("offset");
            int offsetX = offset.size() > 0 ? offset.getInt(0) : 0;
            int offsetY = offset.size() > 1 ? offset.getInt(1) : 0;
            float scale = decorationFloat(id, i, entry, "scale", 1F, 0.05F, 8F);
            float opacity = decorationFloat(id, i, entry, "opacity", 1F, 0F, 1F);

            result.add(new UIThemeDecoration(Link.create(texture), anchor, offsetX, offsetY, scale, opacity));
        }

        return result;
    }

    private static float decorationFloat(String id, int index, MapType entry, String key, float defaultValue, float min, float max)
    {
        if (!entry.has(key))
        {
            return defaultValue;
        }

        float value = entry.getFloat(key, Float.NaN);

        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            LOGGER.warn("Theme \"{}\": decoration {} has invalid {}, falling back to {}", id, index, key, defaultValue);

            return defaultValue;
        }

        return MathUtils.clamp(value, min, max);
    }

    private static UIThemeMotion motionEntry(String id, MapType motion, String key, UIThemeMotion fallback)
    {
        if (!motion.has(key))
        {
            return fallback;
        }

        MapType map = motion.getMap(key);
        boolean enabled = map.getBool("enabled", fallback.enabled);
        int duration = MathUtils.clamp(map.getInt("duration", fallback.duration), 0, 10000);
        IInterp easing = fallback.easing;
        UIThemeMotion.MotionType type = parseMotionType(id, key, map);
        float response = UIThemeMotion.DEFAULT_RESPONSE;
        float damping = UIThemeMotion.DEFAULT_DAMPING;

        if (map.has("easing"))
        {
            String name = map.getString("easing");
            IInterp found = Interpolations.MAP.get(name);

            if (found == null)
            {
                LOGGER.warn("Theme \"{}\": motion \"{}\" uses unknown easing \"{}\", falling back to sine_out", id, key, name);

                found = Interpolations.SINE_OUT;
            }

            easing = found;
        }

        if (type == UIThemeMotion.MotionType.SPRING)
        {
            response = springFloat(id, key, map, "response", UIThemeMotion.DEFAULT_RESPONSE, 0.05F, 3F);
            damping = springFloat(id, key, map, "damping", UIThemeMotion.DEFAULT_DAMPING, 0.1F, 2F);
        }

        UIThemeMotionTracks tracks = parseTracks(id, key, map);
        float scale = springFloat(id, key, map, "scale", 1F, 0.25F, 2F);

        return new UIThemeMotion(enabled, duration, easing, type, response, damping, tracks, scale);
    }

    /**
     * Parse the optional track orchestration: a {@code preset} shortcut
     * expanded first, then explicit {@code tracks} entries overriding the
     * preset per property. Returns null (= the touch point's built-in
     * transform) when neither key is present.
     */
    private static UIThemeMotionTracks parseTracks(String id, String motionKey, MapType map)
    {
        UIThemeMotionTracks preset = null;

        if (map.has("preset"))
        {
            String raw = map.getString("preset");

            preset = switch (raw)
            {
                case "scale" -> UIThemeMotionTracks.PRESET_SCALE;
                case "slide_right" -> UIThemeMotionTracks.PRESET_SLIDE_RIGHT;
                case "slide_up" -> UIThemeMotionTracks.PRESET_SLIDE_UP;
                case "fade" -> UIThemeMotionTracks.PRESET_FADE;
                default -> null;
            };

            if (preset == null)
            {
                LOGGER.warn("Theme \"{}\": motion \"{}\" uses unknown preset \"{}\", ignoring it", id, motionKey, raw);
            }
        }

        if (!map.has("tracks"))
        {
            return preset;
        }

        MapType tracks = map.getMap("tracks");
        float alphaFrom = trackFrom(id, motionKey, tracks, "alpha", preset == null ? 1F : preset.alphaFrom, 0F, 1F);
        float scaleFrom = trackFrom(id, motionKey, tracks, "scale", preset == null ? 1F : preset.scaleFrom, 0.1F, 3F);
        float xFrom = trackFrom(id, motionKey, tracks, "x", preset == null ? 0F : preset.xFrom, -200F, 200F);
        float yFrom = trackFrom(id, motionKey, tracks, "y", preset == null ? 0F : preset.yFrom, -200F, 200F);

        return new UIThemeMotionTracks(alphaFrom, scaleFrom, xFrom, yFrom);
    }

    private static float trackFrom(String id, String motionKey, MapType tracks, String key, float fallback, float min, float max)
    {
        if (!tracks.has(key))
        {
            return fallback;
        }

        float value = tracks.getMap(key).getFloat("from", Float.NaN);

        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            LOGGER.warn("Theme \"{}\": motion \"{}\" track \"{}\" has invalid from value, keeping {}", id, motionKey, key, fallback);

            return fallback;
        }

        return MathUtils.clamp(value, min, max);
    }

    private static UIThemeMotion.MotionType parseMotionType(String id, String key, MapType map)
    {
        if (!map.has("type"))
        {
            return UIThemeMotion.MotionType.EASE;
        }

        String raw = map.getString("type");

        if ("spring".equals(raw))
        {
            return UIThemeMotion.MotionType.SPRING;
        }

        if (!raw.isEmpty() && !"ease".equals(raw))
        {
            LOGGER.warn("Theme \"{}\": motion \"{}\" uses unknown type \"{}\", falling back to ease", id, key, raw);
        }

        return UIThemeMotion.MotionType.EASE;
    }

    private static float springFloat(String id, String motionKey, MapType map, String key, float defaultValue, float min, float max)
    {
        if (!map.has(key))
        {
            return defaultValue;
        }

        float value = map.getFloat(key, Float.NaN);

        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            LOGGER.warn("Theme \"{}\": motion \"{}\" has invalid {} \"{}\", falling back to {}", id, motionKey, key, map.getString(key), defaultValue);

            return defaultValue;
        }

        return MathUtils.clamp(value, min, max);
    }

    /**
     * Code-level dark defaults that terminate every base chain. These values
     * mirror the built-in dark theme document, so even a jar with a broken
     * dark JSON still yields a complete theme.
     */
    public static UITheme defaultDark(String id)
    {
        UITheme.Builder builder = new UITheme.Builder(id, null);

        builder.name = "Dark";
        builder.author = "BBS";
        builder.description = "";
        builder.light = false;

        builder.surfaceChrome = 0xff111316;
        builder.surfaceBase = 0xff171a1f;
        builder.surfaceRaised = 0xff1d2127;
        builder.surfaceDeep = 0xff0f1217;
        builder.surfaceDivider = 0xff30353d;
        builder.accentPrimary = 0xffff3242;
        builder.textPrimary = 0xffffffff;
        builder.textMuted = 0xffaaaaaa;
        builder.statePositive = 0xff59d940;
        builder.stateNegative = 0xffff4059;
        builder.stateWarning = 0xffffbb00;
        builder.stateActive = 0xff0088ff;
        builder.stateHighlight = 0xffddddff;
        builder.stateCursor = 0xff57f52a;
        builder.fieldFill = builder.surfaceDeep;
        builder.fieldBorder = builder.surfaceDivider;
        builder.tabActiveLine = builder.accentPrimary;
        builder.tabActiveGradient = 0;
        builder.areaTint = 0;
        builder.areaTintLight = 0;
        builder.dropFill = 0;
        builder.dropBorder = 0;
        builder.splitterActive = 0;
        builder.splitterIdle = 0;
        builder.shadowMuted = 0;
        builder.trackpadScrub = 0;
        builder.notificationFill = 0;
        builder.notificationText = builder.textPrimary;
        builder.selectionFill = 0;
        builder.selectionOutline = 0;
        builder.iconPressed = 0;
        builder.iconDisabled = 0;
        builder.scrollbarShadow = 0;

        builder.textShadow = true;
        builder.bevel = true;
        builder.panelShadow = true;
        builder.cornerChrome = 0;
        builder.cornerPanel = 0;
        builder.cornerWidget = 0;

        builder.iconsAtlas = null;
        builder.background = null;
        builder.backgroundMode = UITheme.BackgroundMode.STRETCH;
        builder.backgroundDim = 0F;
        builder.decorations = null;

        builder.motionEnabled = true;
        builder.motionSpeed = 1F;
        builder.overlay = new UIThemeMotion(true, 120, Interpolations.SINE_OUT);
        builder.panelSwitch = new UIThemeMotion(true, 100, Interpolations.SINE_INOUT);
        builder.hover = new UIThemeMotion(true, 80, Interpolations.SINE_OUT);
        builder.notification = new UIThemeMotion(true, 150, Interpolations.BACK_OUT);
        builder.contextMenu = new UIThemeMotion(true, 100, Interpolations.QUAD_OUT);
        builder.scrollbar = new UIThemeMotion(true, 200, Interpolations.SINE_INOUT);

        /* v2 coverage entries: smooth scrolling matches the v1 user-setting
         * behavior, everything else is off by default (bit-identical v1 look) */
        builder.scrollSmooth = new UIThemeMotion(true, 100, Interpolations.SINE_OUT);
        builder.hoverScale = new UIThemeMotion(false, 80, Interpolations.SINE_OUT);
        builder.press = new UIThemeMotion(false, 80, Interpolations.SINE_OUT);
        builder.layout = new UIThemeMotion(false, 200, Interpolations.SINE_INOUT);
        builder.toggle = new UIThemeMotion(false, 180, Interpolations.SINE_OUT);
        builder.taskbarHide = new UIThemeMotion(true, 250, Interpolations.SINE_INOUT);

        return builder.build();
    }
}
