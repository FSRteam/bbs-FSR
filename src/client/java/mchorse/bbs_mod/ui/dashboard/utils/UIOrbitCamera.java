package mchorse.bbs_mod.ui.dashboard.utils;

import mchorse.bbs_mod.camera.OrbitCamera;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.Area;

import java.util.function.Supplier;

public class UIOrbitCamera implements IUIElement
{
    public OrbitCamera orbit = new OrbitCamera();
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;
    private boolean control;
    private boolean enabled = true;

    public boolean canControl()
    {
        return this.control;
    }

    public boolean getControl()
    {
        return this.control;
    }

    public void setControl(boolean control)
    {
        if (!control)
        {
            this.cancelGesture();
        }

        this.control = control;
    }

    public void setEnabled(boolean enabled)
    {
        if (!enabled)
        {
            this.cancelGesture();
        }

        this.enabled = enabled;
    }

    @Override
    public IUIElement mouseClicked(UIContext context)
    {
        return this.startGesture(context) == 0L ? null : this;
    }

    /** Start a dashboard orbit from a caller which is not itself in the UI tree. */
    public long startGesture(UIContext context)
    {
        if (!this.enabled)
        {
            return 0L;
        }

        int mode = this.orbit.canStart(context);

        if (mode < 0)
        {
            return 0L;
        }

        long generation = this.dragOwnership.acquireToken(context.mouseButton);

        if (generation == 0L)
        {
            return 0L;
        }

        this.dragGeneration = generation;

        try
        {
            this.orbit.start(mode, context.mouseX, context.mouseY);

            return generation;
        }
        catch (RuntimeException | Error exception)
        {
            if (this.dragOwnership.release(context.mouseButton, generation))
            {
                this.dragGeneration = 0L;
                this.orbit.release();
            }

            throw exception;
        }
    }

    public long gestureGeneration()
    {
        return this.dragGeneration;
    }

    public boolean stopGesture(int mouseButton)
    {
        return this.stopGesture(mouseButton, this.dragGeneration);
    }

    public boolean stopGesture(int mouseButton, long generation)
    {
        if (!this.dragOwnership.release(mouseButton, generation))
        {
            return false;
        }

        if (this.dragGeneration == generation)
        {
            this.dragGeneration = 0L;
        }

        this.orbit.release();

        return true;
    }

    /** Cancel the current gesture without invoking any release-side action. */
    public void cancelGesture()
    {
        this.dragOwnership.cancel();
        this.dragGeneration = 0L;
        this.orbit.release();
    }

    @Override
    public IUIElement mouseScrolled(UIContext context)
    {
        if (!this.control || !this.enabled)
        {
            return null;
        }

        return this.orbit.scroll((int) context.mouseWheel) ? this : null;
    }

    @Override
    public IUIElement mouseReleased(UIContext context)
    {
        return this.stopGesture(context.mouseButton, this.dragGeneration) ? this : null;
    }

    @Override
    public void mouseCanceled(UIContext context)
    {
        this.stopGesture(context.mouseButton, this.dragGeneration);
    }

    @Override
    public void render(UIContext context)
    {
        if (!this.control && !this.dragOwnership.isActive())
        {
            this.orbit.cache(context.mouseX, context.mouseY);

            return;
        }

        this.orbit.drag(context.mouseX, context.mouseY);
        this.orbit.update(context);
    }

    /* Unimplemented GUI element methods */

    @Override
    public void resize()
    {}

    @Override
    public boolean isEnabled()
    {
        return this.enabled;
    }

    @Override
    public boolean isVisible()
    {
        return true;
    }

    @Override
    public IUIElement keyPressed(UIContext context)
    {
        return null;
    }

    @Override
    public IUIElement textInput(UIContext context)
    {
        return null;
    }

    @Override
    public boolean canBeRendered(Area area)
    {
        return true;
    }
}
