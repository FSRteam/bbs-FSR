package mchorse.bbs_mod.film.replays;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Inventory extends BaseValue
{
    private List<ItemStack> stacks = new ArrayList<>();

    public Inventory(String id)
    {
        super(id);
    }

    public List<ItemStack> getStacks()
    {
        return Collections.unmodifiableList(this.stacks);
    }

    public void fromPlayer(Player player)
    {
        this.stacks.clear();

        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
        {
            this.stacks.add(player.getInventory().getItem(i).copy());
        }
    }

    public static void applyToPlayer(Player player, ListType list)
    {
        if (list == null)
        {
            return;
        }

        applyStagedToPlayer(player, stageForPlayer(player, list));
    }

    /** Decode every submitted slot before any player inventory mutation. */
    public static List<ItemStack> stageForPlayer(Player player, ListType list)
    {
        if (player == null || list == null)
        {
            throw new IllegalArgumentException("Player inventory data is required");
        }

        int capacity = player.getInventory().getContainerSize();

        if (list.size() > capacity)
        {
            throw new IllegalArgumentException("Player inventory contains too many slots");
        }

        List<ItemStack> staged = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i++)
        {
            ItemStack stack = KeyframeFactories.ITEM_STACK.tryFromData(list.get(i))
                .orElseThrow(() -> new IllegalArgumentException("Player inventory contains an invalid item stack"));

            staged.add(stack.copy());
        }

        return List.copyOf(staged);
    }

    /** Apply already-decoded slots, rolling back if a container write fails. */
    public static void applyStagedToPlayer(Player player, List<ItemStack> staged)
    {
        if (player == null || staged == null || staged.size() > player.getInventory().getContainerSize())
        {
            throw new IllegalArgumentException("Staged player inventory is invalid");
        }

        List<ItemStack> previous = snapshotPlayer(player, staged.size());

        try
        {
            for (int i = 0; i < staged.size(); i++)
            {
                ItemStack stack = staged.get(i);

                player.getInventory().setItem(i, stack == null ? ItemStack.EMPTY : stack.copy());
            }
        }
        catch (RuntimeException e)
        {
            for (int i = 0; i < previous.size(); i++)
            {
                try
                {
                    player.getInventory().setItem(i, previous.get(i));
                }
                catch (RuntimeException rollbackError)
                {
                    e.addSuppressed(rollbackError);
                }
            }

            throw e;
        }
    }

    public static List<ItemStack> snapshotPlayer(Player player, int size)
    {
        if (player == null || size < 0 || size > player.getInventory().getContainerSize())
        {
            throw new IllegalArgumentException("Player inventory snapshot size is invalid");
        }

        List<ItemStack> snapshot = new ArrayList<>(size);

        for (int i = 0; i < size; i++)
        {
            snapshot.add(player.getInventory().getItem(i).copy());
        }

        return List.copyOf(snapshot);
    }

    @Override
    public BaseType toData()
    {
        ListType data = new ListType();

        for (ItemStack stack : this.stacks)
        {
            if (stack == null)
            {
                stack = ItemStack.EMPTY;
            }

            data.add(KeyframeFactories.ITEM_STACK.toData(stack));
        }

        return data;
    }

    @Override
    public void fromData(BaseType data)
    {
        this.stacks.clear();

        if (data.isList())
        {
            ListType list = data.asList();

            for (BaseType type : list)
            {
                ItemStack stack = KeyframeFactories.ITEM_STACK.fromData(type);

                if (stack == null)
                {
                    stack = ItemStack.EMPTY;
                }

                this.stacks.add(stack);
            }
        }
    }
}
