package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.OrbitCamera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.client.render.multiview.ViewTargetSize;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.line.Line;
import mchorse.bbs_mod.graphics.line.LinePoint;
import mchorse.bbs_mod.settings.values.ui.ValueEditorLayout;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.ui.ValueMotionPath;
import mchorse.bbs_mod.settings.values.ui.ValueOnionSkin;
import mchorse.bbs_mod.ui.film.controller.OrbitFilmCameraController;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.dashboard.utils.UIOrbitCamera;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.film.view.FilmCameraMarkerGeometry;
import mchorse.bbs_mod.ui.film.view.ViewDescriptor;
import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.view.ViewNavigationGesture;
import mchorse.bbs_mod.ui.film.view.ViewNavigationState;
import mchorse.bbs_mod.ui.film.view.ViewPerformanceSettings;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.List;

/** Behavioral coverage for independent view navigation and camera-edit ownership. */
public final class FilmViewNavigationTest
{
    public static void runAll()
    {
        testRepeatedCameraEntryRestoresFreePose();
        testFrameGeometryAndIsolation();
        testNavigationSettingsRoundTrip();
        testOrbitSettingsRoundTrip();
        withInputDefaults(FilmViewNavigationTest::testOrbitAndFlightKeepFsDirectionsAndPivotCentered);
        withInputDefaults(FilmViewNavigationTest::testFlightGestureMatchesFs);
        ViewportPickIntentTest.runAll();
        testCameraGestureReleaseAndCancellation();
        testCameraGestureTargetFence();
        testFrameAndFreeGestureCancellation();
        testEditorSettingsDefensiveCopies();
        withViewDefaults(FilmViewNavigationTest::testPerformanceSettingsRoundTripAndDefaults);
        testPreferredPreviewResolutions();
        FilmViewToolbarLayoutTest.runAll();
        testCameraMarkerProjectionAndRoll();
        testCameraFrustumClipping();
        testCameraFrustumPrecisionAndLineWidth();
    }

    private static void testRepeatedCameraEntryRestoresFreePose()
    {
        Camera free = camera(30_000_000.125D, 70.25D, -30_000_000.5D, 32F, -15F, 12F, 73F);
        Camera bound = camera(1D, 2D, 3D, 90F, 45F, -30F, 40F);
        ViewNavigationState navigation = new ViewNavigationState();
        Position original = new Position(free);

        navigation.initialize(free);
        navigation.enterCameraView(free, new Position(bound));
        navigation.enterCameraView(bound, new Position(bound));
        navigation.setLockCameraToView(true);
        navigation.beginCameraEdit(bound).point.x = 500D;
        navigation.exitCameraView(bound);

        check(!navigation.isInCameraView() && !navigation.isEditingCamera(), "exit must retire camera editing");
        check(navigation.getFreePose().equals(original), "repeated entry must retain the first free pose");
        near(bound.position.x, free.position.x, 0D, "free-view double X was lost");
        near(bound.position.z, free.position.z, 0D, "free-view double Z was lost");
        near(bound.rotation.z, free.rotation.z, 0.000001D, "free-view roll was lost");
        near(bound.fov, free.fov, 0.000001D, "free-view FOV was lost");
    }

    private static void testFrameGeometryAndIsolation()
    {
        ViewFrameGeometry fitted = ViewFrameGeometry.fit(10, 20, 320, 320, 1920, 1080, 1F, 0F, 0F);

        check(fitted.equals(new ViewFrameGeometry(10, 90, 320, 180)), "output aspect must fit a square dock without stretching");
        check(!fitted.contains(20, 30, 10, 20, 320, 320), "letterbox bars must not produce picks");
        check(fitted.contains(20, 100, 10, 20, 320, 320), "camera frame must remain pickable");

        ViewFrameGeometry zoomed = ViewFrameGeometry.fit(10, 20, 320, 320, 1920, 1080, 2F, 0.25F, -0.25F);

        check(zoomed.width() == 640 && zoomed.height() == 360, "frame zoom must scale both axes equally");
        check(!zoomed.contains(9, 50, 10, 20, 320, 320), "zoomed frame picking must stay clipped to its dock");
        check(zoomed.contains(20, 50, 10, 20, 320, 320), "visible zoomed content must remain pickable");

        Camera camera = camera(1D, 2D, 3D, 15F, 20F, 25F, 50F);
        Position original = new Position(camera);
        ViewNavigationState first = new ViewNavigationState();
        ViewNavigationState second = new ViewNavigationState();

        first.enterCameraView(camera, original);
        second.enterCameraView(camera, original);
        first.zoomFrame(2F);
        first.panFrame(0.3F, -0.2F);

        check(new Position(camera).equals(original), "display framing must not edit camera pose or FOV");
        check(second.getFrameZoom() == 1F && second.getFramePanX() == 0F && second.getFramePanY() == 0F,
            "two views of the same camera must have independent framing");
    }

