package mchorse.bbs_mod.film;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.client.compat.BBSWorldRenderContext;

public class FirstPersonFilmController extends WorldFilmController
{
    public FirstPersonFilmController(Film film)
    {
        super(film);
    }

    @Override
    protected void renderEntity(BBSWorldRenderContext context, Replay replay, IEntity entity)
    {
        if (replay.fp.get())
        {
            return;
        }

        super.renderEntity(context, replay, entity);
    }
}