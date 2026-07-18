package mchorse.bbs_mod.entity;

import mchorse.bbs_mod.actions.PlaybackActorActionContext;
import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerNetwork;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ActorEntity extends LivingEntity implements IEntityFormProvider
{
    private static final EntityDataAccessor<Boolean> PLAYBACK_WORLD_ISOLATED = SynchedEntityData.defineId(
        ActorEntity.class,
        EntityDataSerializers.BOOLEAN
    );

    public static AttributeSupplier.Builder createActorAttributes()
    {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.ATTACK_DAMAGE, 1D)
            .add(Attributes.MOVEMENT_SPEED, 0.1D)
            .add(Attributes.ATTACK_SPEED)
            .add(Attributes.LUCK);
    }

    private boolean despawn;
    private String replayId = "";
    @Nullable
    private UUID playbackAudience;
    private final MCEntity entity = new MCEntity(this);
    private Form form;

    private final Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();

    public ActorEntity(EntityType<? extends LivingEntity> entityType, Level level)
    {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder)
    {
        super.defineSynchedData(builder);
        builder.define(PLAYBACK_WORLD_ISOLATED, false);
    }

    public MCEntity getEntity()
    {
        return this.entity;
    }

    public String getReplayId()
    {
        return this.replayId;
    }

    public void setReplayId(String replayId)
    {
        this.replayId = replayId == null ? "" : replayId;
    }

    public void setPlaybackAudience(@Nullable UUID playbackAudience)
    {
        this.playbackAudience = playbackAudience;

        boolean isolated = playbackAudience != null;

        this.entityData.set(PLAYBACK_WORLD_ISOLATED, isolated);

        /* A targeted-command actor is a private visual projection. It must be
         * present in the ServerLevel so the intended client can track it, but
         * it must not acquire physics, block-trigger, AI-target or damage side
         * effects in the shared world. */
        this.noPhysics = isolated;
        this.setNoGravity(isolated);
        this.setInvulnerable(isolated);
    }

    public boolean isPlaybackWorldIsolated()
    {
        return this.entityData.get(PLAYBACK_WORLD_ISOLATED);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key)
    {
        super.onSyncedDataUpdated(key);

        if (PLAYBACK_WORLD_ISOLATED.equals(key))
        {
            this.noPhysics = this.isPlaybackWorldIsolated();
        }
    }

    public boolean isPlaybackVisibleTo(ServerPlayer player)
    {
        return this.playbackAudience == null
            || (player != null && this.playbackAudience.equals(player.getUUID()));
    }

    private boolean isPlaybackActionAuthorized()
    {
        return this.isPlaybackWorldIsolated()
            && PlaybackActorActionContext.isAuthorizedFor(this);
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player)
    {
        return this.isPlaybackVisibleTo(player) && super.broadcastToPlayer(player);
    }

    @Override
    public boolean isSpectator()
    {
        return this.isPlaybackWorldIsolated() || super.isSpectator();
    }

    @Override
    public boolean isPickable()
    {
        return !this.isPlaybackWorldIsolated() && super.isPickable();
    }

    @Override
    public boolean isPushable()
    {
        return !this.isPlaybackWorldIsolated() && super.isPushable();
    }

    @Override
    public boolean isAttackable()
    {
        return this.isPlaybackActionAuthorized()
            || (!this.isPlaybackWorldIsolated() && super.isAttackable());
    }

    @Override
    public boolean hurt(DamageSource source, float amount)
    {
        if (!this.isPlaybackWorldIsolated())
        {
            return super.hurt(source, amount);
        }

        if (source == null
            || !this.isPlaybackActionAuthorized()
            || this.level().isClientSide
            || this.isDeadOrDying())
        {
            return false;
        }

        return this.hurtPlaybackProjection(source, amount);
    }

    private boolean hurtPlaybackProjection(DamageSource source, float amount)
    {
        PlaybackActorDamageProjection.Transition transition = PlaybackActorDamageProjection.apply(
            this.getHealth(),
            amount
        );

        if (!transition.applied())
        {
            return false;
        }

        /* LivingEntity.hurt/die exposes a real entity to global NeoForge
         * damage hooks, game events, sounds, kill score/criteria and death
         * loot. A private actor is only a client projection, so reproduce the
         * tracked visual state without entering any of those world paths. */
        this.noActionTime = 0;
        this.walkAnimation.setSpeed(1.5F);
        this.hurtDuration = 10;
        this.hurtTime = this.hurtDuration;
        this.markHurt();
        this.setHealth(transition.health());
        this.level().broadcastDamageEvent(this, source);

        if (transition.dead())
        {
            this.diePlaybackProjection();
        }

        return true;
    }

    @Override
    public void die(DamageSource source)
    {
        if (!this.isPlaybackWorldIsolated())
        {
            super.die(source);

            return;
        }

        if (this.level().isClientSide || this.isPlaybackActionAuthorized())
        {
            this.diePlaybackProjection();
        }
    }

    private void diePlaybackProjection()
    {
        if (this.isRemoved() || this.dead)
        {
            return;
        }

        this.setHealth(0F);
        this.dead = true;

        if (!this.level().isClientSide)
        {
            this.level().broadcastEntityEvent(this, (byte) 3);
        }

        this.setPose(Pose.DYING);
    }

    @Override
    public void playSound(SoundEvent sound, float volume, float pitch)
    {
        if (!this.isPlaybackWorldIsolated() || this.level().isClientSide)
        {
            super.playSound(sound, volume, pitch);

            return;
        }

        if (this.isSilent() || !(this.level() instanceof ServerLevel level))
        {
            return;
        }

        UUID audience = this.playbackAudience;

        if (audience == null)
        {
            return;
        }

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(audience);

        if (player == null || player.serverLevel() != level)
        {
            return;
        }

        player.connection.send(new ClientboundSoundEntityPacket(
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
            this.getSoundSource(),
            this,
            volume,
            pitch,
            this.random.nextLong()
        ));
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, @Nullable Entity source)
    {
        if (!this.isPlaybackWorldIsolated())
        {
            super.gameEvent(event, source);
        }
    }

    @Override
    public void setRemainingFireTicks(int ticks)
    {
        super.setRemainingFireTicks(this.isPlaybackWorldIsolated() ? 0 : ticks);
    }

    @Override
    public boolean isIgnoringBlockTriggers()
    {
        return this.isPlaybackWorldIsolated() || super.isIgnoringBlockTriggers();
    }

    @Override
    protected void pushEntities()
    {
        if (!this.isPlaybackWorldIsolated())
        {
            super.pushEntities();
        }
    }

    @Override
    public boolean shouldBeSaved()
    {
        return !this.isPlaybackWorldIsolated() && super.shouldBeSaved();
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
        Form lastForm = this.form;

        this.form = form;

        if (!this.level().isClientSide)
        {
            if (lastForm != null) lastForm.onDemorph(this);
            if (form != null) form.onMorph(this);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance)
    {
        double d = this.getBoundingBox().getSize();

        if (Double.isNaN(d))
        {
            d = 1D;
        }

        return distance < (d * 256D) * (d * 256D);
    }

    @Override
    public Iterable<ItemStack> getHandSlots()
    {
        return List.of(this.getItemBySlot(EquipmentSlot.MAINHAND), this.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    @Override
    public Iterable<ItemStack> getArmorSlots()
    {
        return List.of(this.getItemBySlot(EquipmentSlot.FEET), this.getItemBySlot(EquipmentSlot.LEGS), this.getItemBySlot(EquipmentSlot.CHEST), this.getItemBySlot(EquipmentSlot.HEAD));
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot)
    {
        return this.equipment.getOrDefault(slot, ItemStack.EMPTY);
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack)
    {
        this.equipment.put(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    @Override
    public HumanoidArm getMainArm()
    {
        return HumanoidArm.RIGHT;
    }

    @Override
    public void swing(InteractionHand hand, boolean updateSelf)
    {
        /* NeoForge's LivingEntity implementation invokes the held item's
         * onEntitySwing hook before starting the animation. Replay swipes are
         * visual-only, so actors reproduce the vanilla animation state and
         * packet without entering an item-owned server hook. */
        if (!this.swinging || this.swingTime >= this.getCurrentSwingDuration() / 2 || this.swingTime < 0)
        {
            this.swingTime = -1;
            this.swinging = true;
            this.swingingArm = hand;

            if (this.level() instanceof ServerLevel level)
            {
                ClientboundAnimatePacket packet = new ClientboundAnimatePacket(
                    this,
                    hand == InteractionHand.MAIN_HAND
                        ? ClientboundAnimatePacket.SWING_MAIN_HAND
                        : ClientboundAnimatePacket.SWING_OFF_HAND
                );

                if (updateSelf)
                {
                    level.getChunkSource().broadcastAndSend(this, packet);
                }
                else
                {
                    level.getChunkSource().broadcast(this, packet);
                }
            }
        }
    }

    @Override
    public void tick()
    {
        if (!this.level().isClientSide && this.isPlaybackWorldIsolated())
        {
            this.tickPlaybackProjectionServer();

            return;
        }

        super.tick();

        this.tickActorVisualState();

        if (this.level().isClientSide)
        {
            return;
        }

        /* Pickup items */
        AABB box = this.getBoundingBox().inflate(1D, 0.5D, 1D);
        List<Entity> list = this.level().getEntities(this, box);

        for (Entity entity : list)
        {
            if (entity instanceof ItemEntity itemEntity)
            {
                ItemStack itemStack = itemEntity.getItem();
                int i = itemStack.getCount();

                if (!entity.isRemoved() && !itemEntity.hasPickUpDelay())
                {
                    ServerLevel level = (ServerLevel) this.level();
                    level.getChunkSource().broadcast(entity, new ClientboundTakeItemEntityPacket(entity.getId(), this.getId(), i));
                    entity.discard();
                }
            }
        }
    }

    private void tickPlaybackProjectionServer()
    {
        /* ServerLevel already advances tickCount and snapshots old pose before
         * invoking Entity.tick. Keep only state required by the tracked visual
         * projection; LivingEntity.baseTick/tick would expose breathing,
         * equipment, enchantment, travel and AI hooks to the shared world. */
        this.oAttackAnim = this.attackAnim;
        this.firstTick = false;

        if (this.hurtTime > 0)
        {
            this.hurtTime -= 1;
        }

        if (this.invulnerableTime > 0)
        {
            this.invulnerableTime -= 1;
        }

        if (this.isDeadOrDying() && this.level().shouldTickDeath(this))
        {
            this.tickDeath();
        }

        this.tickActorVisualState();
    }

    private void tickActorVisualState()
    {
        this.updateSwingTime();

        if (this.form != null)
        {
            this.form.update(this.entity);
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

    @Override
    public void startSeenByPlayer(ServerPlayer player)
    {
        if (!this.isPlaybackVisibleTo(player))
        {
            return;
        }

        super.startSeenByPlayer(player);

        ServerNetwork.sendEntityForm(player, this);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt)
    {
        super.readAdditionalSaveData(nbt);

        this.despawn = nbt.getBoolean("despawn");
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt)
    {
        super.addAdditionalSaveData(nbt);

        nbt.putBoolean("despawn", true);
    }

    @Override
    protected int getPermissionLevel()
    {
        return 4;
    }
}
