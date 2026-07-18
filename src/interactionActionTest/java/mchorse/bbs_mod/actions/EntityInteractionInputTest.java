package mchorse.bbs_mod.actions;

import mchorse.bbs_mod.actions.types.EntityInteractionActionClip;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;

import java.util.List;
import java.util.Map;

/** Executable regression for serialized and live interact-at input bounds. */
public final class EntityInteractionInputTest
{
    private EntityInteractionInputTest()
    {}

    public static void main(String[] args)
    {
        bootstrapStandaloneMinecraftRuntime();
        runAll();
    }

    private static void bootstrapStandaloneMinecraftRuntime()
    {
        SharedConstants.tryDetectVersion();

        if (LoadingModList.get() == null)
        {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        Bootstrap.bootStrap();
    }

    public static void runAll()
    {
        rejectsSpectatorOnlyConsumption();
        rejectsInvalidSerializedInputs();
        requiresLiveHitToRemainInsideTargetBounds();
    }

    private static void rejectsSpectatorOnlyConsumption()
    {
        check(InteractionActionSemantics.shouldRecordPlayerInteraction(
                net.minecraft.world.InteractionResult.SUCCESS,
                false
            ),
            "an ordinary consumed player interaction was not recorded");
        check(!InteractionActionSemantics.shouldRecordPlayerInteraction(
                net.minecraft.world.InteractionResult.SUCCESS,
                true
            ),
            "a spectator-only menu interaction was recorded for non-spectator replay");
    }

    private static void rejectsInvalidSerializedInputs()
    {
        EntityInteractionActionClip action = validAction();

        check(FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "a valid interact-at action was rejected");

        action.location.get().x = Double.NaN;
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "a NaN interact-at offset passed film preflight");

        action.location.get().x = Double.POSITIVE_INFINITY;
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "an infinite interact-at offset passed film preflight");

        action.location.get().x = FilmPlaybackPolicy.MAX_RELATIVE_ACTION_OFFSET + 1D;
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "an unbounded interact-at offset passed film preflight");

        action = validAction();
        action.target.uuid.set("1-2-3-4-5");
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "a non-canonical target UUID passed film preflight");

        action = validAction();
        action.target.entityType.set("not a resource location");
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "an invalid target entity type passed film preflight");

        action = validAction();
        action.target.position.get().x = FilmPlaybackPolicy.MAX_HORIZONTAL_POSITION;
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(action),
            "an out-of-world target position passed film preflight");

        EntityInteractionActionClip missingTarget = new EntityInteractionActionClip();
        check(!FilmPlaybackPolicy.isEntityInteractionInputAllowed(missingTarget),
            "an entity interaction without a stable target passed film preflight");
    }

    private static void requiresLiveHitToRemainInsideTargetBounds()
    {
        AABB localBounds = new AABB(-0.5D, 0D, -0.5D, 0.5D, 2D, 0.5D);

        check(InteractionActionSemantics.isValidEntityHit(new Vec3(0D, 1D, 0D), localBounds),
            "a normal target-local hit was rejected");
        check(!InteractionActionSemantics.isValidEntityHit(new Vec3(2D, 1D, 0D), localBounds),
            "a hit outside the target bounds was accepted");
        check(!InteractionActionSemantics.isValidEntityHit(new Vec3(Double.NaN, 1D, 0D), localBounds),
            "a non-finite live hit reached the entity interaction hook");
    }

    private static EntityInteractionActionClip validAction()
    {
        EntityInteractionActionClip action = new EntityInteractionActionClip();

        action.target.uuid.set("00000000-0000-0000-0000-000000000001");
        action.target.entityType.set("minecraft:armor_stand");
        action.target.replayId.set("1");
        action.target.position.get().set(0D, 64D, 0D);
        action.interactAt.set(true);
        action.location.get().set(0D, 1D, 0D);

        return action;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
