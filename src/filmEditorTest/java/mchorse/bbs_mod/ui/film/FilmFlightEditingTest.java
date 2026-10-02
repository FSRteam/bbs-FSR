package mchorse.bbs_mod.ui.film;

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.actions.ActionState;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraPoseEditing;
import mchorse.bbs_mod.camera.CameraPoseEvaluator;
import mchorse.bbs_mod.camera.OrbitCamera;
import mchorse.bbs_mod.camera.clips.modifiers.TranslateClip;
import mchorse.bbs_mod.camera.clips.overwrite.IdleClip;
import mchorse.bbs_mod.camera.clips.overwrite.KeyframeClip;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.camera.data.Point;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.camera.CameraTrack;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.ui.ValueMotionPath;
import mchorse.bbs_mod.settings.values.ui.ValueOnionSkin;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.utils.UIOrbitCamera;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.film.clips.UIIdleClip;
import mchorse.bbs_mod.ui.film.clips.UIKeyframeClip;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.utils.UIFilmUndoHandler;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.undo.IUndo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Drives real Film flight, seek, pose-writing and clip editors without a window or GPU. */
public final class FilmFlightEditingTest
{
    private static final Position START = new Position(120, 74, -31, 35, 12, 5, 70);
    private static final Position MOVED = new Position(132, 81, -22, 62, -8, 9, 83);

    private FilmFlightEditingTest()
    {}

    public static void runAll()
    {
        Map<Field, Object> settings = new LinkedHashMap<>();
        Link idleType = Link.bbs("flight_test_idle");
        Link keyframeType = Link.bbs("flight_test_keyframe");
        boolean registerIdle = BBSMod.getFactoryCameraClips().getTypeSilent(new IdleClip()) == null;
        boolean registerKeyframe = BBSMod.getFactoryCameraClips().getTypeSilent(new KeyframeClip()) == null;

        if (registerIdle) BBSMod.getFactoryCameraClips().register(idleType, IdleClip.class);
        if (registerKeyframe) BBSMod.getFactoryCameraClips().register(keyframeType, KeyframeClip.class);

        try
        {
            setting(settings, "editorOnionSkin", new ValueOnionSkin("onion_skin"));
            setting(settings, "editorMotionPath", new ValueMotionPath("motion_path"));
            setting(settings, "editorCameraSmoothness", new ValueFloat("smoothness", 0F));
            /* Authoring reads these while writing a keyframe; leave them at their shipped defaults. */
            setting(settings, "editorSnapToTicks", new ValueBoolean("editorSnapToTicks", true));
            setting(settings, "editorSnapToFilmMarkers", new ValueBoolean("editorSnapToFilmMarkers", true));

            for (String name : List.of("editorRuleOfThirds", "editorCenterLines", "editorCrosshair", "editorRestartOnSeek", "editorLoop",
                "editorSnapToMarkers", "scrollingDisableSmoothnessInEditors"))
            {
                setting(settings, name, new ValueBoolean(name, false));
            }

            testFlightKeepsCameraMode();
            testCameraFlightMouseInput();
            testFlightStartAndToggle();
            testPlainSeekDoesNotWriteData();
            testSeekCommitsBeforeMovingCursor();
            testFlightUndoIsOneEdit();
            testSelectionCommitsBeforeReplacingEditor();
            testSelectionInAnotherCameraKeepsItsEditor();
            testSecondaryFlightAndCameraIsolation();
            testFreeFlightAndCancellation();
            testLayerSelectionAndCameraLocalModifiers();
            testKeyframeTickAndEnd();
            withTimelineInput(FilmFlightEditingTest::testDragAfterProgrammaticClipSelection);
        }
        finally
        {
            for (Map.Entry<Field, Object> entry : settings.entrySet())
            {
                try
                {
                    entry.getKey().set(null, entry.getValue());
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError(exception);
                }
            }

            if (registerIdle) BBSMod.getFactoryCameraClips().unregister(idleType);
            if (registerKeyframe) BBSMod.getFactoryCameraClips().unregister(keyframeType);
        }

        System.out.println("FilmFlightEditingTest: all checks passed");
    }

