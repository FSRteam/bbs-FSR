package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.forms.forms.sound.SoundReflections;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Optional;

/**
 * Finds nearby solid surfaces for a sound form to bounce off.
 *
 * <p>Probes along the six axes rather than tracing arbitrary rays: a room is
 * mostly walls, floor and ceiling, and six probes catch those for a fraction of
 * the cost. It is an approximation, and the guide reports what this actually
 * finds rather than an idealised room.</p>
 *
 * <p>Surfaces are pooled and rewritten in place — this runs once per sounding
 * form per tick.</p>
 */
public class SoundSurfaceProbe
{
    private static final Direction[] DIRECTIONS = {
        Direction.EAST, Direction.WEST,
        Direction.UP, Direction.DOWN,
        Direction.SOUTH, Direction.NORTH
    };

    /** Beyond this a bounce is too faint and too far to be worth probing for. */
    private static final int MAX_STEPS = 24;
    private static final int MAX_ENTITY_SURFACES = 8;

    private final SoundReflections.Surface[] surfaces = new SoundReflections.Surface[DIRECTIONS.length + MAX_ENTITY_SURFACES];
    private final double[] entityDistances = new double[MAX_ENTITY_SURFACES];
    private int count;
    private int blockSurfaceCount;
    private int entitySurfaceCount;
    private boolean blockOccluded;
    private boolean entityOccluded;

    public SoundSurfaceProbe()
    {
        for (int i = 0; i < this.surfaces.length; i++)
        {
            this.surfaces[i] = new SoundReflections.Surface();
        }
    }

    public SoundReflections.Surface[] getSurfaces()
    {
        return this.surfaces;
    }

    public int getCount()
    {
        return this.count;
    }

    public boolean isBlockOccluded()
    {
        return this.blockOccluded;
    }

    public boolean isEntityOccluded()
    {
        return this.entityOccluded;
    }

    /**
     * Look for a solid face along each axis and record the ones found.
     *
     * @param maxDistance the form's cutoff; nothing past it can be heard anyway
     * @return how many surfaces were found
     */
    public int probe(Level level, Entity owner, Entity listenerEntity,
        float x, float y, float z, float listenerX, float listenerY, float listenerZ,
        float maxDistance, boolean testBlocks, boolean collectBlocks,
        boolean testEntities, boolean collectEntities)
    {
        this.count = 0;
        this.blockSurfaceCount = 0;
        this.entitySurfaceCount = 0;
        this.blockOccluded = false;
        this.entityOccluded = false;

        if (level == null)
        {
            return 0;
        }

        Vec3 source = new Vec3(x, y, z);
        Vec3 listener = new Vec3(listenerX, listenerY, listenerZ);

        if (testBlocks)
        {
            this.blockOccluded = this.isBlockPathOccluded(level, source, listener);
        }

        if (collectBlocks)
        {
            this.collectBlockSurfaces(level, x, y, z, maxDistance);
        }

        if (testEntities || collectEntities)
        {
            this.collectEntityInteractions(level, owner, listenerEntity,
                source, listener, maxDistance, testEntities, collectEntities);
        }

        this.count = this.blockSurfaceCount + this.entitySurfaceCount;

        return this.count;
    }

    private void collectBlockSurfaces(Level level, float x, float y, float z, float maxDistance)
    {
        double reach = Math.min(Math.max(maxDistance, 0F), MAX_STEPS);
        Vec3 source = new Vec3(x, y, z);

        if (reach <= 0D)
        {
            return;
        }

        for (Direction direction : DIRECTIONS)
        {
            Vec3 target = source.add(
                direction.getStepX() * reach,
                direction.getStepY() * reach,
                direction.getStepZ() * reach);
            BlockHitResult hit = level.clip(new ClipContext(
                source, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));

            if (hit.getType() != HitResult.Type.BLOCK)
            {
                continue;
            }

            /* The collision ray supplies both the exact hit point and the
             * outward normal of the face entered from the source. This keeps
             * slabs, stairs and other partial collision shapes honest. */
            Vec3 point = hit.getLocation();
            Direction face = hit.getDirection();
            SoundReflections.Surface surface = this.surfaces[this.blockSurfaceCount++];

            surface.set((float) point.x, (float) point.y, (float) point.z,
                face.getStepX(), face.getStepY(), face.getStepZ(), SoundReflections.Surface.BLOCK);
        }
    }

