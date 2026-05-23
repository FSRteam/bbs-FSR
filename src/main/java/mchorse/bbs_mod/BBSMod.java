package mchorse.bbs_mod;

import com.mojang.brigadier.tree.CommandNode;
import mchorse.bbs_mod.actions.ActionHandler;
import mchorse.bbs_mod.actions.ActionManager;
import mchorse.bbs_mod.actions.compat.ActionEventCompat;
import mchorse.bbs_mod.actions.types.AttackActionClip;
import mchorse.bbs_mod.actions.types.DamageActionClip;
import mchorse.bbs_mod.actions.types.SwipeActionClip;
import mchorse.bbs_mod.actions.types.blocks.BreakBlockActionClip;
import mchorse.bbs_mod.actions.types.blocks.InteractBlockActionClip;
import mchorse.bbs_mod.actions.types.blocks.PlaceBlockActionClip;
import mchorse.bbs_mod.actions.types.chat.ChatActionClip;
import mchorse.bbs_mod.actions.types.chat.CommandActionClip;
import mchorse.bbs_mod.actions.types.item.ItemDropActionClip;
import mchorse.bbs_mod.actions.types.item.UseBlockItemActionClip;
import mchorse.bbs_mod.actions.types.item.UseItemActionClip;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.addon.BBSAddonBridge;
import mchorse.bbs_mod.addon.BBSAddonCollector;
import mchorse.bbs_mod.addon.BBSAddonProtocolSelfCheck;
import mchorse.bbs_mod.addon.BBSAddonRegisterEvent;
import mchorse.bbs_mod.addon.demo.BBSAddonDemoBootstrap;
import mchorse.bbs_mod.blocks.ModelBlock;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.camera.clips.converters.DollyToKeyframeConverter;
import mchorse.bbs_mod.camera.clips.converters.DollyToPathConverter;
import mchorse.bbs_mod.camera.clips.converters.IdleConverter;
import mchorse.bbs_mod.camera.clips.converters.IdleToDollyConverter;
import mchorse.bbs_mod.camera.clips.converters.IdleToKeyframeConverter;
import mchorse.bbs_mod.camera.clips.converters.IdleToPathConverter;
import mchorse.bbs_mod.camera.clips.converters.PathToDollyConverter;
import mchorse.bbs_mod.camera.clips.converters.PathToKeyframeConverter;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.clips.misc.CurveClip;
import mchorse.bbs_mod.camera.clips.misc.SubtitleClip;
import mchorse.bbs_mod.camera.clips.modifiers.AngleClip;
import mchorse.bbs_mod.camera.clips.modifiers.DollyZoomClip;
import mchorse.bbs_mod.camera.clips.modifiers.DragClip;
import mchorse.bbs_mod.camera.clips.modifiers.LookClip;
import mchorse.bbs_mod.camera.clips.modifiers.MathClip;
import mchorse.bbs_mod.camera.clips.modifiers.OrbitClip;
import mchorse.bbs_mod.camera.clips.modifiers.RemapperClip;
import mchorse.bbs_mod.camera.clips.modifiers.ShakeClip;
import mchorse.bbs_mod.camera.clips.modifiers.TrackerClip;
import mchorse.bbs_mod.camera.clips.modifiers.TranslateClip;
import mchorse.bbs_mod.camera.clips.overwrite.DollyClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.clips.overwrite.PathClip;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.events.EventBus;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.BillboardForm;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.forms.ExtrudedForm;
import mchorse.bbs_mod.forms.forms.FramebufferForm;
import mchorse.bbs_mod.forms.forms.ItemForm;
import mchorse.bbs_mod.forms.forms.LabelForm;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.forms.forms.TrailForm;
import mchorse.bbs_mod.forms.forms.VanillaParticleForm;
import mchorse.bbs_mod.items.GunItem;
import mchorse.bbs_mod.items.ModelBlockItem;
import mchorse.bbs_mod.loader.LoaderAccess;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.loader.NeoForgeLoaderAccess;
import mchorse.bbs_mod.compat.FabricRegistryCompat;
import mchorse.bbs_mod.morphing.Morph;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.network.compat.NetworkCompat;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.ISourcePack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.DynamicSourcePack;
import mchorse.bbs_mod.resources.packs.ExternalAssetsSourcePack;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;
import mchorse.bbs_mod.settings.Settings;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.SettingsManager;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.factory.MapFactory;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Mod(BBSMod.MOD_ID)
public class BBSMod
{
    public static final String MOD_ID = "bbs";