    private static void testFlightKeepsCameraMode()
    {
        for (boolean secondary : new boolean[] {false, true})
        {
            for (boolean locked : new boolean[] {false, true})
            {
                Fixture fixture = fixture(secondary);

                fixture.view.getNavigation().setLockCameraToView(locked);
                fixture.panel.setFlight(true);
                check(fixture.view.getNavigation().isInCameraView()
                    && fixture.panel.getActivePreview().getViewController().getPovMode() == UIFilmController.CAMERA_MODE_CAMERA,
                    "starting flight switched the selected camera to free view");
                check(fixture.view.getNavigation().isEditingCamera(), "camera flight did not begin a camera edit");
                check(fixture.view.getNavigation().isLockCameraToView() == locked,
                    "camera flight changed the ordinary view-drag lock preference");
                check(fixture.panel.cameraEditor.getClip() == fixture.idle, "starting flight replaced the selected clip");
                fixture.move(MOVED);
                fixture.updateFrame();
                same(new Position(fixture.view.resolveCamera(0F)), MOVED, "the camera preview did not follow live flight input");
                same(fixture.idle.position.get(), START, "live camera flight wrote outside its final undo transaction");
                fixture.panel.toggleFlight();
                same(fixture.idle.position.get(), MOVED, "camera flight depended on the ordinary view-drag lock");
                check(fixture.view.getNavigation().isInCameraView() && !fixture.view.getNavigation().isEditingCamera(),
                    "finishing camera flight changed the view mode or left a transient edit");
                check(fixture.view.getNavigation().isLockCameraToView() == locked,
                    "finishing flight changed the view-drag lock preference");
            }
        }
    }

    private static void testCameraFlightMouseInput()
    {
        Fixture fixture = fixture(false);
        UIContext context = new UIContext(null);

        fixture.panel.setFlight(true);
        context.setMouse(150, 90, 0);
        check(fixture.input.mouseClicked(context) == fixture.input, "camera flight did not accept a left-button drag");
        context.setMouse(174, 78, 0);
        fixture.input.render(context);
        fixture.updateFrame();
        Position expected = START.copy();

        expected.angle.yaw += (float) Math.toDegrees(24D * 0.01D);
        expected.angle.pitch += (float) Math.toDegrees(-12D * 0.01D);
        same(new Position(fixture.view.resolveCamera(0F)), expected, "camera preview ignored flight mouse rotation");
        check(fixture.input.mouseReleased(context) == fixture.input, "camera flight lost its mouse release");
        fixture.panel.setCursor(55);
        same(fixture.idle.position.get(), expected, "camera flight mouse rotation was not written at the original tick");
    }

    private static void testFlightStartAndToggle()
    {
        Fixture fixture = fixture(false);

        fixture.panel.setFlight(true);
        same((Position) get(fixture.panel, "position"), START, "starting primary flight replaced the camera with the stale runner origin");
        same(new Position(fixture.view.resolveCamera(0F)), START, "first flight preview jumped before movement");
        fixture.move(MOVED);
        fixture.panel.toggleFlight();
        same(fixture.idle.position.get(), MOVED, "explicit flight toggle did not store the final input pose");
        check(!fixture.panel.isFlying(), "flight input remained enabled after commit");
        fixture.panel.setCursor(70);
        fixture.panel.setCursor(12);
        same(fixture.sample(), MOVED, "returning to the static clip lost its flown pose");

        Film loaded = new Film();

        loaded.fromData(fixture.panel.getData().toData());
        CameraPoseEvaluator evaluator = new CameraPoseEvaluator();

        evaluator.beginFrame(1L, loaded, 12, 0F, false, null);
        same(evaluator.evaluate(fixture.track.cameraId.get(), null), MOVED, "flight pose did not survive Film save/load");
    }

