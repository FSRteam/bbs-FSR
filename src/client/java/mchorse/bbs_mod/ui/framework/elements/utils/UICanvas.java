package mchorse.bbs_mod.ui.framework.elements.utils;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.ScrollDirection;

public abstract class UICanvas extends UIElement
{
    public Scale scaleX;
    public Scale scaleY;

    public boolean dragging;
    public int mouse;
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;

    protected int lastX;
    protected int lastY;
    protected double lastT;
    protected double lastV;

    public UICanvas()
    {
        super();

        this.scaleX = new Scale(this.area);
        this.scaleX.anchor(0.5F);
        this.scaleY = new Scale(this.area, ScrollDirection.VERTICAL);
        this.scaleY.anchor(0.5F);
    }

    public int toX(double x)
    {
        return (int) Math.round(this.scaleX.to(x));
    }

    public double fromX(int mouseX)
    {
        return this.scaleX.from(mouseX);
    }

    public int toY(double y)
    {
        return (int) Math.round(this.scaleY.to(y));
    }

    public double fromY(int mouseY)
    {
        return this.scaleY.from(mouseY);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.area.isInside(context) && this.isMouseButtonAllowed(context.mouseButton))
        {
            int dragButton = context.mouseButton;

            /* Fake middle mouse click to add an ability to navigate
             * with Ctrl + click dragging */
            if (dragButton == 0 && Window.isCtrlPressed())
            {
                dragButton = 2;
            }

            return this.beginOwnedDrag(context, dragButton);
        }

        return super.subMouseClicked(context);
    }

    protected boolean isMouseButtonAllowed(int mouseButton)
    {
        return mouseButton == 0 || mouseButton == 2;
    }

    /** Begin one drag while retaining the physical button as its owner. */
    protected boolean beginOwnedDrag(UIContext context, int dragButton)
    {
        int ownerButton = context.mouseButton;

        this.dragGeneration = this.dragOwnership.acquireToken(ownerButton);

        if (this.dragGeneration == 0L)
        {
            return false;
        }

        boolean started = false;

        try
        {
            this.dragging = true;
            this.mouse = dragButton;
            this.lastX = context.mouseX;
            this.lastY = context.mouseY;
            this.startDragging(context);
            started = true;

            return true;
        }
        finally
        {
            if (!started)
            {
                this.dragging = false;
                this.dragOwnership.release(ownerButton, this.dragGeneration);
                this.dragGeneration = 0L;
            }
        }
    }

    protected boolean isDragOwnedBy(int mouseButton)
    {
        return this.dragOwnership.isOwnedBy(mouseButton, this.dragGeneration);
    }

    /** Retire the old generation before callbacks can install a replacement drag. */
    protected boolean retireOwnedDrag(int mouseButton)
    {
        if (!this.dragOwnership.release(mouseButton, this.dragGeneration))
        {
            return false;
        }

        this.dragGeneration = 0L;
        this.dragging = false;

        return true;
    }

    protected void startDragging(UIContext context)
    {
        this.lastT = this.scaleX.getShift();
        this.lastV = this.scaleY.getShift();
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        if (context.mouseWheel != 0D && this.area.isInside(context.mouseX, context.mouseY) && !this.dragging)
        {
            this.zoom(context, context.mouseWheel);

            return true;
        }

        return super.subMouseScrolled(context);
    }

    protected void zoom(UIContext context, double scroll)
    {
        int legacyScroll = (int) scroll;

        if (scroll == legacyScroll)
        {
            this.zoom(context, legacyScroll);

            return;
        }

        this.applyZoom(context, scroll);
    }

    /** Retained for binary compatibility with existing canvas subclasses. */
    protected void zoom(UIContext context, int scroll)
    {
        this.applyZoom(context, scroll);
    }

    private void applyZoom(UIContext context, double scroll)
    {
        if (scroll != 0D)
        {
            this.scaleX.zoomAnchor(Scale.getAnchorX(context, this.area), Math.copySign(this.scaleX.getZoomFactor(), scroll));
            this.scaleY.zoomAnchor(Scale.getAnchorY(context, this.area), Math.copySign(this.scaleY.getZoomFactor(), scroll));
        }
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (!this.retireOwnedDrag(context.mouseButton))
        {
            return super.subMouseReleased(context);
        }

        return true;
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.retireOwnedDrag(context.mouseButton);
    }

    @Override
    public void render(UIContext context)
    {
        this.dragging(context);

        context.batcher.clip(this.area, context);

        try
        {
            this.renderCanvas(context);
        }
        finally
        {
            context.batcher.unclip(context);
        }

        super.render(context);
    }

    protected void dragging(UIContext context)
    {
        if (this.dragging && this.mouse == 2)
        {
            float y = this.scaleY.inverse ? 1 : -1;

            this.scaleX.setShift(-(context.mouseX - this.lastX) / this.scaleX.getZoom() + this.lastT);
            this.scaleY.setShift(y * (context.mouseY - this.lastY) / this.scaleY.getZoom() + this.lastV);
        }
    }

    protected void renderCanvas(UIContext context)
    {}
}
