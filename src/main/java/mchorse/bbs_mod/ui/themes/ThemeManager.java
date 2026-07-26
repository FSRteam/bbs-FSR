package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Client-facing entry point of the theme system. Resolves the theme selected
 * by {@code BBSSettings.themeId}, caching the parsed result; {@link #current()}
 * on the hot render path is only a string comparison and a field read.
 *
 * <p>This class must only be touched by client render/UI code: dedicated
 * servers never call it, so its initialization never happens there.
 */
public class ThemeManager
{
    public static final String DEFAULT_THEME_ID = "dark";

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    /** Theme ids double as folder names, so they are strictly whitelisted. */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]+");

    private static volatile UITheme current;
    private static volatile String currentId;

    private ThemeManager()
    {}

    /**
     * The active theme. Never returns null: a broken or missing theme
     * resolves to the built-in dark theme (code-level defaults at worst).
     */
    public static UITheme current()
    {
        String id = currentThemeId();
        UITheme theme = current;

        if (theme == null || !id.equals(currentId))
        {
            theme = resolve(id);
            current = theme;
            currentId = id;
        }

        return theme;
    }

    private static String currentThemeId()
    {
        String id = BBSSettings.themeId == null ? null : BBSSettings.themeId.get();

        return id == null || id.isEmpty() ? DEFAULT_THEME_ID : id;
    }

    public static void setTheme(String id)
    {
        if (BBSSettings.themeId != null && isValidId(id))
        {
            BBSSettings.themeId.set(id);
        }

        current = null;
    }

    /**
     * Drops the cached theme so the next frame re-reads and re-parses the
     * current theme's documents (manual hot reload).
     */
    public static void reload()
    {
        current = null;
    }

    public static boolean isValidId(String id)
    {
        return id != null && VALID_ID.matcher(id).matches();
    }

    /**
     * Icon atlas remap point: when the current theme overrides the icons
     * texture, the default atlas link resolves to the theme's one. Called
     * per icon draw, so the default-theme path is a single null check.
     */
    public static Link resolveIconAtlas(Link atlas)
    {
        UITheme theme = current();

        if (theme.iconsAtlas == null)
        {
            return atlas;
        }

        return Icons.ATLAS.equals(atlas) ? theme.iconsAtlas : atlas;
    }

    /**
     * Enumerates every available theme: built-ins from asset packs and user
     * themes from {@code config/bbs/assets/themes/}. An external theme with
     * the same id overrides the built-in one (standard assets semantics).
     */
    public static Map<String, UITheme> getThemes()
    {
        Set<String> ids = new TreeSet<>();

        /* Built-in ids are listed explicitly so enumeration still works when
         * pack listing fails (e.g. odd dev environments) */
        ids.add(DEFAULT_THEME_ID);
        ids.add("light");
        ids.add("example");
        ids.add("amber");
        ids.add("strawberry");
        ids.add("refreshed");

        try
        {
            for (Link link : BBSMod.getProvider().getLinksFromPath(Link.assets("themes"), false))
            {
                String path = link.path;

                if (path.startsWith("themes/"))
                {
                    path = path.substring("themes/".length());
                }

                if (path.endsWith("/"))
                {
                    path = path.substring(0, path.length() - 1);
                }

                if (!path.isEmpty() && !path.contains("/"))
                {
                    ids.add(path);
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Couldn't enumerate themes from asset packs", e);
        }

        try
        {
            File[] files = new File(BBSMod.getAssetsFolder(), "themes").listFiles();

            if (files != null)
            {
                for (File file : files)
                {
                    if (file.isDirectory() && new File(file, "theme.json").isFile())
                    {
                        ids.add(file.getName());
                    }
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Couldn't enumerate external themes", e);
        }

        Map<String, UITheme> themes = new LinkedHashMap<>();

        for (String id : ids)
        {
            if (!isValidId(id))
            {
                continue;
            }

            UITheme theme = ThemeParser.resolveChain(id, ThemeManager::loadThemeMap);

            if (theme != null)
            {
                themes.put(id, theme);
            }
        }

        return themes;
    }

    private static UITheme resolve(String id)
    {
        UITheme theme = null;

        if (isValidId(id))
        {
            theme = ThemeParser.resolveChain(id, ThemeManager::loadThemeMap);
        }
        else
        {
            LOGGER.warn("\"{}\" isn't a valid theme id, falling back to \"{}\"", id, DEFAULT_THEME_ID);
        }

        if (theme == null && !DEFAULT_THEME_ID.equals(id))
        {
            LOGGER.warn("Theme \"{}\" couldn't be loaded, falling back to \"{}\"", id, DEFAULT_THEME_ID);

            theme = ThemeParser.resolveChain(DEFAULT_THEME_ID, ThemeManager::loadThemeMap);
        }

        if (theme == null)
        {
            theme = ThemeParser.defaultDark(DEFAULT_THEME_ID);
        }

        return validateTextures(theme);
    }

    private static MapType loadThemeMap(String id)
    {
        if (!isValidId(id))
        {
            LOGGER.warn("\"{}\" isn't a valid theme id, ignoring it", id);

            return null;
        }

        Link link = Link.assets("themes/" + id + "/theme.json");

        try (InputStream in = BBSMod.getProvider().getAsset(link))
        {
            MapType map = DataToString.mapFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));

            if (map == null)
            {
                LOGGER.warn("Theme \"{}\": {} isn't valid JSON", id, link);
            }

            return map;
        }
        catch (FileNotFoundException e)
        {
            LOGGER.warn("Theme \"{}\": {} doesn't exist", id, link);

            return null;
        }
        /* LinkageError covers running without a bootstrapped mod runtime
         * (e.g. plain-JVM tests): resolution falls back to code defaults */
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("Theme \"{}\": couldn't read {}", id, link, e);

            return null;
        }
    }

    /**
     * Texture overrides pointing at missing files are dropped at resolve
     * time (with a warning), so the render path never has to re-check them.
     */
    private static UITheme validateTextures(UITheme theme)
    {
        boolean badIcons = theme.iconsAtlas != null && !hasAsset(theme.iconsAtlas);
        boolean badBackground = theme.background != null && !hasAsset(theme.background);
        List<UIThemeDecoration> decorations = null;

        for (UIThemeDecoration decoration : theme.decorations)
        {
            if (!hasAsset(decoration.texture))
            {
                LOGGER.warn("Theme \"{}\": decoration texture {} is missing, dropping it", theme.id, decoration.texture);

                if (decorations == null)
                {
                    decorations = new ArrayList<>(theme.decorations);
                }

                decorations.remove(decoration);
            }
        }

        if (!badIcons && !badBackground && decorations == null)
        {
            return theme;
        }

        UITheme.Builder builder = new UITheme.Builder(theme.id, theme);

        if (badIcons)
        {
            LOGGER.warn("Theme \"{}\": icons texture {} is missing, using the default atlas", theme.id, theme.iconsAtlas);

            builder.iconsAtlas = null;
        }

        if (badBackground)
        {
            LOGGER.warn("Theme \"{}\": background texture {} is missing, ignoring it", theme.id, theme.background);

            builder.background = null;
        }

        if (decorations != null)
        {
            builder.decorations = decorations;
        }

        return builder.build();
    }

    private static boolean hasAsset(Link link)
    {
        try (InputStream in = BBSMod.getProvider().getAsset(link))
        {
            return in != null;
        }
        catch (Exception | LinkageError e)
        {
            return false;
        }
    }
}
