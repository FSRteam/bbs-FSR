package mchorse.bbs_mod.ui.themes;

import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Exports one resolved asset-pack theme directory as an editable user copy. */
public final class ThemeTemplateExporter
{
    private static final String THEME_FILE = "theme.json";

    private ThemeTemplateExporter()
    {}

    public static File export(String sourceId, File themesFolder, AssetProvider provider) throws IOException
    {
        if (!ThemeManager.isValidId(sourceId))
        {
            throw new IOException("Invalid source theme id: " + sourceId);
        }

        Path root = themesFolder.toPath().toAbsolutePath().normalize();

        Files.createDirectories(root);

        String targetId = nextTargetId(root, sourceId + "-copy");
        Path target = root.resolve(targetId).normalize();

        if (!target.getParent().equals(root))
        {
            throw new IOException("Theme export target escaped the themes folder");
        }

        Link documentLink = Link.assets(themePrefix(sourceId) + THEME_FILE);
        MapType document;

        try (InputStream in = provider.getAsset(documentLink))
        {
            document = DataToString.mapFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        if (document == null)
        {
            throw new IOException("Theme " + sourceId + " has an invalid " + THEME_FILE);
        }

        Set<Link> assets = new LinkedHashSet<>(provider.getLinksFromPath(Link.assets("themes/" + sourceId), true));

        collectReferencedAssets(document, sourceId, assets);
        rewriteReferencedAssets(document, sourceId, targetId);

        Files.createDirectory(target);

        try
        {
            for (Link asset : assets)
            {
                copyLocalThemeAsset(provider, asset, sourceId, target);
            }

            Files.writeString(target.resolve(THEME_FILE), DataToString.toString(document, true) + "\n", StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException exception)
        {
            try
            {
                deleteTree(target);
            }
            catch (IOException cleanup)
            {
                exception.addSuppressed(cleanup);
            }

            throw exception;
        }

        return target.toFile();
    }

    private static String nextTargetId(Path root, String base)
    {
        String candidate = base;

        for (int i = 2; Files.exists(root.resolve(candidate)); i++)
        {
            candidate = base + "-" + i;
        }

        return candidate;
    }

    private static void collectReferencedAssets(MapType document, String sourceId, Set<Link> assets)
    {
        BaseType texturesValue = document.get("textures");

        if (BaseType.isMap(texturesValue))
        {
            MapType textures = (MapType) texturesValue;

            collectLocalAsset(textures.getString("icons", null), sourceId, assets);
            collectLocalAsset(textures.getString("background", null), sourceId, assets);
        }

        BaseType decorationsValue = document.get("decorations");

        if (BaseType.isList(decorationsValue))
        {
            for (BaseType value : (ListType) decorationsValue)
            {
                if (BaseType.isMap(value))
                {
                    collectLocalAsset(((MapType) value).getString("texture", null), sourceId, assets);
                }
            }
        }
    }

    private static void collectLocalAsset(String value, String sourceId, Set<Link> assets)
    {
        Link link = value == null ? null : Link.create(value);

        if (isLocalThemeAsset(link, sourceId))
        {
            assets.add(link);
        }
    }

    private static void rewriteReferencedAssets(MapType document, String sourceId, String targetId)
    {
        BaseType texturesValue = document.get("textures");

        if (BaseType.isMap(texturesValue))
        {
            MapType textures = (MapType) texturesValue;

            rewriteLocalAsset(textures, "icons", sourceId, targetId);
            rewriteLocalAsset(textures, "background", sourceId, targetId);
        }

        BaseType decorationsValue = document.get("decorations");

        if (BaseType.isList(decorationsValue))
        {
            for (BaseType value : (ListType) decorationsValue)
            {
                if (BaseType.isMap(value))
                {
                    rewriteLocalAsset((MapType) value, "texture", sourceId, targetId);
                }
            }
        }
    }

    private static void rewriteLocalAsset(MapType owner, String key, String sourceId, String targetId)
    {
        String value = owner.getString(key, null);
        Link link = value == null ? null : Link.create(value);

        if (isLocalThemeAsset(link, sourceId))
        {
            String relative = link.path.substring(themePrefix(sourceId).length());

            owner.putString(key, Link.assets(themePrefix(targetId) + relative).toString());
        }
    }

    private static boolean isLocalThemeAsset(Link link, String sourceId)
    {
        return link != null && Link.ASSETS.equals(link.source) && link.path.startsWith(themePrefix(sourceId));
    }

    private static void copyLocalThemeAsset(AssetProvider provider, Link asset, String sourceId, Path target) throws IOException
    {
        if (!isLocalThemeAsset(asset, sourceId))
        {
            return;
        }

        String relative = asset.path.substring(themePrefix(sourceId).length());

        if (relative.isEmpty() || THEME_FILE.equals(relative) || relative.endsWith("/"))
        {
            return;
        }

        Path output = target.resolve(relative).normalize();

        if (!output.startsWith(target) || output.equals(target))
        {
            throw new IOException("Theme asset escaped the export folder: " + asset);
        }

        Files.createDirectories(output.getParent());

        try (InputStream in = provider.getAsset(asset))
        {
            Files.copy(in, output);
        }
    }

    private static String themePrefix(String id)
    {
        return "themes/" + id + "/";
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (!Files.exists(root))
        {
            return;
        }

        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
