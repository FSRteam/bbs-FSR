package mchorse.bbs_mod.film;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.EntityState;
import net.minecraft.world.entity.Pose;

import java.util.List;
import java.util.Map;

/** Recorded pose of a player currently owned by a world film. */
public record FilmPlayerPose(boolean sneaking, Pose pose)
{
    /**
     * Resolve ownership on every call. This is deliberately not cached: stopping, seeking,
     * replacing or pausing a film must take effect before the next vanilla pose query.
     */
    public static FilmPlayerPose get(int entityId)
    {
        Films films = BBSModClient.getFilms();

        if (films == null)
        {
            return null;
        }

        List<BaseFilmController> controllers = films.getControllers();

        for (int c = controllers.size() - 1; c >= 0; c--)
        {
            BaseFilmController controller = controllers.get(c);
            Map<String, Integer> actors = controller.getActors();

            if (actors == null || !actors.containsValue(entityId) || controller.film == null)
            {
                continue;
            }

            List<Replay> replays = controller.film.replays.getList();

            for (int i = replays.size() - 1; i >= 0; i--)
            {
                Replay replay = replays.get(i);
                Integer actorId = actors.get(replay.getId());

                if (actorId == null || actorId != entityId || i == controller.exception
                    || !replay.enabled.get() || !controller.getEntities().containsKey(replay.getId()))
                {
                    continue;
                }

                int tick = replay.getTick(controller.getTick());
                boolean sneaking = EntityState.isOn(replay.keyframes.sneaking.interpolate(tick));
                boolean swimming = EntityState.isOn(replay.keyframes.swimming.interpolate(tick));
                boolean gliding = EntityState.isOn(replay.keyframes.gliding.interpolate(tick));

                return new FilmPlayerPose(sneaking, EntityState.pose(gliding, swimming, sneaking));
            }
        }

        return null;
    }
}
