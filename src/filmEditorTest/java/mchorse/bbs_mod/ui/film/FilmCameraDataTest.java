package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraCut;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.ui.film.utils.undo.ValueChangeUndo;
import mchorse.bbs_mod.utils.clips.Clips;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exercises real Film persistence, marker replacement and value undo. */
public final class FilmCameraDataTest
{
    private FilmCameraDataTest()
    {}

    public static void runAll()
    {
        Link fixtureType = Link.bbs("camera_test_idle");
        boolean registerFixture = BBSMod.getFactoryCameraClips().getTypeSilent(new IdleClip()) == null;

        if (registerFixture) BBSMod.getFactoryCameraClips().register(fixtureType, IdleClip.class);

        try
        {
            testLegacyRoundTrip();
            testStableIdsAndTrackRoundTrip();
            testCutsAndReplacement();
            testBrokenReferencesRetainData();
            testAtomicDeletionAndUndo();
        }
        finally
        {
            if (registerFixture) BBSMod.getFactoryCameraClips().unregister(fixtureType);
        }
    }

    private static void testLegacyRoundTrip()
    {
        Film original = new Film();
        original.camera.addClip(idle(7, 18, new Position(1, 2, 3, 25, 45, 12, 80)));
        MapType legacy = original.toData().asMap();
        legacy.remove("camera_tracks");
        legacy.remove("camera_cuts");
        legacy.remove("active_camera");
        Film loaded = new Film();
        Clips legacyClips = loaded.camera;
        CameraTrack previous = loaded.addCamera("Previous film", new Position());
        loaded.setActiveCamera(previous.cameraId.get());
        loaded.setCameraCut(10, previous.cameraId.get());
        loaded.fromData(legacy);

        check(loaded.camera == legacyClips, "legacy load replaced the public camera object");
        check(loaded.cameraTracks.getList().isEmpty() && loaded.cameraCuts.getList().isEmpty(), "old film inherited prior cameras");
        check(Film.LEGACY_CAMERA_ID.equals(loaded.activeCameraId.get()), "old film did not restore legacy output");
        check(loaded.getCameraClips(Film.LEGACY_CAMERA_ID) == loaded.camera, "legacy clips were copied");
        check(BaseType.equals(legacy.get("camera"), loaded.toData().asMap().get("camera")), "legacy clips changed during round trip");
        check(loaded.calculateDuration() == 25, "legacy duration changed without additional tracks");
    }

    private static void testStableIdsAndTrackRoundTrip()
    {
        Film film = new Film();
        Position base = new Position(1, 2, 3, 10, 20, 30, 65);
        base.point.x = 24_000_000.125D;
        CameraTrack first = film.addCamera("First", base);
        CameraTrack second = film.addCamera("Second", new Position());
        String firstId = first.cameraId.get();
        String secondId = second.cameraId.get();
        first.clips.addClip(idle(4, 40, base));
        second.clips.addClip(idle(15, 30, new Position()));
        film.setActiveCamera(firstId);
        film.setCameraCut(80, secondId);
        Collections.swap(film.cameraTracks.getAllTyped(), 0, 1);
        film.cameraTracks.sync();
        film.renameCamera(firstId, "Renamed");
        check(!firstId.equals(secondId) && firstId.equals(first.cameraId.get()), "reorder or rename changed the camera identity");
        check(film.calculateDuration() == 45, "duration was tied to selected camera or marker time");

        Film loaded = new Film();
        loaded.fromData(film.toData());
        CameraTrack restored = loaded.getCameraTrack(firstId);
        check(restored != null && "Renamed".equals(restored.name.get()), "stable id did not survive load");
        check(restored.copyPosition().equals(base), "base pose lost double precision, roll or FOV");
        check(BaseType.equals(restored.clips.toData(), first.clips.toData()), "camera clips did not round trip");
        check(firstId.equals(loaded.activeCameraId.get()) && secondId.equals(loaded.resolveCameraId(0)), "references changed with list order");
        Position copy = loaded.getCameraBasePosition(firstId);
        copy.point.x = 0D;
        check(restored.copyPosition().point.x == base.point.x, "base getter exposed writable Film state");
        loaded.removeCamera(firstId);
        check(!firstId.equals(loaded.addCamera("New", new Position()).cameraId.get()), "deleted id was reused");
        loaded.removeCamera(secondId);
        check(loaded.calculateDuration() == 0, "removed cameras continued extending playback");
        expectRejectedId(film, Film.LEGACY_CAMERA_ID);
        expectRejectedId(film, secondId);
    }

    private static void testCutsAndReplacement()
    {
        Film film = new Film();
        String first = film.addCamera("First", new Position()).cameraId.get();
        String second = film.addCamera("Second", new Position()).cameraId.get();
        film.setActiveCamera(second);
        check(second.equals(film.resolveCameraId(0)), "default output ignored without cuts");
        film.setCameraCut(30, second);
        film.setCameraCut(10, first);
        film.cameraCuts.setCut(20, "unavailable");
        check(first.equals(film.resolveCameraId(0)), "earliest future cut did not override default output");
        check(first.equals(film.resolveCameraId(10)), "cut boundary was not inclusive");
        check(first.equals(film.resolveCameraId(25)), "invalid cut displaced the last valid cut");
        check(second.equals(film.resolveCameraId(30)), "last valid cut lost at its boundary");
        film.cameraCuts.add(new CameraCut("duplicate", 10, second));
        film.cameraCuts.sync();
        check(second.equals(film.resolveCameraId(0)), "future duplicate did not resolve last-write-wins");
        check(second.equals(film.resolveCameraId(10)), "past duplicate used a different tie rule");
        BaseType before = film.cameraCuts.toData();
        film.setCameraCut(10, Film.LEGACY_CAMERA_ID);
        check(film.cameraCuts.getList().stream().filter(cut -> cut.tick.get() == 10).count() == 1, "same-tick edit retained duplicate choices");
        ValueChangeUndo replacement = new ValueChangeUndo(film.cameraCuts.getPath(), before, film.cameraCuts.toData());
        replacement.undo(film);
        check(second.equals(film.resolveCameraId(0)), "cut undo did not restore imported selection");
        replacement.redo(film);
        check(Film.LEGACY_CAMERA_ID.equals(film.resolveCameraId(10)), "cut redo did not restore new selection");
        check(film.removeCameraCut(10) && !film.removeCameraCut(10), "remove-cut result did not reflect mutation");
        check(second.equals(film.resolveCameraId(0)), "removing earliest cut did not select next valid cut");
    }

