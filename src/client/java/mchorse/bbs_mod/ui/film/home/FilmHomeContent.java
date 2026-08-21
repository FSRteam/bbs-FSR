package mchorse.bbs_mod.ui.film.home;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for the film home's editorial content (news items and the ad board).
 * Content lives in {@code config/bbs/assets/film_home/*.json} so it can be
 * edited without rebuilding; missing files fall back to the copies bundled in
 * the jar. Everything degrades silently: malformed JSON or missing files
 * simply yield empty lists, and the UI hides sections without content.
 */
public class FilmHomeContent
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-home");

    public static final FilmHomeContent INSTANCE = new FilmHomeContent();

    public List<NewsItem> news = new ArrayList<>();
    public List<AdItem> ads = new ArrayList<>();

    public static class NewsItem
    {
        public String id = "";
        /** update | notice | tutorial */
        public String tag = "update";
        public String date = "";
        public String title = "";
        public String summary = "";
        /** Optional file name under film_home/images/, resolved through Link.assets. */
        public String image = "";
        public String url = "";

        public Link imageLink()
        {
            return this.image.isEmpty() ? null : Link.assets("film_home/images/" + this.image);
        }
    }

    /**
     * One ad tile of the bottom-left ad board: a 4:3 image plus the link
     * string offered for copying in the detail overlay.
     */
    public static class AdItem
    {
        public String image = "";
        public String link = "";

        public Link imageLink()
        {
            return this.image.isEmpty() ? null : Link.assets("film_home/" + this.image);
        }
    }

    private boolean loaded;

    public synchronized void load()
    {
        this.news = new ArrayList<>();
        this.ads = new ArrayList<>();
        this.loaded = true;

        JsonObject news = readJson("news.json");

        if (news != null && news.has("items") && news.get("items").isJsonArray())
        {
            for (JsonElement element : news.getAsJsonArray("items"))
            {
                if (!element.isJsonObject())
                {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                NewsItem item = new NewsItem();

                item.id = string(object, "id");
                item.tag = string(object, "tag", "update");
                item.date = string(object, "date");
                item.title = string(object, "title");
                item.summary = string(object, "summary");
                item.image = string(object, "image");
                item.url = string(object, "url");

                if (!item.title.isEmpty())
                {
                    this.news.add(item);
                }
            }
        }

        JsonObject ads = readJson("ads.json");

        if (ads != null && ads.has("items") && ads.get("items").isJsonArray())
        {
            for (JsonElement element : ads.getAsJsonArray("items"))
            {
                if (!element.isJsonObject())
                {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                AdItem item = new AdItem();

                item.image = string(object, "image");
                item.link = string(object, "link");

                if (!item.image.isEmpty())
                {
                    this.ads.add(item);
                }
            }
        }
    }

    public synchronized void ensureLoaded()
    {
        if (!this.loaded)
        {
            this.load();
        }
    }

    /**
     * User override first ({@code config/bbs/assets/film_home/<name>}), then
     * the jar-bundled default. Returns null when neither exists.
     */
    private static JsonObject readJson(String name)
    {
        byte[] bytes = readOverride(name);

        if (bytes == null)
        {
            try (InputStream stream = FilmHomeContent.class.getResourceAsStream("/assets/bbs/assets/film_home/" + name))
            {
                if (stream != null)
                {
                    bytes = stream.readAllBytes();
                }
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=film_home phase=load result=skip reason=bundled_read_failed file={}", name);
            }
        }

        if (bytes == null)
        {
            return null;
        }

        try
        {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=film_home phase=parse result=drop reason=malformed_json file={}", name);

            return null;
        }
    }

    private static byte[] readOverride(String name)
    {
        try
        {
            File file = BBSMod.getAssetsPath("film_home/" + name);

            return file != null && file.isFile() ? Files.readAllBytes(file.toPath()) : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String string(JsonObject object, String key)
    {
        return string(object, key, "");
    }

    private static String string(JsonObject object, String key, String fallback)
    {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }
}
