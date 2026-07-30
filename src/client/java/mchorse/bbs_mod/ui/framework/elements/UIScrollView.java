package mchorse.bbs_mod.ui.framework.elements;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.IViewportStack;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.ScrollDirection;

import java.util.List;
import java.util.function.Consumer;

/**
 * Scroll area GUI class
 * 
 * This bad boy allows to scroll stuff
 */
public class UIScrollView extends UIElement implements IViewport
{
    public Scroll scroll;

    public Consumer<UIContext> preRenderCallback;

    public UIScrollView()
    {
        this(ScrollDirection.VERTICAL);
    }

    public UIScrollView(ScrollDirection direction)
    {
        super();

        this.scroll = new Scroll(this.area, 0);
        this.scroll.direction = direction;
        this.scroll.scrollSpeed = 20;
    }

    public UIScrollView preRender(Consumer<UIContext> callback)
    {
        this.preRenderCallback = callback;

        return this;
    }

    @Override
    public void apply(IViewportStack stack)
    {
        stack.pushViewport(this.area);

        if (this.scroll.direction == ScrollDirection.VERTICAL)
        {
            stack.shiftY((int) this.scroll.getScroll());
        }
        else
        {
            stack.shiftX((int) this.scroll.getScroll());
        }
    }

    @Override
    public void unapply(IViewportStack stack)
    {
        if (this.scroll.direction == ScrollDirection.VERTICAL)
        {
            stack.shiftY((int) -this.scroll.getScroll());
        }
        else
        {
            stack.shiftX((int) -this.scroll.getScroll());
        }

        stack.popViewport();
    }

    @Override
    public void resize()
    {
        super.resize();

        this.scroll.clamp();
    }

    @Override
    protected IUIElement childrenMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            if (context.isFocused() && this.isDescendant((UIElement) context.activeElement))
            {
                context.unfocus();
            }

            return null;
        }

        if (this.scroll.mouseClicked(context))
        {
            return this;
        }

        this.apply(context);

        try
        {
            return super.childrenMouseClicked(context);
        }
        finally
        {
            this.unapply(context);
        }
    }

    @Override
    protected IUIElement childrenMouseScrolled(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            if (context.isFocused() && this.isDescendant((UIElement) context.activeElement))
            {
                context.unfocus();
            }

            return null;
        }

        this.apply(context);
        IUIElement result;

        try
        {
            result = super.childrenMouseScrolled(context);
        }
        finally
        {
            this.unapply(context);
        }

        if (result != null)
        {
            return result;
        }

        return this.scroll.mouseScroll(context) ? this : null;
    }

    @Override
    protected IUIElement childrenMouseReleased(UIContext context)
    {
        boolean scrollReleased = this.scroll.tryMouseReleased(context);

        this.apply(context);
        IUIElement result;

        try
        {
            result = super.childrenMouseReleased(context);
        }
        finally
        {
            this.unapply(context);
        }

        return result == null && scrollReleased ? this : result;
    }

    @Override
    protected IUIElement childMouseReleasedCaptured(
        UIContext context,
        IUIElement child,
        List<IUIElement> path,
        int childIndex
    )
    {
        boolean scrollReleased = this.scroll.tryMouseReleased(context);

        this.apply(context);
        IUIElement result;

        try
        {
            result = super.childMouseReleasedCaptured(context, child, path, childIndex);
        }
        finally
        {
            this.unapply(context);
        }

        return result == null && scrollReleased ? this : result;
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        return this.scroll.tryMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.scroll.cancelDragging(context.mouseButton);
        super.subMouseCanceled(context);
    }

    @Override
    protected IUIElement childrenKeyPressed(UIContext context)
    {
        this.apply(context);

        try
        {
            return super.childrenKeyPressed(context);
        }
        finally
        {
            this.unapply(context);
        }
    }

    @Override
    protected IUIElement childrenTextInput(UIContext context)
    {
        this.apply(context);

        try
        {
            return super.childrenTextInput(context);
        }
        finally
        {
            this.unapply(context);
        }
    }

    @Override
    public void render(UIContext context)
    {
        UIElement lastTooltip = context.tooltip.element;

        this.scroll.drag(context.mouseX, context.mouseY);

        context.batcher.clip(this.area, context);

        try
        {
            this.apply(context);

            try
            {
                this.preRender(context);
                super.render(context);
                this.postRender(context);
            }
            finally
            {
                this.unapply(context);
            }

            this.scroll.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
        }
        finally
        {
            context.batcher.unclip(context);
        }

        /* Clear tooltip in case if it was set outside of scroll area within the scroll */
        if (!this.area.isInside(context) && context.tooltip.element != lastTooltip)
        {
            context.tooltip.set(context, null);
        }
    }

    protected void preRender(UIContext context)
    {
        if (this.preRenderCallback != null)
        {
            this.preRenderCallback.accept(context);
        }
    }

    protected void postRender(UIContext context)
    {}
}