    private static void testSeekCommitsBeforeMovingCursor()
    {
        Fixture fixture = fixture(false);

        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.setCursor(55);
        same(fixture.idle.position.get(), MOVED, "seeking cancelled the camera flight edit");
        check(fixture.panel.getCursor() == 55 && !fixture.panel.isFlying(), "seek did not complete after writing the old tick");
        same(fixture.next.position.get(), START, "seek wrote flight data into the next clip");
        fixture.panel.setCursor(20);
        same(fixture.sample(), MOVED, "the static pose was only stored as a one-tick overlay");
        check(fixture.track.clips.get().size() == 2, "a future higher layer caused an unnecessary keyframe overlay");
    }

    private static void testPlainSeekDoesNotWriteData()
    {
        Fixture fixture = fixture(false);
        BaseType before = fixture.panel.getData().toData();

        fixture.panel.setCursor(10);
        long epoch = fixture.view.getHistoryEpoch();

        fixture.panel.setCursor(10);
        check(fixture.view.getHistoryEpoch() == epoch, "clicking the current tick unnecessarily invalidated view history");

        for (int tick : new int[] {0, 9, 10, 20, 45, 70, 12})
        {
            fixture.panel.setCursor(tick);
            check(fixture.panel.getCursor() == tick, "ordinary seek did not update the shared playback tick");
            check(before.equals(fixture.panel.getData().toData()), "ordinary seek rewrote Film data without a flight edit");
        }
    }

    private static void testSelectionCommitsBeforeReplacingEditor()
    {
        Fixture fixture = fixture(false);

        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.cameraEditor.pickClip(fixture.next);
        same(fixture.idle.position.get(), MOVED, "clip selection lost the previous camera edit");
        same(fixture.next.position.get(), START, "clip selection wrote to the replacement editor");
        check(fixture.panel.cameraEditor.getClip() == fixture.next, "flight commit stole the new clip selection");
    }

    private static void testFlightUndoIsOneEdit()
    {
        Fixture fixture = fixture(false);
        FlightUndo undo = new FlightUndo(fixture.panel);
        Film film = fixture.panel.getData();

        set(fixture.panel, "undoHandler", undo);
        film.preCallback(undo::handlePreValues);
        fixture.panel.setFlight(true);
        fixture.panel.toggleFlight();
        check(undo.getUndoManager().getTotalUndos() == 0, "a flight without movement created an undo entry");
        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.setCursor(55);
        check(undo.getUndoManager().getTotalUndos() == 1, "one flight edit did not produce exactly one undo step");
        check(undo.getUndoManager().undo(film), "flight write could not be undone");
        same(fixture.idle.position.get(), START, "undo failed to restore the static camera pose");
        check(undo.getUndoManager().redo(film), "flight write could not be redone");
        same(fixture.idle.position.get(), MOVED, "redo failed to restore the flown camera pose");
    }

    private static void testSelectionInAnotherCameraKeepsItsEditor()
    {
        Fixture fixture = fixture(true);
        IdleClip next = idle(50, 30, 3, START);

        fixture.panel.getData().camera.addClip(next);
        fixture.panel.selectCameraTrack(Film.LEGACY_CAMERA_ID);
        fixture.panel.cameraEditor.pickClip(fixture.panel.getData().camera.get(0));
        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.cameraEditor.pickClip(next);
        same(fixture.idle.position.get(), MOVED, "flight failed to commit to the independently bound camera");
        check(Film.LEGACY_CAMERA_ID.equals(fixture.panel.getEditedCameraId())
            && fixture.panel.cameraEditor.clips.getClips() == fixture.panel.getData().camera
            && fixture.panel.cameraEditor.getClip() == next, "a flight commit replaced the timeline receiving the next selection");
    }

