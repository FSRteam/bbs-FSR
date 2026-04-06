package mchorse.bbs_mod.items;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.client.renderer.item.BBSItemRenderers;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.forms.FormUtils;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

public class GunItem extends Item
{
    public static Entity actor;

    public GunItem(Properties settings)
    {
        super(settings);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        Entity owner = actor == null ? player : actor;
        ItemStack stack = player.getItemInHand(hand);
        GunProperties properties = this.getProperties(stack);

        /* Launch the player */
        if (properties.launch)
        {
            Vec3 rotationVector = owner.getViewVector(1.0F).scale(properties.launchPower);

            if (properties.launchAdditive)
            {
                owner.addDeltaMovement(rotationVector);
            }
            else
            {
                owner.setDeltaMovement(rotationVector);
            }

            return InteractionResultHolder.success(stack);
        }

        if (!level.isClientSide)
        {
            /* Shoot projectiles */
            int projectiles = Math.max(properties.projectiles, 1);

            for (int i = 0; i < projectiles; i++)
            {
                GunProjectileEntity projectile = BBSMod.GUN_PROJECTILE_ENTITY.create(level);

                if (projectile == null)
                {
                    continue;
                }

                float yaw = owner.getYHeadRot() + (float) (properties.scatterY * (Math.random() - 0.5D));
                float pitch = owner.getXRot() + (float) (properties.scatterX * (Math.random() - 0.5D));

                projectile.setProperties(properties);
                projectile.setForm(FormUtils.copy(properties.projectileForm));
                projectile.setPos(owner.getX(), owner.getEyeY(), owner.getZ());
                projectile.shootFromRotation(owner, pitch, yaw, 0F, properties.speed, 0F);
                projectile.refreshDimensions();

                level.addFreshEntity(projectile);
            }

            if (!properties.cmdFiring.isEmpty() && owner.getServer() != null)
            {
                owner.getServer().getCommands().performPrefixedCommand(owner.createCommandSourceStack(), properties.cmdFiring);
            }
        }

        return InteractionResultHolder.pass(stack);
    }

    private GunProperties getProperties(ItemStack stack)
    {
        return GunProperties.get(stack);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer)
    {
        consumer.accept(new IClientItemExtensions()
        {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer()
            {
                return BBSItemRenderers.getGunCustomRenderer();
            }
        });
    }
}
