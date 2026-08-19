package mchorse.bbs_mod.utils.keyframes.factories;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.IInterp;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.util.thread.SidedThreadGroups;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Optional;
import java.util.function.Supplier;

public class ItemStackKeyframeFactory implements IKeyframeFactory<ItemStack>
{
    /**
     * Item components such as enchantments encode registry holders through
     * {@code RegistryFixedCodec}, which fails with a plain {@code NbtOps}.
     *
     * <p>The registry context is resolved per call from the current environment
     * (see {@link #currentRegistry()}), not from a process-wide provider: the
     * server thread uses the server registry so server-side playback decodes
     * holders bound to the server registry, and the client thread uses the
     * client level registry (installed via {@link #setClientRegistryAccess})
     * so recording and the film editor preserve client-bound holders. Callers
     * that already hold a provider can pass it explicitly to the
     * {@code ...(value, provider)} overloads.</p>
     */
    private static volatile Supplier<HolderLookup.Provider> clientRegistryAccess;

    /**
     * Install the client level registry access. Only consulted when no server
     * thread is active, so server-side decode always binds holders to the
     * server registry. A {@code null} supplier clears the client access.
     */
    public static void setClientRegistryAccess(Supplier<HolderLookup.Provider> provider)
    {
        clientRegistryAccess = provider;
    }

    private static HolderLookup.Provider currentRegistry()
    {
        if (isServerThread())
        {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

            if (server != null)
            {
                return server.registryAccess();
            }
        }

        Supplier<HolderLookup.Provider> client = clientRegistryAccess;

        return client == null ? null : client.get();
    }

    /**
     * The server keeps its own registry instances on the server thread (and
     * its workers), which are distinct from the client thread's instances even
     * in an integrated server. {@code SidedThreadGroups.SERVER} identifies that
     * thread group, so film decode during server-side playback binds holders to
     * the server registry and stream encoding through {@code
     * RegistryFriendlyByteBuf} succeeds.
     */
    private static boolean isServerThread()
    {
        for (ThreadGroup group = Thread.currentThread().getThreadGroup(); group != null; group = group.getParent())
        {
            if (group == SidedThreadGroups.SERVER)
            {
                return true;
            }
        }

        return false;
    }

    private static DynamicOps<Tag> ops(HolderLookup.Provider provider)
    {
        return provider == null ? NbtOps.INSTANCE : provider.createSerializationContext(NbtOps.INSTANCE);
    }

    /**
     * The {@code DynamicOps} for the current thread's registry context, falling
     * back to a plain {@link NbtOps} when no registry is available. Client UI
     * panels use this so item NBT display and editing handle registry-held
     * components such as enchantments.
     */
    public static DynamicOps<Tag> currentOps()
    {
        return ops(currentRegistry());
    }

    @Override
    public ItemStack fromData(BaseType data)
    {
        return this.fromData(data, currentRegistry());
    }

    public ItemStack fromData(BaseType data, HolderLookup.Provider provider)
    {
        return this.tryFromData(data, provider).orElse(ItemStack.EMPTY);
    }

    /**
     * Unlike {@link #fromData(BaseType)}, this preserves decode failure so a
     * network mutation can stage the complete inventory before touching the
     * player. An empty map is the existing serialized representation of an
     * empty slot and remains valid.
     */
    public Optional<ItemStack> tryFromData(BaseType data)
    {
        return this.tryFromData(data, currentRegistry());
    }

    public Optional<ItemStack> tryFromData(BaseType data, HolderLookup.Provider provider)
    {
        if (data == null)
        {
            return Optional.empty();
        }

        if (data instanceof MapType map && map.isEmpty())
        {
            return Optional.of(ItemStack.EMPTY);
        }

        try
        {
            DataResult<Pair<ItemStack, Tag>> decode = ItemStack.CODEC.decode(ops(provider), DataStorageUtils.toNbt(data));

            return decode.result().map(Pair::getFirst);
        }
        catch (RuntimeException e)
        {
            return Optional.empty();
        }
    }

    @Override
    public BaseType toData(ItemStack value)
    {
        return this.toData(value, currentRegistry());
    }

    public BaseType toData(ItemStack value, HolderLookup.Provider provider)
    {
        Optional<Tag> result = ItemStack.CODEC.encodeStart(ops(provider), value).result();

        return result.map(DataStorageUtils::fromNbt).orElse(new MapType());
    }

    @Override
    public ItemStack createEmpty()
    {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean compare(Object a, Object b)
    {
        if (a instanceof ItemStack itemA && b instanceof ItemStack itemB)
        {
            return ItemStack.matches(itemA, itemB);
        }

        return false;
    }

    @Override
    public ItemStack copy(ItemStack value)
    {
        return value.copy();
    }

    @Override
    public ItemStack interpolate(ItemStack preA, ItemStack a, ItemStack b, ItemStack postB, IInterp interpolation, float x)
    {
        return a;
    }
}
