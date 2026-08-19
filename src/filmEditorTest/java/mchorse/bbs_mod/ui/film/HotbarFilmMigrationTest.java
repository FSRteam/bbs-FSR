package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Regression coverage for the FS inventory-to-hotbar film migration. */
public final class HotbarFilmMigrationTest
{
    private HotbarFilmMigrationTest() {}

    public static void runAll()
    {
        testLegacyFilmMigration();
        testRecordingAndEmptyChannels();
    }

    private static void testLegacyFilmMigration()
    {
        Film source = new Film();
        Replay replay = source.replays.addReplay();

        replay.fp.set(true);
        replay.keyframes.selectedSlot.insert(0F, 1);
        replay.keyframes.selectedSlot.insert(2F, 2);

        MapType data = source.toData().asMap();
        MapType replayData = data.getList("replays").get(0).asMap();
        MapType keyframes = replayData.getMap("keyframes");
        KeyframeChannel<ItemStack> oldHand = new KeyframeChannel<>("item_main_hand", KeyframeFactories.ITEM_STACK);

        oldHand.insert(0F, new ItemStack(Items.STICK));
        oldHand.insert(2F, new ItemStack(Items.DIAMOND));
        keyframes.put("item_main_hand", oldHand.toData());

        ListType inventory = new ListType();
        int size = net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
            + EquipmentSlot.values().length - 1;

        for (int i = 0; i < size; i++)
        {
            ItemStack stack = ItemStack.EMPTY;

            if (i == 0)
            {
                stack = new ItemStack(Items.BREAD);
            }
            else if (i == net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE + EquipmentSlot.CHEST.getIndex())
            {
                stack = new ItemStack(Items.IRON_CHESTPLATE);
            }
            else if (i == net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND)
            {
                stack = new ItemStack(Items.SHIELD);
            }

            inventory.add(KeyframeFactories.ITEM_STACK.toData(stack));
        }

        data.put("inventory", inventory);

        Film migrated = new Film();
        migrated.fromData(data);
        Replay result = migrated.replays.getList().get(0);

        check(same(result.keyframes.hotbar.get(0).interpolate(0F, ItemStack.EMPTY), Items.BREAD),
            "legacy inventory hotbar slot was not migrated");
        check(same(result.keyframes.hotbar.get(1).interpolate(0F, ItemStack.EMPTY), Items.STICK),
            "legacy main-hand key was not migrated to the selected hotbar slot");
        check(same(result.keyframes.hotbar.get(2).interpolate(2F, ItemStack.EMPTY), Items.DIAMOND),
            "legacy selected-slot hand change was not replayed into its hotbar slot");
        check(same(result.keyframes.armorChest.interpolate(0F, ItemStack.EMPTY), Items.IRON_CHESTPLATE),
            "legacy worn armor was not migrated");
        check(same(result.keyframes.offHand.interpolate(0F, ItemStack.EMPTY), Items.SHIELD),
            "legacy offhand was not migrated");
        check(!result.keyframes.drivesHotbarSlot(3),
            "an empty legacy channel would overwrite an item picked up during playback");

        MapType current = migrated.toData().asMap();

        check(!current.has("inventory"), "the removed Film.inventory field was serialized again");
        check(!current.getList("replays").get(0).asMap().getMap("keyframes").has("item_main_hand"),
            "the removed item_main_hand channel was serialized again");
    }

    private static void testRecordingAndEmptyChannels()
    {
        ReplayKeyframes recorded = new ReplayKeyframes("keyframes");
        StubEntity source = new StubEntity();

        for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
        {
            source.setHotbarStack(i, new ItemStack(Items.APPLE, i + 1));
        }

        recorded.record(0, source, null);

        for (int i = 0; i < ReplayKeyframes.HOTBAR_SIZE; i++)
        {
            check(!recorded.hotbar.get(i).isEmpty(), "recording did not write hotbar slot " + i);
            check(recorded.hotbar.get(i).interpolate(0F, ItemStack.EMPTY).getCount() == i + 1,
                "recording changed the count in hotbar slot " + i);
        }

        ReplayKeyframes playback = new ReplayKeyframes("keyframes");
        StubEntity target = new StubEntity();
        target.setHotbarStack(1, new ItemStack(Items.DIRT));
        playback.hotbar.get(0).insert(0F, new ItemStack(Items.STONE));
        playback.selectedSlot.insert(0F, 0);
        playback.applyEquipment(0F, target);

        check(same(target.getHotbarStack(0), Items.STONE), "recorded hotbar value was not applied");
        check(same(target.getHotbarStack(1), Items.DIRT), "empty hotbar channel erased a live item");
    }

    private static boolean same(ItemStack stack, net.minecraft.world.item.Item item)
    {
        return stack != null && stack.is(item);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
