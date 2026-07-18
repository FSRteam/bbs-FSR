package mchorse.bbs_mod.compat;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Executable regressions for NeoForge tracking units and external resource ownership. */
public final class PlatformResourceConsistencyTest
{
    private PlatformResourceConsistencyTest()
    {}

    public static void main(String[] args) throws Exception
    {
        assertTrackingRangeUnits();
        assertExternalAssetOwnership();
        assertDefaultEnglishChromaSkyKey();

        System.out.println("PlatformResourceConsistencyTest: all tests passed");
    }

    private static void assertTrackingRangeUnits()
    {
        assertEquals(
            16,
            FabricRegistryCompat.toNeoForgeTrackingRange(256, FabricRegistryCompat.TrackingRangeUnit.BLOCKS),
            "256-block actor tracking range"
        );
        assertEquals(
            24,
            FabricRegistryCompat.toNeoForgeTrackingRange(24, FabricRegistryCompat.TrackingRangeUnit.CHUNKS),
            "24-chunk projectile tracking range"
        );
        assertEquals(
            2,
            FabricRegistryCompat.toNeoForgeTrackingRange(17, FabricRegistryCompat.TrackingRangeUnit.BLOCKS),
            "partial tracking chunk rounding"
        );
    }

    private static void assertExternalAssetOwnership() throws Exception
    {
        Path parent = Files.createTempDirectory("bbs-provider-test-");
        Path root = Files.createDirectory(parent.resolve("assets"));
        Path sibling = Files.createDirectory(parent.resolve("assets_backup"));
        Path nested = Files.createDirectories(root.resolve("models"));
        Path owned = Files.writeString(nested.resolve("actor.json"), "{}");
        Path foreign = Files.writeString(sibling.resolve("actor.json"), "{}");
        Path deleted = Files.writeString(root.resolve("deleted.json"), "{}");

        Files.delete(deleted);

        try
        {
            ExternalAssetsSourcePack pack = new ExternalAssetsSourcePack("assets", root.toFile()).providesFiles();

            assertLink(pack.getLink(owned.toFile()), "assets", "models/actor.json", "owned file");
            assertLink(pack.getLink(root.toFile()), "assets", "", "provider root");
            assertLink(
                pack.getLink(root.resolve("models/../models/actor.json").toFile()),
                "assets",
                "models/actor.json",
                "normalized owned file"
            );
            assertLink(pack.getLink(deleted.toFile()), "assets", "deleted.json", "deleted owned file");
            assertLink(
                pack.getLink(root.resolve("future/nested.json").toFile()),
                "assets",
                "future/nested.json",
                "future owned file"
            );

            if (pack.getLink(foreign.toFile()) != null)
            {
                throw new AssertionError("same-prefix sibling directory was claimed by the assets provider");
            }

            if (pack.getLink(root.resolve("../assets_backup/actor.json").toFile()) != null)
            {
                throw new AssertionError("parent traversal escaped the assets provider root");
            }

            assertForwardAsset(pack, new Link("assets", "models/actor.json"), owned, "owned file");
            assertDeletedForwardAsset(pack, new Link("assets", "deleted.json"), deleted);
            assertForwardRejected(pack, new Link("assets", "models/../models/actor.json"), "normalized traversal");
            assertForwardRejected(pack, new Link("assets", "../assets_backup/actor.json"), "same-prefix sibling traversal");
            assertForwardRejected(pack, new Link("assets", "..\\assets_backup\\actor.json"), "Windows sibling traversal");
            assertForwardRejected(pack, new Link("assets", owned.toAbsolutePath().toString()), "absolute owned path");
            assertForwardRejected(pack, new Link("assets", foreign.toAbsolutePath().toString()), "absolute foreign path");
            assertForwardRejected(pack, new Link("other", "models/actor.json"), "foreign source");

            List<Link> modelLinks = new ArrayList<>();

            pack.getLinksFromPath(modelLinks, new Link("assets", "models"), true);

            if (!modelLinks.contains(new Link("assets", "models/actor.json")))
            {
                throw new AssertionError("contained directory enumeration omitted models/actor.json: " + modelLinks);
            }

            assertReparsePointEscapeRejected(pack, root, sibling);
        }
        finally
        {
            Files.deleteIfExists(owned);
            Files.deleteIfExists(foreign);
            Files.deleteIfExists(nested);
            Files.deleteIfExists(root);
            Files.deleteIfExists(sibling);
            Files.deleteIfExists(parent);
        }
    }

    private static void assertReparsePointEscapeRejected(
        ExternalAssetsSourcePack pack,
        Path root,
        Path outside
    ) throws Exception
    {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
        {
            assertWindowsJunctionEscapeRejected(pack, root, outside);
        }
        else
        {
            assertPosixSymlinkEscapeRejected(pack, root, outside);
        }
    }

    private static void assertWindowsJunctionEscapeRejected(
        ExternalAssetsSourcePack pack,
        Path root,
        Path outside
    ) throws Exception
    {
        Path junction = root.resolve("outside-junction");
        Process process;

        try
        {
            process = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                "mklink",
                "/J",
                junction.toString(),
                outside.toString()
            ).redirectErrorStream(true).start();
        }
        catch (IOException | SecurityException e)
        {
            System.out.println("Windows junction regression skipped: mklink is unavailable (" + e.getMessage() + ")");

            return;
        }

        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
        int exitCode = process.waitFor();