    public static final EventBus events = new EventBus();

    private static final Logger LOGGER = LoggerFactory.getLogger(BBSMod.class);

    private final IEventBus modBus;
    private final BBSAddonCollector addonCollector;
    private final BBSAddonBridge addonBridge;

    private static ActionManager actions;

    /* Important folders */
    private static File gameFolder;
    private static File assetsFolder;
    private static File settingsFolder;

    /* Core services */
    private static AssetProvider provider;
    private static DynamicSourcePack dynamicSourcePack;
    private static ExternalAssetsSourcePack originalSourcePack;

    /* Foundation services */
    private static SettingsManager settings;
    private static FormArchitect forms;

    /* Data */
    private static FilmManager films;

    private static List<Runnable> runnables = new ArrayList<>();
    private static int commandRegisterCount;

    private static MapFactory<Clip, ClipFactoryData> factoryCameraClips;
    private static MapFactory<Clip, ClipFactoryData> factoryActionClips;

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<ActorEntity>> ACTOR_ENTITY = ENTITY_TYPES.register("actor", () -> FabricRegistryCompat.buildEntityTypeWithBlockRange(
        MOD_ID + ":actor",
        MobCategory.CREATURE,
        ActorEntity::new,
        EntityDimensions.fixed(0.6F, 1.8F),
        256,
        1
    ));

    public static final DeferredHolder<EntityType<?>, EntityType<GunProjectileEntity>> GUN_PROJECTILE_ENTITY = ENTITY_TYPES.register("gun_projectile", () -> FabricRegistryCompat.buildEntityTypeWithChunkRange(
        MOD_ID + ":gun_projectile",
        MobCategory.MISC,
        GunProjectileEntity::new,
        EntityDimensions.fixed(0.25F, 0.25F),
        24,
        1
    ));