    private static void testNavigationSettingsRoundTrip()
    {
        Camera free = camera(123D, 45D, -67D, 18F, -24F, 9F, 83F);
        ViewNavigationState state = new ViewNavigationState();

        state.setFreeMode(2);
        state.enterCameraView(free, null);
        state.setLockCameraToView(true);
        state.setFrameZoom(2.5F);
        state.panFrame(0.2F, -0.3F);
        state.beginCameraEdit(free).point.x = 999D;

        ViewNavigationState restored = new ViewNavigationState();

        restored.fromData(state.toData());
        check(restored.isInCameraView() && restored.isLockCameraToView(), "camera view options must round-trip");
        check(!restored.isEditingCamera(), "an in-flight edit must not resume after settings reload");
        check(restored.getFreeMode() == 2 && restored.getFrameZoom() == 2.5F, "mode and zoom were not restored");
        near(restored.getFramePanY(), -0.3F, 0D, "frame pan was not restored");

        Camera output = new Camera();

        restored.exitCameraView(output);
        near(output.position.x, 123D, 0D, "settings reload must preserve the saved free pose");

        MapType invalid = new MapType();

        invalid.putFloat("zoom", Float.NaN);
        invalid.putFloat("pan_x", Float.POSITIVE_INFINITY);
        invalid.putFloat("pan_y", -100F);
        restored.fromData(invalid);
        check(restored.getFrameZoom() == 1F && restored.getFramePanX() == 0F && restored.getFramePanY() == -1F,
            "malformed framing values must remain finite and bounded");
        check(restored.getFreePose().equals(new Position()), "missing view settings must not retain another Film's pose");

        MapType corruptPose = state.toData();

        corruptPose.getMap("free_pose").getMap("point").putDouble("x", Double.NaN);
        restored.fromData(corruptPose);
        restored.initialize(free);
        check(restored.getFreePose().equals(new Position(free)), "a malformed persisted pose must be reseeded from the current camera");
    }

    private static void testOrbitSettingsRoundTrip()
    {
        MapType settings = new MapType();

        settings.putDouble("x", 30_000_000.125D);
        settings.putDouble("y", 72.25D);
        settings.putDouble("z", -30_000_000.5D);
        settings.putFloat("pitch", -0.25F);
        settings.putFloat("yaw", 1.5F);
        settings.putFloat("distance", 12.5F);
        settings.putBool("positioned", true);
        settings.putBool("attached", false);
        settings.putBool("ortho", true);

        OrbitFilmCameraController first = new OrbitFilmCameraController(null);
        OrbitFilmCameraController second = new OrbitFilmCameraController(null);

        first.fromData(settings);
        second.fromData(first.toData());
        near(second.getOrbitCenter(0F).x, 30_000_000.125D, 0D, "orbit persistence lost distant-world precision");
        near(second.getOrbitCenter(0F).z, -30_000_000.5D, 0D, "orbit persistence lost distant-world Z");
        near(second.toData().getFloat("distance"), 12.5F, 0D, "orbit distance did not round-trip");
        check(second.isOrtho() && !second.isAttached(), "orbit projection and attachment were not restored");

        second.getOrbitCenter(0F).set(0D, 0D, 0D);
        second.toggleOrtho();
        check(first.isOrtho(), "one view's orbit options changed another view");
        near(second.getOrbitCenter(0F).x, 30_000_000.125D, 0D, "orbit center leaked a mutable internal pivot");

        second.fromData(new MapType());
        check(!second.isOrtho() && second.isAttached() && !second.toData().getBool("positioned"),
            "a Film without orbit settings must start with fresh defaults");
        near(second.getOrbitCenter(0F).x, 0D, 0D, "reset orbit retained the previous Film's pivot");

        settings.putDouble("x", Double.POSITIVE_INFINITY);
        second.fromData(settings);
        check(Double.isFinite(second.getOrbitCenter(0F).x) && !second.toData().getBool("positioned"),
            "malformed orbit coordinates must never enter camera rendering");
    }

    private static void testCameraGestureReleaseAndCancellation()
    {
        Camera camera = camera(1D, 2D, 3D, 10F, 90F, 30F, 55F);
        Position original = new Position(camera);
        ViewNavigationState navigation = new ViewNavigationState();
        ViewNavigationGesture gesture = new ViewNavigationGesture();
        ViewNavigationGesture.Target target = new ViewNavigationGesture.Target(new Object(), "camera-a", 10);

        navigation.enterCameraView(camera, original);
        check(gesture.begin(1, 20, 30, ViewNavigationGesture.Mode.CAMERA, navigation, camera, target), "camera gesture did not start");
        gesture.drag(20, 30, 320, 180, false);
        check(gesture.release(1, target).cameraPose() == null, "zero-motion release must not create a camera edit");
        check(new Position(camera).equals(original), "a click must not clamp or change the rendered camera");

        gesture.begin(1, 20, 30, ViewNavigationGesture.Mode.CAMERA, navigation, camera, target);
        gesture.drag(30, 35, 320, 180, false);
        check(!gesture.release(2, target).consumed() && gesture.isActive(), "another mouse button must not finish a camera gesture");
        ViewNavigationGesture.Completion completion = gesture.release(1, target);

        check(completion.cameraPose() != null && !gesture.isActive() && !navigation.isEditingCamera(),
            "matching release must return one completed camera pose");
        near(completion.cameraPose().angle.yaw, original.angle.yaw + 2F, 0.00001D, "camera rotation did not follow the drag");
        check(new Position(camera).equals(original), "gesture must return a pose without mutating its source camera");
        completion.cameraPose().point.x = 999D;
        check(navigation.getEditingPose().point.x != 999D, "completed pose must be a defensive snapshot");

        gesture.begin(1, 20, 30, ViewNavigationGesture.Mode.CAMERA, navigation, camera, target);
        gesture.drag(100, 100, 320, 180, false);
        gesture.cancel();
        check(!navigation.isEditingCamera() && gesture.release(1, target).cameraPose() == null,
            "cancellation must never turn into a committing release");
    }

