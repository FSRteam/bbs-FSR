package mchorse.bbs_mod.camera.controller;

import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.utils.MathUtils;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public class CameraController implements ICameraController
{
    public Camera camera = new Camera();
    private ICameraController current;
    private List<ICameraController> controllers = new ArrayList<>();

    public Vector3d getPosition()
    {
        return this.camera.position;
    }

    public float getYaw()
    {
        return MathUtils.toDeg(this.camera.rotation.y - MathUtils.PI);
    }

    public float getPitch()
    {
        return MathUtils.toDeg(this.camera.rotation.x);
    }

    public float getRoll()
    {
        return MathUtils.toDeg(this.camera.rotation.z);
    }

    public double getFOV()
    {
        return MathUtils.toDeg(this.camera.fov);
    }

    public void updateCurrent()
    {
        ICameraController current = null;

        for (ICameraController controller : this.controllers)
        {
            if (current == null)
            {
                current = controller;
            }
            else if (controller.getPriority() > current.getPriority())
            {
                current = controller;
            }
        }

        this.current = current;
    }

    public ICameraController getCurrent()
    {
        return this.current;
    }

    public void add(ICameraController controller)
    {
        this.controllers.add(controller);
        this.updateCurrent();
    }

    public void remove(Class clazz)
    {
        Throwable failure = null;
        Iterator<ICameraController> it = this.controllers.iterator();

        while (it.hasNext())
        {
            ICameraController controller = it.next();

            if (controller.getClass() == clazz)
            {
                it.remove();
                failure = appendFailure(failure, this.shutdownController(controller));
            }
        }

        this.updateCurrent();
        rethrowFailure(failure);
    }

    public List<ICameraController> removeAll(Class<? extends ICameraController> clazz)
    {
        List<ICameraController> removed = new ArrayList<>();
        Throwable failure = null;
        Iterator<ICameraController> it = this.controllers.iterator();

        while (it.hasNext())
        {
            ICameraController controller = it.next();

            if (controller.getClass() == clazz)
            {
                it.remove();
                removed.add(controller);
                failure = appendFailure(failure, this.shutdownController(controller));
            }
        }

        this.updateCurrent();
        rethrowFailure(failure);

        return removed;
    }

    public List<ICameraController> removeMatching(Predicate<? super ICameraController> predicate)
    {
        List<ICameraController> removed = new ArrayList<>();
        Throwable failure = null;
        Iterator<ICameraController> iterator = this.controllers.iterator();

        while (iterator.hasNext())
        {
            ICameraController controller = iterator.next();

            if (predicate.test(controller))
            {
                iterator.remove();
                removed.add(controller);
                failure = appendFailure(failure, this.shutdownController(controller));
            }
        }

        this.updateCurrent();
        rethrowFailure(failure);

        return removed;
    }

    public ICameraController remove(ICameraController controller)
    {
        Iterator<ICameraController> it = this.controllers.iterator();
        ICameraController removed = null;
        Throwable failure = null;

        while (it.hasNext())
        {
            ICameraController next = it.next();

            if (next == controller)
            {
                it.remove();

                removed = next;
                failure = appendFailure(failure, this.shutdownController(next));
            }
        }

        this.updateCurrent();
        rethrowFailure(failure);

        return removed;
    }

    @Override
    public void update()
    {
        if (this.current != null)
        {
            this.current.update();
        }
    }

    @Override
    public void setup(Camera camera, float transition)
    {
        if (this.current != null)
        {
            this.current.setup(camera, transition);
        }
    }

    public boolean has(ICameraController controller)
    {
        return this.controllers.contains(controller);
    }

    public boolean has(Class clazz)
    {
        for (ICameraController controller : this.controllers)
        {
            if (controller.getClass() == clazz)
            {
                return true;
            }
        }

        return false;
    }

    public void reset()
    {
        Throwable failure = null;

        for (ICameraController controller : new ArrayList<>(this.controllers))
        {
            failure = appendFailure(failure, this.shutdownController(controller));
        }

        this.controllers.clear();
        this.current = null;
        rethrowFailure(failure);
    }

    private Throwable shutdownController(ICameraController controller)
    {
        try
        {
            controller.shutdown();

            return null;
        }
        catch (RuntimeException | Error failure)
        {
            return failure;
        }
    }

    private static Throwable appendFailure(Throwable first, Throwable next)
    {
        if (next == null)
        {
            return first;
        }

        if (first == null)
        {
            return next;
        }

        if (first != next)
        {
            first.addSuppressed(next);
        }

        return first;
    }

    private static void rethrowFailure(Throwable failure)
    {
        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }

        if (failure instanceof Error error)
        {
            throw error;
        }
    }
}
