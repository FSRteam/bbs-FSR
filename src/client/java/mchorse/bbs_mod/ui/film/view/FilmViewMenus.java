package mchorse.bbs_mod.ui.film.view;

import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraCut;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.framework.elements.overlay.UINumberOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/** Menus share Film edits while preserving the preview that opened them. */
public final class FilmViewMenus
{
    private FilmViewMenus()
    {}

    public static String cameraName(Film film, String cameraId)
    {
        CameraTrack track = film == null ? null : film.getCameraTrack(cameraId);

        return track == null ? UIKeys.FILM_VIEW_LEGACY_CAMERA.get() : track.name.get();
    }

    public static void cameras(UIFilmPanel panel, UIFilmPreview preview, ContextMenuManager menu)
    {
        Film film = panel.getData();
        ViewDescriptor view = preview.getViewDescriptor();

        if (film == null)
        {
            return;
        }

        menu.action(Icons.REFRESH, UIKeys.FILM_VIEW_FREE, !view.getNavigation().isInCameraView(), preview::exitCameraView);
        menu.action(Icons.FILM, UIKeys.FILM_VIEW_FOLLOW_OUTPUT, view.isFollowOutput(), () -> preview.bindCamera(view.getCameraId(), true));
        menu.action(Icons.CAMERA, UIKeys.FILM_VIEW_LEGACY_CAMERA, !view.isFollowOutput() && Film.LEGACY_CAMERA_ID.equals(view.getCameraId()),
            () -> preview.bindCamera(Film.LEGACY_CAMERA_ID, false));

        for (CameraTrack track : film.cameraTracks.getList())
        {
            String id = track.cameraId.get();

            if (film.hasCamera(id))
            {
                menu.action(Icons.CAMERA, IKey.raw(track.name.get()), !view.isFollowOutput() && id.equals(view.getCameraId()), () ->
                {
                    if (panel.getData() == film && film.hasCamera(id))
                    {
                        preview.bindCamera(id, false);
                    }
                });
            }
        }

        menu.action(Icons.ADD, UIKeys.FILM_VIEW_CREATE_FROM_VIEW, () -> createCamera(panel, preview, film));
        menu.action(Icons.EDITOR, UIKeys.FILM_VIEW_CAMERA_ACTIONS,
            () -> preview.getContext().replaceContextMenu(actions -> cameraActions(panel, preview, actions)));
    }

    public static void cameraActions(UIFilmPanel panel, UIFilmPreview preview, ContextMenuManager menu)
    {
        cameraActions(panel, preview, preview.getViewDescriptor().resolveCameraId(), menu);
    }

    public static void cameraActions(UIFilmPanel panel, UIFilmPreview preview, String id, ContextMenuManager menu)
    {
        Film film = panel.getData();

        if (film == null)
        {
            return;
        }

        menu.action(Icons.CAMERA, UIKeys.FILM_VIEW_VIEW_THROUGH.format(cameraName(film, id)), () ->
        {
            if (panel.getData() == film && film.hasCamera(id))
            {
                preview.bindCamera(id, false);
            }
        });

        menu.action(Icons.CURVES, UIKeys.FILM_VIEW_EDIT_TRACKS.format(cameraName(film, id)), () ->
        {
            if (panel.getData() == film)
            {
                panel.editCameraTrack(id);
            }
        });
        menu.action(Icons.MOVE_TO, UIKeys.FILM_VIEW_EDIT_TRANSFORM,
            () -> preview.getContext().replaceContextMenu(transform -> transform(panel, preview, film, id, transform)));
        Position viewPose = new Position(preview.getDisplayedCamera());

        menu.action(Icons.MOVE_TO, UIKeys.FILM_VIEW_ALIGN_CAMERA, () ->
        {
            if (panel.getData() == film && film.hasCamera(id))
            {
                panel.writeCameraPose(id, viewPose);
            }
        });
        menu.action(Icons.VIDEO_CAMERA, UIKeys.FILM_VIEW_DEFAULT_OUTPUT, id.equals(film.activeCameraId.get()),
            () -> edit(panel, film, () -> film.setActiveCamera(id)));
        menu.action(Icons.ADD, UIKeys.FILM_VIEW_CUT_HERE, () -> edit(panel, film, () -> film.setCameraCut(panel.getCursor(), id)));
        menu.action(Icons.LIST, UIKeys.FILM_VIEW_OUTPUT_CUTS, () -> preview.getContext().replaceContextMenu(cuts -> cuts(panel, film, cuts)));

        if (!Film.LEGACY_CAMERA_ID.equals(id))
        {
            menu.action(Icons.EDITOR, UIKeys.FILM_VIEW_RENAME_ACTION, () -> renameCamera(panel, film, id));
            menu.action(Icons.REMOVE, UIKeys.FILM_VIEW_DELETE_CAMERA, () ->
            {
                if (panel.getData() == film)
                {
                    panel.deleteCamera(id);
                }
            });
        }
    }

