package mchorse.bbs_mod.film.camera;

import mchorse.bbs_mod.film.Film;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure camera cut resolver shared by UI, playback and output. */
public final class FilmCameraResolver
{
    public static final String LEGACY_CAMERA_ID = "legacy";

    private FilmCameraResolver()
    {}

    public static String resolve(Film film, int tick)
    {
        if (film == null)
        {
            return LEGACY_CAMERA_ID;
        }

        CameraCut firstFuture = null;
        CameraCut lastPast = null;

        for (CameraCut cut : film.cameraCuts.getList())
        {
            String cameraId = cut.cameraId.get();

            if (!film.hasCamera(cameraId))
            {
                continue;
            }

            int markerTick = cut.tick.get();

            if (markerTick <= tick)
            {
                if (lastPast == null || markerTick >= lastPast.tick.get())
                {
                    lastPast = cut;
                }
            }
            else if (firstFuture == null || markerTick <= firstFuture.tick.get())
            {
                firstFuture = cut;
            }
        }

        String selected = lastPast != null ? lastPast.cameraId.get() : firstFuture != null ? firstFuture.cameraId.get() : film.activeCameraId.get();

        return film.hasCamera(selected) ? selected : LEGACY_CAMERA_ID;
    }

    /** Report broken references without modifying or discarding their source data. */
    public static List<Diagnostic> diagnose(Film film)
    {
        if (film == null)
        {
            return List.of();
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < film.cameraTracks.getList().size(); i++)
        {
            String id = film.cameraTracks.getList().get(i).cameraId.get();
            String path = "camera_tracks/" + i + "/id";

            if (id == null || id.isBlank() || LEGACY_CAMERA_ID.equals(id))
            {
                diagnostics.add(new Diagnostic(path, id, "missing or reserved camera id"));
            }
            else if (!ids.add(id))
            {
                diagnostics.add(new Diagnostic(path, id, "duplicate camera id"));
            }
        }

        if (!film.hasCamera(film.activeCameraId.get()))
        {
            diagnostics.add(new Diagnostic("active_camera", film.activeCameraId.get(), "unavailable output camera"));
        }

        for (int i = 0; i < film.cameraCuts.getList().size(); i++)
        {
            CameraCut cut = film.cameraCuts.getList().get(i);

            if (!film.hasCamera(cut.cameraId.get()))
            {
                diagnostics.add(new Diagnostic("camera_cuts/" + i + "/camera", cut.cameraId.get(), "unavailable cut camera"));
            }
        }

        return List.copyOf(diagnostics);
    }

    public record Diagnostic(String path, String cameraId, String reason)
    {}
}
