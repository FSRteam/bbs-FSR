package mchorse.bbs_mod.entity;

import mchorse.bbs_mod.forms.entities.MCEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.network.ServerNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ActorEntity extends LivingEntity implements IEntityFormProvider
{
    public static AttributeSupplier.Builder createActorAttributes()
    {
        return LivingEntity.createLivingAttributes()
            .add(Attributes.ATTACK_DAMAGE, 1D)
            .add(Attributes.MOVEMENT_SPEED, 0.1D)
            .add(Attributes.ATTACK_SPEED)
            .add(Attributes.LUCK);
    }

    private boolean despawn;
    private final MCEntity entity = new MCEntity(this);
    private Form form;

    private final Map<EquipmentSlot, ItemStack> equipment = new HashMap<>();

    public ActorEntity(EntityType<? extends LivingEntity> entityType, Level level)
    {
        super(entityType, level);
    }

    public MCEntity getEntity()
    {
        return this.entity;
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
        double d = this.getBoundingBox().getAverageSideLength();

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
    public void tick()
    {
        super.tick();

        this.updateSwingTime();

        if (this.form != null)
        {
            this.form.update(this.entity);
        }

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
        super.startSeenByPlayer(player);

        ServerNetwork.sendEntityForm(player, this);
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

    @Override
    protected int getPermissionLevel()
    {
        return 4;
    }
}
