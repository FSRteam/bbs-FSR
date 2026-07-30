package mchorse.bbs_mod.ui.framework.elements.input;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.lwjgl.glfw.GLFW;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.MathBuilder;
import mchorse.bbs_mod.settings.values.numeric.ValueDouble;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragEndEvent;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragStartEvent;
import mchorse.bbs_mod.ui.framework.elements.input.text.UIBaseTextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Factor;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;

public class UITrackpad extends UIBaseTextbox
{
    private static final Set<Character> allowedNumberCharacters = ".-+/*^%() ".chars()
        .mapToObj((o) -> (char) o)
        .collect(Collectors.toSet());
    private static final Factor globalFactor = new Factor(20, 1, 40, (x) ->
    {
        if (x <= 10) return x / 100D;
        else if (x <= 20) return (x - 10) / 10D;
        else if (x <= 30) return (x - 20) / 1D;

        return (x - 30) * 10D;
    });

    private static final DecimalFormat FORMAT;

    public Consumer<Double> callback;

    protected double value;

    /* Trackpad options */
    public double strong = 1D;
    public double normal = 0.25D;
    public double weak = 0.05D;
    public double increment = 1D;
    public double min = Float.NEGATIVE_INFINITY;
    public double max = Float.POSITIVE_INFINITY;
    public boolean integer;
    public boolean delayedInput;
    public boolean onlyNumbers;

    public boolean relative;
    public boolean allowCanceling = true;
    public IKey forcedLabel;

    /* Value dragging fields */
    private boolean wasInside;
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;
    private int shiftX;
    private int initialX;
    private int initialY;
    private double lastValue;

    private Timer changed = new Timer(30);

    private long time;
    private double focusStartValue;
    private Area plusOne = new Area();
    private Area minusOne = new Area();

