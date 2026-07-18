package mchorse.bbs_mod.utils.keyframes.factories;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.IInterp;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public class ItemStackKeyframeFactory implements IKeyframeFactory<ItemStack>
{
    @Override
    public ItemStack fromData(BaseType data)
    {
        return this.tryFromData(data).orElse(ItemStack.EMPTY);
    }

    /**
     * Unlike {@link #fromData(BaseType)}, this preserves decode failure so a
     * network mutation can stage the complete inventory before touching the
     * player. An empty map is the existing serialized representation of an
     * empty slot and remains valid.
     */
    public Optional<ItemStack> tryFromData(BaseType data)
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
            DataResult<Pair<ItemStack, Tag>> decode = ItemStack.CODEC.decode(NbtOps.INSTANCE, DataStorageUtils.toNbt(data));

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
        Optional<Tag> result = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, value).result();

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
            return ItemStack.isSameItemSameComponents(itemA, itemB);
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
