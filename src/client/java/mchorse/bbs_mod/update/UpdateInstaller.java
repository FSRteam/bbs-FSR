package mchorse.bbs_mod.update;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.utils.net.Hashes;
import mchorse.bbs_mod.utils.net.HttpTransfer;
import mchorse.bbs_mod.utils.net.SharedHttp;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * In-game self updater: downloads the release jar through the shared
 * download kernel (streamed to disk, SHA-256 digested during the transfer,
 * capped), then swaps it with the running mod file — the running jar is
 * renamed to {@code *.jar.pre-update} (Windows allows renaming open files)
 * and the download takes its place. The user picks up the new build on the
 * next game start; stale {@code .pre-update} backups are swept on later
 * startups.
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
    private static volatile double downloadProgress = -1D;

    public static boolean canAutoInstall(FSRUpdates.Release release)
    {
        return FMLEnvironment.production
            && release != null
            && !release.sha256.isEmpty()
            && release.url.startsWith("https://");
    }

    /** 0..1 while a jar download is running, negative otherwise. */
    public static double progress()
    {
        return downloadProgress;
    }

    public static boolean isDownloading()
    {
        return downloadProgress >= 0D;
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
                MessageDigest digest = Hashes.digest("SHA-256");

                HttpTransfer.download(SharedHttp.get(), URI.create(release.url), temp, -1L, MAX_JAR_BYTES,
                    "BBS-FSR-Updates/" + FSRUpdates.currentVersion(), digest,
                    (downloaded, total, speed, eta) ->
                    {
                        downloadProgress = total > 0L ? Math.min(1D, (double) downloaded / total) : -1D;
                    });

                String hash = HexFormat.of().formatHex(digest.digest());

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
            finally
            {
                downloadProgress = -1D;
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
