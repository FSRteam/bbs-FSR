package mchorse.bbs_mod.update;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.stream.Stream;

/**
 * In-game self updater: downloads the release jar, verifies its SHA-256
 * against the pushed hash, then swaps it with the running mod file — the
 * running jar is renamed to {@code *.jar.pre-update} (Windows allows
 * renaming open files) and the download takes its place. The user picks up
 * the new build on the next game start; stale {@code .pre-update} backups
 * are swept on later startups.
 *
 * <p>Only offered in a production environment with a hash-pinned release:
 * without the hash the popup falls back to the plain download link.
 */
public class UpdateInstaller
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-updates");
    private static final long MAX_JAR_BYTES = 100L * 1024 * 1024;
    private static final String TEMP_PREFIX = "fsr-update-";
    private static final String BACKUP_SUFFIX = ".jar.pre-update";

    private static volatile boolean cleanedUp;

    public static boolean canAutoInstall(FSRUpdates.Release release)
    {
        return FMLEnvironment.production
            && release != null
            && !release.sha256.isEmpty()
            && release.url.startsWith("https://");
    }

    public static void install(UIContext context, FSRUpdates.Release release)
    {
        if (!canAutoInstall(release))
        {
            return;
        }

        context.notifyInfo(L10n.lang("bbs.updates.notify_downloading").format(release.version));

        FSRUpdates.POOL.submit(() ->
        {
            Path temp = modsDir().resolve(TEMP_PREFIX + sanitize(release.version) + ".jar.part");

            try
            {
                byte[] bytes = httpGet(release.url);

                Files.write(temp, bytes);

                String hash = sha256(bytes);

                if (!hash.equals(release.sha256))
                {
                    Files.deleteIfExists(temp);
                    LOGGER.warn("[BBS-SEM] topic=updates phase=install result=hash_mismatch expected={} got={}", release.sha256, hash);

                    Minecraft.getInstance().execute(() -> notify(context, L10n.lang("bbs.updates.notify_bad_hash").get(), true));

                    return;
                }

                Minecraft.getInstance().execute(() -> swap(context, release, temp));
            }
            catch (Exception e)
            {
                LOGGER.warn("[BBS-SEM] topic=updates phase=install result=failed error={}", e.getMessage());

                try
                {
                    Files.deleteIfExists(temp);
                }
                catch (IOException io)
                {}

                Minecraft.getInstance().execute(() -> notify(context, L10n.lang("bbs.updates.notify_download_failed").format(e.getMessage()).get(), true));
            }
        });
    }

    private static void swap(UIContext context, FSRUpdates.Release release, Path temp)
    {
        Path current;

        try
        {
            current = LoaderAccessHolder.get().getModFile(BBSMod.MOD_ID)
                .filter((path) -> path.getFileName().toString().endsWith(".jar"))
                .orElse(null);
        }
        catch (Exception e)
        {
            current = null;
        }

        if (current == null)
        {
            /* Can't locate the running jar (unpacked/dev install): leave the
             * verified download next to mods for a manual swap. */
            keepForManual(context, release, temp);

            return;
        }

        Path backup = current.resolveSibling(current.getFileName().toString().replaceFirst("\\.jar$", "") + BACKUP_SUFFIX);

        try
        {
            FSRUpdates.move(current, backup);
        }
        catch (IOException e)
        {
            LOGGER.warn("[BBS-SEM] topic=updates phase=install result=rename_failed error={}", e.getMessage());
            keepForManual(context, release, temp);

            return;
        }

        try
        {
            FSRUpdates.move(temp, current);
        }
        catch (IOException e)
        {
            LOGGER.warn("[BBS-SEM] topic=updates phase=install result=swap_failed error={}", e.getMessage());

            try
            {
                FSRUpdates.move(backup, current);
            }
            catch (IOException restore)
            {
                LOGGER.error("[BBS-SEM] topic=updates phase=install result=restore_failed", restore);
            }

            notify(context, L10n.lang("bbs.updates.notify_swap_failed").get(), true);

            return;
        }

        LOGGER.info("[BBS-SEM] topic=updates phase=install result=swapped version={}", release.version);

        UIConfirmOverlayPanel confirm = new UIConfirmOverlayPanel(
            L10n.lang("bbs.updates.restart_title"),
            L10n.lang("bbs.updates.restart_body").format(release.version),
            (ok) ->
            {
                if (ok)
                {
                    Minecraft.getInstance().stop();
                }
            }
        );

        UIOverlay.addOverlay(context, confirm);
    }

    private static void keepForManual(UIContext context, FSRUpdates.Release release, Path temp)
    {
        Path manual = modsDir().resolve(TEMP_PREFIX + sanitize(release.version) + ".jar");

        try
        {
            FSRUpdates.move(temp, manual);

            notify(context, L10n.lang("bbs.updates.notify_manual_ready").format(manual.toString()).get(), true);
        }
        catch (IOException e)
        {
            notify(context, L10n.lang("bbs.updates.notify_swap_failed").get(), true);
        }
    }

    /**
     * Best-effort sweep of leftover {@code *.jar.pre-update} backups; runs
     * once per session before the first check (the new jar is already the
     * loaded one, so the old file is no longer locked).
     */
    public static void cleanupOnce()
    {
        if (cleanedUp)
        {
            return;
        }

        cleanedUp = true;

        if (!FMLEnvironment.production)
        {
            return;
        }

        try (Stream<Path> files = Files.list(modsDir()))
        {
            files.filter((path) -> path.getFileName().toString().endsWith(BACKUP_SUFFIX)).forEach((path) ->
            {
                try
                {
                    Files.deleteIfExists(path);
                    LOGGER.info("[BBS-SEM] topic=updates phase=cleanup removed={}", path.getFileName());
                }
                catch (IOException e)
                {
                    LOGGER.warn("[BBS-SEM] topic=updates phase=cleanup result=failed file={} error={}", path.getFileName(), e.getMessage());
                }
            });
        }
        catch (Exception e)
        {
            LOGGER.warn("[BBS-SEM] topic=updates phase=cleanup result=failed error={}", e.getMessage());
        }
    }

    private static Path modsDir()
    {
        return FMLPaths.MODSDIR.get();
    }

    private static String sanitize(String version)
    {
        return version.replaceAll("[^a-zA-Z0-9.\\-]", "_");
    }

    private static byte[] httpGet(String url) throws IOException
    {
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "BBS-FSR-Updates");

            int code = connection.getResponseCode();

            if (code != 200)
            {
                throw new IOException("HTTP " + code);
            }

            try (InputStream in = connection.getInputStream())
            {
                byte[] bytes = in.readNBytes((int) MAX_JAR_BYTES + 1);

                if (bytes.length > MAX_JAR_BYTES)
                {
                    throw new IOException("payload too large");
                }

                return bytes;
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

    private static String sha256(byte[] bytes)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder builder = new StringBuilder();

            for (byte b : digest.digest(bytes))
            {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private static void notify(UIContext context, String message, boolean error)
    {
        if (context == null)
        {
            LOGGER.info("[BBS-SEM] topic=updates phase=notify message={} error={}", message, error);

            return;
        }

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