    public static final DeferredHolder<Block, ModelBlock> MODEL_BLOCK = BLOCKS.register("model", () -> new ModelBlock(FabricRegistryCompat.blockSettings()
        .noLootTable()
        .noCollission()
        .noOcclusion()
        .strength(0F)));
    public static final DeferredHolder<Block, Block> CHROMA_RED_BLOCK = BLOCKS.register("chroma_red", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_GREEN_BLOCK = BLOCKS.register("chroma_green", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_BLUE_BLOCK = BLOCKS.register("chroma_blue", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_CYAN_BLOCK = BLOCKS.register("chroma_cyan", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_MAGENTA_BLOCK = BLOCKS.register("chroma_magenta", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_YELLOW_BLOCK = BLOCKS.register("chroma_yellow", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_BLACK_BLOCK = BLOCKS.register("chroma_black", BBSMod::createChromaBlock);
    public static final DeferredHolder<Block, Block> CHROMA_WHITE_BLOCK = BLOCKS.register("chroma_white", BBSMod::createChromaBlock);

    public static final DeferredHolder<Item, ModelBlockItem> MODEL_BLOCK_ITEM = ITEMS.register("model", () -> new ModelBlockItem(MODEL_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, GunItem> GUN_ITEM = ITEMS.register("gun", () -> new GunItem(new Item.Properties().stacksTo(1)));
    public static final DeferredHolder<Item, BlockItem> CHROMA_RED_BLOCK_ITEM = ITEMS.register("chroma_red", () -> new BlockItem(CHROMA_RED_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_GREEN_BLOCK_ITEM = ITEMS.register("chroma_green", () -> new BlockItem(CHROMA_GREEN_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_BLUE_BLOCK_ITEM = ITEMS.register("chroma_blue", () -> new BlockItem(CHROMA_BLUE_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_CYAN_BLOCK_ITEM = ITEMS.register("chroma_cyan", () -> new BlockItem(CHROMA_CYAN_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_MAGENTA_BLOCK_ITEM = ITEMS.register("chroma_magenta", () -> new BlockItem(CHROMA_MAGENTA_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_YELLOW_BLOCK_ITEM = ITEMS.register("chroma_yellow", () -> new BlockItem(CHROMA_YELLOW_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_BLACK_BLOCK_ITEM = ITEMS.register("chroma_black", () -> new BlockItem(CHROMA_BLACK_BLOCK.get(), new Item.Properties()));
    public static final DeferredHolder<Item, BlockItem> CHROMA_WHITE_BLOCK_ITEM = ITEMS.register("chroma_white", () -> new BlockItem(CHROMA_WHITE_BLOCK.get(), new Item.Properties()));

    public static final GameRules.Key<GameRules.BooleanValue> BBS_EDITING_RULE = GameRules.register("bbsEditing", GameRules.Category.MISC, GameRules.BooleanValue.create(true));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ModelBlockEntity>> MODEL_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register("model_block_entity", () -> FabricRegistryCompat.buildBlockEntityType(
        ModelBlockEntity::new,
        MODEL_BLOCK.get()
    ));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ITEM_GROUP = CREATIVE_MODE_TABS.register("main", () -> FabricRegistryCompat.itemGroupBuilder()
        .icon(() -> createModelBlockStack(Link.assets("textures/icon.png")))
        .title(Component.translatable("itemGroup.bbs.main"))
        .displayItems((context, entries) ->
        {
            entries.accept(createModelBlockStack(Link.assets("textures/model_block.png")));
            entries.accept(new ItemStack(CHROMA_RED_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_GREEN_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_BLUE_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_CYAN_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_MAGENTA_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_YELLOW_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_BLACK_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(CHROMA_WHITE_BLOCK_ITEM.get()));
            entries.accept(new ItemStack(GUN_ITEM.get()));
        })
        .build());

    public static final DeferredHolder<SoundEvent, SoundEvent> CLICK = SOUND_EVENTS.register("click", () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MOD_ID, "click")));

    private static SoundEvent createSound(String path)
    {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MOD_ID, path));
    }

    private static File worldFolder;

    private static Block createChromaBlock()
    {
        return new Block(FabricRegistryCompat.blockSettings()
            .noLootTable()
            .requiresCorrectToolForDrops()
            .strength(-1F, 3600000F));
    }

    private static ItemStack createModelBlockStack(Link texture)
    {
        ItemStack stack = new ItemStack(MODEL_BLOCK_ITEM.get());
        BillboardForm form = new BillboardForm();
        ModelProperties properties = new ModelProperties();

        form.transform.get().translate.set(0F, 0.5F, 0F);
        form.texture.set(texture);
        properties.setForm(form);
        properties.getTransformFirstPerson().translate.set(0F, 0F, -0.25F);

        MapType data = properties.toData();
        CompoundTag nbt = new CompoundTag();

        nbt.put("Properties", DataStorageUtils.toNbt(data));
        BlockItem.setBlockEntityData(stack, MODEL_BLOCK_ENTITY.get(), nbt);

        return stack;
    }

    /**
     * Main folder, where all the other folders are located.
     */
    public static File getGameFolder()
    {
        return gameFolder;
    }

    public static File getGamePath(String path)
    {
        return new File(gameFolder, path);
    }

    /**
     * Assets folder within game's folder. It's used to store any assets that can
     * be loaded by {@link #provider}.
     */
    public static File getAssetsFolder()
    {
        ISourcePack sourcePack = getDynamicSourcePack().getSourcePack();

        if (sourcePack instanceof ExternalAssetsSourcePack pack)
        {
            return pack.getFolder();
        }

        return assetsFolder;
    }

    public static File getAudioFolder()
    {
        return getAssetsPath("audio");
    }

    public static File getAssetsPath(String path)
    {
        return new File(getAssetsFolder(), path);
    }

    public static File getAudioCacheFolder()
    {
        return getSettingsPath("audio_cache");
    }

    /**
     * Config folder within game's folder. It's used to store any configuration
     * files.
     */
    public static File getSettingsFolder()
    {
        return settingsFolder;
    }

    public static File getSettingsPath(String path)
    {
        return new File(settingsFolder, path);
    }

    public static File getExportFolder()
    {
        return getGamePath("export");
    }

    public static ActionManager getActions()
    {
        return actions;
    }

    public static AssetProvider getProvider()
    {
        return provider;
    }

    public static DynamicSourcePack getDynamicSourcePack()
    {
        return dynamicSourcePack;
    }

    public static ExternalAssetsSourcePack getOriginalSourcePack()
    {
        return originalSourcePack;
    }

    public static SettingsManager getSettings()
    {
        return settings;
    }

    public static FormArchitect getForms()
    {
        return forms;
    }

    public static FilmManager getFilms()
    {
        return films;
    }

    public static MapFactory<Clip, ClipFactoryData> getFactoryCameraClips()
    {
        return factoryCameraClips;
    }

    public static MapFactory<Clip, ClipFactoryData> getFactoryActionClips()
    {
        return factoryActionClips;
    }

    public BBSMod()
    {
        this.modBus = ModLoadingContext.get().getActiveContainer().getEventBus();
        this.addonCollector = new BBSAddonCollector();
        this.addonBridge = new BBSAddonBridge(this.addonCollector);

        if (!FMLEnvironment.production)
        {
            BBSAddonDemoBootstrap.bind(this.modBus);
        }

        LoaderAccessHolder.set(new NeoForgeLoaderAccess(() -> new ArrayList<>(this.addonCollector.getAddons())));

        this.modBus.addListener(this::onConstructMod);
        this.modBus.addListener(this::onCommonSetup);
        BBSMod.ENTITY_TYPES.register(this.modBus);
        BBSMod.BLOCKS.register(this.modBus);
        BBSMod.ITEMS.register(this.modBus);
        BBSMod.BLOCK_ENTITY_TYPES.register(this.modBus);
        BBSMod.SOUND_EVENTS.register(this.modBus);
        BBSMod.CREATIVE_MODE_TABS.register(this.modBus);
        this.modBus.addListener(BBSRegistries::onEntityAttributes);
        this.modBus.addListener(NetworkCompat::onRegisterPayloadHandlers);

        if (FMLEnvironment.dist == Dist.CLIENT)
        {
            this.registerClientNeoEventsBridge();
        }

        LOGGER.info("[BBS-SEM] topic=cmd.register entry=BBSMod#<init> wire_mode=NeoForge.EVENT_BUS.addListener dispatch_id=n/a");
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onStartTracking);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoinLevel);
    }

    private void registerClientNeoEventsBridge()
    {
        try
        {
            Class<?> bridgeClass = Class.forName("mchorse.bbs_mod.client.BBSClientNeoEvents");
            Method registerMethod = bridgeClass.getMethod("register", IEventBus.class);

            registerMethod.invoke(null, this.modBus);
        }
        catch (Exception | LinkageError e)
        {
            LOGGER.warn("[bbs-client] failed to register NeoForge client event bridge, continue without it", e);
        }
    }

    private void onConstructMod(final FMLConstructModEvent event)
    {
        LOGGER.info("[bbs-addon] opening registration window on mod bus");

        /* Early settings registration — must happen before resource reload
           triggers LanguageManagerMixin, which accesses BBSSettings.language */
        LoaderAccess loader = LoaderAccessHolder.get();
        gameFolder = loader.getGameDir().toFile();
        settingsFolder = new File(gameFolder, "config/bbs/settings");
        settings = new SettingsManager();
        setupConfig(Icons.PROCESSOR, "bbs", new File(settingsFolder, "bbs.json"), BBSSettings::register);

        try
        {
            this.modBus.post(new BBSAddonRegisterEvent(this.addonCollector));
        }
        catch (Exception e)
        {
            LOGGER.error("[bbs-addon] registration event failed, continue without crashing core", e);
        }
        finally
        {
            this.addonCollector.closeRegistrationWindow();
        }
    }

    private void onCommonSetup(final FMLCommonSetupEvent event)
    {
        LoaderAccess loader = LoaderAccessHolder.get();
        BBSAddonProtocolSelfCheck.run(loader, this.addonCollector);
        List<BBSAddonMod> addonEntrypoints = loader.getEntrypoints("bbs-addon", BBSAddonMod.class);
        LOGGER.info("[bbs-addon] loader resolved {} registered addon(s)", addonEntrypoints.size());
        assetsFolder = new File(gameFolder, "config/bbs/assets");

        assetsFolder.mkdirs();
        this.addonBridge.bridgeToInternalBus(events);

        actions = new ActionManager();
        ActionEventCompat.register();
        ActionHandler.registerHandlers(actions);

        originalSourcePack = new ExternalAssetsSourcePack(Link.ASSETS, assetsFolder).providesFiles();
        dynamicSourcePack = new DynamicSourcePack(originalSourcePack);
        provider = new AssetProvider();
        provider.register(dynamicSourcePack);
        provider.register(new InternalAssetsSourcePack());

        events.post(new RegisterSourcePacksEvent(provider));

        forms = new FormArchitect();
        forms
            .register(Link.bbs("billboard"), BillboardForm.class, null)
            .register(Link.bbs("label"), LabelForm.class, null)
            .register(Link.bbs("model"), ModelForm.class, null)
            .register(Link.bbs("particle"), ParticleForm.class, null)
            .register(Link.bbs("extruded"), ExtrudedForm.class, null)
            .register(Link.bbs("block"), BlockForm.class, null)
            .register(Link.bbs("item"), ItemForm.class, null)
            .register(Link.bbs("anchor"), AnchorForm.class, null)
            .register(Link.bbs("mob"), MobForm.class, null)
            .register(Link.bbs("vanilla_particles"), VanillaParticleForm.class, null)
            .register(Link.bbs("trail"), TrailForm.class, null)
            .register(Link.bbs("framebuffer"), FramebufferForm.class, null);

        films = new FilmManager(() -> new File(worldFolder, "bbs/films"));

        factoryCameraClips = new MapFactory<Clip, ClipFactoryData>()
            .register(Link.bbs("idle"), IdleClip.class, new ClipFactoryData(Icons.FRUSTUM, 0x159e64)
                .withConverter(Link.bbs("dolly"), new IdleToDollyConverter())
                .withConverter(Link.bbs("path"), new IdleToPathConverter())
                .withConverter(Link.bbs("keyframe"), new IdleToKeyframeConverter()))
            .register(Link.bbs("dolly"), DollyClip.class, new ClipFactoryData(Icons.CAMERA, 0xffa500)
                .withConverter(Link.bbs("idle"), IdleConverter.CONVERTER)
                .withConverter(Link.bbs("path"), new DollyToPathConverter())
                .withConverter(Link.bbs("keyframe"), new DollyToKeyframeConverter()))
            .register(Link.bbs("path"), PathClip.class, new ClipFactoryData(Icons.GALLERY, 0x6820ad)
                .withConverter(Link.bbs("idle"), IdleConverter.CONVERTER)
                .withConverter(Link.bbs("dolly"), new PathToDollyConverter())
                .withConverter(Link.bbs("keyframe"), new PathToKeyframeConverter()))
            .register(Link.bbs("keyframe"), KeyframeClip.class, new ClipFactoryData(Icons.CURVES, 0xde2e9f)
                .withConverter(Link.bbs("idle"), IdleConverter.CONVERTER))
            .register(Link.bbs("translate"), TranslateClip.class, new ClipFactoryData(Icons.UPLOAD, 0x4ba03e))
            .register(Link.bbs("angle"), AngleClip.class, new ClipFactoryData(Icons.ARC, 0xd77a0a))
            .register(Link.bbs("drag"), DragClip.class, new ClipFactoryData(Icons.FADING, 0x4baff7))
            .register(Link.bbs("shake"), ShakeClip.class, new ClipFactoryData(Icons.EXCHANGE, 0x159e64))
            .register(Link.bbs("math"), MathClip.class, new ClipFactoryData(Icons.GRAPH, 0x6820ad))
            .register(Link.bbs("look"), LookClip.class, new ClipFactoryData(Icons.VISIBLE, 0x197fff))
            .register(Link.bbs("orbit"), OrbitClip.class, new ClipFactoryData(Icons.GLOBE, 0xd82253))
            .register(Link.bbs("remapper"), RemapperClip.class, new ClipFactoryData(Icons.TIME, 0x222222))
            .register(Link.bbs("audio"), AudioClip.class, new ClipFactoryData(Icons.SOUND, 0xffc825))
            .register(Link.bbs("subtitle"), SubtitleClip.class, new ClipFactoryData(Icons.FONT, 0x888899))
            .register(Link.bbs("curve"), CurveClip.class, new ClipFactoryData(Icons.ARC, 0xff1493))
            .register(Link.bbs("tracker"), TrackerClip.class, new ClipFactoryData(Icons.USER, 0xffffff))
            .register(Link.bbs("dolly_zoom"), DollyZoomClip.class, new ClipFactoryData(Icons.FILTER, 0x7d56c9));

        factoryActionClips = new MapFactory<Clip, ClipFactoryData>()
            .register(Link.bbs("chat"), ChatActionClip.class, new ClipFactoryData(Icons.BUBBLE, Colors.YELLOW))
            .register(Link.bbs("command"), CommandActionClip.class, new ClipFactoryData(Icons.PROPERTIES, Colors.ACTIVE))
            .register(Link.bbs("place_block"), PlaceBlockActionClip.class, new ClipFactoryData(Icons.BLOCK, Colors.INACTIVE))
            .register(Link.bbs("interact_block"), InteractBlockActionClip.class, new ClipFactoryData(Icons.FULLSCREEN, Colors.MAGENTA))
            .register(Link.bbs("break_block"), BreakBlockActionClip.class, new ClipFactoryData(Icons.BULLET, Colors.GREEN))
            .register(Link.bbs("use_item"), UseItemActionClip.class, new ClipFactoryData(Icons.POINTER, Colors.BLUE))
            .register(Link.bbs("use_block_item"), UseBlockItemActionClip.class, new ClipFactoryData(Icons.BUCKET, Colors.CYAN))
            .register(Link.bbs("drop_item"), ItemDropActionClip.class, new ClipFactoryData(Icons.ARROW_DOWN, Colors.DEEP_PINK))
            .register(Link.bbs("attack"), AttackActionClip.class, new ClipFactoryData(Icons.DROP, Colors.RED))
            .register(Link.bbs("damage"), DamageActionClip.class, new ClipFactoryData(Icons.SKULL, Colors.CURSOR))
            .register(Link.bbs("swipe"), SwipeActionClip.class, new ClipFactoryData(Icons.LIMB, Colors.ORANGE));

        events.post(new RegisterSettingsEvent());

        ServerNetwork.setup();
    }

    private void onServerStarted(ServerStartedEvent event)
    {
        worldFolder = event.getServer().getWorldPath(LevelResource.ROOT).toFile();
    }

    private void onServerStopped(ServerStoppedEvent event)
    {
        resetServerRuntimeState();
    }

    private void onServerStopping(ServerStoppingEvent event)
    {
        resetServerRuntimeState();
    }

    private void onServerTickPre(ServerTickEvent.Pre event)
    {
        if (actions != null)
        {
            actions.tick();
        }
    }

    private void onServerTickPost(ServerTickEvent.Post event)
    {
        ActionEventCompat.flushBlockBreakAfterQueue();

        for (Runnable runnable : runnables)
        {
            runnable.run();
        }

        runnables.clear();
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            if (player.getServer() != null)
            {
                ServerNetwork.sendHandshake(player.getServer(), player);
            }
        }
    }

    private void onStartTracking(PlayerEvent.StartTracking event)
    {
        if (event.getTarget() instanceof ServerPlayer tracked && event.getEntity() instanceof ServerPlayer watcher)
        {
            runnables.add(() ->
            {
                Morph morph = Morph.getMorph(tracked);
                if (morph != null)
                {
                    ServerNetwork.sendMorph(watcher, tracked.getId(), morph.getForm());
                }
            });
        }
    }

    private void onEntityJoinLevel(EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide())
        {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer player)
        {
            Morph morph = Morph.getMorph(player);

            if (morph != null)
            {
                ServerNetwork.sendMorphToTracked(player, morph.getForm());
            }
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event)
    {
        commandRegisterCount++;
        LOGGER.info("[BBS-SEM] topic=cmd.register entry=BBSMod#onRegisterCommands wire_mode=NeoForge.EVENT_BUS.addListener dispatch_id={}",
            Integer.toHexString(System.identityHashCode(event.getDispatcher())));
        LOGGER.info("[BBS-SEM] topic=cmd.selection selection={} server_type={}",
            event.getCommandSelection(),
            mapServerType(event.getCommandSelection()));

        BBSCommands.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());

        CommandNode<?> bbsNode = event.getDispatcher().getRoot().getChild("bbs");
        int bbsNodeCount = bbsNode == null ? 0 : bbsNode.getChildren().size();

        LOGGER.info("[BBS-SEM] topic=cmd.rebuild selection={} reload_index={} bbs_node_count={}",
            event.getCommandSelection(),
            commandRegisterCount,
            bbsNodeCount
        );
    }

    private void resetServerRuntimeState()
    {
        if (actions != null)
        {
            actions.reset();
        }

        commandRegisterCount = 0;
        ServerNetwork.reset();
        runnables.clear();
    }

    public static Settings setupConfig(Icon icon, String id, File destination, Consumer<SettingsBuilder> registerer)
    {
        SettingsBuilder builder = new SettingsBuilder(icon, id, destination);
        Settings settings = builder.getConfig();

        registerer.accept(builder);

        BBSMod.settings.modules.put(settings.getId(), settings);
        BBSMod.settings.load(settings, settings.file);

        return settings;
    }

    private static String mapServerType(Commands.CommandSelection selection)
    {
        return switch (selection)
        {
            case DEDICATED -> "dedicated";
            case INTEGRATED -> "integrated";
            case ALL -> "all";
        };
    }
}