    private static void testOrbitAndFlightKeepFsDirectionsAndPivotCentered()
    {
        float[] yaws = {0F, 1.2F, 3.14F, -2.7F};
        float[] pitches = {-0.6F, 0F, 0.6F};
        int[][] drags = {{24, 0}, {-24, 0}, {0, 24}, {0, -24}};
        ViewFrameGeometry frame = new ViewFrameGeometry(13, 17, 320, 180);

        for (float yaw : yaws)
        {
            for (float pitch : pitches)
            {
                for (int[] drag : drags)
                {
                    MapType settings = new MapType();

                    settings.putDouble("x", 30_000_000.125D);
                    settings.putDouble("y", 72.25D);
                    settings.putDouble("z", -30_000_000.5D);
                    settings.putFloat("pitch", pitch);
                    settings.putFloat("yaw", yaw);
                    settings.putFloat("distance", 12.5F);
                    settings.putBool("positioned", true);
                    settings.putBool("attached", false);

                    DirectionalController controller = new DirectionalController();
                    DirectionalOrbit orbit = new DirectionalOrbit(controller);
                    UIContext context = new UIContext(null);

                    orbit.fromData(settings);
                    Camera before = orbit.sampleCamera(context);
                    Vector3d center = orbit.getOrbitCenter(0F);
                    Vector3d foreground = new Vector3d(before.position).sub(center).normalize().add(center);
                    Position feature = new Position();

                    feature.point.set(foreground.x, foreground.y, foreground.z);
                    FilmCameraMarkerGeometry.ScreenPoint featureBefore = FilmCameraMarkerGeometry.project(feature, before, frame);
                    Camera flightAfter = dragFlight(before, 173, 107, drag[0], drag[1]);
                    FilmCameraMarkerGeometry.ScreenPoint featureAfterFlight = FilmCameraMarkerGeometry.project(feature, flightAfter, frame);
                    context.setMouse(173, 107, 0);
                    long generation = orbit.startGesture(context);

                    check(generation != 0L, "the real Film orbit press did not start");
                    context.setMouse(173 + drag[0], 107 + drag[1], 0);
                    orbit.handleOrbiting(context);
                    Camera after = orbit.sampleCamera(context);
                    FilmCameraMarkerGeometry.ScreenPoint featureAfter = FilmCameraMarkerGeometry.project(feature, after, frame);

                    check(featureBefore != null && featureAfter != null && featureAfterFlight != null,
                        "the same visible subject feature must project through both gesture routes");
                    double flightMotion = (featureAfterFlight.x() - featureBefore.x()) * drag[0]
                        + (featureAfterFlight.y() - featureBefore.y()) * drag[1];
                    double orbitMotion = (featureAfter.x() - featureBefore.x()) * drag[0]
                        + (featureAfter.y() - featureBefore.y()) * drag[1];

                    check(flightMotion < 0D, "Film mouse flight must retain FS right/down camera look and left/up scenery motion");
                    check(orbitMotion > 0D, "FS orbit must move a near-side subject feature with the pointer");
                    near(after.position.distance(center), before.position.distance(center), 0.00001D,
                        "orbit rotation must preserve its distance from the pivot");

                    Position pivot = new Position();

                    pivot.point.set(center.x, center.y, center.z);
                    FilmCameraMarkerGeometry.ScreenPoint projected = FilmCameraMarkerGeometry.project(pivot, after, frame);

                    check(projected != null, "orbit pivot left the camera frustum");
                    near(projected.x(), 173D, 0.0001D, "horizontal orbit lost its center in the displayed projection");
                    near(projected.y(), 107D, 0.0001D, "vertical orbit lost its center in the displayed projection");

                    Vector3f ray = after.getMouseRay(173, 107, frame.x(), frame.y(), frame.width(), frame.height(), new Vector3f());
                    Vector3d towardCenter = new Vector3d(center).sub(after.position).normalize();

                    check(towardCenter.dot(new Vector3d(ray)) > 0.99999D,
                        "orbit picking ray must still face its pivot after dragging");

                    check(orbit.wasDragged() && !orbit.stop(2, generation) && orbit.gestureGeneration() == generation,
                        "another button's release must not finish the left drag");
                    check(orbit.stop(0, generation) && orbit.gestureGeneration() == 0L,
                        "matching release must retire the real Film orbit gesture");
                    context.setMouse(500, 400, 0);
                    orbit.handleOrbiting(context);
                    Camera released = orbit.sampleCamera(context);
                    check(new Position(released).equals(new Position(after)), "cursor motion after release changed the orbit");

                    controller.flying = true;
                    context.setMouse(173, 107, 2);
                    check(orbit.startGesture(context) == 0L, "Film orbit middle pan must keep FS flight restrictions");
                    context.setMouse(173, 107, 0);
                    check(orbit.startGesture(context) != 0L, "Film orbit flight must accept its left-button route");
                    orbit.stop();
                }
            }
        }
    }