    private boolean isBlockPathOccluded(Level level, Vec3 source, Vec3 listener)
    {
        if (source.distanceToSqr(listener) <= 1e-6D)
        {
            return false;
        }

        BlockHitResult hit = level.clip(new ClipContext(
            source, listener, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));

        return hit.getType() == HitResult.Type.BLOCK
            && hit.getLocation().distanceToSqr(source) + 1e-6D < listener.distanceToSqr(source);
    }

    private void collectEntityInteractions(Level level, Entity owner, Entity listenerEntity,
        Vec3 source, Vec3 listener, float maxDistance, boolean testEntities, boolean collectEntities)
    {
        double reach = Math.min(Math.max(maxDistance, 0F), MAX_STEPS);
        AABB reflectionArea = new AABB(
            source.x - reach, source.y - reach, source.z - reach,
            source.x + reach, source.y + reach, source.z + reach);
        AABB directArea = new AABB(
            Math.min(source.x, listener.x), Math.min(source.y, listener.y), Math.min(source.z, listener.z),
            Math.max(source.x, listener.x), Math.max(source.y, listener.y), Math.max(source.z, listener.z)).inflate(0.01D);
        AABB query = collectEntities ? reflectionArea.minmax(directArea) : directArea;
        List<Entity> entities = level.getEntities(owner, query,
            candidate -> candidate != owner && candidate != listenerEntity
                && candidate.isAlive() && !candidate.isSpectator());
        double directLength = source.distanceToSqr(listener);

        for (Entity entity : entities)
        {
            AABB box = entity.getBoundingBox();

            if (testEntities && !this.entityOccluded)
            {
                Optional<Vec3> hit = box.clip(source, listener);

                if (hit.isPresent())
                {
                    double distance = hit.get().distanceToSqr(source);

                    this.entityOccluded = distance > 1e-6D && distance + 1e-6D < directLength;
                }
            }

            if (collectEntities && box.intersects(reflectionArea))
            {
                this.addEntitySurface(level, box, source);
            }
        }
    }

    private void addEntitySurface(Level level, AABB box, Vec3 source)
    {
        double best = Double.POSITIVE_INFINITY;
        int axis = -1;

        if (source.x <= box.minX && box.minX - source.x < best) { best = box.minX - source.x; axis = 0; }
        if (source.x >= box.maxX && source.x - box.maxX < best) { best = source.x - box.maxX; axis = 1; }
        if (source.y <= box.minY && box.minY - source.y < best) { best = box.minY - source.y; axis = 2; }
        if (source.y >= box.maxY && source.y - box.maxY < best) { best = source.y - box.maxY; axis = 3; }
        if (source.z <= box.minZ && box.minZ - source.z < best) { best = box.minZ - source.z; axis = 4; }
        if (source.z >= box.maxZ && source.z - box.maxZ < best) { best = source.z - box.maxZ; axis = 5; }

        if (axis < 0)
        {
            return;
        }

        float px = (float) Math.max(box.minX, Math.min(box.maxX, source.x));
        float py = (float) Math.max(box.minY, Math.min(box.maxY, source.y));
        float pz = (float) Math.max(box.minZ, Math.min(box.maxZ, source.z));
        float nx = 0F;
        float ny = 0F;
        float nz = 0F;

        switch (axis)
        {
            case 0 -> { px = (float) box.minX; nx = -1F; }
            case 1 -> { px = (float) box.maxX; nx = 1F; }
            case 2 -> { py = (float) box.minY; ny = -1F; }
            case 3 -> { py = (float) box.maxY; ny = 1F; }
            case 4 -> { pz = (float) box.minZ; nz = -1F; }
            case 5 -> { pz = (float) box.maxZ; nz = 1F; }
        }

        Vec3 point = new Vec3(px, py, pz);

        /* A wall between the source and an entity makes that face unavailable
         * as a reflection surface. The listener entity itself is filtered
         * above so the listener's body cannot occlude its own ears. */
        if (this.isBlockPathOccluded(level, source, point))
        {
            return;
        }

        double distanceSq = best * best;
        int slot = this.entitySurfaceCount;

        if (slot >= MAX_ENTITY_SURFACES)
        {
            slot = 0;

            for (int i = 1; i < MAX_ENTITY_SURFACES; i++)
            {
                if (this.entityDistances[i] > this.entityDistances[slot])
                {
                    slot = i;
                }
            }

            if (distanceSq >= this.entityDistances[slot])
            {
                return;
            }
        }
        else
        {
            this.entitySurfaceCount++;
        }

        this.entityDistances[slot] = distanceSq;

        this.surfaces[this.blockSurfaceCount + slot].set(
            px, py, pz, nx, ny, nz, SoundReflections.Surface.ENTITY);
    }
}
