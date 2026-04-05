package mchorse.bbs_mod.entity;

import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.items.GunProperties;
import mchorse.bbs_mod.network.ServerNetwork;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class GunProjectileEntity extends Projectile implements IEntityFormProvider
{
    private boolean despawn;
    private GunProperties properties = new GunProperties();
    private Form form;
    private final IEntity stub = new StubEntity();
    private final IEntity target = new MCEntity(this);

    private boolean stuck;
    private int lifeLeft;
    private int bounces;
    private BlockState stuckBlockState;
    private boolean impacted;

    public GunProjectileEntity(EntityType<? extends Projectile> type, Level level)
    {
        super(type, level);
    }

    private void vanish()
    {
        this.discard();
        this.executeCommand(this.properties.cmdVanish);
    }

    private void impact()
    {
        if (this.level().isClientSide)
        {
            return;
        }

        if (!this.impacted)
        {
            this.setForm(FormUtils.copy(this.properties.impactForm));
            ServerNetwork.sendEntityFormToTracking(this, this);

            this.impacted = true;
        }

        this.executeCommand(this.properties.cmdImpact);
    }

    private void executeCommand(String command)
    {
        if (!command.isEmpty() && this.getServer() != null)
        {
            this.getServer().getCommands().performPrefixedCommand(this.createCommandSourceStack(), command);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder)
    {}

    public GunProperties getProperties()
    {
        return this.properties;
    }

    public void setProperties(GunProperties properties)
    {
        this.properties = properties;
        this.bounces = properties.bounces;
    }

    @Override
    public int getEntityId()
    {
        return this.getId();
    }

    @Override
    public Form getForm()
    {
        return this.form;
    }

    @Override
    public void setForm(Form form)
    {
        this.form = form;

        if (this.form != null)
        {
            this.form.playMain();
        }
    }

    public IEntity getEntity()
    {
        return this.properties.useTarget ? this.target : this.stub;
    }

    @Override
    protected int getPermissionLevel()
    {
        return 2;
    }

    @Override
    public boolean acceptsSuccess()
    {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance)
    {
        return true;
    }

    @Override
    public void tick()
    {
        super.tick();

        this.getEntity().update();

        if (this.form != null)
        {
            this.form.update(this.getEntity());
        }

        if (!this.level().isClientSide)
        {
            this.lifeLeft += 1;

            int ticking = this.properties.ticking;

            if (ticking > 0 && this.lifeLeft % ticking == 0)
            {
                this.executeCommand(this.properties.cmdTicking);
            }

            if (this.lifeLeft >= this.properties.lifeSpan)
            {
                this.vanish();
            }
        }

        /* Movement code */
        Vec3 velocity = this.getDeltaMovement();

        if (this.xRotO == 0F && this.yRotO == 0F)
        {
            this.setYRot(MathUtils.toDeg((float) Mth.atan2(velocity.x, velocity.z)));
            this.setXRot(MathUtils.toDeg((float) Mth.atan2(velocity.y, velocity.horizontalDistance())));

            this.yRotO = this.getYRot();
            this.xRotO = this.getXRot();
        }

        BlockPos blockPos = this.blockPosition();
        BlockState blockState = this.level().getBlockState(blockPos);

        if (this.isInWaterOrRain() || blockState.is(Blocks.POWDER_SNOW))
        {
            this.extinguishFire();
        }

        if (this.stuck && this.properties.collideBlocks)
        {
            if (this.stuckBlockState != blockState && this.shouldFall())
            {
                this.fall();
            }
        }
        else
        {
            Vec3 oldPos = this.position();
            Vec3 pos = oldPos.add(velocity);

            HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity, ClipContext.Block.COLLIDER);

            if (hitResult.getType() != HitResult.Type.MISS)
            {
                pos = hitResult.getLocation();
            }

            EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(this.level(), this, oldPos, pos, this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0D), this::canHitEntity);

            if (entityHitResult != null)
            {
                hitResult = entityHitResult;
            }

            boolean canCollide =
                (this.properties.collideBlocks && hitResult.getType() == HitResult.Type.BLOCK) ||
                (this.properties.collideEntities && hitResult.getType() == HitResult.Type.ENTITY);

            if (canCollide)
            {
                this.onHit(hitResult);
                this.hasImpulse = true;
            }

            velocity = this.getDeltaMovement();

            double x = this.getX() + velocity.x;
            double y = this.getY() + velocity.y;
            double z = this.getZ() + velocity.z;
            double horizontalLength = velocity.horizontalDistance();

            this.setYRot(MathUtils.toDeg((float) Mth.atan2(velocity.x, velocity.z)));
            this.setXRot(MathUtils.toDeg((float) Mth.atan2(velocity.y, horizontalLength)));
            this.setXRot(lerpRotation(this.xRotO, this.getXRot()));
            this.setYRot(lerpRotation(this.yRotO, this.getYRot()));

            float friction = this.properties.friction;
            float gravity = this.properties.gravity;

            if (this.isInWater())
            {
                for (int particles = 0; particles < 4; ++particles)
                {
                    float hitbox = 0.25F;

                    this.level().addParticle(ParticleTypes.BUBBLE, x - velocity.x * hitbox, y - velocity.y * hitbox, z - velocity.z * hitbox, velocity.x, velocity.y, velocity.z);
                }

                friction = 0.6F;
            }

            this.setDeltaMovement(velocity.multiply(friction).subtract(0, gravity, 0));
            this.setPos(x, y, z);
            this.checkInsideBlocks();
        }
    }

    @Override
    public void checkDespawn()
    {
        super.checkDespawn();

        if (this.despawn)
        {
            this.discard();
        }
    }

    private boolean shouldFall()
    {
        return this.stuck && this.level().noCollision((new AABB(this.position(), this.position())).inflate(0.06));
    }

    private void fall()
    {
        Vec3 velocity = this.getDeltaMovement();

        this.stuck = false;

        this.setDeltaMovement(velocity.multiply(this.getRandom().nextFloat() * 0.2F, this.getRandom().nextFloat() * 0.2F, this.getRandom().nextFloat() * 0.2F));
    }

    @Override
    public void move(MoverType movementType, Vec3 movement)
    {
        super.move(movementType, movement);

        if (movementType != MoverType.SELF && this.shouldFall())
        {
            this.fall();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult entityHitResult)
    {
        super.onHitEntity(entityHitResult);

        if (this.level().isClientSide || this.properties.damage <= 0F)
        {
            return;
        }

        Entity entity = entityHitResult.getEntity();
        float length = (float) this.getDeltaMovement().length();
        int damage = Mth.ceil(Mth.clamp(length * this.properties.damage, 0F, Integer.MAX_VALUE));

        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().magic();

        int fireTicks = entity.getRemainingFireTicks();
        boolean deflectsArrows = entity.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES);

        if (this.isOnFire() && !deflectsArrows)
        {
            entity.igniteForSeconds(5.0F);
        }

        if (entity.hurt(source, (float) damage))
        {
            if (entity instanceof LivingEntity livingEntity)
            {
                if (this.properties.knockback > 0)
                {
                    double resistanceFactor = Math.max(0D, 1D - livingEntity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
                    Vec3 punchVector = this.getDeltaMovement().normalize().multiply(this.properties.knockback * 0.6D * resistanceFactor);

                    if (punchVector.lengthSqr() > 0D)
                    {
                        livingEntity.push(punchVector.x, 0.1D, punchVector.z);
                    }
                }

                if (owner instanceof LivingEntity && this.level() instanceof ServerLevel serverLevel)
                {
                    EnchantmentHelper.doPostAttackEffects(serverLevel, livingEntity, source);
                }

                this.onHit(livingEntity);
            }
        }
        else if (deflectsArrows)
        {
            this.deflect();
        }
        else
        {
            entity.setRemainingFireTicks(fireTicks);
            this.setDeltaMovement(this.getDeltaMovement().scale(-0.1D));
            this.setYRot(this.getYRot() + 180F);

            this.yRotO += 180F;
        }
    }

    public void deflect()
    {
        float random = this.getRandom().nextFloat() * 360F;

        this.setDeltaMovement(this.getDeltaMovement().yRot(MathUtils.toRad(random)).scale(0.5D));
        this.setYRot(this.getYRot() + random);

        this.yRotO += random;
    }

    @Override
    protected void onHitBlock(BlockHitResult blockHitResult)
    {
        super.onHitBlock(blockHitResult);

        Vec3 velocity = blockHitResult.getLocation().subtract(this.getX(), this.getY(), this.getZ());

        if (this.bounces > 0)
        {
            this.bounces -= 1;

            velocity = this.getDeltaMovement();

            float damp = this.properties.bounceDamping;

            if (blockHitResult.getDirection().getAxis() == Direction.Axis.X) velocity = velocity.multiply(-damp, damp, damp);
            if (blockHitResult.getDirection().getAxis() == Direction.Axis.Y) velocity = velocity.multiply(damp, -damp, damp);
            if (blockHitResult.getDirection().getAxis() == Direction.Axis.Z) velocity = velocity.multiply(damp, damp, -damp);
        }
        else
        {
            this.stuckBlockState = this.level().getBlockState(blockHitResult.getBlockPos());
            this.stuck = true;

            if (this.properties.vanish)
            {
                this.vanish();
            }
        }

        this.setDeltaMovement(velocity);

        Vec3 gravity = velocity.normalize().scale(0.05D);

        this.setPos(this.getX() - gravity.x, this.getY() - gravity.y, this.getZ() - gravity.z);
        this.impact();
    }

    protected void onHit(LivingEntity target)
    {
        if (this.bounces <= 0 && this.properties.vanish)
        {
            this.vanish();
        }
        else
        {
            this.impact();
        }
    }

    @Override
    protected Entity.MovementEmission getMovementEmission()
    {
        return Entity.MovementEmission.NONE;
    }

    @Override
    public boolean isAttackable()
    {
        return false;
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player)
    {
        super.startSeenByPlayer(player);
        ServerNetwork.sendEntityForm(player, this);
        ServerNetwork.sendGunProperties(player, this);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt)
    {
        super.readAdditionalSaveData(nbt);
        this.despawn = nbt.getBoolean("despawn");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt)
    {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("despawn", true);
    }
}
