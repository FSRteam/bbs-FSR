package mchorse.bbs_mod.ui.film.controller;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.PreviewHud;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import java.util.Locale;
import static mchorse.bbs_mod.ui.film.controller.UIFilmController.*;

/** View-local editor HUD and selection readouts. */
public class FilmControllerHud
{
    private final UIFilmController controller;

    public FilmControllerHud(UIFilmController controller)
    {
        this.controller = controller;
    }

    final PreviewHud previewHud = new PreviewHud();

    String getTargetLabel(FilmTarget target)
    {
        return switch (target.kind())
        {
            case ROOT -> TrackCategories.REPLAY.label.get();
            case ANCHOR, BONE ->
            {
                UIKeyframeEditor editor = this.controller.panel.replayEditor.keyframeEditor;
                String label = editor == null ? null : editor.getTargetLabel();

                yield label != null ? label : target.bone();
            }
            default -> null;
        };
    }


    void renderViewHUD(UIContext context, Area area)
    {
        PreviewHud hud = this.previewHud;
        hud.begin(area);
        FontRenderer font = context.batcher.getFont();
        UIFilmController scene = this.controller.panel.getController();
        int mode = scene.getMouseMode();

        if (scene.controlled != null && scene.recorder.recording)
        {
            String tally = scene.recorder.recordingCountdown <= 0
                ? UIKeys.FILM_CONTROLLER_TICKS.format(this.controller.getTick()).get()
                : String.valueOf(scene.recorder.recordingCountdown / 20F);
            Area block = hud.push(PreviewHud.Anchor.TOP_LEFT, Icons.SPHERE.w + PreviewHud.GAP + font.getWidth(tally), Icons.SPHERE.h);
            hud.include(PreviewHud.Anchor.TOP_LEFT, block, c ->
            {
                c.batcher.icon(Icons.SPHERE, Colors.RED | Colors.A100, block.x, block.y);
                c.batcher.text(tally, block.x + Icons.SPHERE.w + PreviewHud.GAP, block.my() - font.getHeight() / 2, BBSSettings.textColor(), true);
            });
        }

        if (BBSSettings.editorLoop.get())
        {
            hud.icon(context, PreviewHud.Anchor.TOP_LEFT, Icons.REFRESH, Colors.WHITE | Colors.A100);
        }

        if (this.controller.isViewFlying())
        {
            hud.text(context, PreviewHud.Anchor.TOP_LEFT,
                UIKeys.FILM_CONTROLLER_SPEED.format(this.controller.panel.dashboard.orbit.speed.getValue()).get(), BBSSettings.textColor());
        }

        Replay replay = this.controller.panel.replayEditor.getReplay();

        if (replay != null && BBSSettings.editorPreviewSelectionHud.get())
        {
            Form form = replay.form.get();
            if (form != null) hud.inset(PreviewHud.Anchor.TOP_RIGHT, 40 + PreviewHud.GAP);
            Area first = hud.text(context, PreviewHud.Anchor.TOP_RIGHT, replay.getName(), BBSSettings.textColor());
            Area last = first;
            FilmTarget target = this.controller.getEditTarget();
            String label = null;

            if (this.controller.canShowGizmo() && !target.isNone())
            {
                label = this.getTargetLabel(target);
            }

            if (label != null)
            {
                last = hud.text(context, PreviewHud.Anchor.TOP_RIGHT, label, BBSSettings.mutedTextColor());
            }

            if (form != null)
            {
                Area thumbnail = new Area();
                thumbnail.setSize(40, 40);
                thumbnail.setPos(area.ex() - PreviewHud.MARGIN - 40, Math.max(area.y, (first.y + last.ey()) / 2 - 20));
                hud.backdrop(thumbnail);
                hud.include(PreviewHud.Anchor.TOP_RIGHT, thumbnail, c ->
                {
                    c.batcher.clip(thumbnail.x, thumbnail.y, thumbnail.w, thumbnail.h, c);
                    try
                    {
                        FormUtilsClient.renderPreview(form, c, thumbnail.x, thumbnail.y, thumbnail.ex(), thumbnail.ey());
                    }
                    finally
                    {
                        c.batcher.unclip(c);
                    }
                });
            }
        }

        if (scene.controlled != null && mode > 0)
        {
            String label = switch (mode)
            {
                case 2 -> UIKeys.FILM_GROUPS_RIGHT_STICK.get();
                case 3 -> UIKeys.FILM_GROUPS_TRIGGERS.get();
                case 4 -> UIKeys.FILM_GROUPS_EXTRA_1.get();
                case 5 -> UIKeys.FILM_GROUPS_EXTRA_2.get();
                default -> UIKeys.FILM_GROUPS_LEFT_STICK.get();
            };
            hud.reserve(PreviewHud.Anchor.BOTTOM_LEFT, 30);
            hud.label(context, PreviewHud.Anchor.BOTTOM_LEFT, label, BBSSettings.textColor(), BBSSettings.primaryColor(Colors.A100));
            int size = (int) (Math.min(area.w, area.h) * 0.75F);
            int x = area.x + (area.w - size) / 2;
            int y = area.y + (area.h - size) / 2;
            int color = Colors.setA(BBSSettings.textColor(), 0.5F);
            context.batcher.outline(x, y, x + size, y + size, color);
            int bx = area.mx() + (int) (scene.mouse.getStick().y * size / 2);
            int by = area.my() + (int) (scene.mouse.getStick().x * size / 2);
            context.batcher.box(bx - 4, by - 4, bx + 4, by + 4, color);
        }

        if (BBSProfiler.enabled)
        {
            for (BBSProfiler.Timer timer : BBSProfiler.Timer.VALUES)
            {
                String label = String.format(Locale.ROOT, "%s: %.2f ms", timer.name().toLowerCase(Locale.ROOT), BBSProfiler.getMs(timer));

                hud.text(context, PreviewHud.Anchor.BOTTOM_LEFT, label, BBSSettings.textColor());
            }

            for (BBSProfiler.Section section : BBSProfiler.Section.VALUES)
            {
                hud.text(context, PreviewHud.Anchor.BOTTOM_LEFT, section.name().toLowerCase(Locale.ROOT)
                    + ": " + BBSProfiler.get(section), BBSSettings.mutedTextColor());
            }
        }

        hud.flush(context);

        /* The visual gizmo draws here, before the picking preview, so the bone /
         * sphere hover highlights composite on top of it. It moved out of the
         * world pass into the UI pipeline so its translucent parts blend
         * correctly (see Gizmo#renderInterface). */
        if (this.controller.canShowGizmo())
        {
            this.controller.gizmo.renderGizmo(context);
        }

        this.controller.renderPickingPreview(context, area);

        this.controller.orbitGizmo.render(context, area);

        this.controller.orbit.handleOrbiting(context);
    }
}
