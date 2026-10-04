package mchorse.bbs_mod.forms.structure;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Fake {@link Level} backing the block entity renderers of a structure form.
 *
 * <p>Block entity renderers (chests, signs, beds, BBS model blocks) call {@link #setLevel} and then
 * query the world for neighbors and light. Feeding them the real {@code mc.level} answers those
 * queries at unrelated real-world coordinates (double chests fail to pair, etc.). This world instead
 * redirects {@link #getBlockState}/{@link #getFluidState}/{@link #getBlockEntity} to the structure
 * data, so neighbor lookups resolve within the structure.</p>
 *
 * <p>Everything else is borrowed from the real client world: the constructor copies its properties,
 * dimension, registries and profiler, and the remaining abstract methods delegate to it (or no-op
 * for mutators that must never touch the real world). Construction needs a live client world for
 * those registries — {@link #create} returns {@code null} otherwise and the caller falls back to
 * {@code mc.level}.</p>
 */
public class StructureWorld extends Level
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The fallback below is a silent downgrade in quality, so it is worth saying once — but only once. */
    private static boolean reportedFailure;

    private final ClientLevel delegate;
    private final StructureRenderData data;
    private final Map<BlockPos, BlockEntity> blockEntities;

    private StructureWorld(ClientLevel delegate, StructureRenderData data, Map<BlockPos, BlockEntity> blockEntities)
    {
        super(
            (WritableLevelData) delegate.getLevelData(),
            delegate.dimension(),
            delegate.registryAccess(),
            delegate.dimensionTypeRegistration(),
            delegate.getProfilerSupplier(),
            true,  /* client side */
            false, /* not a debug world */
            0L,    /* biome-zoomer seed; unused, biome comes from the structure view */
            0      /* no chained neighbor updates */
        );

        this.delegate = delegate;
        this.data = data;
        this.blockEntities = blockEntities;
    }

    /** Build a structure-backed world, or {@code null} if there is no client world to borrow from. */
    @Nullable
    public static Level create(StructureRenderData data, Map<BlockPos, BlockEntity> blockEntities)
    {
        ClientLevel world = Minecraft.getInstance().level;

        if (world == null)
        {
            return null;
        }

        try
        {
            return new StructureWorld(world, data, blockEntities);
        }
        catch (Exception e)
        {
            /* Properties not castable / accessor missing on this build. The caller falls back to
             * mc.level, where block entities resolve their neighbours at unrelated coordinates —
             * double chests stop pairing and the like. Worth knowing about when that shows up. */
            if (!reportedFailure)
            {
                reportedFailure = true;

                LOGGER.warn("Couldn't build a structure-backed world, block entities will see the real world instead", e);
            }

            return null;
        }
    }

    /* --- Structure-backed reads --------------------------------------------------------------- */

    @Override
    public BlockState getBlockState(BlockPos pos)
    {
        return this.data.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos)
    {
        return this.data.getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos)
    {
        return this.blockEntities.get(pos);
    }

    /* --- Borrowed from the real client world -------------------------------------------------- */

    @Override
    public ChunkSource getChunkSource()
    {
        return this.delegate.getChunkSource();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks()
    {
        return this.delegate.getBlockTicks();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks()
    {
        return this.delegate.getFluidTicks();
    }

    @Override
    public TickRateManager tickRateManager()
    {
        return this.delegate.tickRateManager();
    }

    @Override
    public RecipeManager getRecipeManager()
    {
        return this.delegate.getRecipeManager();
    }

    @Override
    public Scoreboard getScoreboard()
    {
        return this.delegate.getScoreboard();
    }

    @Override
    public FeatureFlagSet enabledFeatures()
    {
        return this.delegate.enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shaded)
    {
        return StructureLighting.getBrightness(direction, shaded);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver)
    {
        /* Borrow the real client world's biome (its tint colors barely matter here — block
         * entities rarely tint themselves — but the interface demands an answer) */
        return this.delegate.getBlockTint(pos, colorResolver);
    }

    @Override
    public int getBrightness(LightLayer type, BlockPos pos)
    {
        return this.data.getLighting().getLightLevel(type, pos);
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ)
    {
        return this.delegate.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
    }

    @Override
    public List<? extends Player> players()
    {
        return List.of();
    }

    /* --- Inert: a render-only world never mutates state or resolves entities/maps -------------- */

    @Override
    protected LevelEntityGetter<Entity> getEntities()
    {
        return null;
    }

    @Nullable
    @Override
    public Entity getEntity(int id)
    {
        return null;
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id)
    {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData state)
    {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter)
    {
    }

    @Override
    public void levelEvent(@Nullable Player player, int eventId, BlockPos pos, int data)
    {
    }

    @Override
    public MapId getFreeMapId()
    {
        return new MapId(0);
    }

    @Override
    public PotionBrewing potionBrewing()
    {
        return this.delegate.potionBrewing();
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags)
    {
    }

    @Override
    public void destroyBlockProgress(int entityId, BlockPos pos, int progress)
    {
    }

    @Override
    public void playSeededSound(@Nullable Player except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed)
    {
    }

    @Override
    public void playSeededSound(@Nullable Player except, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed)
    {
    }

    @Override
    public String gatherChunkSourceStats()
    {
        return "StructureWorld";
    }

    @Override
    public void setDayTimeFraction(float dayTimeFraction)
    {
    }

    @Override
    public float getDayTimeFraction()
    {
        return 0F;
    }

    @Override
    public float getDayTimePerTick()
    {
        return 0F;
    }

    @Override
    public void setDayTimePerTick(float dayTimePerTick)
    {
    }
}
