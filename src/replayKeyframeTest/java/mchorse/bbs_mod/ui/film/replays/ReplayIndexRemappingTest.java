package mchorse.bbs_mod.ui.film.replays;

import com.mojang.serialization.Lifecycle;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import mchorse.bbs_mod.actions.values.ActionTarget;
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
import mchorse.bbs_mod.film.replays.ReplayIndexRemapper;
import mchorse.bbs_mod.film.replays.ReplayReferenceRemapper;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.forms.values.ValueAnchor;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.KeyframeNavigationTest;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.film.utils.keyframes.KeyframeInteractionTest;
import mchorse.bbs_mod.utils.clips.Clip;
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

public final class ReplayIndexRemappingTest
{
    private static final int[] REFERENCES = {-1, 0, 1, 2, 3, 99};

    public static void main(String[] args)
    {
        Runnable restoreRuntime = installHeadlessClientRuntime();

        try
        {
            testEverySingleDeletion();
            testBatchDeletion();
            testArbitraryReorder();
            testIdentityNotEquality();
            testActionTargetReorderAndDeletion();
            testFilmReferenceTransaction();
            testGroupedSoundChannelsPreserveLegacyFallback();
            testGroupedSoundLoopIntervalLifecycle();
            testBbsVolumeFieldsHaveNoFiniteUpperLimit();
            testReplayTrackCategories();
            testGlintLayerKeyframes();
            testEnchantedEquipmentSerializationRoundTrip();
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

            source.mainHand.insert(0F, sword);
            source.armorChest.insert(0F, chest);

            BaseType data = source.toData();
            BaseType loaded = DataStorageUtils.readFromBytes(DataStorageUtils.writeToBytes(data));
            ReplayKeyframes copy = new ReplayKeyframes("keyframes");

            copy.fromData(loaded);

            ItemStack hand = copy.mainHand.interpolate(0F);
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

    private static void testEverySingleDeletion()
    {
        List<Object> previousOrder = replayOrder();

        for (int removed = 0; removed < previousOrder.size(); removed++)
        {
            List<Object> currentOrder = new ArrayList<>(previousOrder);

            currentOrder.remove(removed);
            assertMapping(previousOrder, currentOrder, "single deletion " + removed);
        }
    }

    private static void testBatchDeletion()
    {
        List<Object> previousOrder = replayOrder();
        List<Object> currentOrder = List.of(previousOrder.get(0), previousOrder.get(2));

        assertMapping(previousOrder, currentOrder, "batch deletion");
    }

    private static void testArbitraryReorder()
    {
        List<Object> previousOrder = replayOrder();
        List<Object> currentOrder = List.of(
            previousOrder.get(2),
            previousOrder.get(0),
            previousOrder.get(3),
            previousOrder.get(1)
        );

        assertMapping(previousOrder, currentOrder, "arbitrary reorder");
    }

    private static void testIdentityNotEquality()
    {
        String first = new String("same");
        String second = new String("same");
        List<String> previousOrder = List.of(first, second);
        List<String> currentOrder = List.of(second, first);
        int[] oldToNew = ReplayIndexRemapper.create(previousOrder, currentOrder);

        assertEquals(1, ReplayIndexRemapper.remap(0, oldToNew), "identity first");
        assertEquals(0, ReplayIndexRemapper.remap(1, oldToNew), "identity second");
    }

    private static void testActionTargetReorderAndDeletion()
    {
        AttackActionClip attack = new AttackActionClip();
        EntityInteractionActionClip interaction = new EntityInteractionActionClip();
        NestedTargetClip addon = new NestedTargetClip();

        attack.target.uuid.set("00000000-0000-0000-0000-000000000001");
        attack.target.entityType.set("bbs:actor");
        attack.target.replayId.set("1");
        attack.target.position.get().set(1D, 2D, 3D);
        interaction.target.uuid.set("00000000-0000-0000-0000-000000000002");
        interaction.target.entityType.set("bbs:actor");
        interaction.target.replayId.set("2");
        interaction.target.position.get().set(4D, 5D, 6D);
        addon.target.uuid.set("00000000-0000-0000-0000-000000000003");
        addon.target.replayId.set("0");

        ReplayReferenceRemapper.remap(List.of(attack, interaction, addon), new int[] {1, 2, 0});

        assertEquals("2", attack.target.replayId.get(), "attack target reorder");
        assertEquals("0", interaction.target.replayId.get(), "entity-interaction target reorder");
        assertEquals("1", addon.target.replayId.get(), "nested addon action target reorder");

        interaction.target.replayId.set("invalid");
        ReplayReferenceRemapper.remap(List.of(attack, interaction), new int[] {0, 1, ReplayIndexRemapper.NO_TARGET});

        assertEquals("", attack.target.replayId.get(), "deleted attack target replay fallback");
        assertEquals("", interaction.target.replayId.get(), "invalid entity-interaction replay fallback");
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

        ValueGroup anchorHolder = new ValueGroup("holder");
        ValueAnchor aliasedAnchor = new ValueAnchor("anchor", new Anchor());

        aliasedAnchor.getOriginalValue().replay = 0;
        aliasedAnchor.setRuntimeValue(aliasedAnchor.getOriginalValue());
        anchorHolder.add(aliasedAnchor);
        ReplayReferenceRemapper.remap(List.of(anchorHolder), new int[] {1, 0});

        assertEquals(1, aliasedAnchor.getOriginalValue().replay,
            "aliased runtime/original anchor was remapped twice");
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

        attack.target.uuid.set("00000000-0000-0000-0000-000000000011");
        attack.target.replayId.set("1");
        interaction.target.uuid.set("00000000-0000-0000-0000-000000000012");
        interaction.target.replayId.set("2");
        source.actions.addClip(attack);
        source.actions.addClip(interaction);
        camera.selector.set(2);
        film.camera.addClip(camera);
        form.anchor.get().replay = 1;
        Anchor runtimeAnchor = new Anchor();

        runtimeAnchor.replay = 0;
        form.anchor.setRuntimeValue(runtimeAnchor);
        nestedForm.anchor.get().replay = 2;
        part.setForm(nestedForm);
        form.parts.addBodyPart(part);
        source.form.set(form);

        List<Replay> previousOrder = new ArrayList<>(film.replays.getList());

        film.replays.remove(secondTarget);
        film.replays.add(0, secondTarget);
        film.replays.sync();
        UIReplayList.remapReplayReferences(film, previousOrder);

        assertEquals("2", attack.target.replayId.get(), "film attack target reorder");
        assertEquals("0", interaction.target.replayId.get(), "film entity target reorder");
        assertEquals(2, form.anchor.getOriginalValue().replay, "top-level form anchor reorder");
        assertEquals(1, form.anchor.getRuntimeValue().replay, "transient form anchor did not follow the same reorder");
        assertEquals(0, nestedForm.anchor.get().replay, "nested body-part anchor reorder");
        assertEquals(0, camera.selector.get(), "camera entity selector reorder");

        previousOrder = new ArrayList<>(film.replays.getList());
        film.replays.remove(firstTarget);
        UIReplayList.remapReplayReferences(film, previousOrder);

        assertEquals("", attack.target.replayId.get(), "deleted film action target fallback");
        assertTrue(attack.target.isPresent(), "deleted film action target became legacy raycast");
        assertEquals(ReplayIndexRemapper.NO_TARGET, form.anchor.getOriginalValue().replay, "deleted top-level form anchor");
        assertEquals(0, nestedForm.anchor.get().replay, "surviving nested body-part anchor");
        assertEquals("0", interaction.target.replayId.get(), "surviving film entity target");
        assertEquals(0, camera.selector.get(), "surviving camera selector");
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

    private static List<Object> replayOrder()
    {
        return List.of(new Object(), new Object(), new Object(), new Object());
    }

    private static void assertMapping(List<?> previousOrder, List<?> currentOrder, String scenario)
    {
        int[] oldToNew = ReplayIndexRemapper.create(previousOrder, currentOrder);

        assertEquals(previousOrder.size(), oldToNew.length, scenario + " mapping size");

        for (int oldReference : REFERENCES)
        {
            int expected = expectedIndex(oldReference, previousOrder, currentOrder);
            int actual = ReplayIndexRemapper.remap(oldReference, oldToNew);

            assertEquals(expected, actual, scenario + " reference " + oldReference);
        }
    }

    private static int expectedIndex(int oldIndex, List<?> previousOrder, List<?> currentOrder)
    {
        if (oldIndex < 0 || oldIndex >= previousOrder.size())
        {
            return ReplayIndexRemapper.NO_TARGET;
        }

        Object target = previousOrder.get(oldIndex);

        for (int i = 0; i < currentOrder.size(); i++)
        {
            if (currentOrder.get(i) == target)
            {
                return i;
            }
        }

        return ReplayIndexRemapper.NO_TARGET;
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

    private static final class NestedTargetClip extends Clip
    {
        private final ValueGroup nested = new ValueGroup("nested");
        private final ActionTarget target = new ActionTarget("target");

        private NestedTargetClip()
        {
            this.nested.add(this.target);
            this.add(this.nested);
        }

        @Override
        protected Clip create()
        {
            return new NestedTargetClip();
        }
    }
}