    private static void testFlightGestureMatchesFs()
    {
        for (int[] drag : new int[][] {{24, 0}, {-24, 0}, {0, 24}, {0, -24}})
        {
            Camera before = camera(10D, 20D, 30D, 0F, 0F, 0F, 70F);
            ViewFrameGeometry frame = new ViewFrameGeometry(0, 0, 320, 180);
            Position feature = new Position();

            before.updatePerspectiveProjection(frame.width(), frame.height());
            feature.point.set(10D, 20D, 20D);
            FilmCameraMarkerGeometry.ScreenPoint start = FilmCameraMarkerGeometry.project(feature, before, frame);
            Camera after = dragFlight(before, 150, 90, drag[0], drag[1]);
            FilmCameraMarkerGeometry.ScreenPoint end = FilmCameraMarkerGeometry.project(feature, after, frame);

            check(start != null && end != null
                && (end.x() - start.x()) * drag[0] + (end.y() - start.y()) * drag[1] < 0D,
                "FS flight must project right/down drags as left/up scenery motion");
        }
    }

    private static Camera dragFlight(Camera before, int x, int y, int dx, int dy)
    {
        UIOrbitCamera flight = new UIOrbitCamera();
        UIContext context = new UIContext(null);

        flight.orbit = new OrbitCamera()
        {
            @Override
            public float getAngleSpeed()
            {
                return 0.01F;
            }
        };
        flight.setControl(false);
        flight.orbit.setup(before);
        flight.setControl(true);
        context.setMouse(x, y, 0);
        check(flight.mouseClicked(context) == flight, "dashboard flight did not acquire the left press");
        context.setMouse(x + dx, y + dy, 0);
        flight.render(context);

        Position pose = new Position();
        Camera after = new Camera();

        flight.orbit.apply(pose);
        pose.apply(after);
        after.updateView();
        after.updatePerspectiveProjection(320, 180);
        near(after.position.distance(before.position), 0D, 0.000001D, "mouse flight must rotate in place");
        near(after.rotation.x, before.rotation.x + dy * 0.01F, 0.000001D, "flight pitch changed from FS");
        near(after.rotation.y, before.rotation.y + dx * 0.01F, 0.000001D, "flight yaw changed from FS");
        check(flight.mouseReleased(context) == flight && !flight.orbit.isDragging(), "flight release did not stop its real input route");
        context.setMouse(x + 300, y + 300, 0);
        flight.render(context);
        Position releasedPose = new Position();

        flight.orbit.apply(releasedPose);
        check(releasedPose.equals(pose), "released flight still followed the mouse");
        flight.setControl(false);

        flight.setControl(true);
        context.setMouse(x, y, 0);
        check(flight.mouseClicked(context) == flight, "flight did not recover after control was disabled");
        context.setMouse(x + dx, y + dy, 0);
        flight.render(context);
        near(flight.orbit.rotation.x, after.rotation.x + dy * 0.01F, 0.000001D,
            "flight pitch changed after control was re-enabled");
        near(flight.orbit.rotation.y, after.rotation.y + dx * 0.01F, 0.000001D,
            "flight yaw changed after control was re-enabled");
        flight.mouseReleased(context);
        flight.setControl(false);

        return after;
    }

    private static void withInputDefaults(Runnable test)
    {
        ValueFloat smoothness = BBSSettings.editorCameraSmoothness;
        ValueBoolean requiresFlight = BBSSettings.editorOrbitMovementRequiresFlight;

        try
        {
            BBSSettings.editorCameraSmoothness = new ValueFloat("test_smoothness", 0F);
            BBSSettings.editorOrbitMovementRequiresFlight = new ValueBoolean("test_requires_flight", true);
            test.run();
        }
        finally
        {
            BBSSettings.editorCameraSmoothness = smoothness;
            BBSSettings.editorOrbitMovementRequiresFlight = requiresFlight;
        }
    }

