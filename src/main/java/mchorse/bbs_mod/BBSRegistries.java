package mchorse.bbs_mod;

import mchorse.bbs_mod.entity.ActorEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

public final class BBSRegistries
{
    private BBSRegistries()
    {}

    public static void onRegister(RegisterEvent event)
    {
        event.register(Registries.ENTITY_TYPE, helper ->
        {
            registerEntityType(helper, "actor", BBSMod.ACTOR_ENTITY);
            registerEntityType(helper, "gun_projectile", BBSMod.GUN_PROJECTILE_ENTITY);
        });

        event.register(Registries.BLOCK, helper ->
        {
            registerBlock(helper, "model", BBSMod.MODEL_BLOCK);
            registerBlock(helper, "chroma_red", BBSMod.CHROMA_RED_BLOCK);
            registerBlock(helper, "chroma_green", BBSMod.CHROMA_GREEN_BLOCK);
            registerBlock(helper, "chroma_blue", BBSMod.CHROMA_BLUE_BLOCK);
            registerBlock(helper, "chroma_cyan", BBSMod.CHROMA_CYAN_BLOCK);
            registerBlock(helper, "chroma_magenta", BBSMod.CHROMA_MAGENTA_BLOCK);
            registerBlock(helper, "chroma_yellow", BBSMod.CHROMA_YELLOW_BLOCK);
            registerBlock(helper, "chroma_black", BBSMod.CHROMA_BLACK_BLOCK);
            registerBlock(helper, "chroma_white", BBSMod.CHROMA_WHITE_BLOCK);
        });

        event.register(Registries.ITEM, helper ->
        {
            registerItem(helper, "model", BBSMod.MODEL_BLOCK_ITEM);
            registerItem(helper, "gun", BBSMod.GUN_ITEM);
            registerItem(helper, "chroma_red", BBSMod.CHROMA_RED_BLOCK_ITEM);
            registerItem(helper, "chroma_green", BBSMod.CHROMA_GREEN_BLOCK_ITEM);
            registerItem(helper, "chroma_blue", BBSMod.CHROMA_BLUE_BLOCK_ITEM);
            registerItem(helper, "chroma_cyan", BBSMod.CHROMA_CYAN_BLOCK_ITEM);
            registerItem(helper, "chroma_magenta", BBSMod.CHROMA_MAGENTA_BLOCK_ITEM);
            registerItem(helper, "chroma_yellow", BBSMod.CHROMA_YELLOW_BLOCK_ITEM);
            registerItem(helper, "chroma_black", BBSMod.CHROMA_BLACK_BLOCK_ITEM);
            registerItem(helper, "chroma_white", BBSMod.CHROMA_WHITE_BLOCK_ITEM);
        });

        event.register(Registries.BLOCK_ENTITY_TYPE, helper ->
            registerBlockEntityType(helper, "model_block_entity", BBSMod.MODEL_BLOCK_ENTITY)
        );

        event.register(Registries.SOUND_EVENT, helper -> registerSoundEvent(helper, "click", BBSMod.CLICK));

        event.register(Registries.CREATIVE_MODE_TAB, helper -> helper.register(id("main"), BBSMod.ITEM_GROUP));
    }

    public static void onEntityAttributes(EntityAttributeCreationEvent event)
    {
        event.put(BBSMod.ACTOR_ENTITY, ActorEntity.createActorAttributes().build());
    }

    private static void registerEntityType(RegisterEvent.RegisterHelper<EntityType<?>> helper, String path, EntityType<?> entityType)
    {
        helper.register(id(path), entityType);
    }

    private static void registerBlock(RegisterEvent.RegisterHelper<Block> helper, String path, Block block)
    {
        helper.register(id(path), block);
    }

    private static void registerBlockEntityType(RegisterEvent.RegisterHelper<BlockEntityType<?>> helper, String path, BlockEntityType<?> blockEntityType)
    {
        helper.register(id(path), blockEntityType);
    }

    private static void registerItem(RegisterEvent.RegisterHelper<Item> helper, String path, Item item)
    {
        helper.register(id(path), item);
    }

    private static void registerSoundEvent(RegisterEvent.RegisterHelper<SoundEvent> helper, String path, SoundEvent soundEvent)
    {
        helper.register(id(path), soundEvent);
    }

    private static ResourceLocation id(String path)
    {
        return ResourceLocation.fromNamespaceAndPath(BBSMod.MOD_ID, path);
    }
}
