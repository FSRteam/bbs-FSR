package mchorse.bbs_mod.ui.framework.elements;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;

import java.util.List;

public interface IUIElement
{
    /**
     * Should be called when position has to be recalculated
     */
    public default void resize()
    {}

    /**
     * Whether this element is enabled (and can accept any input) 
     */
    public default boolean isEnabled()
    {
        return false;
    }

    /**
     * Whether this element is visible
     */
    public default boolean isVisible()
    {
        return true;
    }

    /**
     * Mouse was clicked
     */
    public default IUIElement mouseClicked(UIContext context)
    {
        return null;
    }

    /**
     * Mouse wheel was scrolled
     */
    public default IUIElement mouseScrolled(UIContext context)
    {
        return null;
    }

    /**
     * Mouse was released
     */
    public default IUIElement mouseReleased(UIContext context)
    {
        return null;
    }

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
    public default IUIElement keyPressed(UIContext context)
    {
        return null;
    }

    /**
     * Text was inputted
     */
    public default IUIElement textInput(UIContext context)
    {
        return null;
    }

    /**
     * Determines whether this element can be rendered on the screen
     */
    public default boolean canBeRendered(Area viewport)
    {
        return false;
    }

    /**
     * Draw its components on the screen
     */
    public default void render(UIContext context)
    {}
}