    private static void testCameraGestureTargetFence()
    {
        Camera camera = new Camera();
        Object film = new Object();
        ViewNavigationGesture.Target target = new ViewNavigationGesture.Target(film, "camera-a", 10);
        ViewNavigationGesture.Target[] replacements = {
            new ViewNavigationGesture.Target(new Object(), "camera-a", 10),
            new ViewNavigationGesture.Target(film, "camera-b", 10),
            new ViewNavigationGesture.Target(film, "camera-a", 11)
        };

        for (ViewNavigationGesture.Target replacement : replacements)
        {
            ViewNavigationState state = new ViewNavigationState();
            ViewNavigationGesture gesture = new ViewNavigationGesture();

            state.enterCameraView(camera, null);
            gesture.begin(1, 0, 0, ViewNavigationGesture.Mode.CAMERA, state, camera, target);
            gesture.drag(20, 10, 320, 180, false);
            check(gesture.release(1, replacement).cameraPose() == null && !state.isEditingCamera(),
                "camera edits must be fenced by Film identity, camera id and playhead");
        }
    }

    private static void testFrameAndFreeGestureCancellation()
    {
        Camera camera = camera(10D, 20D, 30D, 45F, 10F, 0F, 70F);
        ViewNavigationState state = new ViewNavigationState();
        ViewNavigationGesture gesture = new ViewNavigationGesture();
        Object film = new Object();
        ViewNavigationGesture.Target target = new ViewNavigationGesture.Target(film, "camera-a", 0);

        state.initialize(camera);
        Position original = state.getFreePose().copy();
        gesture.begin(2, 0, 0, ViewNavigationGesture.Mode.FREE, state, camera, target);
        gesture.drag(50, 25, 320, 180, false);
        check(!state.getFreePose().equals(original), "free-view pan must update only its view state");
        gesture.cancel();
        check(state.getFreePose().equals(original), "cancel must restore the original free-view pose");

        state.panFrame(0.1F, -0.2F);
        gesture.begin(2, 0, 0, ViewNavigationGesture.Mode.FRAME, state, camera, target);
        gesture.drag(100, 50, 320, 180, false);
        gesture.cancel();
        near(state.getFramePanX(), 0.1F, 0.000001D, "cancel must restore frame X pan");
        near(state.getFramePanY(), -0.2F, 0.000001D, "cancel must restore frame Y pan");

        gesture.begin(2, 0, 0, ViewNavigationGesture.Mode.FRAME, state, camera, target);
        gesture.drag(32, 18, 320, 180, false);
        ViewNavigationGesture.Completion completion = gesture.release(2, new ViewNavigationGesture.Target(film, "camera-b", 20));

        check(completion.consumed() && completion.cameraPose() == null, "frame navigation must work during output playback and cuts");
        near(state.getFramePanX(), 0.2F, 0.000001D, "frame navigation must not be canceled by the advancing playhead");
    }

    private static void testEditorSettingsDefensiveCopies()
    {
        ValueEditorLayout settings = new ValueEditorLayout("layout");
        MapType views = new MapType();
        MapType film = new MapType();

        film.putString("camera", "camera-a");
        views.put("film-a", film);
        settings.setFilmViewSettings(views);
        film.putString("camera", "changed");
        check(settings.getFilmViewSettings().getMap("film-a").getString("camera").equals("camera-a"),
            "settings setter must copy nested view data");
        settings.getFilmViewSettings().getMap("film-a").putString("camera", "changed-again");

        ValueEditorLayout restored = new ValueEditorLayout("layout");

        restored.fromData(settings.toData());
        check(restored.getFilmViewSettings().getMap("film-a").getString("camera").equals("camera-a"),
            "view settings must round-trip without exposing their mutable backing map");
        check(restored.getFilmViewSettings().getMap("film-b").isEmpty(), "Film view settings must remain independent");
    }

    private static void testCameraMarkerProjectionAndRoll()
    {
        Position pose = new Position(0, 0, 0, 0, 0, 90, 90);
        Vector3f[] corners = FilmCameraMarkerGeometry.corners(pose, 2F, 2F);

        near(corners[0].x, -2D, 0.00001D, "frustum roll must rotate its horizontal extent");
        near(corners[0].y, 4D, 0.00001D, "frustum roll must rotate its vertical extent");
        near(corners[0].z, -2D, 0.00001D, "frustum must face the camera's forward axis");

        Camera camera = camera(30_000_000.125D, 75D, -30_000_000.25D, 0F, 0F, 0F, 90F);
        ViewFrameGeometry frame = new ViewFrameGeometry(100, 50, 320, 180);
        Position marker = new Position();

        camera.updatePerspectiveProjection(320, 180);
        camera.updateView();
        marker.point.set(camera.position.x, camera.position.y, camera.position.z - 10D);
        FilmCameraMarkerGeometry.ScreenPoint center = FilmCameraMarkerGeometry.project(marker, camera, frame);

        check(center != null, "camera object in front of the view must project");
        near(center.x(), 260D, 0.00001D, "marker must project into the displayed frame's X origin");
        near(center.y(), 140D, 0.00001D, "marker must project into the displayed frame's Y origin");
        marker.point.x += 0.25D;
        FilmCameraMarkerGeometry.ScreenPoint nearby = FilmCameraMarkerGeometry.project(marker, camera, frame);

        check(nearby != null && nearby.x() > center.x(), "far-world marker picking must preserve sub-block double precision");
        marker.point.z = camera.position.z + 10D;
        check(FilmCameraMarkerGeometry.project(marker, camera, frame) == null, "objects behind the camera must not be pickable");
    }