    private static void testSecondaryFlightAndCameraIsolation()
    {
        Fixture fixture = fixture(true);
        Position legacy = ((IdleClip) fixture.panel.getData().camera.get(0)).position.get().copy();

        fixture.panel.setFlight(true);
        same((Position) get(fixture.panel, "position"), START, "secondary flight used the primary pose");
        fixture.move(MOVED);
        fixture.panel.setCursor(55);
        same(fixture.idle.position.get(), MOVED, "secondary camera flight was not committed");
        same(((IdleClip) fixture.panel.getData().camera.get(0)).position.get(), legacy, "secondary edit leaked into legacy camera");
        check(Film.LEGACY_CAMERA_ID.equals(fixture.panel.getData().activeCameraId.get()), "editing changed the output camera");
    }

    private static void testFreeFlightAndCancellation()
    {
        Fixture fixture = fixture(false);

        fixture.panel.preview.exitCameraView();
        fixture.view.getNavigation().getFreePose().set(START);
        BaseType before = fixture.panel.getData().toData();

        fixture.panel.setFlight(true);
        same((Position) get(fixture.panel, "position"), START, "free flight replaced the starting view pose");
        check(!fixture.view.getNavigation().isInCameraView(), "free flight entered camera view");
        fixture.move(MOVED);
        fixture.panel.setCursor(55);
        check(before.equals(fixture.panel.getData().toData()), "free flight unexpectedly edited Film data");
        same(fixture.view.getNavigation().getFreePose(), MOVED, "free flight lost its latest pose when ending before a render");

        fixture = fixture(false);
        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.setFlight(false);
        same(fixture.idle.position.get(), START, "lifecycle cancellation committed a camera edit");

        fixture = fixture(false);
        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.getData().removeCamera(fixture.track.cameraId.get());
        fixture.panel.toggleFlight();
        same(((IdleClip) fixture.panel.getData().camera.get(0)).position.get(), new Position(4, 66, 9, 0, 0),
            "deleting the flight camera redirected its pending write into legacy");
    }

    private static void testLayerSelectionAndCameraLocalModifiers()
    {
        Fixture fixture = fixture(false);
        IdleClip disabled = idle(0, 40, 100, START);

        disabled.enabled.set(false);
        fixture.track.clips.addClip(disabled);
        check(CameraPoseEditing.findEditableClip(fixture.track.clips, disabled, 12) == fixture.idle,
            "disabled or future layers prevented writing the active static clip");

        TranslateClip translate = new TranslateClip();

        translate.duration.set(40);
        translate.layer.set(5);
        translate.translate.set(new Point(8, 2, -3));
        fixture.track.clips.addClip(translate);
        fixture.sample();
        fixture.panel.setFlight(true);
        fixture.move(MOVED);
        fixture.panel.toggleFlight();
        same(fixture.sample(), MOVED, "nonlegacy modifier offsets were applied twice on flight write");
        check(Math.abs(fixture.idle.position.get().point.x - (MOVED.point.x - 8D)) < 0.00001D,
            "editing used snapshots from the shared legacy runner");
    }

    private static void testKeyframeTickAndEnd()
    {
        for (int tick : new int[] {12, 30})
        {
            Fixture fixture = fixture(false);
            KeyframeClip keyframe = new KeyframeClip();
            Camera camera = new Camera();

            START.apply(camera);
            keyframe.fromCamera(camera);
            keyframe.tick.set(10);
            keyframe.duration.set(20);
            keyframe.layer.set(3);
            fixture.track.clips.addClip(keyframe);
            fixture.panel.cameraEditor.pickClip(keyframe);
            fixture.panel.setCursor(tick);
            fixture.sample();
            fixture.panel.setFlight(true);
            fixture.move(MOVED);
            fixture.panel.setCursor(55);
            check(Math.abs(keyframe.x.interpolate(tick - 10) - MOVED.point.x) < 0.00001D,
                "keyframe flight wrote at the new playhead instead of the captured relative tick");
            check(fixture.track.clips.get().size() == 3, "exclusive-end editing created a stray one-tick clip");
        }
    }

