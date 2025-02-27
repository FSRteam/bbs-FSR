package mchorse.bbs_mod.items;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.entity.GunProjectileEntity;
import mchorse.bbs_mod.forms.FormUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class GunItem extends Item
{
    public static Entity actor;

    public GunItem(Settings settings)
    {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand)
    {
        ItemStack stack = user.getStackInHand(hand);
        GunProperties properties = this.getProperties(stack);

        /* Launch the player */
        if (properties.launch)
        {
            Vec3d rotationVector = user.getRotationVector().multiply(properties.launchPower);

            if (properties.launchAdditive)
            {
                user.addVelocity(rotationVector);
            }
            else
            {
                user.setVelocity(rotationVector);
            }

            return new TypedActionResult<>(ActionResult.SUCCESS, stack);
        }

        if (!world.isClient)
        {
            /* Shoot projectiles */
            int projectiles = Math.max(properties.projectiles, 1);
            Entity owner = actor == null ? user : actor;

            for (int i = 0; i < projectiles; i++)
            {
                GunProjectileEntity projectile = new GunProjectileEntity(BBSMod.GUN_PROJECTILE_ENTITY, world);
                float yaw = user.getHeadYaw() + (float) (properties.scatterY * (Math.random() - 0.5D));
                float pitch = user.getPitch() + (float) (properties.scatterX * (Math.random() - 0.5D));

                projectile.setProperties(properties);
                projectile.setForm(FormUtils.copy(properties.projectileForm));
                projectile.setPos(user.getX(), user.getY() + user.getEyeHeight(user.getPose()), user.getZ());
                projectile.setVelocity(owner, pitch, yaw, 0F, properties.speed, 0F);
                projectile.calculateDimensions();

                world.spawnEntity(projectile);
            }

            if (!properties.cmdFiring.isEmpty())
            {
                user.getServer().getCommandManager().executeWithPrefix(user.getCommandSource(), properties.cmdFiring);
            }
        }

        return new TypedActionResult<>(ActionResult.PASS, stack);
    }

    private GunProperties getProperties(ItemStack stack)
    {
        return GunProperties.get(stack);
    }
}