package mchorse.bbs_mod.ui.framework.elements;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

import java.util.List;

public interface IUIElement
{
    /**
     * Should be called when position has to be recalculated
     */
    public void resize();

    /**
     * Whether this element is enabled (and can accept any input) 
     */
    public boolean isEnabled();

    /**
     * Whether this element is visible
     */
    public boolean isVisible();

    /**
     * Mouse was clicked
     */
    public IUIElement mouseClicked(UIContext context);

    /**
     * Mouse wheel was scrolled
     */
    public IUIElement mouseScrolled(UIContext context);

    /**
     * Mouse was released
     */
    public IUIElement mouseReleased(UIContext context);

    /** Release through the exact ancestor path that owned the initiating press. */
    public default IUIElement mouseReleasedCaptured(UIContext context, List<IUIElement> path, int index)
    {
        return index >= 0 && index < path.size() && path.get(index) == this && index == path.size() - 1
            ? this.mouseReleased(context)
            : null;
    }

    /**
     * An owned mouse gesture was canceled without a physical button release.
     */
    public default void mouseCanceled(UIContext context)
    {}

    /**
     * Key was typed
     */
    public IUIElement keyPressed(UIContext context);

    /**
     * Component was inputted
     */
    public IUIElement textInput(UIContext context);

    /**
     * Determines whether this element can be rendered on the screen
     */
    public boolean canBeRendered(Area viewport);

    /**
     * Draw its components on the screen
     */
    public void render(UIContext context);
}