    private static Fixture fixture(boolean secondary)
    {
        FlightPanel panel = allocate(FlightPanel.class);
        Film film = new Film();
        CameraTrack track = film.addCamera("Flight camera", START);
        IdleClip idle = idle(0, 40, 1, START);
        IdleClip next = idle(50, 30, 10, START);
        UIDashboard dashboard = allocate(UIDashboard.class);
        UIOrbitCamera input = new UIOrbitCamera();
        CameraPoseEvaluator evaluator = new CameraPoseEvaluator();
        RunnerCameraController runner = new RunnerCameraController(panel, null);
        FlightController controller = new FlightController(panel);

        input.orbit = new OrbitCamera()
        {
            @Override
            public float getAngleSpeed()
            {
                return 0.01F;
            }
        };
        track.clips.addClip(idle);
        track.clips.addClip(next);
        film.camera.addClip(idle(0, 100, 1, new Position(4, 66, 9, 0, 0)));
        set(panel, "data", film);
        set(panel, "dashboard", dashboard);
        set(dashboard, "orbitUI", input);
        set(dashboard, "orbit", input.orbit);
        set(panel, "runner", runner);
        set(panel, "controller", controller);
        set(panel, "position", new Position());
        set(panel, "flightStartPose", new Position());
        set(panel, "camera", new Camera());
        set(panel, "cameraPoseEvaluator", evaluator);
        set(panel, "editedCameraId", track.cameraId.get());
        panel.recorder = allocate(FlightRecorder.class);
        panel.cameraEditor = allocate(FlightClipsPanel.class);
        panel.cameraEditor.filmPanel = panel;
        panel.cameraEditor.clips = allocate(UIClips.class);
        panel.cameraEditor.setClips(track.clips);
        ViewDescriptor view = new ViewDescriptor(panel, secondary ? "preview2" : "preview", !secondary);
        FlightPreview preview = allocate(FlightPreview.class);

        set(preview, "panel", panel);
        set(preview, "view", view);
        set(preview, "viewController", controller);
        panel.previews = List.of(preview);
        panel.preview = preview;
        set(panel, "activePreview", preview);
        view.setCameraId(track.cameraId.get());
        view.setFollowOutput(false);
        view.getNavigation().enterCameraView(new Camera(), START);
        controller.restorePov(UIFilmController.CAMERA_MODE_CAMERA);
        runner.ticks = 12;
        panel.cameraEditor.pickClip(idle);
        Fixture fixture = new Fixture(panel, track, idle, next, view, input);

        fixture.sample();

        return fixture;
    }

    private static void testDragAfterProgrammaticClipSelection()
    {
        for (int mode : new int[] {2, 1, 0})
        {
            for (boolean cancel : new boolean[] {false, true})
            {
                Fixture fixture = fixture(false);
                UIClips timeline = new UIClips(fixture.panel.cameraEditor, null);
                UIContext context = new UIContext(null);

                fixture.panel.cameraEditor.clips = timeline;
                timeline.xy(0, 0).wh(480, 240);
                timeline.resize();
                timeline.setClips(fixture.track.clips);
                timeline.scale.view(0, 100);
                check(timeline.getSelection().isEmpty() && fixture.panel.cameraEditor.getClip() == fixture.idle,
                    "the fixture must retain the property clip with no timeline selection");
                int mouseX = mode == 1 ? timeline.toGraphX(0) + 1
                    : mode == 2 ? timeline.toGraphX(40) - 1 : timeline.toGraphX(20);
                int mouseY = timeline.toLayerY(fixture.idle.layer.get()) + 10;

                context.setMouse(mouseX, mouseY, 0);
                check(timeline.subMouseClicked(context), "the clip did not accept its drag press");
                dragTimeline(timeline, mouseX + 48, mouseY);
                dragTimeline(timeline, mouseX + 48, mouseY);
                check(timeline.getClipsFromSelection().equals(List.of(fixture.idle)),
                    "dragging a property-selected clip did not establish its timeline selection");
                check(mode == 0 ? fixture.idle.tick.get() > 0
                    : mode == 1 ? fixture.idle.tick.get() > 0 && fixture.idle.duration.get() < 40
                    : fixture.idle.duration.get() > 40, "the selected clip's move/edge drag was not applied");

                if (cancel)
                {
                    timeline.subMouseCanceled(context);
                    check(fixture.idle.tick.get() == 0 && fixture.idle.duration.get() == 40,
                        "cancelling the clip drag did not restore its original bounds");
                    check(timeline.getSelection().isEmpty(), "cancelling the drag did not restore its previous selection");
                }
                else
                {
                    timeline.subMouseReleased(context);
                    check(mode == 0 ? fixture.idle.tick.get() > 0
                        : mode == 1 ? fixture.idle.tick.get() > 0 : fixture.idle.duration.get() > 40,
                        "releasing the drag discarded its edited bounds");
                }

                check(!(boolean) get(timeline, "grabbing"), "clip drag ownership remained after completion");
                check(fixture.next.tick.get() == 50 && fixture.next.duration.get() == 30,
                    "dragging one clip modified another clip");
            }
        }
    }

