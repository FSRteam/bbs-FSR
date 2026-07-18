package mchorse.bbs_mod.actions;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Transactional copies between a Film inventory template and its live player projection. */
final class FirstPersonInventoryProjection
{
    interface Slots
    {
        int size();

        ItemStack get(int slot);

        void set(int slot, ItemStack stack);
    }

    private FirstPersonInventoryProjection()
    {}

    static void apply(Player player, List<ItemStack> template)
    {
        Objects.requireNonNull(player, "First-person inventory player cannot be null");

        apply(new Slots()
        {
            @Override
            public int size()
            {
                return player.getInventory().getContainerSize();
            }

            @Override
            public ItemStack get(int slot)
            {
                return player.getInventory().getItem(slot);
            }

            @Override
            public void set(int slot, ItemStack stack)
            {
                player.getInventory().setItem(slot, stack);
            }
        }, template);
    }

    static void apply(Slots slots, List<ItemStack> template)
    {
        Objects.requireNonNull(slots, "First-person inventory slots cannot be null");
        Objects.requireNonNull(template, "First-person inventory template cannot be null");

        int size = slots.size();

        if (size < 0)
        {
            throw new IllegalArgumentException("First-person inventory size cannot be negative");
        }

        List<ItemStack> previous = copySlots(slots, size);
        List<ItemStack> projected = copyTemplate(template, size);

        try
        {
            for (int i = 0; i < size; i++)
            {
                slots.set(i, projected.get(i));
            }
        }
        catch (RuntimeException | LinkageError failure)
        {
            for (int i = 0; i < size; i++)
            {
                try
                {
                    slots.set(i, previous.get(i).copy());
                }
                catch (RuntimeException | LinkageError rollbackFailure)
                {
                    failure.addSuppressed(rollbackFailure);
                }
            }

            throw failure;
        }
    }

    private static List<ItemStack> copySlots(Slots slots, int size)
    {
        List<ItemStack> copy = new ArrayList<>(size);

        for (int i = 0; i < size; i++)
        {
            copy.add(copy(slots.get(i)));
        }

        return copy;
    }

    private static List<ItemStack> copyTemplate(List<ItemStack> template, int size)
    {
        List<ItemStack> copy = new ArrayList<>(size);

        for (int i = 0; i < size; i++)
        {
            copy.add(copy(i < template.size() ? template.get(i) : ItemStack.EMPTY));
        }

        return copy;
    }

    private static ItemStack copy(ItemStack stack)
    {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }
}
