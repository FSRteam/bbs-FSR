package mchorse.bbs_mod.cubic.render.vanilla;

import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Optional GeckoLib armor support (Iron's Spellbooks / Iron's Lib). Armor from those mods
 * registers custom models through {@link GeoRenderProvider#getGeoArmorRenderer}, not through
 * the vanilla {@code IClientItemExtensions} hook BBS already resolves. This class mirrors the
 * GeckoLib mixin call sequence ({@code GeoArmorRenderer.prepForRender} then
 * {@code renderToBuffer}) so geo geometry renders instead of BBS drawing the inner armor box.
 *
 * <p>The dependency is {@code clientCompileOnly}, and every public method probes
 * {@link #isAvailable()} first, so an install without GeckoLib is byte-for-byte unchanged.
 * All methods are internally safe against a missing/older GeckoLib at runtime.</p>
 */
public class GeoArmorSupport
{
    private static final String PROVIDER = "software.bernie.geckolib.animatable.client.GeoRenderProvider";
    private static Boolean available;

    private GeoArmorSupport()
    {}

    /** Whether GeckoLib is loadable in this runtime. Cached after first probe. */
    public static boolean isAvailable()
    {
        if (available == null)
        {
            available = probe();
        }

        return available;
    }

    private static boolean probe()
    {
        try
        {
            return Class.forName(PROVIDER) != null;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /** Whether the given item carries a GeckoLib armor renderer. Safe without GeckoLib. */
    public static boolean isGeoArmor(ItemStack stack)
    {
        if (!isAvailable() || stack == null || stack.isEmpty())
        {
            return false;
        }

        try
        {
            var renderer = GeoRenderProvider.of(stack).getGeoArmorRenderer(null, stack, null, null);

            return renderer instanceof GeoArmorRenderer<?>;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * Renders a GeckoLib armor piece, mirroring GeckoLib's own {@code HumanoidArmorLayer}
     * interception. Returns whether the piece was a geo armor and was rendered.
     */
    public static boolean renderGeoArmor(PoseStack matrices, MultiBufferSource vertexConsumers, LivingEntity entity,
        ItemStack stack, EquipmentSlot slot, HumanoidModel<?> baseModel, int light)
    {
        if (!isAvailable() || entity == null || stack == null || slot == null || baseModel == null)
        {
            return false;
        }

        try
        {
            /* The provider method is generic over the entity type, but the incoming base
             * model is an unbounded HumanoidModel<?>. Casting to the raw type lets the
             * inferred T collapse to LivingEntity without a capture-constraint clash. */
            @SuppressWarnings({ "unchecked", "rawtypes" })
            HumanoidModel<?> model = GeoRenderProvider.of(stack).getGeoArmorRenderer(
                entity, stack, slot, (HumanoidModel) (Object) baseModel);

            if (!(model instanceof GeoArmorRenderer<?> renderer))
            {
                return false;
            }

            /* prepForRender is mandatory: without it renderToBuffer logs an error and renders
             * nothing. The vanilla harness passes the buffer source and animation weights;
             * netHeadYaw/headPitch/limbSwing default to 0 like GeckoLib's deprecated overload. */
            renderer.prepForRender(entity, stack, slot, baseModel, vertexConsumers,
                0F, 0F, 0F, 0F, 0F);

            /* GeoArmorRenderer copies base pose properties itself during preRender
             * (applyBaseModel), so a manual copyPropertiesTo is unnecessary (and the
             * wildcard generic makes it untypeable here). */
            model.renderToBuffer(matrices, null, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

            return true;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}