package mchorse.bbs_mod.ui.film.replays;

import com.mojang.serialization.Lifecycle;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.clips.modifiers.LookClip;
import mchorse.bbs_mod.cubic.glint.GlintControls;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeNavigationTest;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.film.utils.keyframes.KeyframeInteractionTest;
import mchorse.bbs_mod.utils.factory.MapFactory;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.keyframes.factories.ItemStackKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.SoundKeyframeFactory;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.fml.loading.LoadingModList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replay identity under list mutation.
 *
 * <p>These tests used to be about the index remapper: a film's replays were addressed by their
 * position, so every insertion, removal and reorder had to be followed by a transaction that
 * rewrote every reference. The stable-id migration removed that whole mechanism — a reference now
 * names an actor's permanent id, and nothing has to be rewritten. What is left to check is the
 * property the remapping was supposed to establish, and the failure mode it could not:
 * <em>deleting</em> an actor used to make a reference quietly resolve onto whichever actor slid
 * into the freed slot. It no longer can, because the id does not move.</p>
 *
 * <p>So the tests below pin down four invariants: a replay's id never changes under removal or
 * reorder (and the id of a removed replay is never recycled); an id resolves to the one actor that
 * carries it, even when two actors hold identical content; ids survive a save/load unchanged, no
 * matter how many times the document is loaded; and the deleted-actor reference stays dangling
 * instead of being retargeted or cleared.</p>
 */
public final class ReplayIndexRemappingTest
{
    /** How many replays the identity scenarios build. Single deletion is enumerated over every slot. */
    private static final int REPLAY_COUNT = 4;

    public static void main(String[] args)
    {
        Runnable restoreRuntime = installHeadlessClientRuntime();

        try
        {
            testEverySingleDeletion();
            testBatchDeletion();
            testArbitraryReorder();
            testIdentityNotEquality();
            testIdRoundTripIsIdempotent();
            testPositionalRenumberingIsRefused();
            testActionTargetSurvivesReplayDeletion();
            testFilmReferenceTransaction();
            testGroupedSoundChannelsPreserveLegacyFallback();
            testGroupedSoundLoopIntervalLifecycle();
            testBbsVolumeFieldsHaveNoFiniteUpperLimit();
            testReplayTrackCategories();
            testGlintLayerKeyframes();
            testForeignChannelRoundTrip();
            testEnchantedEquipmentSerializationRoundTrip();
            testEnchantedItemPickerNbtRoundTrip();
            testDeathKeyframeChannel();
            ReplayIdentityLookupSourceTest.run();
            KeyframeNavigationTest.run();
            KeyframeInteractionTest.run();

            System.out.println("Replay/keyframe consistency tests passed");
        }
        finally
        {
            restoreRuntime.run();
        }
    }