    static
    {
        FORMAT = new DecimalFormat("#.###");
        FORMAT.setRoundingMode(RoundingMode.HALF_EVEN);
        FORMAT.setGroupingUsed(false);
        FORMAT.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.ENGLISH));
    }

    public static void updateAmplifier(UIContext context)
    {
        if (context.mouseWheel == 0D)
        {
            return;
        }

        globalFactor.addX(context.mouseWheel > 0D ? 1 : -1);
        context.notifyOrUpdate(UIKeys.TRACKPAD_GLOBAL_AMPLIFIER.format(globalFactor.getValue()), Colors.BLUE);
    }

    public static String format(double number)
    {
        return FORMAT.format(number).replace(',', '.');
    }

    public UITrackpad()
    {
        this(null);
    }

    public UITrackpad(Consumer<Double> callback)
    {
        super();

        this.callback = callback;

        this.setValue(0);
        this.h(UIConstants.CONTROL_HEIGHT);
    }

    public UITrackpad max(double max)
    {
        this.max = max;

        return this;
    }

    public UITrackpad limit(double min)
    {
        this.min = min;

        return this;
    }

    public UITrackpad limit(double min, double max)
    {
        this.min = min;
        this.max = max;

        return this;
    }

    public UITrackpad limit(ValueInt value)
    {
        return this.limit(value.getMin(), value.getMax(), true);
    }

    public UITrackpad limit(ValueFloat value)
    {
        return this.limit(value.getMin(), value.getMax(), false);
    }

    public UITrackpad limit(ValueDouble value)
    {
        return this.limit(value.getMin(), value.getMax(), false);
    }

    public UITrackpad limit(double min, double max, boolean integer)
    {
        this.integer = integer;

        return this.limit(min, max);
    }

    public UITrackpad integer()
    {
        this.integer = true;

        return this;
    }

    public UITrackpad increment(double increment)
    {
        this.increment = increment;

        return this;
    }

    public UITrackpad values(double normal)
    {
        this.normal = normal;
        this.weak = normal / 5F;
        this.strong = normal * 5F;

        return this;
    }

    public UITrackpad values(double normal, double weak, double strong)
    {
        this.normal = normal;
        this.weak = weak;
        this.strong = strong;

        return this;
    }

    public UITrackpad delayedInput()
    {
        this.delayedInput = true;

        return this;
    }

    public UITrackpad onlyNumbers()
    {
        this.onlyNumbers = true;

        return this;
    }

    public UITrackpad relative(boolean relative)
    {
        this.relative = relative;

        return this;
    }

    public UITrackpad forcedLabel(IKey label)
    {
        this.forcedLabel = label;

        return this;
    }

    public UITrackpad disableCanceling()
    {
        this.allowCanceling = false;

        return this;
    }

    /* Values presets */

    public UITrackpad degrees()
    {
        return this.increment(15D).values(1D, 0.1D, 5D  );
    }

    public UITrackpad block()
    {
        return this.increment(1 / 16D).values(1 / 32D, 1 / 128D, 1 / 2D);
    }

    public UITrackpad metric()
    {
        return this.values(0.1D, 0.01D, 1);
    }

    /**
     * Whether this trackpad is dragging
     */
    public boolean isDragging()
    {
        return this.dragOwnership.isActive();
    }

    public boolean isDraggingTime()
    {
        return this.isDragging() && System.currentTimeMillis() - this.time > 150;
    }

    public double getValue()
    {
        return this.value;
    }

    /**
     * Set the value of the field. The input value would be rounded up to 3
     * decimal places.
     */
    public void setValue(double value)
    {
        this.setValueInternal(value);

        if (!this.textbox.isFocused())
        {
            this.updateTextField();
        }
    }

    private void updateTextField()
    {
        if (Window.isAltPressed())
        {
            this.textbox.setText(this.integer ? String.valueOf((int) this.value) : String.valueOf(this.value));
        }
        else
        {
            this.textbox.setText(this.integer ? format((int) this.value) : format(this.value));
        }
    }

    private void setValueInternal(double value)
    {
        value = MathUtils.clamp(value, this.min, this.max);

        if (this.integer)
        {
            value = (int) value;
        }

        this.value = value;
    }

    /**
     * Set value of this field and also notify the trackpad listener so it
     * could detect the value change.
     */
    public void setValueAndNotify(double value)
    {
        double oldValue = this.value;

        this.setValue(value);
        this.accept(this.value, oldValue);
    }

    /** User commands must keep a focused textbox and its committed value in sync. */
    private void setUserValueAndNotify(double value)
    {
        this.setValueAndNotify(value);

        if (this.textbox.isFocused())
        {
            this.updateTextField();
            this.focusStartValue = this.value;
        }
    }

    private void accept(double value, double oldValue)
    {
        if (this.callback != null)
        {
            this.callback.accept(this.relative ? value - oldValue : this.value);
        }
    }

    @Override
    public void focus(UIContext context)
    {
        super.focus(context);

        this.focusStartValue = this.value;

        this.updateTextField();
        this.textbox.setFocused(true);
        this.textbox.moveCursorToEnd();
    }

    @Override
    public void unfocus(UIContext context)
    {
        this.evaluate();

        super.unfocus(context);

        this.textbox.setFocused(false);

        /* Reset the value in case it's out of range */
        if (this.delayedInput && Double.compare(this.value, this.focusStartValue) != 0)
        {
            this.accept(this.value, this.focusStartValue);
        }

        this.setValue(this.value);
    }

    /**
     * Update the bounding box of this GUI field
     */
    @Override
    public void resize()
    {
        super.resize();

        int w = this.area.w < 60 ? 12 : 20;

        this.textbox.area.copy(this.area);
        this.plusOne.copy(this.area);
        this.minusOne.copy(this.area);
        this.plusOne.w = this.minusOne.w = w;
        this.plusOne.x = this.area.ex() - w;
    }

    /**
     * Delegates mouse click to text field and initiate value dragging if the
     * cursor inside of trackpad's bounding box.
     */
    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 2 && !this.isDragging() && this.area.isInside(context))
        {
            this.setUserValueAndNotify(-this.value);

            return true;
        }

        if (context.mouseButton == 0)
        {
            this.wasInside = this.area.isInside(context);

            if (this.textbox.isFocused())
            {
                this.textbox.mouseClicked(context.mouseX, context.mouseY, context.mouseButton);

                if (!this.textbox.isFocused())
                {
                    context.focus(null);
                }
            }

            if (this.wasInside && !this.textbox.isFocused() && !this.isDragging())
            {
                if (Window.isCtrlPressed())
                {
                    this.setValueAndNotify(Math.round(this.value));
                    this.wasInside = false;

                    return true;
                }

                this.dragGeneration = this.dragOwnership.acquireToken(context.mouseButton);

                if (this.dragGeneration == 0L)
                {
                    return true;
                }

                this.initialX = context.mouseX;
                this.initialY = context.mouseY;
                this.lastValue = this.value;
                this.time = System.currentTimeMillis();

                try
                {
                    this.getEvents().emit(new UITrackpadDragStartEvent(this));
                }
                catch (RuntimeException | Error exception)
                {
                    this.dragOwnership.release(context.mouseButton, this.dragGeneration);
                    this.dragGeneration = 0L;
                    this.wasInside = false;

                    throw exception;
                }
            }
        }

        return context.mouseButton == 0 && this.wasInside;
    }

    /**
     * Reset value dragging
     */
    @Override
    public boolean subMouseReleased(UIContext context)
    {
        boolean wasDragging = this.isDragging();
        long releasedGeneration = this.dragGeneration;

        if (wasDragging && !this.dragOwnership.isOwnedBy(context.mouseButton, releasedGeneration))
        {
            return false;
        }

        boolean wasDraggingTime = this.isDraggingTime();
        boolean pressWasInside = this.wasInside;

        if (wasDragging)
        {
            this.dragOwnership.release(context.mouseButton, releasedGeneration);
            this.dragGeneration = 0L;
        }

        this.wasInside = false;
        this.shiftX = 0;

        this.textbox.mouseReleased(context.mouseX, context.mouseY, context.mouseButton);

        if (context.mouseButton == 0 && !wasDraggingTime && !this.textbox.isFocused())
        {
            if (pressWasInside)
            {
                if (this.plusOne.isInside(context))
                {
                    this.setValueAndNotify(this.value + this.increment);
                }
                else if (this.minusOne.isInside(context))
                {
                    this.setValueAndNotify(this.value - this.increment);
                }
                else
                {
                    context.focus(this);
                }
            }
        }

        if (this.delayedInput && wasDraggingTime)
        {
            this.accept(this.value, this.lastValue);
        }

        if (wasDragging)
        {
            this.getEvents().emit(new UITrackpadDragEndEvent(this));
        }

        return wasDragging || super.subMouseReleased(context);
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        if (this.dragOwnership.isOwnedBy(context.mouseButton, this.dragGeneration))
        {
            this.cancelDragging();
        }

        super.subMouseCanceled(context);
    }

    private boolean cancelDragging()
    {
        if (!this.isDragging())
        {
            return false;
        }

        long generation = this.dragGeneration;

        this.dragOwnership.release(0, generation);
        this.dragGeneration = 0L;
        this.wasInside = false;
        this.shiftX = 0;

        if (this.delayedInput)
        {
            this.setValue(this.lastValue);
        }
        else
        {
            this.setValueAndNotify(this.lastValue);
        }

        return true;
    }

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        Area area = new Area();
        int w = this.area.w / 2;

        area.copy(this.area);
        area.x = area.mx() - w / 2;
        area.w = w;

        if (this.isDragging() && context.mouseWheel != 0D)
        {
            updateAmplifier(context);

            return true;
        }
        else if (context.mouseWheel != 0D && area.isInside(context)
            && context.hasNotScrolledForMore(500) && BBSSettings.enableTrackpadScrolling.get())
        {
            if (context.mouseWheel > 0)
            {
                this.setUserValueAndNotify(this.value + this.getValueModifier());
            }
            else
            {
                this.setUserValueAndNotify(this.value - this.getValueModifier());
            }

            return true;
        }

        return super.subMouseScrolled(context);
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (this.allowCanceling && this.isDragging() && context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            return this.cancelDragging();
        }

        if (this.isFocused())
        {
            if (context.isHeld(GLFW.GLFW_KEY_UP))
            {
                this.setUserValueAndNotify(this.value + this.getValueModifier());

                return true;
            }
            else if (context.isHeld(GLFW.GLFW_KEY_DOWN))
            {
                this.setUserValueAndNotify(this.value - this.getValueModifier());

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_TAB))
            {
                context.focus(this, Window.isShiftPressed() ? -1 : 1);

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                context.unfocus();

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ENTER))
            {
                context.focus(null);
            }
        }
        else if (this.area.isInside(context))
        {
            if (!context.isFocused() && !Window.isCtrlPressed() && (context.isPressed(GLFW.GLFW_KEY_MINUS) || context.isPressed(GLFW.GLFW_KEY_KP_SUBTRACT)))
            {
                this.setUserValueAndNotify(-this.value);

                return true;
            }
        }

        String old = this.textbox.getText();
        boolean result = this.textbox.keyPressed(context);
        String text = this.textbox.getText();

        if (this.textbox.isFocused() && !text.equals(old))
        {
            try
            {
                double oldValue = this.value;

                this.setValueInternal(text.isEmpty() ? 0 : Double.parseDouble(text));

                if (!this.delayedInput)
                {
                    this.accept(this.value, oldValue);
                }
            }
            catch (Exception e)
            {}
        }

        return result;
    }

    private boolean evaluate()
    {
        String text = this.textbox.getText().trim();

        try
        {
            Float.parseFloat(text);

            return false;
        }
        catch (Exception e)
        {}

        try
        {
            MathBuilder builder = new MathBuilder();

            double oldValue = this.value;

            this.setValue(builder.parse(text).get().doubleValue());
            this.textbox.moveCursorToEnd();

            if (!this.delayedInput)
            {
                this.accept(this.value, oldValue);
            }

            return true;
        }
        catch (Exception e)
        {}

        return false;
    }

    @Override
    public boolean subTextInput(UIContext context)
    {
        char inputCharacter = context.getInputCharacter();

        if (this.onlyNumbers && this.isFocused() && !this.numberCharacterAllowed(inputCharacter))
        {
            context.unfocus();

            return false;
        }

        String old = this.textbox.getText();
        boolean result = this.textbox.textInput(inputCharacter);
        String text = this.textbox.getText();

        if (this.textbox.isFocused() && !text.equals(old))
        {
            try
            {
                double oldValue = this.value;

                this.setValueInternal(text.isEmpty() ? 0 : Double.parseDouble(text));

                if (!this.delayedInput)
                {
                    this.accept(this.value, oldValue);
                }
            }
            catch (Exception e)
            {}
        }

        return result;
    }

    private boolean numberCharacterAllowed(char character)
    {
        return Character.isDigit(character) || allowedNumberCharacters.contains(character);
    }

    /**
     * Draw the trackpad
     *
     * This method will not only render the text box, background and title label,
     * but also dragging the numerical value based on the mouse input.
     */
    @Override
    public void render(UIContext context)
    {
        int x = this.area.x;
        int y = this.area.y;
        int w = this.area.w;
        int h = this.area.h;
        int padding = 0;

        boolean dragging = this.isDraggingTime();
        boolean plus = !dragging && this.plusOne.isInside(context);
        boolean minus = !dragging && this.minusOne.isInside(context);
        int radius = BBSSettings.cornerWidget();

        if (this.isEnabled() && (this.textbox.isFocused() || (!dragging && this.area.isInside(context))))
        {
            context.requestCursor(GLFW.GLFW_IBEAM_CURSOR);
        }

        if (this.textbox.isFocused())
        {
            this.textbox.render(context);

            if (radius <= 0)
            {
                context.batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), Colors.opaque(BBSSettings.accentColorRGB()));
            }
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

            if (dragging)
            {
                /* Draw filling background */
                int scrub = BBSSettings.trackpadScrubColor();
                int color = scrub == 0 ? Colors.A100 | BBSSettings.accentColorRGB() : scrub;
                int fx = MathUtils.clamp(context.mouseX, this.area.x + padding, this.area.ex() - padding);
                int x1 = Math.min(fx, this.initialX);
                int x2 = Math.max(fx, this.initialX);

                if (radius > 0)
                {
                    context.batcher.roundedBox(x1, this.area.y + padding, x2 - x1, this.area.h - padding * 2, radius, color);
                }
                else
                {
                    context.batcher.box(x1, this.area.y + padding, x2, this.area.ey() - padding, color);
                }
            }

            FontRenderer font = context.batcher.getFont();
            String label = this.forcedLabel == null ? format(this.value) : this.forcedLabel.get();
            int lx = this.area.mx(font.getWidth(label));
            int ly = this.area.my() - font.getHeight() / 2;

            context.batcher.text(label, lx, ly, this.textbox.getColor());

            if (BBSSettings.enableTrackpadIncrements.get() || this.area.isInside(context))
            {
                int plusColor = plus ? 0x22ffffff : 0x0affffff;
                int minusColor = minus ? 0x22ffffff : 0x0affffff;

                if (radius > 0)
                {
                    this.renderIncrementZone(context, this.minusOne, minusColor, true, padding, radius);
                    this.renderIncrementZone(context, this.plusOne, plusColor, false, padding, radius);
                }
                else
                {
                    this.plusOne.render(context.batcher, plusColor, padding);
                    this.minusOne.render(context.batcher, minusColor, padding);
                }

                context.batcher.icon(Icons.MOVE_LEFT, minus ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.5F), x + (this.plusOne.w - Icons.MOVE_LEFT.w) / 2, y + (h - 16) / 2);
                context.batcher.icon(Icons.MOVE_RIGHT, plus ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.5F), x + w - this.minusOne.w + (this.minusOne.w - Icons.MOVE_RIGHT.w) / 2, y + (h - 16) / 2);
            }
        }

        if (dragging)
        {
            Minecraft mc = Minecraft.getInstance();
            int ww = mc.getWindow().getScreenWidth();

            double factor = Math.ceil(ww / (double) context.menu.width);
            int mouseX = context.globalX(context.mouseX);

            /* Mouse doesn't change immediately the next frame after Mouse.setCursorPosition(),
             * so this is a hack that stops for double shifting */
            if (this.changed.isTime())
            {
                final int border = 5;
                final int borderPadding = border + 1;
                boolean stop = false;

                boolean wrapCursor = !BBSUiRemoteHeldState.isActive();

                if (wrapCursor && mouseX <= border)
                {
                    Window.moveCursor(ww - (int) (factor * borderPadding), (int) mc.mouseHandler.ypos());

                    this.shiftX -= context.menu.width - borderPadding * 2;
                    this.changed.mark();
                    stop = true;
                }
                else if (wrapCursor && mouseX >= context.menu.width - border)
                {
                    Window.moveCursor((int) (factor * borderPadding), (int) mc.mouseHandler.ypos());

                    this.shiftX += context.menu.width - borderPadding * 2;
                    this.changed.mark();
                    stop = true;
                }

                if (!stop)
                {
                    if (this.isFocused())
                    {
                        context.unfocus();
                    }

                    int dx = (this.shiftX + context.mouseX) - this.initialX;

                    if (dx != 0)
                    {
                        double value = this.getValueModifier();

                        double diff = (Math.abs(dx) - 3) * value;
                        double newValue = this.lastValue + (dx < 0 ? -diff : diff);

                        newValue = diff < 0 ? this.lastValue : newValue;

                        if (this.value != newValue)
                        {
                            if (this.delayedInput)
                            {
                                this.setValue(newValue);
                            }
                            else
                            {
                                this.setValueAndNotify(newValue);
                            }
                        }
                    }
                }
            }

            /* Draw active element */
            context.batcher.outlineCenter(this.initialX, this.initialY, 4, Colors.WHITE);
        }

        this.renderLockedArea(context);

        super.render(context);
    }

    private void renderIncrementZone(UIContext context, Area zone, int color, boolean left, int padding, float radius)
    {
        float x = zone.x + padding;
        float y = zone.y + padding;
        float w = zone.w - padding * 2F;
        float h = zone.h - padding * 2F;

        if (w <= 0F || h <= 0F)
        {
            return;
        }

        float r = Math.max(0.5F, Math.min(radius, Math.min(w, h) / 2F));
        float capWidth = Math.min(w, Math.max(1F, r * 2F));
        int clipWidth = Math.max(1, (int) Math.ceil(Math.min(r, w)));

        if (left)
        {
            context.batcher.clip((int) x, (int) y, clipWidth, Math.max(1, (int) h), context);
            context.batcher.roundedBox(x, y, capWidth, h, r, color);
            context.batcher.unclip(context);

            if (clipWidth < w)
            {
                context.batcher.box(x + clipWidth, y, x + w, y + h, color);
            }
        }
        else
        {
            int bodyWidth = Math.max(0, (int) w - clipWidth);
            float capX = x + w - capWidth;

            context.batcher.clip((int) x + bodyWidth, (int) y, clipWidth, Math.max(1, (int) h), context);
            context.batcher.roundedBox(capX, y, capWidth, h, r, color);
            context.batcher.unclip(context);

            if (bodyWidth > 0)
            {
                context.batcher.box(x, y, x + bodyWidth, y + h, color);
            }
        }
    }

    public double getValueModifier()
    {
        double value = this.normal;

        if (Window.isShiftPressed())
        {
            value = this.strong;
        }
        else if (Window.isAltPressed())
        {
            value = this.weak;
        }
        else if (Window.isCtrlPressed())
        {
            value = this.increment;
        }

        return value * globalFactor.getValue();
    }
}