    private static void createCamera(UIFilmPanel panel, UIFilmPreview preview, Film film)
    {
        Position pose = new Position(preview.getDisplayedCamera());
        UIPromptOverlayPanel overlay = new UIPromptOverlayPanel(UIKeys.FILM_VIEW_CREATE_CAMERA, UIKeys.FILM_VIEW_CAMERA_NAME, name ->
        {
            if (panel.getData() == film && !name.isBlank())
            {
                panel.performCameraEdit(() ->
                {
                    CameraTrack track = film.addCamera(name.strip(), pose);

                    preview.bindCamera(track.cameraId.get(), false);
                    panel.editCameraTrack(track.cameraId.get());
                });
            }
        });

        overlay.text.setText(UIKeys.FILM_VIEW_DEFAULT_CAMERA_NAME.format(film.cameraTracks.getList().size() + 1).get());
        UIOverlay.addOverlay(preview.getContext(), overlay);
    }

    private static void renameCamera(UIFilmPanel panel, Film film, String id)
    {
        UIPromptOverlayPanel overlay = new UIPromptOverlayPanel(UIKeys.FILM_VIEW_RENAME_CAMERA, UIKeys.FILM_VIEW_CAMERA_NAME, name ->
        {
            if (!name.isBlank())
            {
                edit(panel, film, () -> film.renameCamera(id, name.strip()));
            }
        });

        overlay.text.setText(cameraName(film, id));
        UIOverlay.addOverlay(panel.getContext(), overlay);
    }

    private static void cuts(UIFilmPanel panel, Film film, ContextMenuManager menu)
    {
        int tick = panel.getCursor();

        for (CameraCut cut : film.cameraCuts.getList())
        {
            int cutTick = cut.tick.get();

            menu.action(Icons.CAMERA, UIKeys.FILM_VIEW_CUT_ENTRY.format(cutTick, cameraName(film, cut.cameraId.get())), cutTick == tick, () ->
            {
                if (panel.getData() == film)
                {
                    panel.setCursor(cutTick);
                }
            });
        }

        menu.action(Icons.REMOVE, UIKeys.FILM_VIEW_REMOVE_CUT, () -> edit(panel, film, () -> film.removeCameraCut(tick)));
        menu.action(Icons.MOVE_TO, UIKeys.FILM_VIEW_MOVE_CUT_ACTION, () ->
        {
            String cameraId = film.resolveCameraId(tick);
            UINumberOverlayPanel overlay = new UINumberOverlayPanel(UIKeys.FILM_VIEW_MOVE_CUT, UIKeys.FILM_VIEW_NEW_CUT_TICK, value ->
                edit(panel, film, () ->
                {
                    if (film.removeCameraCut(tick))
                    {
                        film.setCameraCut(value.intValue(), cameraId);
                    }
                }));

            overlay.value.limit(0).integer().setValue(tick);
            UIOverlay.addOverlay(panel.getContext(), overlay);
        });
    }

    private static void transform(UIFilmPanel panel, UIFilmPreview preview, Film film, String cameraId, ContextMenuManager menu)
    {
        int tick = panel.getCursor();
        Position pose = panel.getCameraPoseEvaluator().evaluate(cameraId, film.getCameraBasePosition(cameraId));
        IKey[] labels = {UIKeys.GENERAL_X, UIKeys.GENERAL_Y, UIKeys.GENERAL_Z, UIKeys.CAMERA_PANELS_YAW,
            UIKeys.CAMERA_PANELS_PITCH, UIKeys.CAMERA_PANELS_ROLL, UIKeys.CAMERA_PANELS_FOV};
        double[] values = {pose.point.x, pose.point.y, pose.point.z, pose.angle.yaw, pose.angle.pitch, pose.angle.roll, pose.angle.fov};

        for (int i = 0; i < labels.length; i++)
        {
            int component = i;

            menu.action(Icons.EDITOR, UIKeys.FILM_VIEW_TRANSFORM_VALUE.format(labels[i].get(), values[i]), () ->
            {
                UINumberOverlayPanel overlay = new UINumberOverlayPanel(UIKeys.FILM_VIEW_TRANSFORM_TITLE.format(labels[component].get()), UIKeys.KEYFRAMES_VALUE, value ->
                {
                    if (panel.getData() != film || !film.hasCamera(cameraId) || panel.getCursor() != tick)
                    {
                        return;
                    }

                    switch (component)
                    {
                        case 0 -> pose.point.x = value;
                        case 1 -> pose.point.y = value;
                        case 2 -> pose.point.z = value;
                        case 3 -> pose.angle.yaw = value.floatValue();
                        case 4 -> pose.angle.pitch = value.floatValue();
                        case 5 -> pose.angle.roll = value.floatValue();
                        case 6 -> pose.angle.fov = value.floatValue();
                        default -> throw new IllegalStateException("Unknown camera component");
                    }

                    panel.writeCameraPose(cameraId, pose);
                });

                if (component == 6)
                {
                    overlay.value.limit(1, 179);
                }

                overlay.value.setValue(values[component]);
                UIOverlay.addOverlay(preview.getContext(), overlay);
            });
        }
    }

    private static void edit(UIFilmPanel panel, Film film, Runnable action)
    {
        if (panel.getData() == film)
        {
            panel.performCameraEdit(action);
        }
    }
}
