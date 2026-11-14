package mchorse.bbs_mod.utils.keyframes.factories;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.GameRegistries;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.IInterp;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Supplier;

public class ItemStackKeyframeFactory implements IKeyframeFactory<ItemStack>
{
    /* Recording encodes every tick, so report a failed nonempty stack without flooding the log. */
    private static int errorLog;

    @Override
    public boolean isStepped()
    {
        return true;
    }

    /**
     * Compatibility injection into the shared registry owner. The server's
     * owning thread still selects its own provider. A {@code null} supplier
     * clears this override; normal bootstrap sources remain available.
     */
    public static void setClientRegistryAccess(Supplier<HolderLookup.Provider> provider)
    {
        GameRegistries.setClientRegistryAccess(provider);
    }

    /**
     * The {@code DynamicOps} for the current thread's registry context, falling
     * back to a plain {@link NbtOps} when no registry is available. Client UI
     * panels use this so item NBT display and editing handle registry-held
     * components such as enchantments.
     */
    public static DynamicOps<Tag> currentOps()
    {
        return GameRegistries.nbtOps();
    }

    @Override
    public ItemStack fromData(BaseType data)
    {
        return this.fromData(data, GameRegistries.lookup());
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
        return this.tryFromData(data, GameRegistries.lookup());
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
            DataResult<Pair<ItemStack, Tag>> decode = ItemStack.CODEC.decode(GameRegistries.nbtOps(provider), DataStorageUtils.toNbt(data));

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
        return this.toData(value, GameRegistries.lookup());
    }

    public BaseType toData(ItemStack value, HolderLookup.Provider provider)
    {
        DataResult<Tag> encoded = ItemStack.CODEC.encodeStart(GameRegistries.nbtOps(provider), value);
        Optional<Tag> result = encoded.result();

        if (result.isEmpty() && !value.isEmpty() && errorLog++ % 200 == 0)
        {
            System.out.println("[BBS] couldn't write down item stack " + value + ": "
                + encoded.error().map(DataResult.Error::message).orElse("unknown error"));
        }

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