    private static void testPerformanceSettingsRoundTripAndDefaults()
    {
        ViewPerformanceSettings performance = new ViewPerformanceSettings();
        ViewDescriptor first = new ViewDescriptor(null, "preview2", false);
        ViewDescriptor second = new ViewDescriptor(null, "preview3", false);
        ValueEditorLayout layout = new ValueEditorLayout("layout");
        MapType film = new MapType();
        MapType films = new MapType();

        performance.setAuxiliaryBudgetFraction(0.75D);
        first.setResolutionWidth(1280);
        second.setResolutionWidth(640);
        film.put("performance", performance.toData());
        film.put(first.getId(), first.toData());
        film.put(second.getId(), second.toData());
        films.put("film-a", film);
        layout.setFilmViewSettings(films);

        ValueEditorLayout restoredLayout = new ValueEditorLayout("layout");

        restoredLayout.fromData(layout.toData());
        MapType restored = restoredLayout.getFilmViewSettings().getMap("film-a");

        performance.fromData(restored.getMap("performance"));
        first.fromData(restored.getMap(first.getId()));
        second.fromData(restored.getMap(second.getId()));
        near(performance.getAuxiliaryBudgetFraction(), 0.75D, 0D, "shared render budget did not round-trip through editor settings");
        check(first.getResolutionWidth() == 1280 && second.getResolutionWidth() == 640,
            "resolution preferences must round-trip independently for each stable view");
        first.setResolutionWidth(320);
        check(second.getResolutionWidth() == 640, "one preview resolution changed another preview");
        check(restoredLayout.getFilmViewSettings().getMap("film-b").isEmpty(), "performance settings leaked to another Film");

        performance.fromData(new MapType());
        first.fromData(new MapType());
        near(performance.getAuxiliaryBudgetFraction(), 0.2D, 0D, "old settings must use the default shared budget");
        check(first.getResolutionWidth() == 0, "old settings must reset a prior Film's resolution to automatic");

        MapType invalid = new MapType();

        invalid.putString("auxiliary_budget", "broken");
        invalid.putString("resolution_width", "broken");
        performance.fromData(invalid);
        first.fromData(invalid);
        near(performance.getAuxiliaryBudgetFraction(), 0.2D, 0D, "malformed budget must use its default");
        check(first.getResolutionWidth() == 0, "malformed resolution must use automatic sizing");

        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        {
            invalid.putDouble("auxiliary_budget", value);
            invalid.putDouble("resolution_width", value);
            performance.fromData(invalid);
            first.fromData(invalid);
            near(performance.getAuxiliaryBudgetFraction(), 0.2D, 0D, "nonfinite budget entered the renderer");
            check(first.getResolutionWidth() == 0, "nonfinite resolution must reset to automatic");
        }

        performance.setAuxiliaryBudgetFraction(-1D);
        near(performance.getAuxiliaryBudgetFraction(), 0.05D, 0D, "budget lower bound must be 5 percent");
        performance.setAuxiliaryBudgetFraction(100D);
        near(performance.getAuxiliaryBudgetFraction(), 2D, 0D, "budget upper bound must be 200 percent");
        first.setResolutionWidth(-100);
        check(first.getResolutionWidth() == 0, "negative preview widths must select automatic sizing");
        first.setResolutionWidth(1);
        check(first.getResolutionWidth() == 320, "manual resolution must retain the minimum width");
        first.setResolutionWidth(Integer.MAX_VALUE);
        check(first.getResolutionWidth() == 1920, "manual resolution must respect its preference limit");
    }

    private static void testPreferredPreviewResolutions()
    {
        check(ViewPerformanceSettings.resolution(0, 800, 600, 1920, 1080).equals(new ViewTargetSize(640, 360)),
            "automatic resolution must fit the panel and use a supported render size");
        check(ViewPerformanceSettings.resolution(1280, 320, 180, 1920, 1080).equals(new ViewTargetSize(1280, 720)),
            "a preferred resolution must be able to exceed the dock's physical size");
        check(ViewPerformanceSettings.resolution(0, 2, 2, 1920, 1080).equals(new ViewTargetSize(320, 180)),
            "tiny docks must preserve the Film aspect before enforcing minimum quality");
        check(ViewPerformanceSettings.resolution(320, 640, 480, 4096, 1024).equals(new ViewTargetSize(720, 180)),
            "ultrawide previews must preserve aspect while retaining minimum height");

        int[][] outputs = {{1920, 1080}, {1080, 1920}, {1440, 1080}, {1000, 1000}};

        for (int[] output : outputs)
        {
            for (int width : new int[] {0, 320, 480, 640, 960, 1280, 1920})
            {
                ViewTargetSize size = ViewPerformanceSettings.resolution(width, 1800, 1200, output[0], output[1]);
                double aspect = output[0] / (double) output[1];

                check(size.meetsMinimum(), "a usable output aspect must retain minimum preview quality");
                check(size.width() <= 2048 && size.height() <= 2048, "preview resolution exceeded the renderer resource cap");
                check(Math.abs(size.width() - aspect * size.height()) <= Math.max(1D, aspect),
                    "resolution tiers must preserve the Film aspect to within one pixel");
            }
        }
    }

