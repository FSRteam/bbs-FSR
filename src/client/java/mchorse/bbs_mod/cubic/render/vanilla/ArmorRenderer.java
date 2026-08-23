package mchorse.bbs_mod.cubic.render.vanilla;

import com.google.common.base.Suppliers;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import mchorse.bbs_mod.cubic.model.ArmorType;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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

import java.util.function.Supplier;

public class ArmorRenderer
{
    private static final int WHITE = 0xFFFFFFFF;

    /** Cataclysm's Cursium ghost layer uses alpha 125 white over the ghost texture. */
    private static final int GHOST_COLOR = 0x7DFFFFFF;

    private static final ResourceLocation CURSIUM_GHOST_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("cataclysm", "textures/armor/cursium_armor_ghost.png");

    /**
     * Semi-transparent energy-swirl layer matching Cataclysm's {@code CMRenderTypes.GHOST}
     * (energy-swirl shader + translucent blending + LEQUAL depth). Differs from vanilla
     * {@code RenderType.energySwirl}, which is additive; the ghost is a normal translucent
     * pass so its 125-alpha white tint shows correctly under shader packs.
     */
    private static final Supplier<RenderType> GHOST_RENDER_TYPE = Suppliers.memoize(() ->
        RenderType.create(
            "bbs_cursium_ghost",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENERGY_SWIRL_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(CURSIUM_GHOST_TEXTURE, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .createCompositeState(false)
        )
    );

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

            /* Iron's Spellbooks / Iron's Lib armor renders through GeckoLib's GeoRenderProvider
             * (not IClientItemExtensions). Those are full-mesh models that need the entity-level
             * pose stack (not the per-bone matrix BBS applies later), so the geo check in
             * renderArmorSlot is a no-op — it will always let bone-level callers through. Only
             * the dedicated entity-level branch in ModelFormRenderer actually kicks in. */
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

                this.renderArmorPart(part, matrices, vertexConsumers, light, this.getArmorTexture(itemStack, entity, armorSlot, layer, usesInnerModel), color);
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

            if (this.isCursium(itemStack))
            {
                this.renderGhost(part, matrices, light);
            }
        }
    }

    /**
     * Detects L_Ender's Cataclysm Cursium armor by item id without a compile-time
     * dependency on the mod. The ghost layer is a Cataclysm-specific effect, so gating
     * on the mod's own registry id keeps vanilla/other mods on the unchanged path.
     */
    private boolean isCursium(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return false;
        }

        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String namespace = key == null ? "" : key.getNamespace();
        String path = key == null ? "" : key.getPath();

        return namespace.equals("cataclysm")
            && (path.startsWith("cursium_helmet")
                || path.startsWith("cursium_chestplate")
                || path.startsWith("cursium_leggings")
                || path.startsWith("cursium_boots"));
    }

    /**
     * Renders the Cursium ghost layer: the same armor geometry drawn a second time with
     * the energy-swirl shader, semi-transparent blending and the mod's ghost texture.
     * Mirrors Cataclysm's {@code CMRenderTypes.GHOST} so shader packs keep the standard
     * energy-swirl path. The mesh is built separately and deferred through the translucent
     * queue so it sorts correctly against other translucent geometry.
     */
    private void renderGhost(ModelPart part, PoseStack matrices, int light)
    {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

        this.renderPart(part, matrices, builder, light, GHOST_COLOR);

        MeshData mesh = builder.build();

        if (mesh == null)
        {
            return;
        }

        Vector3f origin = FormTranslucentQueue.getSortOrigin();

        if (origin == null)
        {
            origin = new Matrix4f(RenderSystem.getModelViewMatrix()).getTranslation(new Vector3f());
        }

        FormTranslucentQueue.add(new FormTranslucentQueue.RenderLayerCommand(
            GHOST_RENDER_TYPE.get(),
            this.uploadGhost(mesh),
            new Matrix4f(RenderSystem.getModelViewMatrix()),
            new Vector3f(origin),
            null,
            /* The ghost mesh is built by this mod's own builder, never through Iris' extended
             * begin, so its vertex layout is always plain. */
            false
        ));
    }

    private VertexBuffer uploadGhost(MeshData mesh)
    {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(mesh);
        VertexBuffer.unbind();
        return buffer;
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

    public HumanoidModel<?> getModel(EquipmentSlot slot)
    {
        return this.usesInnerModel(slot) ? this.innerModel : this.outerModel;
    }

    /**
     * Picks the model to render an armor piece with, letting mods swap in their own
     * geometry through the client item extension. Without this, modded armor with custom
     * models renders as plain vanilla-shaped armor.
     *
     * <p>The extension wants a {@link LivingEntity}, which is only available when the
     * form is driven by an actual entity or an editor preview that carries a backing
     * entity — otherwise the vanilla model is used, matching the behavior before mod
     * armor was supported at all.</p>
     */
    private HumanoidModel<?> resolveModel(ItemStack itemStack, IEntity entity, EquipmentSlot slot, HumanoidModel<?> original)
    {
        LivingEntity livingEntity = this.resolveLivingEntity(entity);

        if (livingEntity == null)
        {
            return original;
        }

        HumanoidModel<?> replacement = IClientItemExtensions.of(itemStack).getHumanoidArmorModel(livingEntity, itemStack, slot, original);

        return replacement == null ? original : replacement;
    }

    /**
     * Resolves the backing {@link LivingEntity} for a render target. Real entities expose
     * one through {@link MCEntity}; editor previews carry an optional {@link StubEntity}
     * override. Returns {@code null} when neither is available, matching the behavior
     * before mod armor was supported at all.
     */
    private LivingEntity resolveLivingEntity(IEntity entity)
    {
        if (entity instanceof MCEntity mcEntity && mcEntity.getMcEntity() instanceof LivingEntity mcLiving)
        {
            return mcLiving;
        }

        if (entity instanceof StubEntity stub && stub.getMcEntity() instanceof LivingEntity stubLiving)
        {
            return stubLiving;
        }

        return null;
    }

    /**
     * Resolves the armor texture the same way NeoForge's {@code HumanoidArmorLayer} does:
     * the item's own {@code getArmorTexture} hook first, falling back to the material
     * layer's standard path. Cataclysm and other mods override that hook to point at a
     * custom texture location; skipping it would render their armor as the missing-
     * texture checkerboard.
     */
    private ResourceLocation getArmorTexture(ItemStack itemStack, IEntity entity, EquipmentSlot slot, ArmorMaterial.Layer layer, boolean usesInnerModel)
    {
        LivingEntity livingEntity = this.resolveLivingEntity(entity);

        if (livingEntity == null)
        {
            return layer.texture(usesInnerModel);
        }

        return ClientHooks.getArmorTexture(livingEntity, itemStack, layer, usesInnerModel, slot);
    }

    /**
     * Renders a GeckoLib armor piece (Iron's Spellbooks / Iron's Lib) through the standard
     * GeoRenderProvider chain. Geo geometry renders rigidly — the skinning vertex consumer
     * cannot intercept GeckoLib's own buffers — so bending is disabled for this branch.
     */
    private void renderGeoArmor(PoseStack matrices, MultiBufferSource vertexConsumers, IEntity entity,
        ItemStack itemStack, EquipmentSlot slot, HumanoidModel<?> baseModel, int light)
    {
        LivingEntity livingEntity = this.resolveLivingEntity(entity);

        if (livingEntity == null)
        {
            return;
        }

        this.skinning = false;

        GeoArmorSupport.renderGeoArmor(matrices, vertexConsumers, livingEntity, itemStack, slot, baseModel, light);
    }

    private boolean usesInnerModel(EquipmentSlot slot)
    {
        return slot == EquipmentSlot.LEGS;
    }
}
