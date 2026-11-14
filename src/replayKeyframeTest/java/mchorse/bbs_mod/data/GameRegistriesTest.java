package mchorse.bbs_mod.data;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.utils.keyframes.factories.BlockStateKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.ItemStackKeyframeFactory;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.util.thread.SidedThreadGroups;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Real codec and side-selection regressions; the caller bootstraps vanilla registries. */
public final class GameRegistriesTest
{
    private GameRegistriesTest()
    {}

    public static void runAll() throws Exception
    {
        Field sourcesField = GameRegistries.class.getDeclaredField("SOURCES");
        Field overrideField = GameRegistries.class.getDeclaredField("clientRegistryAccess");

        sourcesField.setAccessible(true);
        overrideField.setAccessible(true);

        List<?> previousSources = new ArrayList<>((List<?>) sourcesField.get(null));
        Object previousOverride = overrideField.get(null);

        try
        {
            RegistryFixture client = registry();
            RegistryFixture server = registry();

            clearSources(sourcesField);
            sourceOrderAndAvailability(client.provider(), server.provider(), sourcesField);
            clearSources(sourcesField);
            sideOwnershipAndCodecs(client, server);
        }
        finally
        {
            clearSources(sourcesField);
            overrideField.set(null, previousOverride);

            for (Object source : previousSources)
            {
                GameRegistries.addSource((GameRegistries.Source) source);
            }
        }
    }

    private static void clearSources(Field sourcesField) throws IllegalAccessException
    {
        ItemStackKeyframeFactory.setClientRegistryAccess(null);
        ((List<?>) sourcesField.get(null)).clear();
    }

    private static void sourceOrderAndAvailability(HolderLookup.Provider client, HolderLookup.Provider server,
        Field sourcesField) throws IllegalAccessException
    {
        require(GameRegistries.lookup() == null, "no-world lookup retained a registry");
        require(GameRegistries.nbtOps() == NbtOps.INSTANCE, "no-world codecs need plain NBT ops");

        GameRegistries.addSource(source(() -> client, () -> false));
        GameRegistries.addSource(source(() -> server, () -> false));
        require(GameRegistries.lookup() == client, "fallback must use the first available source");

        GameRegistries.addPreferredSource(source(() -> server, () -> false));
        GameRegistries.addPreferredSource(source(() -> null, () -> true));
        GameRegistries.addSource(null);
        GameRegistries.addPreferredSource(null);
        require(GameRegistries.lookup() == server, "preferred fallback or unavailable source handling changed");

        GameRegistries.addSource(source(() -> client, () -> true));
        require(GameRegistries.lookup() == client, "an owning source must beat a preferred fallback");

        clearSources(sourcesField);

        AtomicReference<HolderLookup.Provider> current = new AtomicReference<>(client);

        GameRegistries.addSource(source(current::get, () -> true));

        DynamicOps<Tag> original = GameRegistries.nbtOps();

        require(original == GameRegistries.nbtOps(client), "the shared per-provider cache was bypassed");
        require(original == ItemStackKeyframeFactory.currentOps(), "legacy ops accessor has another owner");
        current.set(server);

        DynamicOps<Tag> replacement = GameRegistries.nbtOps();

        require(replacement != original, "replacing a world reused the previous registry ops");
        require(replacement == GameRegistries.nbtOps(server), "the replacement provider was not cached");
        current.set(null);
        require(GameRegistries.nbtOps() == NbtOps.INSTANCE, "leaving a world retained its registry ops");
    }

    private static void sideOwnershipAndCodecs(RegistryFixture client, RegistryFixture server) throws Exception
    {
        GameRegistries.addSource(source(server::provider, GameRegistries::isServerThread));
        ItemStackKeyframeFactory.setClientRegistryAccess(client::provider);
        require(GameRegistries.lookup() == client.provider(), "legacy client injection was ignored");

        ItemStackKeyframeFactory factory = new ItemStackKeyframeFactory();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);

        sword.enchant(client.enchantments().getHolderOrThrow(Enchantments.SHARPNESS), 5);

        BaseType encoded = DataStorageUtils.readFromBytes(DataStorageUtils.writeToBytes(factory.toData(sword)));
        Tag pickerNbt = ItemStack.CODEC.encodeStart(GameRegistries.nbtOps(), sword).result()
            .orElseThrow(() -> new AssertionError("the item picker could not encode an enchanted item"));