    private static Runnable installHeadlessClientRuntime()
    {
        bootstrapStandaloneMinecraftRuntime();

        try
        {
            Field cameraFactory = BBSMod.class.getDeclaredField("factoryCameraClips");
            Field actionFactory = BBSMod.class.getDeclaredField("factoryActionClips");
            Field l10n = BBSModClient.class.getDeclaredField("l10n");

            cameraFactory.setAccessible(true);
            actionFactory.setAccessible(true);
            l10n.setAccessible(true);

            Object previousCameraFactory = cameraFactory.get(null);
            Object previousActionFactory = actionFactory.get(null);
            Object previousL10n = l10n.get(null);

            if (previousCameraFactory == null)
            {
                cameraFactory.set(null, new MapFactory<>());
            }

            if (previousActionFactory == null)
            {
                actionFactory.set(null, new MapFactory<>());
            }

            if (previousL10n == null)
            {
                l10n.set(null, new L10n());
            }

            return () ->
            {
                try
                {
                    l10n.set(null, previousL10n);
                    actionFactory.set(null, previousActionFactory);
                    cameraFactory.set(null, previousCameraFactory);
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore the replay/keyframe test runtime", exception);
                }
            };
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not install the replay/keyframe test runtime", exception);
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

    private static void testEnchantedEquipmentSerializationRoundTrip()
    {
        MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        enchantments.register(Enchantments.SHARPNESS, enchantment(Enchantments.SHARPNESS.location()), RegistrationInfo.BUILT_IN);
        enchantments.register(Enchantments.PROTECTION, enchantment(Enchantments.PROTECTION.location()), RegistrationInfo.BUILT_IN);
        HolderLookup.Provider provider = new RegistryAccess.ImmutableRegistryAccess(List.of(enchantments));

        ItemStackKeyframeFactory.setClientRegistryAccess(() -> provider);

        try
        {
            ReplayKeyframes source = new ReplayKeyframes("keyframes");
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);

            sword.enchant(enchantments.getHolderOrThrow(Enchantments.SHARPNESS), 5);
            chest.enchant(enchantments.getHolderOrThrow(Enchantments.PROTECTION), 4);

            source.hotbar.get(0).insert(0F, sword);
            source.armorChest.insert(0F, chest);

            BaseType data = source.toData();
            BaseType loaded = DataStorageUtils.readFromBytes(DataStorageUtils.writeToBytes(data));
            ReplayKeyframes copy = new ReplayKeyframes("keyframes");

            copy.fromData(loaded);

            ItemStack hand = copy.hotbar.get(0).interpolate(0F);
            ItemStack body = copy.armorChest.interpolate(0F);

            assertTrue(
                ItemStack.isSameItemSameComponents(sword, hand),
                "enchanted main-hand item components lost after film serialization"
            );
            assertTrue(
                ItemStack.isSameItemSameComponents(chest, body),
                "enchanted armor components lost after film serialization"
            );
            assertTrue(
                hand.getEnchantmentLevel(enchantments.getHolderOrThrow(Enchantments.SHARPNESS)) == 5,
                "main-hand enchantment level lost after film serialization"
            );
            assertTrue(
                body.getEnchantmentLevel(enchantments.getHolderOrThrow(Enchantments.PROTECTION)) == 4,
                "armor enchantment level lost after film serialization"
            );
        }
        finally
        {
            ItemStackKeyframeFactory.setClientRegistryAccess(null);
        }
    }

    /**
     * The item picker panels ({@code UIUnifiedPickOverlayPanel} /
     * {@code UIItemStackOverlayPanel}) display and edit item NBT through
     * {@code ItemStack.CODEC} with {@link ItemStackKeyframeFactory#currentOps()}.
     * A plain {@code NbtOps} — the 94be896c blind spot — made enchanted items
     * display as "{}" and degrade to air the moment their NBT was edited.
     */
    private static void testEnchantedItemPickerNbtRoundTrip()
    {
        MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(Registries.ENCHANTMENT, Lifecycle.stable());
        enchantments.register(Enchantments.SHARPNESS, enchantment(Enchantments.SHARPNESS.location()), RegistrationInfo.BUILT_IN);
        HolderLookup.Provider provider = new RegistryAccess.ImmutableRegistryAccess(List.of(enchantments));

        ItemStackKeyframeFactory.setClientRegistryAccess(() -> provider);

        try
        {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(enchantments.getHolderOrThrow(Enchantments.SHARPNESS), 5);

            // The picker's updateNbt()/updateItemNbt() shows encodeStart(...).orElse("{}").
            Tag displayed = ItemStack.CODEC.encodeStart(ItemStackKeyframeFactory.currentOps(), sword)
                .result()
                .orElseThrow(() -> new AssertionError("enchanted item failed to encode with currentOps()"));

            assertTrue(!((CompoundTag) displayed).isEmpty(), "enchanted item NBT displayed as {} in the item picker");

            // The picker's NBT field parses that text back; a plain NbtOps would yield ItemStack.EMPTY,
            // turning the selected item into air on the first edit.
            ItemStack parsed = ItemStack.CODEC.parse(ItemStackKeyframeFactory.currentOps(), displayed)
                .result()
                .orElse(ItemStack.EMPTY);

            assertTrue(
                ItemStack.isSameItemSameComponents(sword, parsed),
                "editing enchanted item NBT in the picker lost its components"
            );
        }
        finally
        {
            ItemStackKeyframeFactory.setClientRegistryAccess(null);
        }
    }

    private static Enchantment enchantment(ResourceLocation id)
    {
        return Enchantment.enchantment(
            Enchantment.definition(
                HolderSet.direct(),
                10,
                5,
                Enchantment.dynamicCost(1, 11),
                Enchantment.dynamicCost(21, 11),
                2,
                EquipmentSlotGroup.MAINHAND
            )
        ).build(id);
    }

    /**
     * Exhaustive over every slot. However the one replay is chosen, removing it must leave every
     * survivor's id alone, must make the removed id unresolvable, and must not hand the removed
     * actor's identity to whichever replay slid into the freed slot.
     */
    private static void testEverySingleDeletion()
    {
        for (int removed = 0; removed < REPLAY_COUNT; removed++)
        {
            assertDeletionKeepsIdentities(removed, "single deletion " + removed);
        }
    }

    private static void assertDeletionKeepsIdentities(int removed, String scenario)
    {
        Film film = filmWithReplays();
        List<String> before = replayIds(film);
        Replay deleted = film.replays.getList().get(removed);
        String deletedId = deleted.getId();

        film.replays.remove(deleted);

        assertEquals(REPLAY_COUNT - 1, film.replays.getList().size(), scenario + ": list size");
        assertTrue(film.replays.getById(deletedId) == null,
            scenario + ": the deleted replay is still resolvable by its stable id");

        List<String> expected = new ArrayList<>(before);

        expected.remove(removed);
        assertEquals(String.join(",", expected), String.join(",", replayIds(film)),
            scenario + ": deleting a replay renumbered the survivors");

        if (removed < film.replays.getList().size())
        {
            String occupant = film.replays.getList().get(removed).getId();

            assertTrue(!deletedId.equals(occupant),
                scenario + ": the replay that took the freed slot inherited the deleted id, so a "
                    + "dangling reference would silently resolve onto a different actor");
        }
    }

    private static void testBatchDeletion()
    {
        Film film = filmWithReplays();
        List<String> before = replayIds(film);

        film.replays.remove(film.replays.getList().get(0));
        film.replays.remove(film.replays.getById(before.get(2)));

        assertEquals(REPLAY_COUNT - 2, film.replays.getList().size(), "batch deletion: list size");
        assertTrue(film.replays.getById(before.get(0)) == null && film.replays.getById(before.get(2)) == null,
            "batch deletion left a deleted replay resolvable by its stable id");
        assertEquals(String.join(",", List.of(before.get(1), before.get(3))), String.join(",", replayIds(film)),
            "batch deletion renumbered the survivors");
    }

    private static void testArbitraryReorder()
    {
        Film film = filmWithReplays();
        List<String> before = replayIds(film);
        Replay moved = film.replays.getList().get(REPLAY_COUNT - 1);

        /* Move the last replay to the front through the list's own door. */
        film.replays.remove(moved);
        film.replays.add(0, moved);

        List<String> expected = new ArrayList<>(before);
        String lastId = expected.remove(REPLAY_COUNT - 1);

        expected.add(0, lastId);
        assertEquals(String.join(",", expected), String.join(",", replayIds(film)),
            "reordering rewrote the replays' stable ids");
        assertTrue(film.replays.getById(lastId) == moved,
            "the reordered replay is not resolvable by the id it kept");
        assertTrue(film.replays.getById(before.get(0)) == film.replays.getList().get(1),
            "the replay left in place was retargeted by another replay's move");
    }

    /**
     * Two replays with identical persisted content are still two different actors. Everything that
     * used to be a positional lookup now resolves through the id, so an equality-based lookup would
     * be free to return either of the two.
     */
    private static void testIdentityNotEquality()
    {
        Film film = filmWithReplays();
        Replay first = film.replays.getList().get(0);
        Replay second = film.replays.getList().get(1);

        /* Make the two actors hold the same content, so only identity can tell them apart. */
        second.fromData(first.toData());

        assertTrue(first != second, "two replays collapsed into one object");
        assertTrue(!first.getId().equals(second.getId()), "two replays were issued the same stable id");
        assertTrue(film.replays.getById(first.getId()) == first, "id lookup selected the first equal replay");
        assertTrue(film.replays.getById(second.getId()) == second, "id lookup selected the wrong equal replay");
    }

    /**
     * The other half of "the id lives in the data": ids survive a save and a load, and loading a
     * document twice does not mint a second set of them. This is what makes running a conversion
     * again a no-op instead of a renumbering.
     */
    private static void testIdRoundTripIsIdempotent()
    {
        Film film = filmWithReplays();
        String ids = String.join(",", replayIds(film));
        BaseType data = film.toData();
        Film once = new Film();
        Film twice = new Film();

        once.fromData(data);
        twice.fromData(once.toData());

        assertEquals(ids, String.join(",", replayIds(once)), "replay ids changed on the first load");
        assertEquals(ids, String.join(",", replayIds(twice)), "replay ids changed on the second load");
    }

    /**
     * Positional renumbering is the exact bookkeeping stable ids replaced. It has to fail loudly
     * rather than quietly rewrite every identity, and a refused call must leave the ids as they were.
     */
    private static void testPositionalRenumberingIsRefused()
    {
        Film film = filmWithReplays();
        String before = String.join(",", replayIds(film));

        try
        {
            film.replays.sync();

            throw new AssertionError("a stable-id replay list was renumbered by position");
        }
        catch (UnsupportedOperationException expected)
        {
            /* The contract: there is no longer any way to renumber a replay list by position. */
        }

        assertEquals(before, String.join(",", replayIds(film)), "a refused renumbering still rewrote ids");
    }

    /**
     * An action target addresses its actor by the replay's stable id. Deleting that replay must
     * leave the reference dangling — neither rewritten onto whichever replay took the slot nor
     * cleared — while the tombstone (uuid, type, position) that lets the action fall back to a
     * raycast survives untouched.
     */
    private static void testActionTargetSurvivesReplayDeletion()
    {
        Film film = filmWithReplays();
        Replay deleted = film.replays.getList().get(1);
        String deletedId = deleted.getId();
        AttackActionClip attack = new AttackActionClip();
        EntityInteractionActionClip interaction = new EntityInteractionActionClip();

        attack.target.uuid.set("00000000-0000-0000-0000-000000000001");
        attack.target.entityType.set("bbs:actor");
        attack.target.replayId.set(deletedId);
        attack.target.position.get().set(1D, 2D, 3D);
        interaction.target.uuid.set("00000000-0000-0000-0000-000000000002");
        interaction.target.entityType.set("bbs:actor");
        interaction.target.replayId.set(deletedId);
        interaction.target.position.get().set(4D, 5D, 6D);

        film.replays.remove(deleted);

        assertEquals(deletedId, attack.target.replayId.get(), "deleting the replay rewrote the attack target");
        assertEquals(deletedId, interaction.target.replayId.get(), "deleting the replay rewrote the entity-interaction target");
        assertTrue(film.replays.getById(attack.target.replayId.get()) == null,
            "the dangling attack target resolves again, so the deletion retargeted it");
        assertTrue(film.replays.getById(interaction.target.replayId.get()) == null,
            "the dangling entity-interaction target resolves again, so the deletion retargeted it");
        assertTrue(attack.target.isPresent(), "deleted attack target became a legacy raycast action");
        assertTrue(interaction.target.isPresent(), "deleted entity-interaction target lost its targeted tombstone");
        assertEquals("00000000-0000-0000-0000-000000000001", attack.target.uuid.get(), "deleted attack target UUID tombstone");
        assertEquals("00000000-0000-0000-0000-000000000002", interaction.target.uuid.get(), "entity-interaction UUID tombstone");
        assertEquals("bbs:actor", attack.target.entityType.get(), "deleted attack target type");
        assertEquals("bbs:actor", interaction.target.entityType.get(), "entity-interaction target type");
        assertEquals(1D, attack.target.position.get().x, "deleted attack target x");
        assertEquals(2D, attack.target.position.get().y, "deleted attack target y");
        assertEquals(3D, attack.target.position.get().z, "deleted attack target z");
        assertEquals(4D, interaction.target.position.get().x, "entity-interaction target x");
        assertEquals(5D, interaction.target.position.get().y, "entity-interaction target y");
        assertEquals(6D, interaction.target.position.get().z, "entity-interaction target z");
    }

    private static void testFilmReferenceTransaction()
    {
        if (BBSSettings.recordingPoseTransformOverlays == null)
        {
            BBSSettings.recordingPoseTransformOverlays = new ValueInt("pose_transform_overlays", 0, 0, 42);
        }

        Film film = new Film();
        Replay source = film.replays.addReplay();
        Replay firstTarget = film.replays.addReplay();
        Replay secondTarget = film.replays.addReplay();
        AttackActionClip attack = new AttackActionClip();
        EntityInteractionActionClip interaction = new EntityInteractionActionClip();
        LookClip camera = new LookClip();
        AnchorForm form = new AnchorForm();
        AnchorForm nestedForm = new AnchorForm();
        BodyPart part = new BodyPart("0");

        String sourceId = source.getId();
        String firstId = firstTarget.getId();
        String secondId = secondTarget.getId();

        attack.target.uuid.set("00000000-0000-0000-0000-000000000011");
        attack.target.replayId.set(secondId);
        interaction.target.uuid.set("00000000-0000-0000-0000-000000000012");
        interaction.target.replayId.set(sourceId);
        source.actions.addClip(attack);
        source.actions.addClip(interaction);
        camera.selector.set(secondId);
        film.camera.addClip(camera);
        form.anchor.get().replay = firstId;

        Anchor runtimeAnchor = new Anchor();

        runtimeAnchor.replay = secondId;
        form.anchor.setRuntimeValue(runtimeAnchor);
        nestedForm.anchor.get().replay = sourceId;
        part.setForm(nestedForm);
        form.parts.addBodyPart(part);
        source.form.set(form);

        /* Reorder: the last replay becomes the first. Every reference above addresses an actor by
         * its stable id, so the move must not retarget a single one of them. */
        film.replays.remove(secondTarget);
        film.replays.add(0, secondTarget);

        assertEquals(String.join(",", List.of(secondId, sourceId, firstId)), String.join(",", replayIds(film)),
            "reordering rewrote the replays' stable ids");
        assertEquals(secondId, attack.target.replayId.get(), "reorder retargeted the film attack target");
        assertEquals(sourceId, interaction.target.replayId.get(), "reorder retargeted the film entity target");
        assertEquals(firstId, form.anchor.getOriginalValue().replay, "reorder retargeted the top-level form anchor");
        assertEquals(secondId, form.anchor.getRuntimeValue().replay,
            "the transient form anchor followed the reorder instead of its own id");
        assertEquals(sourceId, nestedForm.anchor.get().replay, "reorder retargeted the nested body-part anchor");
        assertEquals(secondId, camera.selector.get(), "reorder retargeted the camera entity selector");
        assertTrue(film.replays.getById(secondId) == secondTarget, "the reordered replay is not resolvable by its id");
        assertTrue(film.replays.getById(firstId) == firstTarget, "the replay left in place is not resolvable by its id");

        /* Delete an actor a reference points at. The reference has to stay dangling: nothing
         * rewrites it, and it must not resolve onto whichever replay took the freed slot. */
        film.replays.remove(firstTarget);

        assertEquals(String.join(",", List.of(secondId, sourceId)), String.join(",", replayIds(film)),
            "deleting a replay renumbered the survivors");
        assertTrue(film.replays.getById(firstId) == null, "the deleted replay is still resolvable by its id");
        assertEquals(firstId, form.anchor.getOriginalValue().replay, "deletion retargeted the top-level form anchor");
        assertEquals(secondId, form.anchor.getRuntimeValue().replay, "deletion retargeted the transient form anchor");
        assertEquals(secondId, attack.target.replayId.get(), "deletion retargeted the film attack target");
        assertEquals(sourceId, interaction.target.replayId.get(), "deletion retargeted the film entity target");
        assertEquals(sourceId, nestedForm.anchor.get().replay, "deletion retargeted the surviving nested body-part anchor");
        assertEquals(secondId, camera.selector.get(), "deletion retargeted the surviving camera selector");
    }

    @SuppressWarnings("unchecked")
    private static void testGroupedSoundChannelsPreserveLegacyFallback()
    {
        SoundSphereForm form = new SoundSphereForm();
        FormProperties properties = new FormProperties("properties");
        String legacyId = FormUtils.getPropertyPath(form.radius);
        KeyframeChannel<Float> legacy = properties.registerChannel(legacyId, KeyframeFactories.FLOAT);
        String groupedId = SoundKeyframeValue.channelId(form, SoundKeyframeValue.Group.SHAPE);
        KeyframeChannel<SoundKeyframeValue> grouped = properties.getOrCreate(form, groupedId);

        legacy.insert(0F, 12F);
        properties.applyProperties(form, 0F);
        assertEquals(12D, form.radius.get(), "empty grouped sound track preserves a legacy track");

        SoundKeyframeValue shape = SoundKeyframeValue.capture(form, SoundKeyframeValue.Group.SHAPE);

        shape.extent = 24F;
        grouped.insert(0F, shape);
        properties.applyProperties(form, 0F);
        assertEquals(24D, form.radius.get(), "non-empty grouped sound track overrides a legacy track");

        grouped.removeAll();
        properties.applyProperties(form, 0F);
        assertEquals(12D, form.radius.get(),
            "deleting the last grouped sound keyframe restores the legacy track");

        properties.resetProperties(form);
        assertTrue(form.radius.getRuntimeValue() == null, "sound property reset clears runtime state");
    }

    private static void testBbsVolumeFieldsHaveNoFiniteUpperLimit()
    {
        float amplified = 4096F;
        AudioClip clip = new AudioClip();
        SoundSphereForm form = new SoundSphereForm();

        clip.volume.set(amplified);
        form.volume.set(amplified);

        assertEquals(amplified, clip.volume.get(), "film audio volume keeps values above the legacy cap");
        assertEquals(amplified, form.volume.get(), "sound form volume keeps values above the legacy cap");
        assertTrue(!Float.isFinite(clip.volume.getMax()), "film audio volume has no finite upper limit");
        assertTrue(!Float.isFinite(form.volume.getMax()), "sound form volume has no finite upper limit");

        SoundKeyframeValue sound = SoundKeyframeValue.capture(form, SoundKeyframeValue.Group.SOUND);

        sound.volume = amplified * 2F;
        sound.applyRuntime(form, SoundKeyframeValue.Group.SOUND);
        assertEquals(sound.volume, form.volume.get(), "grouped sound keyframes keep amplified volume");

        clip.volume.set(-1F);
        form.volume.setRuntimeValue(null);
        form.volume.set(-1F);
        assertEquals(0D, clip.volume.get(), "film audio volume remains non-negative");
        assertEquals(0D, form.volume.get(), "sound form volume remains non-negative");
    }

    private static void testReplayTrackCategories()
    {
        assertTrackCategory("x", false, UIReplaysEditor.ReplayCategory.PLAYER);
        assertTrackCategory("visible", true, UIReplaysEditor.ReplayCategory.MODEL);
        assertTrackCategory("pose", true, UIReplaysEditor.ReplayCategory.POSE);
        assertTrackCategory("transform_overlay", true, UIReplaysEditor.ReplayCategory.POSE);
        assertTrackCategory("shape_keys", true, UIReplaysEditor.ReplayCategory.POSE);
        assertTrackCategory(FormControlKeys.toGlintControlKey(""), true, UIReplaysEditor.ReplayCategory.POSE);
        assertTrackCategory(PerLimbService.toPoseBoneKey("", "arm"), true, UIReplaysEditor.ReplayCategory.POSE);
        assertTrackCategory(FormControlKeys.toIKControlKey(""), true, UIReplaysEditor.ReplayCategory.IK);
        assertTrackCategory(PerLimbService.toIKTargetKey("", "hand"), true, UIReplaysEditor.ReplayCategory.IK);
        assertTrackCategory(PerLimbService.toPoleTargetKey("", "hand"), true, UIReplaysEditor.ReplayCategory.IK);
        assertTrackCategory(FormControlKeys.toPhysicsControlKey(""), true, UIReplaysEditor.ReplayCategory.PHYSICS);
        assertTrackCategory(FormControlKeys.toWindControlKey(""), true, UIReplaysEditor.ReplayCategory.PHYSICS);
        assertTrackCategory(PerLimbService.toPhysicsTargetKey("", "cape"), true, UIReplaysEditor.ReplayCategory.PHYSICS);
        assertTrackCategory(PerLimbService.toMaterialTextureKey("", "body"), true, UIReplaysEditor.ReplayCategory.MODEL);

        UIKeyframeSheet physics = trackSheet(FormControlKeys.toPhysicsControlKey(""), true);

        assertTrue(UIReplaysEditor.shouldShowTrack(physics, UIReplaysEditor.ReplayCategory.PLAYER, true),
            "all-tracks mode still applies a category filter");
        assertTrue(!UIReplaysEditor.shouldShowTrack(physics, UIReplaysEditor.ReplayCategory.PLAYER, false),
            "player tab includes physics tracks");
        assertTrue(UIReplaysEditor.shouldShowTrack(physics, UIReplaysEditor.ReplayCategory.PHYSICS, false),
            "physics tab drops its own tracks");
    }

    private static void testForeignChannelRoundTrip()
    {
        KeyframeChannel<Float> live = new KeyframeChannel<>("known_channel", KeyframeFactories.FLOAT);

        live.insert(0F, 1.5F);

        MapType foreignData = live.toData().asMap();

        foreignData.putString("type", "myaddon:custom_factory");

        MapType data = new MapType();

        data.put("known_channel", live.toData());
        data.put("foreign_channel", foreignData);

        FormProperties properties = new FormProperties("properties");

        properties.fromData(data);

        assertTrue(properties.get("known_channel") instanceof KeyframeChannel,
            "known-factory channel is not registered after load");
        assertTrue(properties.get("foreign_channel") == null,
            "unknown-factory channel became a live value");

        MapType saved = properties.toData().asMap();

        assertTrue(saved.has("foreign_channel"), "unknown-factory channel is dropped on save");
        assertEquals("myaddon:custom_factory", saved.getMap("foreign_channel").getString("type"),
            "unknown-factory channel type is not preserved verbatim");
        assertEquals(1, saved.getMap("foreign_channel").getList("keyframes").size(),
            "unknown-factory channel keyframes are not preserved");
        assertTrue(saved.has("known_channel"), "known channel disappeared next to a foreign one");
    }

    private static void testGlintLayerKeyframes()
    {
        GlintControls a = new GlintControls();
        GlintControls b = new GlintControls();

        a.get("arm").mode = 0F;
        a.get("arm").speed = 1F;
        b.get("arm").mode = 2F;
        b.get("arm").speed = 3F;
        b.get("arm").transform.translate.x = 4F;

        GlintControls early = KeyframeFactories.GLINT.interpolate(a, a, b, b, Interpolations.LINEAR, 0.25F).copy();
        GlintControls late = KeyframeFactories.GLINT.interpolate(a, a, b, b, Interpolations.LINEAR, 0.75F).copy();

        assertEquals(0D, early.get("arm").mode, "glint mode remains discrete before the midpoint");
        assertEquals(2D, late.get("arm").mode, "glint mode switches at the midpoint");
        assertEquals(1.5D, early.get("arm").speed, "glint speed interpolates");
        assertEquals(1D, early.get("arm").transform.translate.x, "glint transform interpolates");

        /* A stored default is meaningful: it explicitly turns off a statically glinted bone. */
        late.get("leg");
        KeyframeChannel<GlintControls> channel = new KeyframeChannel<>("glint_layer", KeyframeFactories.GLINT);

        channel.insert(0F, late);

        KeyframeChannel<GlintControls> restored = new KeyframeChannel<>("glint_layer", KeyframeFactories.GLINT);

        restored.fromData(channel.toData());
        assertTrue(restored.getFactory() == KeyframeFactories.GLINT, "glint keyframe factory type was not restored");
        assertTrue(restored.get(0).getValue().controls.containsKey("leg"),
            "an explicit default/off glint bone disappeared during serialization");
    }

    private static void assertTrackCategory(String id, boolean owned, UIReplaysEditor.ReplayCategory expected)
    {
        UIReplaysEditor.ReplayCategory actual = UIReplaysEditor.categoryOf(trackSheet(id, owned));

        assertTrue(actual == expected,
            "track " + id + " was classified as " + actual + " instead of " + expected);
    }

    private static UIKeyframeSheet trackSheet(String id, boolean owned)
    {
        KeyframeChannel<Float> channel = new KeyframeChannel<>(id, KeyframeFactories.FLOAT);
        UIKeyframeSheet sheet = new UIKeyframeSheet(id, mchorse.bbs_mod.l10n.keys.IKey.constant(id), 0, false, channel, null);

        return owned ? sheet.form(new AnchorForm()) : sheet;
    }

    @SuppressWarnings("unchecked")
    private static void testGroupedSoundLoopIntervalLifecycle()
    {
        SoundSphereForm form = new SoundSphereForm();

        assertEquals(0D, form.loopInterval.get(), "sound form loop interval defaults to seamless looping");
        assertEquals(0D, form.loopInterval.getMin(), "sound form loop interval remains non-negative");
        assertTrue(!Float.isFinite(form.loopInterval.getMax()),
            "sound form loop interval has no finite upper limit");
        assertTrue(form.get("loop_interval") == form.loopInterval,
            "sound form loop interval is absent from the persisted value tree");

        form.loopInterval.set(2.5F);
        BaseType persistedInterval = form.loopInterval.toData();
        SoundSphereForm restored = new SoundSphereForm();

        restored.loopInterval.fromData(persistedInterval);
        assertEquals(2.5D, restored.loopInterval.get(), "sound form persistence loses the loop interval");

        SoundKeyframeValue snapshot = SoundKeyframeValue.capture(restored, SoundKeyframeValue.Group.SOUND);

        assertEquals(2.5D, snapshot.loopInterval, "grouped sound snapshot loses the loop interval");

        SoundKeyframeFactory factory = new SoundKeyframeFactory(SoundKeyframeValue.Group.SOUND);
        SoundKeyframeValue copied = factory.copy(snapshot);

        assertTrue(copied != snapshot, "grouped sound copy aliases the source snapshot");
        assertEquals(2.5D, copied.loopInterval, "grouped sound copy loses the loop interval");

        BaseType encoded = factory.toData(snapshot);
        MapType encodedMap = encoded.asMap();
        SoundKeyframeValue decoded = factory.fromData(encoded);
        SoundKeyframeValue legacyDecoded = factory.fromData(new MapType());

        assertTrue(encodedMap.has("loop_interval"), "grouped sound serialization omits the loop interval");
        assertEquals(2.5D, encodedMap.getFloat("loop_interval"),
            "grouped sound serialization writes the wrong loop interval");
        assertEquals(2.5D, decoded.loopInterval, "grouped sound serialization round-trip loses the loop interval");
        assertEquals(0D, legacyDecoded.loopInterval,
            "grouped sound snapshots without loop_interval no longer preserve seamless looping");

        SoundKeyframeValue start = snapshot.copy();
        SoundKeyframeValue end = snapshot.copy();

        start.loopInterval = 2F;
        end.loopInterval = 6F;

        SoundKeyframeValue interpolated = factory.interpolate(
            start, start, end, end, Interpolations.LINEAR, 0.25F).copy();

        assertEquals(3D, interpolated.loopInterval, "grouped sound loop interval does not interpolate");

        form.loopInterval.set(0.75F);
        snapshot.loopInterval = 4F;
        snapshot.applyRuntime(form, SoundKeyframeValue.Group.SOUND);
        assertEquals(4D, form.loopInterval.get(), "grouped sound runtime application loses the loop interval");

        SoundKeyframeValue.clearRuntime(form, SoundKeyframeValue.Group.SOUND);
        assertTrue(form.loopInterval.getRuntimeValue() == null,
            "grouped sound runtime clear leaves the loop interval overridden");
        assertEquals(0.75D, form.loopInterval.get(),
            "grouped sound runtime clear does not restore the persisted loop interval");

        FormProperties properties = new FormProperties("properties");
        String legacyId = FormUtils.getPropertyPath(form.loopInterval);
        KeyframeChannel<Float> legacy = properties.registerChannel(legacyId, KeyframeFactories.FLOAT);
        String groupedId = SoundKeyframeValue.channelId(form, SoundKeyframeValue.Group.SOUND);
        KeyframeChannel<SoundKeyframeValue> grouped = properties.getOrCreate(form, groupedId);

        legacy.insert(0F, 1.25F);
        properties.applyProperties(form, 0F);
        assertEquals(1.25D, form.loopInterval.get(),
            "empty grouped sound track does not fall back to the legacy loop interval");

        SoundKeyframeValue groupedValue = SoundKeyframeValue.capture(form, SoundKeyframeValue.Group.SOUND);

        groupedValue.loopInterval = 3.5F;
        grouped.insert(0F, groupedValue);
        properties.applyProperties(form, 0F);
        assertEquals(3.5D, form.loopInterval.get(),
            "non-empty grouped sound track does not override the legacy loop interval");

        grouped.removeAll();
        properties.applyProperties(form, 0F);
        assertEquals(1.25D, form.loopInterval.get(),
            "deleting the last grouped sound keyframe does not restore the legacy loop interval");

        properties.resetProperties(form);
        assertTrue(form.loopInterval.getRuntimeValue() == null,
            "sound property reset leaves the loop interval runtime value behind");
        assertEquals(0.75D, form.loopInterval.get(),
            "sound property reset does not restore the persisted loop interval");
    }

    /** A film with {@link #REPLAY_COUNT} replays, each already carrying the id its list assigned. */
    private static Film filmWithReplays()
    {
        Film film = new Film();

        for (int i = 0; i < REPLAY_COUNT; i++)
        {
            film.replays.addReplay();
        }

        return film;
    }

    /** The replays' ids in list order — the sequence every identity assertion compares. */
    private static List<String> replayIds(Film film)
    {
        List<String> ids = new ArrayList<>();

        for (Replay replay : film.replays.getList())
        {
            ids.add(replay.getId());
        }

        return ids;
    }

    private static void assertEquals(int expected, int actual, String message)
    {
        if (expected != actual)
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertEquals(double expected, double actual, String message)
    {
        if (Double.compare(expected, actual) != 0)
        {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message)
    {
        if (!value)
        {
            throw new AssertionError(message);
        }
    }

    private static void testDeathKeyframeChannel()
    {
        ReplayKeyframes keyframes = new ReplayKeyframes("keyframes");
        StubEntity entity = new StubEntity();

        assertTrue(keyframes.getChannels().stream().anyMatch(c -> c.getId().equals("death")),
            "death channel is not registered in ReplayKeyframes");
        assertTrue(keyframes.CURATED_CHANNELS.contains("death"),
            "death channel is missing from CURATED_CHANNELS");

        /* Empty channel must not touch the entity's death state (backward compatibility). */
        keyframes.applyDeath(5, entity);
        assertTrue(!entity.isDead(), "empty death channel marked the entity as dead");
        assertEquals(0D, entity.getDeath(), "empty death channel wrote a non-zero death");

        /* A recorded death (1) maps onto the 0..20 death-time range. */
        keyframes.death.insert(0, 1D);
        keyframes.applyDeath(0, entity);
        assertEquals(20D, entity.getDeath(), "death 1 did not map to death-time 20");
        assertTrue(entity.isDead(), "applied death did not flag the entity as dead");

        /* A mid-ramp value (0.5) maps to an intermediate topple progress. */
        entity.setDeath(0F);
        keyframes.death.insert(10, 0D);
        keyframes.death.insert(11, 1D);
        keyframes.applyDeath(10, entity);
        assertEquals(0D, entity.getDeath(), "death 0 should map to no topple");
        keyframes.applyDeath(11, entity);
        assertEquals(20D, entity.getDeath(), "death 1 after a ramp did not map to 20");

        assertTrue(entity.getHurtTimer() >= 1, "applied death did not keep the red flash active");
    }
}
