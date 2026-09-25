package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;

/** Logic regression tests for stable multi-camera data and cut resolution. */
public final class CameraTracksResolverTest
{
    private CameraTracksResolverTest()
    {}

    public static void runAll()
    {
        FilmCameraDataTest.runAll();
        CameraPoseEvaluationTest.runAll();

        Film film = new Film();
        CameraTrack first = film.addCamera("First", new Position(1F, 2F, 3F, 10F, 20F));
        CameraTrack second = film.addCamera("Second", new Position(4F, 5F, 6F, 30F, 40F));

        check(!first.cameraId.get().equals(second.cameraId.get()), "camera ids must be stable and unique");

        film.activeCameraId.set(second.cameraId.get());
        check(film.resolveCameraId(0).equals(second.cameraId.get()), "active camera was not used without cuts");

        film.setCameraCut(10, first.cameraId.get());
        check(film.resolveCameraId(9).equals(first.cameraId.get()), "the earliest future cut should select the pending output camera");
        check(film.resolveCameraId(10).equals(first.cameraId.get()), "a cut at the current tick was not selected");

        film.setCameraCut(10, Film.LEGACY_CAMERA_ID);
        check(film.resolveCameraId(10).equals(Film.LEGACY_CAMERA_ID), "same-tick cut replacement was not last-write-wins");

        film.cameraCuts.setCut(20, "missing-camera");
        check(film.resolveCameraId(20).equals(Film.LEGACY_CAMERA_ID), "invalid cut reference did not fall back to legacy");

        IdleClip clip = new IdleClip();
        clip.tick.set(0);
        clip.duration.set(12);
        film.camera.addClip(clip);

        IdleClip trackClip = new IdleClip();
        trackClip.tick.set(0);
        trackClip.duration.set(30);
        first.clips.addClip(trackClip);
        check(film.calculateDuration() == 30, "film duration did not include every camera track");

        film.removeCamera(first.cameraId.get());
        check(film.resolveCameraId(10).equals(Film.LEGACY_CAMERA_ID), "removing a camera did not repair cut references");
        check(film.getCameraTrack(first.cameraId.get()) == null, "removed camera track is still addressable");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