    private static void dragTimeline(UIClips timeline, int mouseX, int mouseY)
    {
        try
        {
            Method input = UIClips.class.getDeclaredMethod("handleInput", int.class, int.class);

            input.setAccessible(true);
            input.invoke(timeline, mouseX, mouseY);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void withTimelineInput(Runnable test)
    {
        check(!BBSUiRemoteHeldState.isActive(), "the headless timeline fixture requires an idle input owner");

        try
        {
            Method install = BBSUiRemoteHeldState.class.getDeclaredMethod("install", long.class, BBSUiRemoteInputState.class);

            install.setAccessible(true);
            install.invoke(null, 1L, new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
            test.run();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
        finally
        {
            BBSUiRemoteHeldState.clear();
        }
    }

    private static IdleClip idle(int tick, int duration, int layer, Position pose)
    {
        IdleClip clip = new IdleClip();

        clip.tick.set(tick);
        clip.duration.set(duration);
        clip.layer.set(layer);
        clip.position.set(pose);

        return clip;
    }

    private static void same(Position actual, Position expected, String message)
    {
        check(Math.abs(actual.point.x - expected.point.x) < 0.00001D
            && Math.abs(actual.point.y - expected.point.y) < 0.00001D
            && Math.abs(actual.point.z - expected.point.z) < 0.00001D
            && Math.abs(actual.angle.yaw - expected.angle.yaw) < 0.0001D
            && Math.abs(actual.angle.pitch - expected.angle.pitch) < 0.0001D
            && Math.abs(actual.angle.roll - expected.angle.roll) < 0.0001D
            && Math.abs(actual.angle.fov - expected.angle.fov) < 0.0001D, message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T allocate(Class<T> type)
    {
        try
        {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeType.getDeclaredField("theUnsafe");

            singleton.setAccessible(true);

            return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException
    {
        while (type != null)
        {
            try
            {
                Field field = type.getDeclaredField(name);

                field.setAccessible(true);

                return field;
            }
            catch (NoSuchFieldException exception)
            {
                type = type.getSuperclass();
            }
        }

        throw new NoSuchFieldException(name);
    }

    private static void set(Object target, String name, Object value)
    {
        try
        {
            field(target.getClass(), name).set(target, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static Object get(Object target, String name)
    {
        try
        {
            return field(target.getClass(), name).get(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void setting(Map<Field, Object> previous, String name, Object value)
    {
        try
        {
            Field field = field(BBSSettings.class, name);

            previous.put(field, field.get(null));
            field.set(null, value);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(FlightPanel panel, CameraTrack track, IdleClip idle, IdleClip next, ViewDescriptor view, UIOrbitCamera input)
    {
        private void updateFrame()
        {
            set(this.panel, "sceneFrameId", (long) get(this.panel, "sceneFrameId") + 1L);

            try
            {
                Method update = UIFilmPanel.class.getDeclaredMethod("updateLogic", UIContext.class);

                update.setAccessible(true);
                update.invoke(this.panel, new UIContext(null));
            }
            catch (ReflectiveOperationException exception)
            {
                throw new AssertionError(exception);
            }
        }

        private Position sample()
        {
            CameraPoseEvaluator evaluator = this.panel.getCameraPoseEvaluator();

            evaluator.invalidate();
            evaluator.beginFrame(1L, this.panel.getData(), this.panel.getCursor(), 0F, false, null);

            return new Position(this.view.resolveCamera(0F));
        }

        private void move(Position pose)
        {
            Camera camera = new Camera();

            pose.apply(camera);
            this.input.orbit.setup(camera);
        }
    }

    /* Only UI construction/rendering are stubbed. Flight and data mutation use production methods. */
    private static final class FlightPanel extends UIFilmPanel
    {
        private List<UIFilmPreview> previews;

        private FlightPanel()
        {
            super(null);
        }

        @Override
        public List<UIFilmPreview> getPreviews()
        {
            return this.previews;
        }

        @Override
        public Collection<ViewDescriptor> getViewDescriptors()
        {
            return this.previews.stream().map(UIFilmPreview::getViewDescriptor).toList();
        }

        @Override
        public void fillData()
        {}

        @Override
        public void saveViewSettings()
        {}

        @Override
        public void notifyServer(ActionState state)
        {}
    }

    private static final class FlightPreview extends UIFilmPreview
    {
        private FlightPreview()
        {
            super((UIFilmPanel) null);
        }

        @Override
        public void cancelViewInteraction()
        {
            this.getViewDescriptor().getPanel().cancelFlight(this);
        }

        @Override
        public void requestRefresh()
        {}

        @Override
        public Camera getDisplayedCamera()
        {
            return this.getViewDescriptor().resolveCamera(0F);
        }
    }

    private static final class FlightController extends UIFilmController
    {
        private FlightController(UIFilmPanel panel)
        {
            super(panel, false);
        }

        @Override
        public IntObjectMap<IEntity> getEntities()
        {
            return new IntObjectHashMap<>();
        }

        @Override
        public void handleCamera(Camera camera, float transition)
        {}
    }

    private static final class FlightRecorder extends UIFilmRecorder
    {
        private FlightRecorder()
        {
            super(null);
        }

        @Override
        public boolean isExporting()
        {
            return false;
        }
    }

    private static final class FlightClipsPanel extends UIClipsPanel
    {
        private FlightClipsPanel()
        {
            super(null, null);
        }

        @Override
        public void setClips(Clips clips)
        {
            FilmFlightEditingTest.set(this.clips, "clips", clips);
        }

        @Override
        public void embedView(UIElement element)
        {}

        @Override
        public void fillData()
        {}

        @Override
        public void pickClip(Clip clip)
        {
            if (this.getClip() != clip) this.filmPanel.prepareClipSelection(this);
            UIClip<?> editor = clip == null ? null : clip instanceof IdleClip ? allocate(FlightIdleClip.class) : allocate(UIKeyframeClip.class);

            if (editor != null)
            {
                FilmFlightEditingTest.set(editor, "clip", clip);
                FilmFlightEditingTest.set(editor, "editor", this);
            }

            FilmFlightEditingTest.set(this, "panel", editor);
        }
    }

    private static final class FlightIdleClip extends UIIdleClip
    {
        private FlightIdleClip()
        {
            super(null, null);
        }

        @Override
        public void fillData()
        {}
    }

    private static final class FlightUndo extends UIFilmUndoHandler
    {
        private FlightUndo(UIFilmPanel panel)
        {
            super(panel);
        }

        @Override
        protected void handleUndos(IUndo<ValueGroup> undo, boolean redo)
        {}

        @Override
        protected void handleCommittedValues(List<BaseValue> values)
        {}
    }
}
