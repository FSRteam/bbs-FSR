package mchorse.bbs_mod.client.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression for model-block item data restoration before caching. */
final class ModelBlockItemRendererSourceTest
{
    private ModelBlockItemRendererSourceTest()
    {}

    static void runAll()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/client/renderer/item/ModelBlockItemRenderer.java");
        String getMethod = method(source, "public Item get(ItemStack stack)", "public static class Item");

        check(source.contains("DataStorageUtils.readFromNbtCompound(tag, \"Properties\")"),
            "model-block item renderer no longer restores the saved Properties payload directly");
        check(source.contains("entity.getProperties().fromData(mapType);"),
            "model-block item renderer no longer applies the restored Properties payload");
        check(!source.contains("java.lang.reflect.Method") && !source.contains("getLoadWithComponentsMethod"),
            "model-block item renderer restored the mapping-sensitive reflective load chain");
        check(getMethod.contains("blockEntityData == null || blockEntityData.isEmpty()"),
            "model-block item renderer can cache an empty item without saved Properties data");
        assertOrdered(getMethod,
            "applyBlockEntityTag(entity, blockEntityData.copyTag())",
            "Item item = new Item(entity);",
            "item.syncWorld(Minecraft.getInstance().level);",
            "this.map.put(stack, item);");
        check(getMethod.contains("cached.syncWorld(Minecraft.getInstance().level);"),
            "cached model-block items no longer adopt the current client world");
    }

    private static String readSource(String relativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();

        while (current != null)
        {
            Path source = current.resolve(relativePath);

            if (Files.isRegularFile(source))
            {
                return read(source);
            }

            Path nestedSource = current.resolve("new").resolve(relativePath);

            if (Files.isRegularFile(nestedSource))
            {
                return read(nestedSource);
            }

            current = current.getParent();
        }

        throw new AssertionError("could not locate " + relativePath);
    }

    private static String read(Path source)
    {
        try
        {
            return Files.readString(source);
        }
        catch (IOException e)
        {
            throw new AssertionError("could not read " + source, e);
        }
    }

    private static String method(String source, String startMarker, String endMarker)
    {
        int start = source.indexOf(startMarker);
        int end = start < 0 ? -1 : source.indexOf(endMarker, start + startMarker.length());

        check(start >= 0 && end > start, "could not locate production method: " + startMarker);

        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... markers)
    {
        int previous = -1;

        for (String marker : markers)
        {
            int next = source.indexOf(marker, previous + 1);

            check(next > previous, "model-block item restoration order drifted at: " + marker);
            previous = next;
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
