package mchorse.bbs_mod.client.compat;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.camera.controller.ICameraController;

/** Lifecycle regression for stale camera-controller resurrection. */
final class CameraControllerResetTest
{
    private CameraControllerResetTest()
    {}

    public static void main(String[] args)
    {
        runAll();

        System.out.println("CameraControllerResetTest passed");
    }

    static void runAll()
    {
        CameraController owner = new CameraController();
        ICameraController stale = new TestController(20);
        ICameraController secondary = new TestController(10);

        owner.add(stale);
        owner.add(secondary);

        check(owner.getCurrent() == stale, "test did not install the stale high-priority controller");

        owner.reset();

        check(owner.getCurrent() == null, "camera reset retained its current controller");
        check(!owner.has(stale) && !owner.has(secondary), "camera reset retained stale controllers in its owner list");

        ICameraController fresh = new TestController(1);

        owner.add(fresh);

        check(owner.getCurrent() == fresh, "a stale pre-reset controller became current after a fresh add");
        check(!owner.has(stale) && !owner.has(secondary), "a fresh add resurrected a stale pre-reset controller");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private record TestController(int priority) implements ICameraController
    {
        @Override
        public void setup(Camera camera, float transition)
        {}

        @Override
        public int getPriority()
        {
            return this.priority;
        }
    }
}