        if (exitCode != 0)
        {
            System.out.println("Windows junction regression skipped: mklink /J is unavailable (" + output + ")");

            return;
        }

        try
        {
            assertNotOwned(pack.getLink(junction.resolve("actor.json").toFile()), "existing junction target");
            assertNotOwned(pack.getLink(junction.resolve("future.json").toFile()), "future junction target");
            assertForwardRejected(pack, new Link("assets", "outside-junction/actor.json"), "existing junction target");
            assertForwardRejected(pack, new Link("assets", "outside-junction/future.json"), "future junction target");
            assertEnumerationRejected(pack, new Link("assets", "outside-junction"), "junction directory");
        }
        finally
        {
            Files.deleteIfExists(junction);
        }
    }

    private static void assertPosixSymlinkEscapeRejected(
        ExternalAssetsSourcePack pack,
        Path root,
        Path outside
    ) throws Exception
    {
        Path symlink = root.resolve("outside-symlink");

        try
        {
            Files.createSymbolicLink(symlink, outside);
        }
        catch (IOException | UnsupportedOperationException | SecurityException e)
        {
            System.out.println("POSIX symlink regression skipped: symbolic links are unavailable (" + e.getMessage() + ")");

            return;
        }

        try
        {
            assertNotOwned(pack.getLink(symlink.resolve("actor.json").toFile()), "existing symlink target");
            assertNotOwned(pack.getLink(symlink.resolve("future.json").toFile()), "future symlink target");
            assertForwardRejected(pack, new Link("assets", "outside-symlink/actor.json"), "existing symlink target");
            assertForwardRejected(pack, new Link("assets", "outside-symlink/future.json"), "future symlink target");
            assertEnumerationRejected(pack, new Link("assets", "outside-symlink"), "symlink directory");
        }
        finally
        {
            Files.deleteIfExists(symlink);
        }
    }

    private static void assertDefaultEnglishChromaSkyKey() throws Exception
    {
        String key = "\"bbs.ui.camera.panels.curves.chroma_sky_color\"";
        String english = Files.readString(Path.of("src/client/resources/assets/bbs/assets/strings/en_us.json"));
        String simplifiedChinese = Files.readString(Path.of("src/client/resources/assets/bbs/assets/strings/zh_cn.json"));

        if (!english.contains(key) || !simplifiedChinese.contains(key))
        {
            throw new AssertionError("canonical chroma sky color key is inconsistent across default language files");
        }
    }

    private static void assertForwardAsset(
        ExternalAssetsSourcePack pack,
        Link link,
        Path expected,
        String label
    ) throws Exception
    {
        if (!pack.hasAsset(link))
        {
            throw new AssertionError(label + " was not found through the provider");
        }

        if (pack.getFile(link) == null
            || !pack.getFile(link).toPath().toRealPath().equals(expected.toRealPath()))
        {
            throw new AssertionError(label + " did not resolve to the contained file");
        }

        try (InputStream stream = pack.getAsset(link))
        {
            if (!"{}".equals(new String(stream.readAllBytes(), Charset.defaultCharset())))
            {
                throw new AssertionError(label + " returned unexpected bytes");
            }
        }
    }

    private static void assertDeletedForwardAsset(ExternalAssetsSourcePack pack, Link link, Path expected) throws Exception
    {
        if (pack.hasAsset(link))
        {
            throw new AssertionError("deleted contained leaf still exists through the provider");
        }

        if (pack.getFile(link) == null
            || !pack.getFile(link).toPath().toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize()))
        {
            throw new AssertionError("deleted contained leaf lost its owned provider path");
        }

        try (InputStream ignored = pack.getAsset(link))
        {
            throw new AssertionError("deleted contained leaf unexpectedly opened");
        }
        catch (IOException expectedFailure)
        {}
    }

    private static void assertForwardRejected(ExternalAssetsSourcePack pack, Link link, String label) throws Exception
    {
        if (pack.hasAsset(link) || pack.getFile(link) != null)
        {
            throw new AssertionError(label + " escaped provider containment");
        }

        try (InputStream ignored = pack.getAsset(link))
        {
            throw new AssertionError(label + " unexpectedly opened through the provider");
        }
        catch (IOException expected)
        {}

        assertEnumerationRejected(pack, link, label);
    }

    private static void assertEnumerationRejected(ExternalAssetsSourcePack pack, Link link, String label)
    {
        List<Link> links = new ArrayList<>();

        pack.getLinksFromPath(links, link, true);

        if (!links.isEmpty())
        {
            throw new AssertionError(label + " enumerated foreign provider entries: " + links);
        }
    }

    private static void assertLink(Link link, String source, String path, String label)
    {
        if (link == null || !source.equals(link.source) || !path.equals(link.path))
        {
            throw new AssertionError(label + " expected " + source + ":" + path + " but got " + link);
        }
    }

    private static void assertNotOwned(Link link, String label)
    {
        if (link != null)
        {
            throw new AssertionError(label + " escaped provider containment as " + link);
        }
    }

    private static void assertEquals(int expected, int actual, String label)
    {
        if (expected != actual)
        {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }
}
