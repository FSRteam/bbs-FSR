package mchorse.bbs_mod.ui.themes;

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

        MapType style = map.getMap("style");

        builder.textShadow = style.getBool("text_shadow", fallback.textShadow);
        builder.bevel = style.getBool("bevel", fallback.bevel);
        builder.panelShadow = style.getBool("panel_shadow", fallback.panelShadow);

        MapType textures = map.getMap("textures");

        builder.iconsAtlas = link(textures, "icons", fallback.iconsAtlas);
        builder.background = link(textures, "background", fallback.background);

        MapType motion = map.getMap("motion");

        builder.motionEnabled = motion.getBool("enabled", fallback.motionEnabled);
        builder.motionSpeed = MathUtils.clamp(motion.getFloat("speed", fallback.motionSpeed), 0.1F, 10F);
        builder.overlay = motionEntry(id, motion, "overlay", fallback.overlay);
        builder.panelSwitch = motionEntry(id, motion, "panel_switch", fallback.panelSwitch);
        builder.hover = motionEntry(id, motion, "hover", fallback.hover);
        builder.notification = motionEntry(id, motion, "notification", fallback.notification);
        builder.contextMenu = motionEntry(id, motion, "context_menu", fallback.contextMenu);
        builder.scrollbar = motionEntry(id, motion, "scrollbar", fallback.scrollbar);

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

        return new UIThemeMotion(enabled, duration, easing);
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

        builder.textShadow = true;
        builder.bevel = true;
        builder.panelShadow = true;

        builder.iconsAtlas = null;
        builder.background = null;

        builder.motionEnabled = true;
        builder.motionSpeed = 1F;
        builder.overlay = new UIThemeMotion(true, 120, Interpolations.SINE_OUT);
        builder.panelSwitch = new UIThemeMotion(true, 100, Interpolations.SINE_INOUT);
        builder.hover = new UIThemeMotion(true, 80, Interpolations.SINE_OUT);
        builder.notification = new UIThemeMotion(true, 150, Interpolations.BACK_OUT);
        builder.contextMenu = new UIThemeMotion(true, 100, Interpolations.QUAD_OUT);
        builder.scrollbar = new UIThemeMotion(true, 200, Interpolations.SINE_INOUT);

        return builder.build();
    }
}
