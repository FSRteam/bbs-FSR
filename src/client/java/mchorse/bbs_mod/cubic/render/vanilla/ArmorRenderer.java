package mchorse.bbs_mod.cubic.render.vanilla;

import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.forms.entities.IEntity;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.component.DyedItemColor;

public class ArmorRenderer
{
    private static final int WHITE = 0xFFFFFFFF;
    private final HumanoidModel<?> innerModel;
    private final HumanoidModel<?> outerModel;
    private final TextureAtlas armorTrimsAtlas;

    public ArmorRenderer(HumanoidModel<?> innerModel, HumanoidModel<?> outerModel, ModelManager modelManager)
    {
        this.innerModel = innerModel;
        this.outerModel = outerModel;
        this.armorTrimsAtlas = modelManager.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
    }

    public void renderArmorSlot(PoseStack matrices, MultiBufferSource vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        ItemStack itemStack = entity.getEquipmentStack(armorSlot);
        Item item = itemStack.getItem();

        if (item instanceof ArmorItem armorItem && armorItem.getEquipmentSlot() == armorSlot)
        {
            boolean usesInnerModel = this.usesInnerModel(armorSlot);
            HumanoidModel<?> humanoidModel = this.getModel(armorSlot);
            ModelPart part = this.getPart(humanoidModel, type);

            humanoidModel.setAllVisible(true);

            part.x = part.y = part.z = 0F;
            part.xRot = part.yRot = part.zRot = 0F;
            part.xScale = part.yScale = part.zScale = 1F;

            int dyedColor = 0xFF000000 | DyedItemColor.getOrDefault(itemStack, DyedItemColor.LEATHER_COLOR);

            for (ArmorMaterial.Layer layer : armorItem.getMaterial().value().layers())
            {
                int color = layer.dyeable() ? dyedColor : WHITE;

                this.renderArmorPart(part, matrices, vertexConsumers, light, layer.texture(usesInnerModel), color);
            }

            ArmorTrim trim = itemStack.get(DataComponents.TRIM);

            if (trim != null)
            {
                this.renderTrim(part, armorItem.getMaterial(), matrices, vertexConsumers, light, trim, usesInnerModel);
            }

            if (itemStack.hasFoil())
            {
                this.renderGlint(part, matrices, vertexConsumers, light);
            }
        }
    }

    private ModelPart getPart(HumanoidModel<?> humanoidModel, ArmorType type)
    {
        switch (type)
        {
            case HELMET -> {
                return humanoidModel.head;
            }
            case CHEST, LEGGINGS -> {
                return humanoidModel.body;
            }
            case LEFT_ARM -> {
                return humanoidModel.leftArm;
            }
            case RIGHT_ARM -> {
                return humanoidModel.rightArm;
            }
            case LEFT_LEG, LEFT_BOOT -> {
                return humanoidModel.leftLeg;
            }
            case RIGHT_LEG, RIGHT_BOOT -> {
                return humanoidModel.rightLeg;
            }
        }

        return humanoidModel.head;
    }

    private void renderArmorPart(ModelPart part, PoseStack matrices, MultiBufferSource vertexConsumers, int light, ResourceLocation texture, int color)
    {
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderType.armorCutoutNoCull(texture));

        part.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY, color);
    }

    private void renderTrim(ModelPart part, Holder<ArmorMaterial> material, PoseStack matrices, MultiBufferSource vertexConsumers, int light, ArmorTrim trim, boolean leggings)
    {
        TextureAtlasSprite sprite = this.armorTrimsAtlas.getSprite(leggings ? trim.innerTexture(material) : trim.outerTexture(material));
        VertexConsumer vertexConsumer = sprite.wrap(vertexConsumers.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));

        part.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY, WHITE);
    }

    private void renderGlint(ModelPart part, PoseStack matrices, MultiBufferSource vertexConsumers, int light)
    {
        part.render(matrices, vertexConsumers.getBuffer(RenderType.armorEntityGlint()), light, OverlayTexture.NO_OVERLAY, WHITE);
    }

    private HumanoidModel<?> getModel(EquipmentSlot slot)
    {
        return this.usesInnerModel(slot) ? this.innerModel : this.outerModel;
    }

    private boolean usesInnerModel(EquipmentSlot slot)
    {
        return slot == EquipmentSlot.LEGS;
    }
}