    private static void testBrokenReferencesRetainData()
    {
        Film film = new Film();
        CameraTrack first = film.addCamera("First damaged identity", new Position());
        CameraTrack second = film.addCamera("Second damaged identity", new Position());
        first.clips.addClip(idle(2, 7, new Position(3, 4, 5, 0, 0)));
        first.cameraId.set("duplicate");
        second.cameraId.set("duplicate");
        film.activeCameraId.set("missing-output");
        film.cameraCuts.setCut(7, "duplicate");
        film.cameraCuts.setCut(9, "missing-cut");
        MapType source = film.toData().asMap();
        source.getList("camera_tracks").add(new StringType("unreadable camera entry"));
        source.getList("camera_cuts").add(new StringType("unreadable cut entry"));
        Film loaded = new Film();
        loaded.fromData(source);
        check(!loaded.hasCamera("duplicate"), "ambiguous id silently selected first stored camera");
        check(Film.LEGACY_CAMERA_ID.equals(loaded.resolveCameraId(100)), "broken output did not fall back to legacy");
        check(loaded.cameraTracks.getList().size() == 3, "malformed camera data was discarded");
        check(BaseType.equals(source.get("camera_tracks"), loaded.toData().asMap().get("camera_tracks")), "fallback rewrote camera records");
        check(BaseType.equals(source.get("camera_cuts"), loaded.toData().asMap().get("camera_cuts")), "fallback rewrote cut records");
        check(loaded.getCameraDiagnostics().stream().anyMatch(item -> "active_camera".equals(item.path())), "missing output diagnostic omitted path");
        check(loaded.getCameraDiagnostics().stream().anyMatch(item -> "camera_cuts/1/camera".equals(item.path())), "missing cut diagnostic omitted path");
        MapType missingId = first.toData().asMap();
        missingId.remove("id");
        ListType entries = new ListType();
        entries.add(missingId);
        loaded.cameraTracks.fromData(entries);
        check(loaded.cameraTracks.getList().get(0).cameraId.get().isEmpty(), "list index became a camera identity");
    }

    private static void testAtomicDeletionAndUndo()
    {
        Film film = new Film();
        film.setId("camera-delete-test");
        CameraTrack track = film.addCamera("Removed", new Position(1, 2, 3, 4, 5));
        String id = track.cameraId.get();
        track.clips.addClip(idle(2, 19, track.copyPosition()));
        film.setActiveCamera(id);
        film.setCameraCut(4, id);
        film.setCameraCut(8, id);
        BaseType before = film.toData();
        List<BaseType> captured = new ArrayList<>();
        List<Integer> completed = new ArrayList<>();
        film.preCallback((value, flag) ->
        {
            check(value == film, "deletion emitted child undo record");
            check((flag & IValueListener.FLAG_UNMERGEABLE) != 0, "deletion could merge with another operation");
            captured.add(value.toData());
        });
        film.postCallback((value, flag) ->
        {
            check(!film.hasCamera(id) && Film.LEGACY_CAMERA_ID.equals(film.activeCameraId.get()), "callback saw partial deletion");
            check(film.cameraCuts.getList().stream().allMatch(cut -> Film.LEGACY_CAMERA_ID.equals(cut.cameraId.get())), "callback saw dangling cuts");
            completed.add(flag);
        });
        check(film.removeCamera(id), "valid deletion failed");
        check(captured.size() == 1 && completed.size() == 1, "deletion was not one notification pair");
        check(BaseType.equals(before, captured.get(0)), "undo snapshot captured after mutation");
        ValueChangeUndo undo = new ValueChangeUndo(film.getPath(), captured.get(0), film.toData());
        undo.undo(film);
        check(BaseType.equals(before, film.toData()), "one undo did not restore track, output and all cuts");
        undo.redo(film);
        check(!film.hasCamera(id) && film.cameraCuts.getList().size() == 2, "redo deleted markers or retained camera");
        check(!film.removeCamera(Film.LEGACY_CAMERA_ID) && !film.removeCamera("missing"), "protected/absent camera reported deleted");
    }

    private static void expectRejectedId(Film film, String id)
    {
        try
        {
            film.cameraTracks.addTrack(id, "Invalid identity", new Position());
            throw new AssertionError("accepted reserved/duplicate camera id: " + id);
        }
        catch (IllegalArgumentException expected)
        {}
    }

    private static IdleClip idle(int tick, int duration, Position position)
    {
        IdleClip clip = new IdleClip();
        clip.tick.set(tick);
        clip.duration.set(duration);
        clip.position.set(position);

        return clip;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
