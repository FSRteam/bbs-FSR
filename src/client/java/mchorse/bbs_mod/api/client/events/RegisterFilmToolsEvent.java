package mchorse.bbs_mod.api.client.events;

import mchorse.bbs_mod.api.client.editor.FilmEditorTool;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;

import java.util.function.Function;

/** Registers per-film-editor tools. */
public final class RegisterFilmToolsEvent
{
    public void register(Function<UIFilmController, FilmEditorTool> factory)
    {
        UIFilmController.registerTool(factory);
    }
}