    private static void testCameraFrustumClipping()
    {
        Camera camera = camera(0D, 0D, 0D, 0F, 0F, 0F, 90F);
        ViewFrameGeometry frame = new ViewFrameGeometry(100, 50, 320, 180);
        Position origin = new Position(camera);

        camera.updatePerspectiveProjection(frame.width(), frame.height());
        FilmCameraMarkerGeometry.ScreenSegment near = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(0F, 0F, 0.02F), new Vector3f(0.5F, 0F, -1F), camera, frame);

        checkSegmentWithinFrame(near, frame, "a frustum crossing the eye must be clipped before projection");
        near(near.from().depth(), -1D, 0.00001D, "a near-plane crossing must begin at the near plane");
        check(FilmCameraMarkerGeometry.projectSegment(origin, new Vector3f(0F, 0F, 0.1F),
            new Vector3f(1F, 0F, 2F), camera, frame) == null, "a frustum entirely behind the camera must be rejected");
        check(FilmCameraMarkerGeometry.projectSegment(origin, new Vector3f(-0.0001F, 0F, -0.005F),
            new Vector3f(0.0001F, 0F, -0.005F), camera, frame) == null, "a line in front of the near plane must be rejected");

        FilmCameraMarkerGeometry.ScreenSegment sides = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(-100F, 0F, -1F), new Vector3f(100F, 0F, -1F), camera, frame);

        checkSegmentWithinFrame(sides, frame, "side-plane clipping must remain within the displayed frame");
        near(sides.from().x(), frame.x(), 0.00001D, "left side-plane intersection was not clipped");
        near(sides.to().x(), frame.x() + frame.width(), 0.00001D, "right side-plane intersection was not clipped");

        FilmCameraMarkerGeometry.ScreenSegment vertical = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(0F, -100F, -1F), new Vector3f(0F, 100F, -1F), camera, frame);

        checkSegmentWithinFrame(vertical, frame, "vertical clipping must remain within the displayed frame");
        near(vertical.from().y(), frame.y() + frame.height(), 0.00001D, "bottom side-plane intersection was not clipped");
        near(vertical.to().y(), frame.y(), 0.00001D, "top side-plane intersection was not clipped");

        FilmCameraMarkerGeometry.ScreenSegment far = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(0F, 0F, -1F), new Vector3f(0.5F, 0F, -600F), camera, frame);

        checkSegmentWithinFrame(far, frame, "far-plane clipping must remain finite");
        near(far.to().depth(), 1D, 0.00001D, "a far-plane crossing must end at the far plane");
        check(FilmCameraMarkerGeometry.projectSegment(origin, new Vector3f(0F, 0F, -1F),
            new Vector3f(0F, 0F, -2F), camera, frame) == null, "screen-degenerate guides must not reach line tessellation");
        check(FilmCameraMarkerGeometry.projectSegment(origin, new Vector3f(Float.NaN, 0F, -1F),
            new Vector3f(1F, 0F, -2F), camera, frame) == null, "nonfinite guides must not reach line tessellation");

        camera.projection.identity().ortho(-2F, 2F, -1F, 1F, 0.01F, 300F);
        FilmCameraMarkerGeometry.ScreenSegment ortho = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(-20F, 0F, -1F), new Vector3f(20F, 0F, -1F), camera, frame);

        checkSegmentWithinFrame(ortho, frame, "orthographic previews must use the same clip-space contract");
        near(ortho.from().x(), frame.x(), 0.00001D, "orthographic left clipping failed");
        near(ortho.to().x(), frame.x() + frame.width(), 0.00001D, "orthographic right clipping failed");
    }

    private static void testCameraFrustumPrecisionAndLineWidth()
    {
        Camera camera = camera(30_000_000.125D, 75D, -30_000_000.25D, 0F, 0F, 0F, 90F);
        ViewFrameGeometry frame = new ViewFrameGeometry(100, 50, 320, 180);
        Position origin = new Position(camera);

        camera.updatePerspectiveProjection(frame.width(), frame.height());
        origin.point.x += 0.25D;
        origin.point.z -= 10D;
        FilmCameraMarkerGeometry.ScreenSegment distant = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(-0.1F, 0F, 0F), new Vector3f(0.1F, 0F, 0F), camera, frame);

        checkSegmentWithinFrame(distant, frame, "distant-world camera guides must remain visible");
        check(distant.from().x() > 260F, "camera guides lost sub-block world precision before projection");
        origin.point.set(0.25D, 0D, -10D);
        camera.position.set(0D, 0D, 0D);
        FilmCameraMarkerGeometry.ScreenSegment local = FilmCameraMarkerGeometry.projectSegment(origin,
            new Vector3f(-0.1F, 0F, 0F), new Vector3f(0.1F, 0F, 0F), camera, frame);

        checkSegmentWithinFrame(local, frame, "local camera guide did not project");
        near(distant.from().x(), local.from().x(), 0D, "relative guide positions changed at the world border");
        near(distant.to().x(), local.to().x(), 0D, "relative guide endpoint changed at the world border");
        origin.point.set(0D, 0D, 0D);

        for (float depth : new float[] {0.011F, 1F, 100F})
        {
            for (int scale : new int[] {1, 4})
            {
                ViewFrameGeometry zoomed = new ViewFrameGeometry(100, 50, 320 * scale, 180 * scale);
                FilmCameraMarkerGeometry.ScreenSegment segment = FilmCameraMarkerGeometry.projectSegment(origin,
                    new Vector3f(-depth * 0.25F, -depth * 0.1F, -depth),
                    new Vector3f(depth * 0.25F, depth * 0.1F, -depth), camera, zoomed);

                checkSegmentWithinFrame(segment, zoomed, "close and distant guides must share finite projection");
                Line<Void> line = new Line<>();
                List<LinePoint<Void>> vertices = line.add(segment.from().x(), segment.from().y())
                    .add(segment.to().x(), segment.to().y()).build(FilmCameraMarkerGeometry.LINE_HALF_WIDTH);

                check(vertices.size() == 4, "a clipped camera guide must tessellate to one finite quad");

                for (int i = 0; i < vertices.size(); i += 2)
                {
                    LinePoint<Void> first = vertices.get(i);
                    LinePoint<Void> second = vertices.get(i + 1);

                    near(Math.hypot(first.x - second.x, first.y - second.y), 1D, 0.0001D,
                        "camera guide width must stay at one GUI pixel across distance and frame zoom");
                }
            }
        }
    }

    private static void checkSegmentWithinFrame(FilmCameraMarkerGeometry.ScreenSegment segment, ViewFrameGeometry frame, String message)
    {
        check(segment != null, message);

        for (FilmCameraMarkerGeometry.ScreenPoint point : new FilmCameraMarkerGeometry.ScreenPoint[] {segment.from(), segment.to()})
        {
            check(Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.depth()), message);
            check(point.x() >= frame.x() && point.x() <= frame.x() + frame.width()
                && point.y() >= frame.y() && point.y() <= frame.y() + frame.height()
                && point.depth() >= -1F && point.depth() <= 1F, message);
        }
    }

    private static void withViewDefaults(Runnable test)
    {
        ValueOnionSkin onionSkin = BBSSettings.editorOnionSkin;
        ValueMotionPath motionPath = BBSSettings.editorMotionPath;
        ValueBoolean thirds = BBSSettings.editorRuleOfThirds;
        ValueBoolean center = BBSSettings.editorCenterLines;
        ValueBoolean crosshair = BBSSettings.editorCrosshair;

        try
        {
            BBSSettings.editorOnionSkin = new ValueOnionSkin("onion_skin");
            BBSSettings.editorMotionPath = new ValueMotionPath("motion_path");
            BBSSettings.editorRuleOfThirds = new ValueBoolean("rule_of_thirds", false);
            BBSSettings.editorCenterLines = new ValueBoolean("center_lines", false);
            BBSSettings.editorCrosshair = new ValueBoolean("crosshair", false);
            test.run();
        }
        finally
        {
            BBSSettings.editorOnionSkin = onionSkin;
            BBSSettings.editorMotionPath = motionPath;
            BBSSettings.editorRuleOfThirds = thirds;
            BBSSettings.editorCenterLines = center;
            BBSSettings.editorCrosshair = crosshair;
        }
    }

    private static Camera camera(double x, double y, double z, float yaw, float pitch, float roll, float fov)
    {
        Camera camera = new Camera();
        Position pose = new Position(0, 0, 0, yaw, pitch, roll, fov);

        pose.point.set(x, y, z);
        pose.apply(camera);
        camera.updateView();

        return camera;
    }

    private static void near(double actual, double expected, double tolerance, String message)
    {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private FilmViewNavigationTest()
    {}

    private static final class DirectionalOrbit extends OrbitFilmCameraController
    {
        private DirectionalOrbit(UIFilmController controller)
        {
            super(controller);
            this.enabled = true;
        }

        @Override
        protected float getAngleSpeed()
        {
            return 0.01F;
        }

        private Camera sampleCamera(UIContext context)
        {
            Camera camera = new Camera();

            this.update(context);
            this.setup(camera, 0F);
            camera.updateView();
            camera.updatePerspectiveProjection(320, 180);

            return camera;
        }
    }

    private static final class DirectionalController extends UIFilmController
    {
        private boolean flying;

        private DirectionalController()
        {
            super(null, false);
        }

        @Override
        public boolean isViewFlying()
        {
            return this.flying;
        }

        @Override
        public void setViewOrthoDistance(float distance)
        {}
    }
}
