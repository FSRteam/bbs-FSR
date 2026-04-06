package mchorse.bbs_mod.cubic.render.vanilla;

import com.google.common.collect.Maps;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.forms.entities.IEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.TexturedRenderLayers;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.model.BipedEntityModel;
import net.minecraft.client.renderer.model.BakedModelManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.DyeableArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.trim.ArmorTrim;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public class ArmorRenderer
{
    private static final Map<String, ResourceLocation> ARMOR_TEXTURE_CACHE = Maps.newHashMap();
    private final BipedEntityModel innerModel;
    private final BipedEntityModel outerModel;
    private final SpriteAtlasTexture armorTrimsAtlas;

    public ArmorRenderer(BipedEntityModel innerModel, BipedEntityModel outerModel, BakedModelManager bakery)
    {
        this.innerModel = innerModel;
        this.outerModel = outerModel;
        this.armorTrimsAtlas = bakery.getAtlas(TexturedRenderLayers.ARMOR_TRIMS_ATLAS_TEXTURE);
    }

    public void renderArmorSlot(PoseStack matrices, MultiBufferSource vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        ItemStack itemStack = entity.getEquipmentStack(armorSlot);
        Item item = itemStack.getItem();

        if (item instanceof ArmorItem armorItem)
        {
            if (armorItem.getSlotType() == armorSlot)
            {
                boolean innerModel = this.usesInnerModel(armorSlot);
                BipedEntityModel bipedModel = this.getModel(armorSlot);
                ModelPart part = this.getPart(bipedModel, type);

                bipedModel.setVisible(true);

                part.pivotX = part.pivotY = part.pivotZ = 0F;
                part.pitch = part.yaw = part.roll = 0F;
                part.xScale = part.yScale = part.zScale = 1F;

                if (armorItem instanceof DyeableArmorItem dyeableArmorItem)
                {
                    int color = dyeableArmorItem.getColor(itemStack);
                    float r = (float)(color >> 16 & 255) / 255.0F;
                    float g = (float)(color >> 8 & 255) / 255.0F;
                    float b = (float)(color & 255) / 255.0F;

                    this.renderArmorParts(part, matrices, vertexConsumers, light, armorItem, innerModel, r, g, b, null);
                    this.renderArmorParts(part, matrices, vertexConsumers, light, armorItem, innerModel, 1F, 1F, 1F, "overlay");
                }
                else
                {
                    this.renderArmorParts(part, matrices, vertexConsumers, light, armorItem, innerModel, 1F, 1F, 1F, null);
                }

                ArmorTrim.getTrim(entity.getWorld().getRegistryManager(), itemStack, true).ifPresent((trim) ->
                {
                    this.renderTrim(part, armorItem.getMaterial(), matrices, vertexConsumers, light, trim, innerModel);
                });

                if (itemStack.hasGlint())
                {
                    this.renderGlint(part, matrices, vertexConsumers, light);
                }
            }
        }
    }

    private ModelPart getPart(BipedEntityModel bipedModel, ArmorType type)
    {
        switch (type)
        {
            case HELMET -> {
                return bipedModel.head;
            }
            case CHEST, LEGGINGS -> {
                return bipedModel.body;
            }
            case LEFT_ARM -> {
                return bipedModel.leftArm;
            }
            case RIGHT_ARM -> {
                return bipedModel.rightArm;
            }
            case LEFT_LEG, LEFT_BOOT -> {
                return bipedModel.leftLeg;
            }
            case RIGHT_LEG, RIGHT_BOOT -> {
                return bipedModel.rightLeg;
            }
        }

        return bipedModel.head;
    }

    private void renderArmorParts(ModelPart part, PoseStack matrices, MultiBufferSource vertexConsumers, int light, ArmorItem item, boolean secondTextureLayer, float red, float green, float blue, String overlay)
    {
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderType.getArmorCutoutNoCull(this.getArmorTexture(item, secondTextureLayer, overlay)));

        part.render(matrices, vertexConsumer, light, OverlayTexture.DEFAULT_UV, red, green, blue, 1F);
    }

    private void renderTrim(ModelPart part, ArmorMaterial material, PoseStack matrices, MultiBufferSource vertexConsumers, int light, ArmorTrim trim, boolean leggings)
    {
        Sprite sprite = this.armorTrimsAtlas.getSprite(leggings ? trim.getLeggingsModelId(material) : trim.getGenericModelId(material));
        VertexConsumer vertexConsumer = sprite.getTextureSpecificVertexConsumer(vertexConsumers.getBuffer(TexturedRenderLayers.getArmorTrims(trim.getPattern().value().decal())));

        part.render(matrices, vertexConsumer, light, OverlayTexture.DEFAULT_UV, 1F, 1F, 1F, 1F);
    }

    private void renderGlint(ModelPart part, PoseStack matrices, MultiBufferSource vertexConsumers, int light)
    {
        part.render(matrices, vertexConsumers.getBuffer(RenderType.getArmorEntityGlint()), light, OverlayTexture.DEFAULT_UV, 1F, 1F, 1F, 1F);
    }

    private BipedEntityModel getModel(EquipmentSlot slot)
    {
        return this.usesInnerModel(slot) ? this.innerModel : this.outerModel;
    }

    private boolean usesInnerModel(EquipmentSlot slot)
    {
        return slot == EquipmentSlot.LEGS;
    }

    private ResourceLocation getArmorTexture(ArmorItem item, boolean secondLayer, String overlay)
    {
        String materialName = item.getMaterial().getName();
        String id = "textures/models/armor/" + materialName + "_layer_" + (secondLayer ? 2 : 1) + (overlay == null ? "" : "_" + overlay) + ".png";

        return ARMOR_TEXTURE_CACHE.computeIfAbsent(id, ResourceLocation::new);
    }
}