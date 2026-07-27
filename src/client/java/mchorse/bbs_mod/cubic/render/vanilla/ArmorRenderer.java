package mchorse.bbs_mod.cubic.render.vanilla;

import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
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
import org.joml.Matrix4f;
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

    /* Skinning state for the current slot. When bending is active, geometry gets rendered
     * with an identity pose so the skinner receives local coordinates (see
     * SkinningVertexConsumer), and the two bone matrices below do the actual placement. */
    private final SkinningVertexConsumer skinner = new SkinningVertexConsumer();
    private final PoseStack identityStack = new PoseStack();
    private final Matrix4f upperMatrix = new Matrix4f();
    private final Matrix4f lowerMatrix = new Matrix4f();
    private boolean skinning;
    private float bendStart;
    private float bendEnd;

    public ArmorRenderer(HumanoidModel<?> innerModel, HumanoidModel<?> outerModel, ModelManager modelManager)
    {
        this.innerModel = innerModel;
        this.outerModel = outerModel;
        this.armorTrimsAtlas = modelManager.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
    }

    public void renderArmorSlot(PoseStack matrices, MultiBufferSource vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        this.renderArmorSlot(matrices, null, 0F, 0F, vertexConsumers, entity, armorSlot, type, light);
    }

    /**
     * Renders an armor slot, optionally skinning it between two bones so it bends along
     * with the limb. Passing a {@code null} lower matrix renders the armor rigidly, which
     * is the behavior for models without bending joints.
     */
    public void renderArmorSlot(PoseStack matrices, Matrix4f lowerMatrix, float bendStart, float bendEnd, MultiBufferSource vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        this.skinning = lowerMatrix != null;

        if (this.skinning)
        {
            this.upperMatrix.set(matrices.last().pose());
            this.lowerMatrix.set(lowerMatrix);
            this.bendStart = bendStart;
            this.bendEnd = bendEnd;
            this.identityStack.setIdentity();
        }

        try
        {
            this.renderSlot(matrices, vertexConsumers, entity, armorSlot, type, light);
        }
        finally
        {
            this.skinning = false;
            this.skinner.clear();
        }
    }

    private void renderSlot(PoseStack matrices, MultiBufferSource vertexConsumers, IEntity entity, EquipmentSlot armorSlot, ArmorType type, int light)
    {
        ItemStack itemStack = entity.getEquipmentStack(armorSlot);
        Item item = itemStack.getItem();

        if (item instanceof ArmorItem armorItem && armorItem.getEquipmentSlot() == armorSlot)
        {
            boolean usesInnerModel = this.usesInnerModel(armorSlot);
            HumanoidModel<?> vanillaModel = this.getModel(armorSlot);
            HumanoidModel<?> humanoidModel = this.resolveModel(itemStack, entity, armorSlot, vanillaModel);
            ModelPart part = this.getPart(humanoidModel, type);

            if (humanoidModel != vanillaModel)
            {
                /* The bend range is calibrated against vanilla's armor boxes, so it can't be
                 * applied to a mod's own geometry — its parts may span an entirely different
                 * height and would tear at the wrong place. Render those rigidly instead. */
                this.skinning = false;
            }

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

        this.renderPart(part, matrices, vertexConsumer, light, color);
    }

    private void renderTrim(ModelPart part, Holder<ArmorMaterial> material, PoseStack matrices, MultiBufferSource vertexConsumers, int light, ArmorTrim trim, boolean leggings)
    {
        TextureAtlasSprite sprite = this.armorTrimsAtlas.getSprite(leggings ? trim.innerTexture(material) : trim.outerTexture(material));
        VertexConsumer vertexConsumer = sprite.wrap(vertexConsumers.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));

        this.renderPart(part, matrices, vertexConsumer, light, WHITE);
    }

    private void renderGlint(ModelPart part, PoseStack matrices, MultiBufferSource vertexConsumers, int light)
    {
        this.renderPart(part, matrices, vertexConsumers.getBuffer(RenderType.armorEntityGlint()), light, WHITE);
    }

    /**
     * Renders a part either rigidly or skinned between the two bone matrices. Material
     * layers, trims and glint all funnel through here so they share identical vertex
     * placement — rendering any of them differently would misalign them from the armor.
     */
    private void renderPart(ModelPart part, PoseStack matrices, VertexConsumer vertexConsumer, int light, int color)
    {
        if (this.skinning)
        {
            VertexConsumer skinned = this.skinner.setup(vertexConsumer, this.upperMatrix, this.lowerMatrix, this.bendStart, this.bendEnd);

            part.render(this.identityStack, skinned, light, OverlayTexture.NO_OVERLAY, color);
        }
        else
        {
            part.render(matrices, vertexConsumer, light, OverlayTexture.NO_OVERLAY, color);
        }
    }

    private HumanoidModel<?> getModel(EquipmentSlot slot)
    {
        return this.usesInnerModel(slot) ? this.innerModel : this.outerModel;
    }

    /**
     * Picks the model to render an armor piece with, letting mods swap in their own
     * geometry through the client item extension. Without this, modded armor with custom
     * models renders as plain vanilla-shaped armor.
     *
     * <p>The extension wants a {@link LivingEntity}, which is only available when the
     * form is driven by an actual entity — otherwise the vanilla model is used, matching
     * the behavior before mod armor was supported at all.</p>
     */
    private HumanoidModel<?> resolveModel(ItemStack itemStack, IEntity entity, EquipmentSlot slot, HumanoidModel<?> original)
    {
        if (!(entity instanceof MCEntity mcEntity) || !(mcEntity.getMcEntity() instanceof LivingEntity livingEntity))
        {
            return original;
        }

        HumanoidModel<?> replacement = IClientItemExtensions.of(itemStack).getHumanoidArmorModel(livingEntity, itemStack, slot, original);

        return replacement == null ? original : replacement;
    }

    private boolean usesInnerModel(EquipmentSlot slot)
    {
        return slot == EquipmentSlot.LEGS;
    }
}