        require(pickerNbt instanceof CompoundTag tag && !tag.isEmpty(), "picker NBT degraded to an empty map");
        require(ItemStack.isSameItemSameComponents(sword,
            ItemStack.CODEC.parse(GameRegistries.nbtOps(), pickerNbt).result().orElse(ItemStack.EMPTY)),
            "picker NBT editing lost enchanted components");

        Runnable serverCodec = () ->
        {
            require(GameRegistries.isServerThread(), "NeoForge server thread ancestry was not recognized");
            require(GameRegistries.lookup() == server.provider(), "a server worker selected the client registry");

            ItemStack decoded = factory.tryFromData(encoded)
                .orElseThrow(() -> new AssertionError("server item decode failed"));

            require(decoded.getEnchantmentLevel(server.enchantments().getHolderOrThrow(Enchantments.SHARPNESS)) == 5,
                "server decode lost the enchantment level");
            require(decoded.get(DataComponents.ENCHANTMENTS).keySet().iterator().next()
                == server.enchantments().getHolderOrThrow(Enchantments.SHARPNESS),
                "server decode bound enchantments to the wrong registry");
            require(decoded.get(DataComponents.ENCHANTMENTS).keySet().iterator().next()
                != client.enchantments().getHolderOrThrow(Enchantments.SHARPNESS),
                "server decode retained client-owned holders");
            require(GameRegistries.nbtOps() == GameRegistries.nbtOps(server.provider()),
                "server codec bypassed the shared ops cache");
        };

        inThreadGroup(SidedThreadGroups.SERVER, serverCodec);
        inThreadGroup(new ThreadGroup(SidedThreadGroups.SERVER, "bbs-registry-worker"), serverCodec);

        ItemStack explicit = factory.fromData(encoded, server.provider());

        require(explicit.get(DataComponents.ENCHANTMENTS).keySet().iterator().next()
            == server.enchantments().getHolderOrThrow(Enchantments.SHARPNESS),
            "explicit-provider decode was replaced by the selected client provider");
        require(factory.tryFromData(factory.toData(explicit, server.provider()), server.provider()).isPresent(),
            "explicit-provider encode/decode no longer round trips");
        require(factory.tryFromData(null, server.provider()).isEmpty(), "null input lost its typed failure");
        require(factory.tryFromData(new StringType("invalid"), server.provider()).isEmpty(),
            "invalid input lost its typed failure");
        require(factory.tryFromData(new MapType(), server.provider()).orElseThrow().isEmpty(),
            "the existing empty-slot representation became invalid");

        BlockStateKeyframeFactory blocks = new BlockStateKeyframeFactory();
        BlockState original = Blocks.OAK_STAIRS.defaultBlockState();

        require(original.equals(blocks.fromData(blocks.toData(original))), "shared block-state codec did not round trip");

        ItemStackKeyframeFactory.setClientRegistryAccess(server::provider);
        require(GameRegistries.lookup() == server.provider(), "replacing the legacy injection retained its old provider");
        ItemStackKeyframeFactory.setClientRegistryAccess(null);
        require(GameRegistries.lookup() == server.provider(), "clearing the injection removed the normal sources");
    }

    private static GameRegistries.Source source(Supplier<HolderLookup.Provider> lookup, BooleanSupplier ownsThread)
    {
        return new GameRegistries.Source()
        {
            @Override
            public HolderLookup.Provider lookup()
            {
                return lookup.get();
            }

            @Override
            public boolean isOwnThread()
            {
                return ownsThread.getAsBoolean();
            }
        };
    }

    private static void inThreadGroup(ThreadGroup group, Runnable action) throws Exception
    {
        FutureTask<Void> task = new FutureTask<>(() ->
        {
            action.run();

            return null;
        });

        new Thread(group, task, "bbs-registry-codec-test").start();
        task.get(10, TimeUnit.SECONDS);
    }

    private static RegistryFixture registry()
    {
        MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        Enchantment enchantment = Enchantment.enchantment(Enchantment.definition(HolderSet.direct(), 10, 5,
            Enchantment.dynamicCost(1, 11), Enchantment.dynamicCost(21, 11), 2, EquipmentSlotGroup.MAINHAND))
            .build(Enchantments.SHARPNESS.location());

        enchantments.register(Enchantments.SHARPNESS, enchantment, RegistrationInfo.BUILT_IN);

        return new RegistryFixture(enchantments, new RegistryAccess.ImmutableRegistryAccess(List.of(enchantments)));
    }

    private record RegistryFixture(MappedRegistry<Enchantment> enchantments, HolderLookup.Provider provider)
    {}

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
