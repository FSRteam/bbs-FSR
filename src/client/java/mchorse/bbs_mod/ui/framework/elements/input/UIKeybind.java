package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.keys.KeyAction;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class UIKeybind extends UIElement
{
    public KeyCombo combo;
    public boolean reading;
    public Consumer<KeyCombo> callback;

    private boolean single;
    private boolean mouse;
    private boolean escape;

    private boolean first;
    private final MouseGestureOwnership mouseOwnership = new MouseGestureOwnership();
    private long mouseGeneration;

    public UIKeybind(Consumer<KeyCombo> callback)
    {
        super();

        this.combo = new KeyCombo(null, 0);
        this.combo.keys.clear();

        this.callback = callback;
        this.h(UIConstants.CONTROL_HEIGHT);
    }

    public UIKeybind single()
    {
        this.single = true;

        return this;
    }

    public UIKeybind mouse()
    {
        this.mouse = true;

        return this;
    }

    public UIKeybind escape()
    {
        this.escape = true;

        return this;
    }

    public void setKeyCombo(KeyCombo combo)
    {
        this.combo.copy(combo);

        if (this.single)
        {
            while (this.combo.keys.size() > 1)
            {
                this.combo.keys.remove(this.combo.keys.size() - 1);
            }
        }
    }

    private void finish()
    {
        this.reading = false;
        this.first = false;
        this.mouseOwnership.cancel();
        this.mouseGeneration = 0L;

        this.callback();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            long generation = this.mouseOwnership.acquireToken(context.mouseButton);

            if (generation == 0L)
            {
                return true;
            }

            this.mouseGeneration = generation;

            try
            {
                this.first = true;
                this.reading = true;
                this.combo.keys.clear();
                context.unfocus();
            }
            catch (RuntimeException | Error exception)
            {
                this.mouseOwnership.release(context.mouseButton, generation);
                this.mouseGeneration = 0L;
                this.first = false;
                this.reading = false;

                throw exception;
            }
        }
        else if (this.reading && this.mouse)
        {
            long generation = this.mouseOwnership.acquireToken(context.mouseButton);

            if (generation == 0L)
            {
                return true;
            }

            this.mouseGeneration = generation;
            int key = -context.mouseButton;

            try
            {
                if (!this.combo.keys.contains(key))
                {
                    if (this.single)
                    {
                        this.combo.keys.clear();
                    }

                    this.combo.keys.add(0, key);
                }
            }
            catch (RuntimeException | Error exception)
            {
                this.mouseOwnership.release(context.mouseButton, generation);
                this.mouseGeneration = 0L;

                throw exception;
            }

            return true;
        }

        return this.area.isInside(context);
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        long generation = this.mouseGeneration;

        if (this.first)
        {
            if (!this.mouseOwnership.release(context.mouseButton, generation))
            {
                return super.subMouseReleased(context);
            }

            this.mouseGeneration = 0L;
            this.first = false;

            return true;
        }
        else if (this.reading && this.mouse && this.mouseOwnership.release(context.mouseButton, generation))
        {
            this.mouseGeneration = 0L;
            this.finish();

            return true;
        }

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        long generation = this.mouseGeneration;

        if (this.mouseOwnership.release(context.mouseButton, generation))
        {
            this.mouseGeneration = 0L;
            this.first = false;
            this.reading = false;
        }

        super.subMouseCanceled(context);
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (this.reading)
        {
            if (!this.escape && context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                this.combo.keys.clear();
                this.finish();

                return true;
            }

            if (context.getKeyAction() == KeyAction.PRESSED)
            {
                int key = context.getKeyCode();

                if (!this.combo.keys.contains(key))
                {
                    if (this.single)
                    {
                        this.combo.keys.clear();
                    }

                    this.combo.keys.add(0, key);
                }
            }

            if (this.combo.keys.isEmpty())
            {
                return false;
            }

            for (int key : this.combo.keys)
            {
                if (Window.isKeyPressed(key))
                {
                    return true;
                }
            }

            this.finish();

            return true;
        }

        return super.subKeyPressed(context);
    }

    private void callback()
    {
        if (this.callback != null)
        {
            this.callback.accept(this.combo);
        }
    }

    @Override
    public void render(UIContext context)
    {
        String label = this.combo.keys.isEmpty() ? UIKeys.GENERAL_NONE.get() : this.combo.getKeyCombo();
        FontRenderer font = context.batcher.getFont();
        int w = font.getWidth(label) - 1;
        int radius = BBSSettings.cornerWidget();

        if (this.reading)
        {
            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, BBSSettings.inputSurface());
                context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, BBSSettings.accentOverlay(Colors.A25));
            }
            else
            {
                this.area.render(context.batcher, BBSSettings.inputSurface());
                this.area.render(context.batcher, BBSSettings.accentOverlay(Colors.A25));
            }

            int x = this.area.mx(w);
            int y = this.area.my() + font.getHeight() - 1;
            float a = (float) Math.sin(context.getTickTransition() / 2D);
            int c = Colors.setA(Colors.WHITE, a * 0.5F + 0.5F);

            context.batcher.box(x, y, x + w, y + 1, c);
        }
        else
        {
            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, BBSSettings.inputSurface());
            }
            else
            {
                this.area.render(context.batcher, BBSSettings.inputSurface());
            }
        }

        context.batcher.textShadow(label, this.area.mx(w), this.area.my() - font.getHeight() / 2);

        if (radius > 0)
        {
            this.renderLockedArea(context);
        }

        super.render(context);
    }
}
