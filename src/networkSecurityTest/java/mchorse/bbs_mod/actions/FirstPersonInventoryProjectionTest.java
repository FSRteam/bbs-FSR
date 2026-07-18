package mchorse.bbs_mod.actions;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class FirstPersonInventoryProjectionTest
{
    private FirstPersonInventoryProjectionTest()
    {}

    public static void runAll()
    {
        projectedMutationsDoNotChangeTheFilmTemplate();
        liveRefreshDoesNotChangeTheRecoverySnapshot();
        failedRefreshRollsBackAndCanBeRetried();
    }

    private static void projectedMutationsDoNotChangeTheFilmTemplate()
    {
        ItemStack foodTemplate = new ItemStack(Items.APPLE, 4);
        ItemStack toolTemplate = new ItemStack(Items.IRON_SWORD);
        TestSlots slots = new TestSlots(2);

        FirstPersonInventoryProjection.apply(slots, List.of(foodTemplate, toolTemplate));

        ItemStack projectedFood = slots.get(0);
        ItemStack projectedTool = slots.get(1);

        check(projectedFood != foodTemplate, "food projection retained the Film template instance");
        check(projectedTool != toolTemplate, "tool projection retained the Film template instance");

        projectedFood.shrink(1);
        projectedTool.setDamageValue(7);

        ItemStack dropped = slots.get(0);

        slots.set(0, ItemStack.EMPTY);
        dropped.shrink(1);

        check(foodTemplate.getCount() == 4, "consume/drop mutations changed the Film template count");
        check(toolTemplate.getDamageValue() == 0, "durability mutation changed the Film template damage");
    }

    private static void liveRefreshDoesNotChangeTheRecoverySnapshot()
    {
        TestSlots slots = new TestSlots(
            new ItemStack(Items.EMERALD, 3),
            new ItemStack(Items.DIRT, 2)
        );
        List<ItemStack> recoverySnapshot = slots.snapshot();
        ItemStack firstTemplate = new ItemStack(Items.APPLE, 2);
        ItemStack secondTemplate = new ItemStack(Items.DIAMOND, 5);

        FirstPersonInventoryProjection.apply(slots, List.of(firstTemplate));
        FirstPersonInventoryProjection.apply(slots, List.of(secondTemplate));

        checkStack(slots.get(0), Items.DIAMOND, 5, "live refresh did not install the new Film inventory");
        check(slots.get(0) != secondTemplate, "live refresh retained the new Film template instance");
        checkStack(recoverySnapshot.get(0), Items.EMERALD, 3, "live refresh overwrote the original recovery snapshot");
        checkStack(recoverySnapshot.get(1), Items.DIRT, 2, "live refresh changed another recovery slot");
    }

    private static void failedRefreshRollsBackAndCanBeRetried()
    {
        TestSlots slots = new TestSlots(2);
        ItemStack previousTool = new ItemStack(Items.IRON_SWORD);

        previousTool.setDamageValue(4);

        List<ItemStack> previousTemplate = List.of(new ItemStack(Items.APPLE, 2), previousTool);
        List<ItemStack> nextTemplate = List.of(
            new ItemStack(Items.DIAMOND, 6),
            new ItemStack(Items.EMERALD, 7)
        );

        FirstPersonInventoryProjection.apply(slots, previousTemplate);
        slots.failNextWriteAt(1);

        try
        {
            FirstPersonInventoryProjection.apply(slots, nextTemplate);
            throw new AssertionError("failed live inventory refresh was accepted");
        }
        catch (IllegalStateException exception)
        {
            check("expected slot write failure".equals(exception.getMessage()),
                "live refresh replaced the slot failure");
        }

        checkStack(slots.get(0), Items.APPLE, 2, "failed refresh did not roll back the first display slot");
        checkStack(slots.get(1), Items.IRON_SWORD, 1, "failed refresh did not roll back the second display slot");
        check(slots.get(1).getDamageValue() == 4, "failed refresh did not roll back displayed durability");
        checkStack(nextTemplate.get(0), Items.DIAMOND, 6, "failed refresh mutated the next Film template");
        checkStack(nextTemplate.get(1), Items.EMERALD, 7, "failed refresh mutated another Film template slot");

        FirstPersonInventoryProjection.apply(slots, nextTemplate);

        checkStack(slots.get(0), Items.DIAMOND, 6, "live refresh could not retry after rollback");
        checkStack(slots.get(1), Items.EMERALD, 7, "retried refresh did not install every slot");
    }

    private static void checkStack(ItemStack stack, Item item, int count, String message)
    {
        if (stack.getItem() != item || stack.getCount() != count)
        {
            throw new AssertionError(message + ": got " + stack);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestSlots implements FirstPersonInventoryProjection.Slots
    {
        private final List<ItemStack> stacks;
        private int failingSlot = -1;
        private boolean failNextWrite;

        private TestSlots(int size)
        {
            this.stacks = new ArrayList<>(size);

            for (int i = 0; i < size; i++)
            {
                this.stacks.add(ItemStack.EMPTY);
            }
        }

        private TestSlots(ItemStack... stacks)
        {
            this.stacks = new ArrayList<>(List.of(stacks));
        }

        @Override
        public int size()
        {
            return this.stacks.size();
        }

        @Override
        public ItemStack get(int slot)
        {
            return this.stacks.get(slot);
        }

        @Override
        public void set(int slot, ItemStack stack)
        {
            if (this.failNextWrite && slot == this.failingSlot)
            {
                this.failNextWrite = false;
                this.stacks.set(slot, stack);
                throw new IllegalStateException("expected slot write failure");
            }

            this.stacks.set(slot, stack);
        }

        private List<ItemStack> snapshot()
        {
            List<ItemStack> snapshot = new ArrayList<>(this.stacks.size());

            for (ItemStack stack : this.stacks)
            {
                snapshot.add(stack.copy());
            }

            return snapshot;
        }

        private void failNextWriteAt(int slot)
        {
            this.failingSlot = slot;
            this.failNextWrite = true;
        }
    }
}
