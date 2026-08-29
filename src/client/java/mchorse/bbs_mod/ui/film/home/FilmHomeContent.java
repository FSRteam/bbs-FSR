package mchorse.bbs_mod.ui.film.home;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loader for the film home's editorial content (news items and the ad board).
 *
 * <p>Content resolution, in order of freshness:
 * <ol>
 * <li>local {@code film_home/*.json} (config override, then jar bundle) is
 * applied synchronously so the home paints instantly;</li>
 * <li>remote content ({@code film_home/remote.json} URL chain) is fetched on
 * a background thread — success replaces the lists, bumps the version, is
 * persisted to the disk cache and notifies the UI callback;</li>
 * <li>when every remote URL fails, the last successful cached copy is used;
 * with no cache at all the local lists simply stay put.</li>
 * </ol>
 *
 * <p>Everything degrades silently: malformed JSON, missing files and network
 * failures never crash or throw into the UI, and all IO happens off the
 * render thread (the only synchronous work is the initial local read).
 */
public class FilmHomeContent
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-film-home");

    public static final FilmHomeContent INSTANCE = new FilmHomeContent();

    /** Where the currently displayed lists came from. */
    public enum Source
    {
        LOCAL, CACHED, REMOTE
    }

    /** Remote fetch configuration from {@code film_home/remote.json}. */
    public static class RemoteConfig
    {
        public boolean enabled;
        public final List<String> adsUrls = new ArrayList<>();
        public final List<String> newsUrls = new ArrayList<>();
        public long refreshMinutes = 30L;
    }

    public List<NewsItem> news = new ArrayList<>();
    public List<AdItem> ads = new ArrayList<>();

    public volatile Source newsSource = Source.LOCAL;
    public volatile Source adsSource = Source.LOCAL;
    public volatile boolean fetchingNews;
    public volatile boolean fetchingAds;

    private long lastAttempt;

    /** Raw remote payloads, used to skip no-op swaps. */
    private String adsRaw = "";
    private String newsRaw = "";

    public static class NewsItem
    {
        public String id = "";
        /** update | notice | tutorial */
        public String tag = "update";
        public String date = "";
        public String title = "";
        public String summary = "";
        /** Optional Markdown body rendered in the detail overlay; wins over summary. */
        public String markdown = "";
        /** Optional file name under film_home/images/, or an http(s) URL. */
        public String image = "";
        public String url = "";

        public Link imageLink()
        {
            return WebImages.isRemote(this.image) || this.image.isEmpty() ? null : Link.assets("film_home/images/" + this.image);
        }
    }

    /**
     * One ad tile of the bottom-left ad board: a 4:3 image plus the link
     * string offered for copying in the detail overlay, and an optional
     * Markdown body shown next to the full image.
     */
    public static class AdItem
    {
        /** File name under film_home/, or an http(s) URL. */
        public String image = "";
        public String link = "";
        public String markdown = "";

        public Link imageLink()
        {
            return WebImages.isRemote(this.image) || this.image.isEmpty() ? null : Link.assets("film_home/" + this.image);
        }
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(1, (r) ->
    {
        Thread thread = new Thread(r, "bbs-film-home-remote");

        thread.setDaemon(true);

        return thread;
    });

    private boolean loaded;

    public synchronized void load()
    {
        this.loaded = true;

        /* Local files only seed fresh lists; once remote (or cached) content
         * is on display a local re-read must not roll it back. */
        if (this.adsSource == Source.LOCAL)
        {
            this.ads = new ArrayList<>();

            JsonObject json = readJson("ads.json");

            if (json != null)
            {
                List<AdItem> items = parseAds(json);

                if (!items.isEmpty())
                {
                    this.ads = items;
                }
            }
        }

        if (this.newsSource == Source.LOCAL)
        {
            this.news = new ArrayList<>();

            JsonObject json = readJson("news.json");

            if (json != null)
            {
                List<NewsItem> items = parseNews(json);

                if (!items.isEmpty())
                {
                    this.news = items;
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
     * Kicks one asynchronous refresh round for the remote content. Cheap to
     * call repeatedly: throttled by {@code refresh_minutes} (except the very
     * first call of a session) and de-duplicated while a round is running.
     * The callback runs on the UI thread only when a list actually changed.
     */
    public void refreshAsync(Runnable onApplied)
    {
        RemoteConfig remote = readRemoteConfig();

        if (!remote.enabled || (remote.adsUrls.isEmpty() && remote.newsUrls.isEmpty()))
        {
            return;
        }

        long now = System.currentTimeMillis();

        if (this.lastAttempt != 0L && now - this.lastAttempt < remote.refreshMinutes * 60_000L)
        {
            return;
        }

        if (this.fetchingAds || this.fetchingNews)
        {
            return;
        }

        this.lastAttempt = now;
        this.fetchingAds = !remote.adsUrls.isEmpty();
        this.fetchingNews = !remote.newsUrls.isEmpty();

        POOL.submit(() ->
        {
            boolean changed = false;

            if (!remote.adsUrls.isEmpty())
            {
                changed |= this.fetchContent("ads", remote.adsUrls);
            }

            if (!remote.newsUrls.isEmpty())
            {
                changed |= this.fetchContent("news", remote.newsUrls);
            }

            this.fetchingAds = false;
            this.fetchingNews = false;

            if (onApplied != null)
            {
                Minecraft.getInstance().execute(onApplied);
            }

            if (changed)
            {
                LOGGER.info("[BBS-SEM] topic=film_home phase=remote result=applied");
            }
        });
    }

    /**
     * One content type's fetch round: every URL in order until one parses.
     * On success the lists swap (when the payload differs), the disk cache is
     * refreshed; on total failure the last successful cache is restored once.
     */
    private boolean fetchContent(String kind, List<String> urls)
    {
        for (String url : urls)
        {
            String body = fetchString(url);

            if (body == null)
            {
                continue;
            }

            try
            {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                if (!json.has("items") || !json.get("items").isJsonArray())
                {
                    LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason=bad_payload kind={} url={}", kind, url);

                    continue;
                }

                boolean changed = this.applyRemote(kind, json, body);

                writeCache(kind, body);

                if (changed)
                {
                    LOGGER.info("[BBS-SEM] topic=film_home phase=remote result=updated kind={} url={}", kind, url);
                }

                return true;
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason=malformed_json kind={} url={}", kind, url);
            }
        }

        /* Every URL failed: fall back to the last successful copy, but only
         * when the UI is currently showing something less fresh (local). */
        if ((kind.equals("ads") ? this.adsSource : this.newsSource) == Source.LOCAL)
        {
            String cached = readCache(kind);

            if (cached != null)
            {
                try
                {
                    this.applyRemote(kind, JsonParser.parseString(cached).getAsJsonObject(), cached);
                    this.setSource(kind, Source.CACHED);

                    LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=cache_fallback kind={}", kind);

                    return true;
                }
                catch (Exception e)
                {
                    LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=drop reason=bad_cache kind={}", kind);
                }
            }
        }

        return false;
    }

    /** Swaps the given list if the payload differs; bumps the version then. */
    private synchronized boolean applyRemote(String kind, JsonObject json, String raw)
    {
        boolean changed;

        if (kind.equals("ads"))
        {
            List<AdItem> items = parseAds(json);

            changed = !raw.equals(this.adsRaw);
            this.adsRaw = raw;

            if (changed)
            {
                this.ads = items;
            }

            this.adsSource = Source.REMOTE;
        }
        else
        {
            List<NewsItem> items = parseNews(json);

            changed = !raw.equals(this.newsRaw);
            this.newsRaw = raw;

            if (changed)
            {
                this.news = items;
            }

            this.newsSource = Source.REMOTE;
        }

        return changed;
    }

    private synchronized void setSource(String kind, Source source)
    {
        if (kind.equals("ads"))
        {
            this.adsSource = source;
        }
        else
        {
            this.newsSource = source;
        }
    }

    /* Remote configuration & HTTP & cache */

    /**
     * Reads {@code film_home/remote.json} (config override, then jar bundle).
     * URLs accept a single string or an array (tried in order, first hit
     * wins). Missing or malformed file simply disables remote content.
     */
    public RemoteConfig readRemoteConfig()
    {
        RemoteConfig config = new RemoteConfig();
        JsonObject json = readJson("remote.json");

        if (json == null)
        {
            return config;
        }

        try
        {
            config.enabled = json.has("enabled") && json.get("enabled").isJsonPrimitive() && json.get("enabled").getAsBoolean();

            collectUrls(json.get("ads"), config.adsUrls);
            collectUrls(json.get("news"), config.newsUrls);

            if (json.has("refresh_minutes") && json.get("refresh_minutes").isJsonPrimitive())
            {
                config.refreshMinutes = Math.max(1L, json.get("refresh_minutes").getAsLong());
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason=bad_remote_config");
        }

        return config;
    }

    private static void collectUrls(JsonElement element, List<String> urls)
    {
        if (element == null)
        {
            return;
        }

        if (element.isJsonPrimitive())
        {
            String url = element.getAsString();

            if (!url.isEmpty())
            {
                urls.add(url);
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement child : element.getAsJsonArray())
            {
                if (child.isJsonPrimitive() && !child.getAsString().isEmpty())
                {
                    urls.add(child.getAsString());
                }
            }
        }
    }

    /** Non-blocking-friendly blocking GET (runs on the remote pool only). */
    private static String fetchString(String url)
    {
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection) new URL(url).openConnection();

            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "curl/8.9.0");
            connection.setRequestProperty("Accept", "application/json");

            if (connection.getResponseCode() / 100 != 2)
            {
                LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason=http_{} url={}", connection.getResponseCode(), url);

                return null;
            }

            try (InputStream stream = connection.getInputStream())
            {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int total = 0;
                int read;

                while ((read = stream.read(chunk)) >= 0)
                {
                    total += read;

                    if (total > 2 * 1024 * 1024)
                    {
                        LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason=too_large url={}", url);

                        return null;
                    }

                    buffer.write(chunk, 0, read);
                }

                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=film_home phase=remote result=skip reason={} url={}", e.getClass().getSimpleName(), url);

            return null;
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    /** Last successful remote copies, used when every URL fails. */
    private static File cacheFile(String kind)
    {
        return new File(BBSMod.getSettingsPath("film_home_cache"), kind + ".json");
    }

    private static void writeCache(String kind, String raw)
    {
        try
        {
            File file = cacheFile(kind);

            file.getParentFile().mkdirs();
            Files.write(file.toPath(), raw.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=film_home phase=cache result=skip reason=write_failed kind={}", kind);
        }
    }

    private static String readCache(String kind)
    {
        try
        {
            File file = cacheFile(kind);

            return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /* Parsing (shared by local files and remote payloads) */

    private static List<NewsItem> parseNews(JsonObject news)
    {
        List<NewsItem> items = new ArrayList<>();

        if (news.has("items") && news.get("items").isJsonArray())
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
                item.markdown = string(object, "markdown");
                item.image = string(object, "image");
                item.url = string(object, "url");

                if (!item.title.isEmpty())
                {
                    items.add(item);
                }
            }
        }

        return items;
    }

    private static List<AdItem> parseAds(JsonObject ads)
    {
        List<AdItem> items = new ArrayList<>();

        if (ads.has("items") && ads.get("items").isJsonArray())
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
                item.markdown = string(object, "markdown");

                if (!item.image.isEmpty())
                {
                    items.add(item);
                }
            }
        }

        return items;
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
