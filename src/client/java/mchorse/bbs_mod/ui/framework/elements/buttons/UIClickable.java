package mchorse.bbs_mod.ui.framework.elements.buttons;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.UIUtils;

import java.util.function.Consumer;

public abstract class UIClickable <T> extends UIElement
{
    public Consumer<T> callback;

    private final MouseGestureOwnership pressOwnership = new MouseGestureOwnership();
    private long pressGeneration;
    private ProgrammaticPress programmaticPress;
    protected boolean hover;
    protected boolean pressed;

    public UIClickable(Consumer<T> callback)
    {
        super();

        this.callback = callback;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.isAllowed(context.mouseButton) && this.area.isInside(context))
        {
            this.startPress(context.mouseButton);

            return true;
        }

        return super.subMouseClicked(context);
    }

    private long startPress(int mouseButton)
    {
        long generation = this.pressOwnership.acquireToken(mouseButton);

        if (generation == 0L)
        {
            return 0L;
        }

        this.pressGeneration = generation;
        ProgrammaticPress programmatic = this.programmaticPress;

        if (programmatic != null && programmatic.button == mouseButton && programmatic.generation == 0L)
        {
            programmatic.generation = generation;
        }

        boolean clicked = false;

        try
        {
            this.pressed = true;
            this.playClickSound();
            this.click(mouseButton);
            clicked = true;

            return generation;
        }
        finally
        {
            if (!clicked)
            {
                this.finishPress(mouseButton, generation);
            }
        }
    }

    private boolean finishPress(int mouseButton, long generation)
    {
        if (!this.pressOwnership.release(mouseButton, generation))
        {
            return false;
        }

        this.pressGeneration = 0L;
        this.pressed = false;

        return true;
    }

    @Override
    public void clickItself(UIContext context, int mouseButton)
    {
        ProgrammaticPress previous = this.programmaticPress;
        ProgrammaticPress current = new ProgrammaticPress(mouseButton);
        int mouseX = context.mouseX;
        int mouseY = context.mouseY;
        int button = context.mouseButton;

        this.programmaticPress = current;
        try
        {
            super.clickItself(context, mouseButton);
        }
        finally
        {
            try
            {
                this.finishPress(mouseButton, current.generation);
            }
            finally
            {
                this.programmaticPress = previous;
                context.mouseX = mouseX;
                context.mouseY = mouseY;
                context.mouseButton = button;
            }
        }
    }

    protected boolean isAllowed(int mouseButton)
    {
        return mouseButton == 0;
    }

    protected void playClickSound()
    {
        UIUtils.playClick();
    }

    protected void click(int mouseButton)
    {
        if (this.callback != null)
        {
            this.callback.accept(this.get());
        }
    }

    protected abstract T get();

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        long generation = this.pressGeneration;

        this.finishPress(context.mouseButton, generation);

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.finishPress(context.mouseButton, this.pressGeneration);
    }

    @Override
    public void render(UIContext context)
    {
        this.hover = this.area.isInside(context);

        this.renderSkin(context);
        super.render(context);
    }

    protected abstract void renderSkin(UIContext context);

    private static final class ProgrammaticPress
    {
        private final int button;
        private long generation;

        private ProgrammaticPress(int button)
        {
            this.button = button;
        }
    }
}
