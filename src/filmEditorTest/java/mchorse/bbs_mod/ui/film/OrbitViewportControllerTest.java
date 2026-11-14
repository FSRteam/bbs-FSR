package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.ui.film.controller.OrbitFilmCameraController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_blocks.camera.OrbitModelBlockCameraController;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.camera.OrbitViewportController;
import org.joml.Vector3d;

/** Executes the extracted navigation owner without a Minecraft window or renderer. */
public final class OrbitViewportControllerTest
{
    private OrbitViewportControllerTest()
    {}

    public static void runAll()
    {
        check(OrbitFilmCameraController.class.getSuperclass() == OrbitViewportController.class
                && OrbitModelBlockCameraController.class.getSuperclass() == OrbitViewportController.class,
            "viewport hosts still duplicate the shared orbit owner");

        Fixture orbit = new Fixture();
        Camera flight = new Camera();
        flight.position.set(30_000_000.125D, 71.25D, -30_000_000.5D);
        flight.rotation.set(-0.35F, 1.2F, 0F);
        flight.fov = 1.1F;
        Position original = new Position(flight);

        orbit.anchor.set(30_000_000D, 70D, -30_000_000D);
        orbit.yaw = 0.7F;
        orbit.follow(flight, 0F);
        check(new Position(flight).equals(original), "following flight changed the camera it observes");
        Camera sampled = new Camera();
        orbit.setup(sampled, 0F);
        samePose(sampled, flight, "flight handoff moved the distant camera");

        orbit.anchor.add(3.25D, 0.5D, -8.125D);
        orbit.yaw = -0.4F;
        flight.position.add(1.125D, -2.5D, 0.25D);
        orbit.follow(flight, 0F);
        orbit.setup(sampled, 0F);
        samePose(sampled, flight, "moving anchor pulled the camera while flight owned it");

        Vector3d center = orbit.getOrbitCenter(0F);
        orbit.rebase(new Vector3d(-30_000_000.25D, 80D, 30_000_000.125D), 0.2F);
        orbit.setup(sampled, 0F);
        samePose(sampled, flight, "anchor replacement changed the world camera pose");
        check(orbit.getOrbitCenter(0F).distance(center) < 0.000001D,
            "rebasing the shared orbit lost its double-precision world pivot");

        UIContext context = orbit.context;
        context.setMouse(12, 15, 0);
        long first = orbit.startGesture(context);
        context.setMouse(15, 18, 2);
        check(first != 0L && orbit.startGesture(context) == 0L && !orbit.stop(2, first),
            "a second button stole the shared orbit's initiating press");
        check(orbit.stop(0, first), "matching release did not retire the shared orbit");
        context.setMouse(12, 15, 0);
        long second = orbit.startGesture(context);
        check(second != first && !orbit.stop(0, first) && orbit.isGestureOwnedBy(0),
            "stale release stopped a replacement shared orbit gesture");
        orbit.stop();
    }

    private static void samePose(Camera actual, Camera expected, String message)
    {
        check(actual.position.distance(expected.position) < 0.000001D
                && actual.rotation.distance(expected.rotation) < 0.000001F, message);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class Fixture extends OrbitViewportController
    {
        private final UIContext context = new UIContext(null);
        private final Area viewport = new Area(0, 0, 320, 180);
        private final Camera camera = new Camera();
        private final Vector3d anchor = new Vector3d();
        private float yaw;

        private Fixture()
        {
            this.enabled = true;
        }

        protected UIContext getContext() { return this.context; }
        protected Area getViewport() { return this.viewport; }
        protected Camera getViewportCamera() { return this.camera; }
        protected float getSpeed() { return 0.1F; }
        protected Vector3d getSubjectPivot(float transition) { return null; }
        protected boolean hasSubject() { return false; }
        protected void setOrthoDistance(float distance) {}

        protected void updateAnchor(float transition)
        {
            this.anchorPosition.set(this.anchor);
            this.anchorYaw = this.yaw;
        }

        private void rebase(Vector3d anchor, float yaw)
        {
            this.rebaseAnchor(() ->
            {
                this.anchor.set(anchor);
                this.yaw = yaw;
                this.updateAnchor(0F);
            });
        }
    }
}
