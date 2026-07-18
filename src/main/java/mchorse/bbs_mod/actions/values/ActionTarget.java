package mchorse.bbs_mod.actions.values;

import mchorse.bbs_mod.actions.InteractionActionSemantics;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.values.ValuePoint;
import mchorse.bbs_mod.entity.ActorEntity;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueString;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Stable target identity with a positional fallback for recreated film actors.
 */
public class ActionTarget extends ValueGroup
{
    private static final double FALLBACK_RADIUS = 2D;
    private static final ThreadLocal<Map<String, ? extends Entity>> REPLAY_ACTORS = new ThreadLocal<>();

    public final ValueString uuid = new ValueString("uuid", "");
    public final ValueString entityType = new ValueString("entity_type", "");
    public final ValueString replayId = new ValueString("replay_id", "");
    public final ValuePoint position = new ValuePoint("position", new Point(0D, 0D, 0D));

    public ActionTarget(String id)
    {
        super(id);

        this.add(this.uuid);
        this.add(this.entityType);
        this.add(this.replayId);
        this.add(this.position);
    }

    public boolean isPresent()
    {
        return !this.uuid.get().isEmpty();
    }

    public void capture(Entity entity)
    {
        this.uuid.set(entity.getUUID().toString());
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

        this.entityType.set(type == null ? "" : type.toString());

        String replayId = entity instanceof ActorEntity actor ? actor.getReplayId() : "";

        this.replayId.set(replayId);
        this.position.get().set(entity.getX(), entity.getY(), entity.getZ());
    }

    public void shift(double x, double y, double z)
    {
        if (!this.isPresent())
        {
            return;
        }

        Point position = this.position.get();

        position.set(position.x + x, position.y + y, position.z + z);
    }

    public static void withReplayActors(Map<String, ? extends Entity> actors, Runnable runnable)
    {
        Map<String, ? extends Entity> previous = REPLAY_ACTORS.get();

        if (actors == null)
        {
            REPLAY_ACTORS.remove();
        }
        else
        {
            REPLAY_ACTORS.set(actors);
        }

        try
        {
            runnable.run();
        }
        finally
        {
            if (previous == null)
            {
                REPLAY_ACTORS.remove();
            }
            else
            {
                REPLAY_ACTORS.set(previous);
            }
        }
    }

    @Nullable
    public Entity resolve(ServerLevel level, Predicate<Entity> allowed)
    {
        if (!this.isPresent())
        {
            return null;
        }

        String replayId = this.replayId.get();
        Map<String, ? extends Entity> replayActors = REPLAY_ACTORS.get();

        if (!replayId.isEmpty() && replayActors != null)
        {
            return InteractionActionSemantics.selectScopedTarget(replayActors, replayId,
                (candidate) -> this.matchesScopedTarget(candidate, allowed));
        }

        Entity exact = this.resolveExact(level);

        if (exact != null && allowed.test(exact))
        {
            return exact;
        }

        if (replayId.isEmpty())
        {
            return null;
        }

        Point point = this.position.get();
        Vec3 center = new Vec3(point.x, point.y, point.z);
        AABB box = AABB.ofSize(center, FALLBACK_RADIUS * 2D, FALLBACK_RADIUS * 2D, FALLBACK_RADIUS * 2D);
        Predicate<Entity> matchingActor = (candidate) ->
        {
            ResourceLocation candidateType = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());

            return allowed.test(candidate)
                && candidate instanceof ActorEntity actor
                && replayId.equals(actor.getReplayId())
                && candidateType != null
                && this.entityType.get().equals(candidateType.toString());
        };
        List<Entity> candidates = level.getEntities((Entity) null, box, matchingActor);

        return InteractionActionSemantics.selectTarget(
            candidates,
            this.uuid.get(),
            matchingActor,
            (entity) -> entity.getUUID().toString(),
            (entity) -> entity.position().distanceToSqr(center)
        );
    }

    private boolean matchesScopedTarget(Entity candidate, Predicate<Entity> allowed)
    {
        ResourceLocation candidateType = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType());

        return allowed.test(candidate)
            && candidateType != null
            && this.entityType.get().equals(candidateType.toString());
    }

    @Nullable
    private Entity resolveExact(ServerLevel level)
    {
        try
        {
            return level.getEntity(UUID.fromString(this.uuid.get()));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
