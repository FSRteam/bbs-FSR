package mchorse.bbs_mod.actions;

import com.google.common.collect.MapMaker;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public class SuperFakePlayer extends ServerPlayer
{
    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("12345678-9ABC-DEF1-2345-6789ABCDEF69"), "[BBS Player]");
    private static final Map<SuperFakePlayer.FakePlayerKey, SuperFakePlayer> FAKE_PLAYER_MAP = new MapMaker().weakValues().makeMap();

    public static SuperFakePlayer get(ServerLevel world)
    {
        Objects.requireNonNull(world, "World may not be null.");

        return FAKE_PLAYER_MAP.computeIfAbsent(new SuperFakePlayer.FakePlayerKey(world, PROFILE), key -> new SuperFakePlayer(key.world, key.profile));
    }

    /** The world's actor if it already has one - nothing is born just to be asked. */
    @Nullable
    public static SuperFakePlayer getIfPresent(ServerLevel world)
    {
        return world == null ? null : FAKE_PLAYER_MAP.get(new SuperFakePlayer.FakePlayerKey(world, PROFILE));
    }

    /**
     * Container lids the film is holding open, and the ones the actions of the
     * tick being applied have asked for. A real player holds a lid up by
     * holding its screen open; an actor has no screen, so what it holds is
     * kept here and asserted anew every tick by {@link #flushLids()} - a lid
     * nobody asks for any more comes down, whether the film ran past the clip,
     * was scrubbed backwards or stopped altogether.
     */
    private final Set<BlockPos> openLids = new HashSet<>();
    private final Set<BlockPos> wantedLids = new HashSet<>();

    public void wantLidOpen(BlockPos pos)
    {
        Level level = this.level();

        if (level != null && ContainerLid.isLidded(level, pos))
        {
            this.wantedLids.add(pos.immutable());
        }
    }

    /** Raises the lids this tick asked for and lowers the ones it didn't. */
    public void flushLids()
    {
        Level level = this.level();

        if (level == null)
        {
            return;
        }

        for (BlockPos pos : this.wantedLids)
        {
            if (this.openLids.add(pos))
            {
                ContainerLid.setOpen(level, pos, true);
            }
        }

        Iterator<BlockPos> it = this.openLids.iterator();

        while (it.hasNext())
        {
            BlockPos pos = it.next();

            if (!this.wantedLids.contains(pos))
            {
                ContainerLid.setOpen(level, pos, false);

                it.remove();
            }
        }

        this.wantedLids.clear();
    }

    protected SuperFakePlayer(ServerLevel world, GameProfile profile)
    {
        super(world.getServer(), world, profile, ClientInformation.createDefault());

        this.connection = new SuperFakePlayerNetworkHandler(this);
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
    public boolean acceptsFailure()
    {
        return false;
    }

    @Override
    public boolean shouldInformAdmins()
    {
        return false;
    }

    @Override
    public void tick()
    {}

    @Override
    public void updateOptions(ClientInformation settings)
    {}

    @Override
    public void awardStat(Stat<?> stat, int amount)
    {}

    @Override
    public void resetStat(Stat<?> stat)
    {}

    @Override
    public boolean isInvulnerableTo(DamageSource damageSource)
    {
        return true;
    }

    @Nullable
    @Override
    public PlayerTeam getTeam()
    {
        return null;
    }

    @Override
    public boolean startRiding(Entity entity, boolean force)
    {
        return false;
    }

    @Override
    public void openTextEdit(SignBlockEntity sign, boolean front)
    {}

    /**
     * No window ever opens for an actor - the film draws its own, and a real
     * one would drag a menu and an inventory into the shot. What the world
     * loses along with it, the lid of a chest, is put back by
     * {@link ContainerLid} instead.
     */
    @Override
    public OptionalInt openMenu(@Nullable MenuProvider factory)
    {
        return OptionalInt.empty();
    }

    @Override
    public void openHorseInventory(AbstractHorse horse, Container inventory)
    {}

    private record FakePlayerKey(ServerLevel world, GameProfile profile)
    {}
}
