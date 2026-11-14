package mchorse.bbs_mod.film;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import org.joml.Matrix4f;

import java.util.Map;

/** Keeps an anchored form in the same world frame when its target changes. */
public final class AnchorRebase
{
    private AnchorRebase()
    {}

    /**
     * Rebase {@code to}'s local transform so it produces the world transform previously produced
     * by {@code from}. Relative replays intentionally have no absolute world frame and therefore
     * cannot be rebased.
     */
    public static boolean keepWorldTransform(Map<String, IEntity> entities, IEntity entity, Replay replay,
        float transition, Anchor from, Anchor to)
    {
        if (entities == null || from == null || to == null || (replay != null && replay.relative.get()))
        {
            return false;
        }

        Anchor target = to.copy();
        target.transform.identity();
        Matrix4f world = FilmMatrices.getAnchorMatrix(entities, entity, replay, 0D, 0D, 0D,
            transition, from);
        Matrix4f frame = FilmMatrices.getAnchorMatrix(entities, entity, replay, 0D, 0D, 0D,
            transition, target);

        if (world == null || frame == null || Math.abs(frame.determinant()) < 1E-9F)
        {
            return false;
        }

        to.transform.fromMatrix(frame.invert().mul(world));

        return true;
    }
}
