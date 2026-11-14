package mchorse.bbs_mod.api.client.editor;

import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;

/** Extension tool attached to each film editor controller. */
public abstract class FilmEditorTool extends UIElement
{
    public FilmTarget target(FilmTarget original) { return null; }
    public Boolean startGizmo(UIContext context, int stencilIndex) { return null; }
    public boolean click(UIContext context) { return false; }
    public boolean release(UIContext context) { return false; }
    public boolean key(UIContext context) { return false; }
    public void updateTool(UIContext context) {}
    public void renderTool(IBbsWorldRenderContext context) {}
    public void stopTool() {}
}
