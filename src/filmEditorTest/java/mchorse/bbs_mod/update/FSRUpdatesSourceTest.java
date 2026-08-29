package mchorse.bbs_mod.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-level and pure-logic regression for the FSR update push system: the
 * version comparison math, the decision order in the checker (revoked warning
 * outranks every silence rule, skip means "next version"), the installer's
 * hash gate before any file swap, and the worker's release validation caps.
 */
public final class FSRUpdatesSourceTest
{
    private static final Path CHECKER = Path.of("src/client/java/mchorse/bbs_mod/update/FSRUpdates.java");
    private static final Path POPUP = Path.of("src/client/java/mchorse/bbs_mod/update/UIUpdateOverlayPanel.java");
    private static final Path INSTALLER = Path.of("src/client/java/mchorse/bbs_mod/update/UpdateInstaller.java");
    private static final Path WORKER = Path.of("../../film-home-publisher/worker/worker.js");
    private static final Path EN_US = Path.of("src/client/resources/assets/bbs/assets/strings/en_us.json");
    private static final Path ZH_CN = Path.of("src/client/resources/assets/bbs/assets/strings/zh_cn.json");

    public static void runAll() throws IOException
    {
        testVersionComparison();

        String checker = Files.readString(CHECKER);
        String popup = Files.readString(POPUP);
        String installer = Files.readString(INSTALLER);
        String worker = Files.readString(WORKER);
        String enUs = Files.readString(EN_US);
        String zhCn = Files.readString(ZH_CN);

        int revokedBranch = checker.indexOf("if (revoked != null)");
        int upToDateBranch = checker.indexOf("compareVersions(current, latest.version) >= 0");

        check(revokedBranch >= 0 && upToDateBranch > revokedBranch,
            "the revoked warning must be decided before the up-to-date silence rule");

        check(checker.contains("latest.version.equals(BBSSettings.updateSkippedVersion.get())"),
            "skipped versions must be matched against the pushed latest version");

        check(checker.contains("if (manual || !fromCache)"),
            "cached fallback data must never auto-popup, only manual checks may");

        check(checker.contains("FMLEnvironment") == false && installer.contains("FMLEnvironment.production"),
            "auto-install gating must live in the installer, keyed on production");

        check(popup.contains("!this.release.mandatory"),
            "mandatory releases must not offer the skip button");

        check(installer.contains("!hash.equals(release.sha256)"),
            "the downloaded jar must fail closed on a hash mismatch");

        check(installer.contains("BACKUP_SUFFIX"),
            "the swapped-out jar must be parked as .pre-update for the startup sweep");

        check(installer.contains("Files.deleteIfExists(temp)"),
            "a rejected download must be removed from the mods dir");

        check(worker.contains("MAX_RELEASES = 20"),
            "the release list cap must stay at 20");

        check(worker.contains("!release.url.startsWith(\"https://\")"),
            "release download urls must be https-enforced");

        check(enUs.contains("bbs.updates.install") && zhCn.contains("bbs.updates.install"),
            "update strings must be localized in en_us and zh_cn");
    }

    private static void testVersionComparison()
    {
        check(FSRUpdates.compareVersions("0.0.11", "0.0.12") < 0, "0.0.11 < 0.0.12");
        check(FSRUpdates.compareVersions("0.0.12", "0.0.12") == 0, "0.0.12 == 0.0.12");
        check(FSRUpdates.compareVersions("0.0.13", "0.0.12") > 0, "0.0.13 > 0.0.12");
        check(FSRUpdates.compareVersions("0.1.0", "0.0.99") > 0, "0.1.0 > 0.0.99");
        check(FSRUpdates.compareVersions("0.0.12", "0.0.12-beta.9") > 0, "stable outranks beta at same numerics");
        check(FSRUpdates.compareVersions("0.0.12-beta.1", "0.0.12-beta.2") < 0, "later beta is newer");
        check(FSRUpdates.compareVersions("0.0.12-beta.1", "0.0.11") > 0, "beta of a newer numerics outranks older stable");
        check(FSRUpdates.compareVersions("garbage", "0.0.12") == 0, "unparsable versions compare equal (silent)");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private FSRUpdatesSourceTest()
    {}
}
