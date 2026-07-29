package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.client.renderer.item.ModelBlockItemRenderer;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Regression coverage for model-block item data restoration and cache invalidation. */
final class ModelBlockItemRendererSourceTest
{
    private ModelBlockItemRendererSourceTest()
    {}

    static void runAll()
    {
        cacheInvalidationStaysWired();
        customDataSnapshotsUseDeepEquality();
        savedDisplayFormsRoundTripThroughProductionParser();
    }

    private static void cacheInvalidationStaysWired()
    {
        String source = readSource("src/client/java/mchorse/bbs_mod/client/renderer/item/ModelBlockItemRenderer.java");
        String getMethod = method(source, "public Item get(ItemStack stack)", "public static class Item");
        String updateMethod = method(source, "public void update()", "public void render(ItemStack stack");
        String itemClass = method(source, "public static class Item", "private static float getTickDelta()");

        check(source.contains("DataStorageUtils.readFromNbtCompound(blockEntityData.copyTag(), \"Properties\")"),
            "model-block item renderer no longer restores the saved Properties payload directly");
        check(source.contains("properties.fromData(mapType);"),
            "model-block item renderer no longer applies the restored Properties payload");
        check(source.contains("private final Map<ItemStack, Item> map = new IdentityHashMap<>();"),
            "equal item-stack copies can share mutable model-block preview state");
        check(!source.contains("java.lang.reflect.Method") && !source.contains("getLoadWithComponentsMethod"),
            "model-block item renderer restored the mapping-sensitive reflective load chain");
        assertOrdered(getMethod,
            "CustomData blockEntityData = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY);",
            "Item cached = this.map.get(stack);",
            "cached != null && cached.matches(blockEntityData)",
            "return cached;",
            "BBSMod.getForms() == null",
            "applyBlockEntityData(entity.getProperties(), blockEntityData);",
            "Item item = new Item(entity, blockEntityData);",
            "item.syncWorld(Minecraft.getInstance().level);",
            "this.map.put(stack, item);",
            "replaced.release();");
        check(getMethod.contains("cached.expiration = 20;"),
            "data-less fallback items no longer keep their cache entries alive while rendered");
        check(itemClass.contains("public Item(ModelBlockEntity entity)"),
            "the existing public renderer Item constructor descriptor changed");
        check(itemClass.contains("private final CustomData blockEntityData;"),
            "renderer items no longer retain an immutable block-entity data snapshot");
        check(itemClass.contains("this.blockEntityData.equals("),
            "renderer item cache reuse no longer compares the current component snapshot");
        check(itemClass.contains("properties.getFormThirdPerson()")
                && itemClass.contains("properties.getFormInventory()")
                && itemClass.contains("properties.getFormFirstPerson()"),
            "renderer item retirement no longer releases every display-specific Form");
        assertOrdered(updateMethod, "it.remove();", "item.release();", "continue;");
    }

    private static void customDataSnapshotsUseDeepEquality()
    {
        CompoundTag original = new CompoundTag();
        CompoundTag properties = new CompoundTag();

        properties.putString("texture", "assets:test/default.png");
        original.put("Properties", properties);

        CustomData snapshot = CustomData.of(original);
        CustomData equalSnapshot = CustomData.of(original.copy());
        CompoundTag changed = original.copy();

        changed.getCompound("Properties").putString("texture", "assets:test/replacement.png");

        check(snapshot.equals(equalSnapshot),
            "equal BLOCK_ENTITY_DATA payloads do not compare by deep NBT value");
        check(!snapshot.equals(CustomData.of(changed)),
            "changed BLOCK_ENTITY_DATA payload did not invalidate the immutable snapshot");

        original.getCompound("Properties").putString("texture", "assets:test/mutated-after-copy.png");
        check(snapshot.equals(equalSnapshot),
            "CustomData retained a mutable reference to the source CompoundTag");
    }

    private static void savedDisplayFormsRoundTripThroughProductionParser()
    {
        bootstrapStandaloneMinecraftRuntime();

        Field formsField;

        try
        {
            formsField = BBSMod.class.getDeclaredField("forms");
            formsField.setAccessible(true);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not access the FormArchitect bootstrap field", e);
        }

        FormArchitect previous;

        try
        {
            previous = (FormArchitect) formsField.get(null);
        }
        catch (IllegalAccessException e)
        {
            throw new AssertionError("could not read the FormArchitect bootstrap field", e);
        }

        FormArchitect forms = new FormArchitect();

        forms.register(Link.bbs("billboard"), BillboardForm.class, null);

        try
        {
            formsField.set(null, forms);

            CustomData defaultPlayback = savedProperties("default-playback");
            CustomData replacementPlayback = savedProperties("replacement-playback");

            check(!defaultPlayback.equals(replacementPlayback),
                "replacement replay fixture did not change BLOCK_ENTITY_DATA");
            assertDisplayTextures(parse(defaultPlayback), "default-playback");
            assertDisplayTextures(parse(replacementPlayback), "replacement-playback");
        }
        catch (IllegalAccessException e)
        {
            throw new AssertionError("could not seed the FormArchitect bootstrap field", e);
        }
        finally
        {
            try
            {
                formsField.set(null, previous);
            }
            catch (IllegalAccessException e)
            {
                throw new AssertionError("could not restore the FormArchitect bootstrap field", e);
            }
        }
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    private static CustomData savedProperties(String fixture)
    {
        ModelProperties properties = new ModelProperties();

        properties.setForm(billboard(fixture + "/default.png"));
        properties.setFormFirstPerson(billboard(fixture + "/first-person.png"));
        properties.setFormThirdPerson(billboard(fixture + "/third-person.png"));
        properties.setFormInventory(billboard(fixture + "/gui.png"));

        CompoundTag tag = new CompoundTag();

        DataStorageUtils.writeToNbtCompound(tag, "Properties", properties.toData());

        return CustomData.of(tag);
    }

    private static BillboardForm billboard(String texture)
    {
        BillboardForm form = new BillboardForm();

        form.texture.set(Link.assets(texture));

        return form;
    }

    private static ModelProperties parse(CustomData data)
    {
        ModelProperties properties = new ModelProperties();

        try
        {
            Method parser = ModelBlockItemRenderer.class.getDeclaredMethod(
                "applyBlockEntityData", ModelProperties.class, CustomData.class);

            parser.setAccessible(true);
            parser.invoke(null, properties, data);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("could not invoke the production model-block Properties parser", e);
        }

        return properties;
    }

    private static void assertDisplayTextures(ModelProperties properties, String fixture)
    {
        assertTexture(properties.getForm(ItemDisplayContext.GROUND), fixture + "/default.png", "default");
        assertTexture(properties.getForm(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND),
            fixture + "/first-person.png", "first-person");
        assertTexture(properties.getForm(ItemDisplayContext.THIRD_PERSON_LEFT_HAND),
            fixture + "/third-person.png", "third-person");
        assertTexture(properties.getForm(ItemDisplayContext.GUI), fixture + "/gui.png", "GUI");
    }

    private static void assertTexture(Form form, String expected, String context)
    {
        check(form instanceof BillboardForm,
            context + " saved Form was not restored as a BillboardForm");
        check(Link.assets(expected).equals(((BillboardForm) form).texture.get()),
            context + " saved Form restored the wrong texture");
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
