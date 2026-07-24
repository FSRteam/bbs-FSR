package mchorse.bbs_mod.settings.ui;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.themes.ThemeManager;
import mchorse.bbs_mod.ui.themes.UITheme;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Theme picker overlay: lists every discovered theme (built-in and external)
 * with a five swatch preview, applies a theme on click, and offers reload /
 * open folder / export template actions.
 */
public class UIThemeOverlayPanel extends UIOverlayPanel
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UIThemeOverlayPanel.class);

    public UIThemeList list;
    public UIIcon reload;
    public UIIcon folder;
    public UIIcon export;

    public UIThemeOverlayPanel()
    {
        super(UIKeys.SKINS_PICK_TITLE);

        this.list = new UIThemeList((l) -> this.pick(l.get(0)));
        this.list.full(this.content).x(6).w(1F, -12);

        this.reload = new UIIcon(Icons.REFRESH, (b) -> this.reloadThemes());
        this.reload.tooltip(UIKeys.SKINS_RELOAD, Direction.LEFT);
        this.folder = new UIIcon(Icons.FOLDER, (b) -> openThemesFolder());
        this.folder.tooltip(UIKeys.SKINS_OPEN_FOLDER, Direction.LEFT);
        this.export = new UIIcon(Icons.DOWNLOAD, (b) ->
        {
            if (exportTemplate(this.getContext()) != null)
            {
                this.fill();
            }
        });
        this.export.tooltip(UIKeys.SKINS_EXPORT, Direction.LEFT);

        this.icons.add(this.reload, this.folder, this.export);
        this.content.add(this.list);

        this.fill();
    }

    private void fill()
    {
        Map<String, UITheme> themes = ThemeManager.getThemes();

        this.list.external.clear();

        File[] files = getThemesFolder().listFiles();

        if (files != null)
        {
            for (File file : files)
            {
                if (file.isDirectory() && new File(file, "theme.json").isFile())
                {
                    this.list.external.add(file.getName());
                }
            }
        }

        this.list.clear();
        this.list.add(themes.values());
        this.list.sort();

        String current = BBSSettings.themeId.get();
        List<UITheme> elements = this.list.getList();

        for (int i = 0; i < elements.size(); i++)
        {
            if (elements.get(i).id.equals(current))
            {
                this.list.setIndex(i);

                break;
            }
        }

        this.list.update();
    }

    private void pick(UITheme theme)
    {
        ThemeManager.setTheme(theme.id);
        this.notifyWhenBroken();
    }

    private void reloadThemes()
    {
        ThemeManager.reload();
        this.fill();
        this.notifyWhenBroken();
    }

    /**
     * The manager silently falls back to dark when the selected theme can't
     * be parsed; surface that as a notification (details are in the log).
     */
    private void notifyWhenBroken()
    {
        UIContext context = this.getContext();

        if (context == null)
        {
            return;
        }

        String wanted = BBSSettings.themeId.get();

        if (!ThemeManager.current().id.equals(wanted))
        {
            context.notifyError(UIKeys.SKINS_BROKEN.format(wanted));
        }
    }

    private static File getThemesFolder()
    {
        return new File(BBSMod.getAssetsFolder(), "themes");
    }

    public static void openThemesFolder()
    {
        File folder = getThemesFolder();

        folder.mkdirs();
        UIUtils.openFolder(folder);
    }

    /**
     * Copies the bundled template theme into an unoccupied
     * {@code themes/my-theme[-n]/} folder and posts a notification.
     *
     * @return the created folder, or null when the export failed
     */
    public static File exportTemplate(UIContext context)
    {
        File themes = getThemesFolder();
        File target = new File(themes, "my-theme");

        for (int i = 2; target.exists(); i++)
        {
            target = new File(themes, "my-theme-" + i);
        }

        try
        {
            String json = readTemplateJson();

            target.mkdirs();
            Files.writeString(new File(target, "theme.json").toPath(), json, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to export a theme template to {}", target, e);

            return null;
        }

        if (context != null)
        {
            context.notifySuccess(UIKeys.SKINS_EXPORTED.format(target.getName()));
        }

        return target;
    }

    /**
     * The example theme ships in the jar (same source as docs/theme-spec);
     * fall back to the built-in dark document when it's absent.
     */
    private static String readTemplateJson() throws Exception
    {
        for (String id : new String[] {"example", ThemeManager.DEFAULT_THEME_ID})
        {
            try (InputStream in = BBSMod.getProvider().getAsset(Link.assets("themes/" + id + "/theme.json")))
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            catch (Exception e)
            {}
        }

        throw new Exception("No template theme document is available");
    }

    public static class UIThemeList extends UIList<UITheme>
    {
        /** Theme ids that exist in the external themes folder. */
        public final Set<String> external = new HashSet<>();

        public UIThemeList(Consumer<List<UITheme>> callback)
        {
            super(callback);

            this.scroll.scrollItemSize = 20;
            this.sorting();
        }

        @Override
        protected boolean sortElements()
        {
            this.getList().sort((a, b) -> a.name.compareToIgnoreCase(b.name));

            return true;
        }

        @Override
        protected String elementToString(UIContext context, int i, UITheme theme)
        {
            return theme.name;
        }

        @Override
        protected void renderElementPart(UIContext context, UITheme theme, int i, int x, int y, boolean hover, boolean selected)
        {
            int h = this.scroll.scrollItemSize;
            int size = 8;
            int sx = x + 4;
            int sy = y + (h - size) / 2;
            int[] swatches = {theme.surfaceChrome, theme.surfaceBase, theme.surfaceRaised, theme.accentPrimary, theme.textPrimary};

            for (int swatch : swatches)
            {
                context.batcher.box(sx, sy, sx + size, sy + size, Colors.A100 | swatch);
                context.batcher.outline(sx, sy, sx + size, sy + size, Colors.A25);

                sx += size + 1;
            }

            FontRenderer font = context.batcher.getFont();
            int ty = y + (h - font.getHeight()) / 2;

            context.batcher.textShadow(theme.name, sx + 4, ty, hover ? BBSSettings.highlightColor() : BBSSettings.textColor());

            String meta = (this.external.contains(theme.id) ? UIKeys.SKINS_SOURCE_EXTERNAL : UIKeys.SKINS_SOURCE_BUILTIN).get();

            if (!theme.author.isEmpty())
            {
                meta = theme.author + " · " + meta;
            }

            context.batcher.textShadow(meta, x + this.area.w - font.getWidth(meta) - 6, ty, BBSSettings.mutedTextColor());
        }
    }
}
