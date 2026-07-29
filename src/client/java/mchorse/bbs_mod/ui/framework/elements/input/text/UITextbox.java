package mchorse.bbs_mod.ui.framework.elements.input.text;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.ITextColoring;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.Patterns;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * GUI text element
 * 
 * This element is a wrapper for the text field class
 */
public class UITextbox extends UIBaseTextbox implements ITextColoring
{
    private static final int ICON_BAR_INSET = 2;

    public static final Predicate<String> FILENAME_PREDICATE = (s) -> Patterns.FILENAME.matcher(s).find();
    public static final Predicate<String> PATH_PREDICATE = (s) -> Patterns.PATH.matcher(s).find();

    public Consumer<String> callback;

    private boolean delayedInput;
    private Icon leadingIcon;
    private int iconBarColor;

    public UITextbox()
    {
        this(null);
    }

    public UITextbox(Consumer<String> callback)
    {
        super();

        this.callback = callback;

        this.border().h(UIConstants.CONTROL_HEIGHT);
    }

    public UITextbox(int maxLength, Consumer<String> callback)
    {
        this(callback);

        this.textbox.setLength(maxLength);
    }

    public UITextbox filename()
    {
        return this.validator(FILENAME_PREDICATE);
    }

    public UITextbox path()
    {
        return this.validator(PATH_PREDICATE);
    }

    public UITextbox validator(Predicate<String> validator)
    {
        this.textbox.setValidator(validator);

        return this;
    }

    public UITextbox background(boolean background)
    {
        this.textbox.setBackground(background);
        this.resize();

        return this;
    }

    public UITextbox placeholder(IKey placeholder)
    {
        this.textbox.setPlaceholder(placeholder);

        return this;
    }

    public UITextbox border()
    {
        this.textbox.setBorder(true);

        return this;
    }

    public UITextbox noBorder()
    {
        this.textbox.setBorder(false);

        return this;
    }

    public UITextbox delayedInput()
    {
        this.delayedInput = true;

        return this;
    }

    public UITextbox icon(Icon icon)
    {
        this.leadingIcon = icon;
        this.resize();

        return this;
    }

    public UITextbox barColor(int color)
    {
        this.iconBarColor = color | Colors.A100;

        return this;
    }

    public void setText(String text)
    {
        if (text == null)
        {
            text = "";
        }

        this.textbox.setText(text);
        this.textbox.moveCursorToStart();
    }

    @Override
    protected void userInput(String string)
    {
        if (this.callback != null && !this.delayedInput)
        {
            this.callback.accept(string);
        }
    }

    @Override
    public void unfocus(UIContext context)
    {
        super.unfocus(context);

        if (this.callback != null && this.delayedInput)
        {
            this.callback.accept(this.textbox.getText());
        }
    }

    @Override
    public void setColor(int color, boolean shadow)
    {
        this.textbox.setColor(color);
    }

    @Override
    public void resize()
    {
        super.resize();

        this.textbox.area.copy(this.area);
        this.textbox.setContentInset(this.leadingIcon == null ? 0 : this.area.h + 3);

        if (!this.textbox.hasBackground())
        {
            int h = this.textbox.getFont().getHeight();

            this.textbox.area.x += 4;
            this.textbox.area.y += ((this.area.h - h) / 2);
            this.textbox.area.w -= 8;
            this.textbox.area.h = h;
        }
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        boolean wasFocused = this.textbox.isFocused();

        this.textbox.mouseClicked(context.mouseX, context.mouseY, context.mouseButton);

        if (wasFocused != this.textbox.isFocused())
        {
            context.focus(wasFocused ? null : this);
        }

        return context.mouseButton == 0 && this.area.isInside(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        this.textbox.mouseReleased(context.mouseX, context.mouseY, context.mouseButton);

        return super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.textbox.mouseCanceled(context.mouseButton);

        super.subMouseCanceled(context);
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (this.isFocused())
        {
            if (context.isPressed(GLFW.GLFW_KEY_TAB))
            {
                context.focus(this, Window.isShiftPressed() ? -1 : 1);

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                context.unfocus();

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ENTER) && this.delayedInput)
            {
                if (this.callback != null)
                {
                    this.callback.accept(this.textbox.getText());
                }

                return true;
            }
        }

        return this.textbox.keyPressed(context);
    }

    @Override
    protected boolean subTextInput(UIContext context)
    {
        return this.isFocused() && this.textbox.textInput(context.getInputCharacter());
    }

    @Override
    public void render(UIContext context)
    {
        this.requestTextCursor(context);
        this.textbox.render(context);

        if (this.leadingIcon != null && this.textbox.hasBackground())
        {
            this.renderLeadingIcon(context);
        }

        this.renderLockedArea(context);

        super.render(context);
    }

    private void renderLeadingIcon(UIContext context)
    {
        int barX = Math.min(this.area.ex() - 1, this.area.x + this.area.h + 2);
        int fill = BBSSettings.fieldFillColor();
        int bar = this.iconBarColor == 0 ? BBSSettings.primaryColor(Colors.A100) : this.iconBarColor;

        if (fill == 0)
        {
            fill = BBSSettings.chromeSurface();
        }

        if (barX > this.area.x + 1 && this.area.h > 2)
        {
            int radius = BBSSettings.cornerWidget();

            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x + 1, this.area.y + 1, barX - this.area.x - 1, this.area.h - 2, Math.max(0, radius - 1), fill);
            }
            else
            {
                context.batcher.box(this.area.x + 1, this.area.y + 1, barX, this.area.ey() - 1, fill);
            }

            context.batcher.box(barX, this.area.y + ICON_BAR_INSET, barX + 1, this.area.ey() - ICON_BAR_INSET, bar);
        }

        int iconColor = this.isEnabled() ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.35F);

        context.batcher.icon(this.leadingIcon, iconColor, this.area.x + this.area.h / 2, this.area.my(), 0.5F, 0.5F);
    }
}
