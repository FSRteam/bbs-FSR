package mchorse.bbs_mod.film;

import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Converts films written before the hotbar was split into nine keyframe channels. */
public final class FilmLegacy
{
    public static final String LEGACY_MAIN_HAND = "item_main_hand";
    public static final String LEGACY_INVENTORY = "inventory";

    private FilmLegacy() {}

    public static void migrateHotbar(Film film, BaseType data)
    {
        if (film == null || data == null || !data.isMap())
        {
            return;
        }

        MapType map = data.asMap();
        ListType replayData = map.getList("replays");
        List<ItemStack> inventory = readInventory(map.get(LEGACY_INVENTORY));
        List<Replay> replays = film.replays.getList();

        for (int i = 0; i < replays.size() && i < replayData.size(); i++)
        {
            Replay replay = replays.get(i);

            if (hasHotbar(replay.keyframes))
            {
                continue;
            }

            KeyframeChannel<ItemStack> hand = readLegacyHand(replayData.get(i));
            List<ItemStack> start = replay.fp.get() ? inventory : null;

            if ((hand == null || hand.isEmpty()) && (start == null || start.isEmpty()))
            {
                continue;
            }

            migrate(replay.keyframes, hand, start);
            migrateWorn(replay.keyframes, start);
        }
    }

    private static void migrate(ReplayKeyframes keyframes, KeyframeChannel<ItemStack> hand, List<ItemStack> inventory)
    {
        ItemStack[] hotbar = new ItemStack[ReplayKeyframes.HOTBAR_SIZE];

        for (int i = 0; i < hotbar.length; i++)
        {
            ItemStack stack = inventory == null || i >= inventory.size() ? ItemStack.EMPTY : inventory.get(i);
            hotbar[i] = stack.copy();

            if (!stack.isEmpty())
            {
                keyframes.hotbar.get(i).insert(0, stack.copy());
            }
        }

        /* A replay with no old hand channel only had the inventory background.  Do not
         * synthesize an empty hand key into the selected slot: that would erase items
         * picked up while the replay is running. */
        if (hand == null || hand.isEmpty())
        {
            return;
        }

        int last = lastTick(hand, keyframes.selectedSlot);

        for (int tick = 0; tick <= last; tick++)
        {
            int slot = keyframes.getSelectedSlot(tick);
            ItemStack stack = hand.interpolate(tick, ItemStack.EMPTY);

            if (!ItemStack.matches(hotbar[slot], stack))
            {
                keyframes.hotbar.get(slot).insert(tick, stack.copy());
                hotbar[slot] = stack.copy();
            }
        }
    }

    private static void migrateWorn(ReplayKeyframes keyframes, List<ItemStack> inventory)
    {
        if (inventory == null)
        {
            return;
        }

        for (EquipmentSlot slot : ReplayKeyframes.DRESS_SLOTS)
        {
            KeyframeChannel<ItemStack> channel = keyframes.getEquipmentChannel(slot);

            if (!channel.isEmpty())
            {
                continue;
            }

            int index = slot == EquipmentSlot.OFFHAND
                ? Inventory.SLOT_OFFHAND
                : Inventory.INVENTORY_SIZE + slot.getIndex();
            ItemStack stack = index < inventory.size() ? inventory.get(index) : ItemStack.EMPTY;

            if (!stack.isEmpty())
            {
                channel.insert(0, stack.copy());
            }
        }
    }

    private static boolean hasHotbar(ReplayKeyframes keyframes)
    {
        for (KeyframeChannel<ItemStack> channel : keyframes.hotbar)
        {
            if (!channel.isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    private static int lastTick(KeyframeChannel<ItemStack> hand, KeyframeChannel<Integer> selectedSlot)
    {
        int last = 0;

        for (Keyframe<?> keyframe : hand.getKeyframes())
        {
            last = Math.max(last, (int) Math.ceil(keyframe.getTick()));
        }

        for (Keyframe<?> keyframe : selectedSlot.getKeyframes())
        {
            last = Math.max(last, (int) Math.ceil(keyframe.getTick()));
        }

        return last;
    }

    private static KeyframeChannel<ItemStack> readLegacyHand(BaseType replayData)
    {
        if (replayData == null || !replayData.isMap())
        {
            return null;
        }

        MapType keyframes = replayData.asMap().getMap("keyframes");

        if (!keyframes.has(LEGACY_MAIN_HAND))
        {
            return null;
        }

        KeyframeChannel<ItemStack> hand = new KeyframeChannel<>(LEGACY_MAIN_HAND, KeyframeFactories.ITEM_STACK);
        hand.fromData(keyframes.get(LEGACY_MAIN_HAND));

        return hand;
    }

    private static List<ItemStack> readInventory(BaseType data)
    {
        List<ItemStack> stacks = new ArrayList<>();

        if (data != null && data.isList())
        {
            for (BaseType type : data.asList())
            {
                ItemStack stack = KeyframeFactories.ITEM_STACK.fromData(type);
                stacks.add(stack == null ? ItemStack.EMPTY : stack.copy());
            }
        }

        return stacks;
    }
}
