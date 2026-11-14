package mchorse.bbs_mod.resources;

import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real archive-listing concurrency and source priority regressions. */
public final class AssetProviderParityTest
{
    public static void main(String[] args) throws Exception
    {
        runAll();
    }

    public static void runAll() throws Exception
    {
        sourcePriorityAndSortedDiscovery();
        concurrentArchiveListingAndReplacement();
        transparentPixelCompositing();
        System.out.println("AssetProviderParityTest passed");
    }

    private static void sourcePriorityAndSortedDiscovery() throws Exception
    {
        AssetProvider provider = new AssetProvider();
        AtomicInteger opens = new AtomicInteger();
        provider.register(pack(1, opens, "models/z.json", "models/a.json"));
        AutoCloseable priority = provider.registerFirstCloseable(pack(2, opens, "models/a.json", "models/b.json"));
        Link asset = Link.assets("models/a.json");
        check(!provider.hasAsset(null) && provider.hasAsset(asset) && opens.get() == 0,
            "Asset existence probing must not open streams or reject a registered source");
        List<String> names = provider.getLinksFromPath(Link.assets("models/"), true).stream().map(Link::toString).toList();
        check(names.equals(List.of("assets:models/a.json", "assets:models/b.json", "assets:models/z.json")),
            "Model discovery must be sorted and de-duplicated across source packs");
        try (InputStream stream = provider.getAsset(asset))
        {
            check(stream.read() == 2, "A preferred pack must win over a fallback with the same asset");
        }
        priority.close();
        priority.close();
        try (InputStream stream = provider.getAsset(asset))
        {
            check(stream.read() == 1, "Closing a source registration must leave the fallback available");
        }
    }

    private static ISourcePack pack(int value, AtomicInteger opens, String... names)
    {
        return new ISourcePack()
        {
            public String getPrefix() { return Link.ASSETS; }
            public boolean hasAsset(Link link) { return List.of(names).contains(link.path); }
            public InputStream getAsset(Link link)
            {
                opens.incrementAndGet();
                return new ByteArrayInputStream(new byte[] {(byte) value});
            }
            public File getFile(Link link) { return null; }
            public Link getLink(File file) { return null; }
            public void getLinksFromPath(Collection<Link> links, Link link, boolean recursive)
            {
                for (String name : names) links.add(Link.assets(name));
            }
        };
    }

    private static void transparentPixelCompositing()
    {
        Pixels foreground = Pixels.fromSize(1, 1);
        Pixels ordinary = Pixels.fromSize(2, 2);
        Pixels scaled = Pixels.fromSize(2, 2);
        Pixels explicit = Pixels.fromSize(2, 2);

        try
        {
            foreground.setColor(0, 0, new Color(1F, 0.3F, 0.7F, 0F));
            ordinary.draw(foreground, 0, 0);
            scaled.draw(foreground, 0, 0, 2, 2);
            for (Pixels result : List.of(ordinary, scaled))
            {
                Color pixel = result.getColor(0, 0);
                check(pixel.a == 0F && pixel.r == 0F && pixel.g == 0F && pixel.b == 0F,
                    "Fully transparent compositing must return a finite zero color");
            }
            foreground.setColor(0, 0, new Color(0.8F, 0.2F, 0.6F, 0.5F));
            ordinary.draw(foreground, 1, 1);
            explicit.draw(foreground, 1, 1, 1F);
            Color actual = ordinary.getColor(1, 1);
            Color expected = explicit.getColor(1, 1);
            check(actual.getARGBColor() == expected.getARGBColor() && actual.a > 0.49F && actual.a < 0.51F,
                "The default draw overload must preserve alpha and share the explicit-opacity calculation");
        }
        finally
        {
            foreground.delete();
            ordinary.delete();
            scaled.delete();
            explicit.delete();
        }
    }

    private static void concurrentArchiveListingAndReplacement() throws Exception
    {
        Path directory = Files.createTempDirectory("bbs-fs26-assets-");
        Path first = directory.resolve("first.jar");
        Path replacement = directory.resolve("replacement.jar");
        ExecutorService executor = Executors.newFixedThreadPool(4);

        try
        {
            archive(first, "models/original", 1000);
            archive(replacement, "models/replacement", 3);
            InternalAssetsSourcePack pack = new InternalAssetsSourcePack();
            Method list = InternalAssetsSourcePack.class.getDeclaredMethod("getLinksFromZipFile",
                File.class, Link.class, Collection.class, boolean.class);
            list.setAccessible(true);
            CyclicBarrier start = new CyclicBarrier(4);
            List<Future<List<Link>>> readers = new ArrayList<>();

            for (int worker = 0; worker < 4; worker++)
            {
                readers.add(executor.submit(() ->
                {
                    start.await(10, TimeUnit.SECONDS);
                    List<Link> links = new ArrayList<>();
                    list.invoke(pack, first.toFile(), Link.assets("models/"), links, true);
                    return links;
                }));
            }

            for (Future<List<Link>> reader : readers)
            {
                List<Link> links = reader.get(30, TimeUnit.SECONDS);
                check(links.size() == 1000 && links.stream().map(Link::toString).distinct().count() == 1000,
                    "Concurrent lazy archive cache readers saw missing or duplicate entries");
            }

            List<Link> links = new ArrayList<>();
            list.invoke(pack, replacement.toFile(), Link.assets("models/"), links, true);
            check(links.size() == 3 && links.stream().allMatch(link -> link.path.startsWith("models/replacement")),
                "Switching a pack archive must invalidate the remembered source cache");
        }
        finally
        {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            Files.deleteIfExists(first);
            Files.deleteIfExists(replacement);
            Files.deleteIfExists(directory);
        }
    }

    private static void archive(Path path, String prefix, int entries) throws Exception
    {
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(path)))
        {
            for (int index = 0; index < entries; index++)
            {
                archive.putNextEntry(new ZipEntry("assets/bbs/assets/" + prefix + index + ".json"));
                archive.write(0);
                archive.closeEntry();
            }
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
