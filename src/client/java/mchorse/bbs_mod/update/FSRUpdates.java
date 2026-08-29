package mchorse.bbs_mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.settings.ui.UIValueFactory;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FSR update checker: every BBS dashboard open kicks {@link #onDashboardOpened},
 * which throttles by the user's check-frequency setting and fetches the
 * release list from the update worker on a daemon thread. All selection logic
 * lives here (the worker only stores + validates): revoked warning first
 * (even when the local build is newer than every published release), then
 * up-to-date silence, then skipped-version silence, then the update popup.
 */
public class FSRUpdates
{
    public static final String VERSION_URL = "https://fsrapi.twiap.studio/api/version";
    private static final Pattern VERSION_RE = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta\\.(\\d+))?$");
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    public static final FSRUpdates INSTANCE = new FSRUpdates();

    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-updates");
    static final ExecutorService POOL = Executors.newSingleThreadExecutor((r) ->
    {
        Thread thread = new Thread(r, "bbs-fsr-updates");

        thread.setDaemon(true);

        return thread;
    });

    public enum Status
    {
        IDLE, CHECKING, UP_TO_DATE, AVAILABLE, SKIPPED, REVOKED, FAILED
    }

    /** One published release as served by GET /api/version. */
    public static class Release
    {
        public String version = "";
        public String channel = "stable";
        public String date = "";
        public String url = "";
        public String sha256 = "";
        public String notes = "";
        public String revokedReason = "";
        public boolean mandatory;
        public boolean revoked;
    }

    public volatile Status status = Status.IDLE;
    public volatile Release available;
    public volatile boolean lastCheckFromCache;

    private volatile boolean fetching;
    private volatile boolean cleanedUp;
    private WeakReference<UIDashboard> dashboard;

    /* Entry points */

    /** Called on every BBS dashboard open. */
    public static void onDashboardOpened(UIDashboard dashboard)
    {
        INSTANCE.dashboardOpened(dashboard);
    }

    private void dashboardOpened(UIDashboard dashboard)
    {
        this.dashboard = new WeakReference<>(dashboard);

        UpdateInstaller.cleanupOnce();

        this.checkAsync(false, null);
    }

    /**
     * Runs one check round. Background rounds respect the enabled flag and
     * the frequency throttle; manual rounds always fetch (the result is
     * always reported back through toasts or the update popup).
     */
    public void checkAsync(boolean manual, UIContext context)
    {
        if (this.fetching)
        {
            return;
        }

        if (!manual && !BBSSettings.updateEnabled.get())
        {
            return;
        }

        long now = System.currentTimeMillis();
        long last = BBSSettings.updateLastCheck.get();

        if (!manual && last != 0L && now - last < intervalMinutes() * 60_000L)
        {
            return;
        }

        this.fetching = true;
        this.status = Status.CHECKING;
        BBSSettings.updateLastCheck.set(now);

        POOL.submit(() ->
        {
            List<Release> releases = null;
            boolean cached = false;

            try
            {
                String body = httpGet(VERSION_URL);

                releases = parseReleases(body);
                writeCache(body);
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=updates phase=check result=failed error={}", e.getMessage());
            }

            if (releases == null)
            {
                releases = readCache();
                cached = true;
            }

            final List<Release> finalReleases = releases;
            final boolean finalCached = cached;

            Minecraft.getInstance().execute(() ->
            {
                this.fetching = false;
                this.apply(finalReleases, finalCached, manual, context);
            });
        });
    }

    /* Decision logic */

    private void apply(List<Release> releases, boolean fromCache, boolean manual, UIContext context)
    {
        this.lastCheckFromCache = fromCache;

        if (releases == null)
        {
            this.status = Status.FAILED;

            if (manual)
            {
                notify(context, L10n.lang("bbs.updates.notify_failed").get(), true);
            }

            return;
        }

        String current = currentVersion();
        String channel = BBSSettings.updateChannel.get() == 1 ? "preview" : "stable";
        Release revoked = null;
        Release latest = null;

        for (Release release : releases)
        {
            if (release.revoked && release.version.equals(current))
            {
                revoked = release;
            }

            if (!release.revoked && release.channel.equals(channel))
            {
                if (latest == null || compareVersions(release.version, latest.version) > 0)
                {
                    latest = release;
                }
            }
        }

        /* The revoked warning outranks every silence rule: a locally newer
         * (or custom) build that matches a recalled version must still be
         * reported. */
        if (revoked != null)
        {
            this.status = Status.REVOKED;
            this.showPopup(context, revoked, latest, true);

            return;
        }

        if (latest == null || current.isEmpty() || compareVersions(current, latest.version) >= 0)
        {
            this.status = Status.UP_TO_DATE;

            if (manual)
            {
                notify(context, L10n.lang("bbs.updates.notify_up_to_date").get(), false);
            }

            return;
        }

        if (latest.version.equals(BBSSettings.updateSkippedVersion.get()))
        {
            /* Skipped means "remind me at the NEXT version", so a newer
             * release than the skipped one falls through to the popup. */
            this.status = Status.SKIPPED;

            if (manual)
            {
                notify(context, L10n.lang("bbs.updates.notify_skipped").format(latest.version).get(), false);
            }

            return;
        }

        this.available = latest;
        this.status = Status.AVAILABLE;

        /* Only pop on fresh data: a cached copy from a previous session may
         * be stale, and the background path must stay silent-ish. Manual
         * checks always show what was found. */
        if (manual || !fromCache)
        {
            this.showPopup(context, latest, null, false);
        }
    }

    private void showPopup(UIContext context, Release release, Release latest, boolean revoked)
    {
        UIContext ctx = context;

        if (ctx == null && this.dashboard != null)
        {
            UIDashboard open = this.dashboard.get();

            if (open != null && open.context != null)
            {
                ctx = open.context;
            }
        }

        if (ctx == null)
        {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int w = Math.min(480, (int) (minecraft.getWindow().getGuiScaledWidth() * 0.9F));
        int h = Math.min(420, (int) (minecraft.getWindow().getGuiScaledHeight() * 0.9F));

        UIUpdateOverlayPanel panel = new UIUpdateOverlayPanel(release, latest, revoked);

        UIOverlay.addOverlay(ctx, panel, Math.max(280, w), Math.max(240, h));
    }

    /* Helpers used by the settings widgets */

    /** Mode labels for the channel/frequency circulate buttons. */
    public static List<IKey> modeLabels(ValueInt value)
    {
        List<IKey> labels = new ArrayList<>();

        if (value == BBSSettings.updateChannel)
        {
            labels.add(L10n.lang("bbs.updates.channel_stable"));
            labels.add(L10n.lang("bbs.updates.channel_preview"));
        }
        else
        {
            labels.add(L10n.lang("bbs.updates.interval_always"));
            labels.add(L10n.lang("bbs.updates.interval_daily"));
            labels.add(L10n.lang("bbs.updates.interval_weekly"));
            labels.add(L10n.lang("bbs.updates.interval_biweekly"));
            labels.add(L10n.lang("bbs.updates.interval_monthly"));
        }

        return labels;
    }

    /** Manual check button + live status line for the settings category. */
    public static List<UIElement> createSettingsWidgets(UIElement ui)
    {
        UIButton check = new UIButton(L10n.lang("bbs.updates.check_now"), (b) ->
        {
            INSTANCE.checkAsync(true, b.getContext());
        });

        check.w(90);

        UIText status = new UIText(new IKey()
        {
            @Override
            public String get()
            {
                return statusLine();
            }
        }).updates();

        return List.of(UIValueFactory.column(check, BBSSettings.updateCheckTrigger), status.marginBottom(8));
    }

    public static String statusLine()
    {
        Status status = INSTANCE.status;
        String when = formatLastCheck();
        String suffix = " · " + L10n.lang("bbs.updates.last_check").format(when).get();

        switch (status)
        {
            case CHECKING: return L10n.lang("bbs.updates.status_checking").get() + suffix;
            case UP_TO_DATE: return L10n.lang("bbs.updates.status_up_to_date").get() + suffix;
            case AVAILABLE:
            {
                Release release = INSTANCE.available;

                return L10n.lang("bbs.updates.status_available").format(release == null ? "?" : release.version).get() + suffix;
            }
            case SKIPPED: return L10n.lang("bbs.updates.status_skipped").get() + suffix;
            case REVOKED: return L10n.lang("bbs.updates.status_revoked").get() + suffix;
            case FAILED: return L10n.lang("bbs.updates.status_failed").get() + (INSTANCE.lastCheckFromCache ? L10n.lang("bbs.updates.status_cached").get() : "") + suffix;
            default: return L10n.lang("bbs.updates.status_idle").get() + suffix;
        }
    }

    private static String formatLastCheck()
    {
        long last = BBSSettings.updateLastCheck.get();

        if (last == 0L)
        {
            return L10n.lang("bbs.updates.last_check_never").get();
        }

        return Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static void notify(UIContext context, String message, boolean error)
    {
        if (context != null)
        {
            if (error)
            {
                context.notifyError(IKey.constant(message));
            }
            else
            {
                context.notifyInfo(IKey.constant(message));
            }
        }
    }

    private static int intervalMinutes()
    {
        switch (BBSSettings.updateInterval.get())
        {
            case 0: return 0;
            case 1: return 1440;
            case 3: return 20160;
            case 4: return 43200;
            default: return 10080;
        }
    }

    /* Version & data plumbing */

    public static String currentVersion()
    {
        try
        {
            return LoaderAccessHolder.get().getModVersion(BBSMod.MOD_ID).orElse("");
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * Semver-subset comparison: numeric segments first, then the beta suffix
     * where a stable release outranks any beta with the same numerics and a
     * later beta outranks an earlier one.
     */
    public static int compareVersions(String a, String b)
    {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);

        if (pa == null || pb == null)
        {
            return 0;
        }

        for (int i = 0; i < 3; i++)
        {
            if (pa[i] != pb[i])
            {
                return Integer.compare(pa[i], pb[i]);
            }
        }

        return Integer.compare(pa[3], pb[3]);
    }

    private static int[] parseVersion(String version)
    {
        Matcher matcher = VERSION_RE.matcher(version == null ? "" : version.trim());

        if (!matcher.matches())
        {
            return null;
        }

        return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            matcher.group(4) == null ? Integer.MAX_VALUE : Integer.parseInt(matcher.group(4))
        };
    }

    static List<Release> parseReleases(String body) throws IOException
    {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        JsonArray array = json.getAsJsonArray("releases");
        List<Release> releases = new ArrayList<>();

        if (array == null)
        {
            throw new IOException("missing releases array");
        }

        for (JsonElement element : array)
        {
            JsonObject object = element.getAsJsonObject();
            Release release = new Release();

            release.version = string(object, "version");
            release.channel = string(object, "channel");
            release.date = string(object, "date");
            release.url = string(object, "url");
            release.sha256 = string(object, "sha256");
            release.notes = string(object, "notes");
            release.revokedReason = string(object, "revokedReason");
            release.mandatory = object.has("mandatory") && object.get("mandatory").isJsonPrimitive() && object.get("mandatory").getAsBoolean();
            release.revoked = object.has("revoked") && object.get("revoked").isJsonPrimitive() && object.get("revoked").getAsBoolean();

            if (release.version.isEmpty())
            {
                continue;
            }

            releases.add(release);
        }

        return releases;
    }

    private static String string(JsonObject object, String key)
    {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static File cacheFile()
    {
        return BBSMod.getSettingsPath("update_cache.json");
    }

    private static void writeCache(String body)
    {
        try
        {
            Files.write(cacheFile().toPath(), body.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=updates phase=cache_write result=failed error={}", e.getMessage());
        }
    }

    private static List<Release> readCache()
    {
        try
        {
            File file = cacheFile();

            if (file.isFile())
            {
                return parseReleases(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=updates phase=cache_read result=failed error={}", e.getMessage());
        }

        return null;
    }

    private static String httpGet(String url) throws IOException
    {
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "BBS-FSR-Updates");

            int code = connection.getResponseCode();

            if (code != 200)
            {
                throw new IOException("HTTP " + code);
            }

            try (InputStream in = connection.getInputStream())
            {
                byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);

                if (bytes.length > MAX_BODY_BYTES)
                {
                    throw new IOException("payload too large");
                }

                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }
    }

    static void move(Path from, Path to) throws IOException
    {
        try
        {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
