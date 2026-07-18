package mchorse.bbs_mod.blocks.entities;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.events.ModelBlockEntityUpdateCallback;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.forms.forms.Form;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ModelBlockEntity extends BlockEntity
{
    private ModelProperties properties = new ModelProperties();
    private IEntity entity = new StubEntity();

    private float lastYaw = Float.NaN;
    private float currentYaw = Float.NaN;

    public ModelBlockEntity(BlockPos pos, BlockState state)
    {
        super(BBSMod.MODEL_BLOCK_ENTITY.get(), pos, state);
    }

    public String getName()
    {
        BlockPos pos = this.getBlockPos();
        Form form = this.getProperties().getForm();
        String s = "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";

        if (form != null)
        {
            s += " " + form.getDisplayName();
        }

        return s;
    }

    public ModelProperties getProperties()
    {
        return this.properties;
    }

    public IEntity getEntity()
    {
        return this.entity;
    }

    public void setLookYaw(float yaw)
    {
        this.lastYaw = yaw;
        this.currentYaw = yaw;
    }

    public float updateLookYawContinuous(float yaw)
    {
        if (Float.isNaN(this.currentYaw))
        {
            this.setLookYaw(yaw);

            return this.currentYaw;
        }

        float diff = yaw - this.lastYaw;

        while (diff > Math.PI) diff -= (float) (Math.PI * 2);
        while (diff < -Math.PI) diff += (float) (Math.PI * 2);

        this.currentYaw += diff;
        this.lastYaw = yaw;

        return this.currentYaw;
    }

    public void resetLookYaw()
    {
        this.lastYaw = this.currentYaw = Float.NaN;
    }

    public void snapLookYawToBase(float lastYaw, float currentYaw)
    {
        this.lastYaw = lastYaw;
        this.currentYaw = currentYaw;
    }

    public void tick(Level world, BlockPos pos, BlockState state)
    {
        ModelBlockEntityUpdateCallback.EVENT.invoker().update(this);

        this.entity.update();
        this.entity.setWorld(world);
        this.properties.update(this.entity);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries)
    {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries)
    {
        super.saveAdditional(nbt, registries);

        MapType data = this.properties.toData();

        DataStorageUtils.writeToNbtCompound(nbt, "Properties", data);
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries)
    {
        super.loadAdditional(nbt, registries);

        BaseType baseType = DataStorageUtils.readFromNbtCompound(nbt, "Properties");

        if (baseType instanceof MapType mapType)
        {
            this.properties.fromData(mapType);
        }
    }

    public void updateForm(MapType data, Level world)
    {
        ModelProperties replacement = new ModelProperties();

        replacement.fromData(data);

        BlockPos pos = this.getBlockPos();
        BlockState blockState = world.getBlockState(pos);
        ModelProperties previous = this.properties;

        try
        {
            this.properties = replacement;
            this.setChanged();
            world.sendBlockUpdated(pos, blockState, blockState, Block.UPDATE_CLIENTS);
        }
        catch (RuntimeException | LinkageError e)
        {
            this.properties = previous;

            throw e;
        }
    }
}
