package mchorse.bbs_mod.ui.framework.elements.input;

import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * A numeric field for a value with both ends: instead of a relative drag it
 * lays the value out along a track, so where you are within the range is
 * visible at a glance.
 *
 * Pressing the track puts the value under the cursor at once and keeps it there
 * for the rest of the drag, while pressing the handle itself grabs it where it
 * stands. Since the left button is spent on that, the value is typed by hand
 * through the middle button instead, which brings it up selected. Modifiers
 * refine the travel rather than scale a step: shift slows it down, alt slows it
 * further, and ctrl snaps onto {@link #increment}. Arrow keys and the wheel
 * still move by the step fields.
 *
 * Given no finite limits there is no track to travel along, so the drag falls
 * back to a trackpad's relative one rather than leaving the element inert.
 */
public class UISliderTrackpad extends UINumericInput<UISliderTrackpad>
{
    private static final float VALUE_ALPHA = 0.75F;
    private static final float DRAG_VALUE_ALPHA = 0.92F;
    private static final float HANDLE_ALPHA = 0.8F;
    private static final float HANDLE_HOVER_ALPHA = 0.95F;
    private static final float MARKER_ALPHA = 0.55F;

    /** How much travel the modifiers shave off a positional drag. */
    private static final double SLOW_DRAG = 0.25D;
    private static final double PRECISE_DRAG = 0.05D;

    /** Dead zone of an unbounded drag, matching {@link UITrackpad}'s. */
    private static final int DRAG_THRESHOLD = 3;

    protected final Area handleArea = new Area();

    protected boolean wasInside;
    protected boolean dragging;
    private final MouseGestureOwnership dragOwnership = new MouseGestureOwnership();
    private long dragGeneration;
    protected int initialX;

    /** The value the gesture began with, restored when it gets cancelled. */
    protected double startValue;

    /** The value the drag travels from — a press on the track moves it under the cursor. */
    protected double anchorValue;

    public UISliderTrackpad()
    {
        this(null);
    }

    public UISliderTrackpad(Consumer<Double> callback)
    {
        super(callback);
    }

    @Override
    public boolean isDragging()
    {
        return this.dragging && this.dragOwnership.isActive();
    }

    /* Geometry */

    /**
     * Whether the value has both ends, i.e. whether it can be laid out along a
     * track at all.
     */
    protected boolean hasSliderRange()
    {
        return Double.isFinite(this.min) && Double.isFinite(this.max) && this.max > this.min;
    }

    protected int getHandleWidth()
    {
        return Math.min(Math.max(this.area.h / 3, 6), 10);
    }

    protected int getHandlePadding()
    {
        return this.getHandleWidth() / 2;
    }

    protected int getTrackWidth()
    {
        return Math.max(this.area.w - this.getHandlePadding() * 2, 1);
    }

    protected float getProgress()
    {
        if (!this.hasSliderRange())
        {
            return 0F;
        }

        return (float) MathUtils.clamp((this.value - this.min) / (this.max - this.min), 0D, 1D);
    }

    protected int getHandleCenter()
    {
        int handleMinX = this.area.x + this.getHandlePadding();

        return handleMinX + Math.round(this.getTrackWidth() * this.getProgress());
    }

    protected void updateHandleArea()
    {
        if (!this.hasSliderRange())
        {
            this.handleArea.set(this.area.x, this.area.y, 0, this.area.h);

            return;
        }

        int handleWidth = this.getHandleWidth();
        int handleCenter = this.getHandleCenter();

        this.handleArea.set(handleCenter - handleWidth / 2, this.area.y, handleWidth, this.area.h);
    }

    /* Dragging */

    /**
     * The value the given spot on the track stands for.
     */
    protected double getValueFromMouse(int mouseX)
    {
        int left = this.area.x + this.getHandlePadding();
        double factor = MathUtils.clamp((mouseX - left) / (double) this.getTrackWidth(), 0D, 1D);

        return this.min + factor * (this.max - this.min);
    }

    /**
     * The value the current drag has travelled to. Bounded drags map the
     * cursor's travel onto the track, unbounded ones multiply it by the step,
     * the way a trackpad does.
     */
    protected double getDraggedValue(int mouseX)
    {
        int dx = mouseX - this.initialX;

        if (!this.hasSliderRange())
        {
            double diff = (Math.abs(dx) - DRAG_THRESHOLD) * this.getValueModifier();

            return diff < 0D ? this.anchorValue : this.anchorValue + (dx < 0 ? -diff : diff);
        }

        double value = this.anchorValue + (dx / (double) this.getTrackWidth()) * (this.max - this.min) * this.getDragPrecision();

        if (Window.isCtrlPressed() && this.increment > 0D)
        {
            value = Math.round(value / this.increment) * this.increment;
        }

        return value;
    }

    protected double getDragPrecision()
    {
        if (Window.isAltPressed())
        {
            return PRECISE_DRAG;
        }
        else if (Window.isShiftPressed())
        {
            return SLOW_DRAG;
        }

        return 1D;
    }

    /**
     * Move the value, but only when it actually moves — otherwise a drag held
     * still would write it every single frame.
     */
    protected void applyValue(double value)
    {
        if (this.value == this.normalize(value))
        {
            return;
        }

        if (this.delayedInput)
        {
            this.setValue(value);
        }
        else
        {
            this.setValueAndNotify(value);
        }
    }

    protected void updateDragging(int mouseX)
    {
        this.applyValue(this.getDraggedValue(mouseX));
    }

    protected void beginDragging(UIContext context)
    {
        this.dragGeneration = this.dragOwnership.acquireToken(context.mouseButton);

        if (this.dragGeneration == 0L)
        {
            return;
        }

        this.dragging = true;
        this.initialX = context.mouseX;
        this.startValue = this.value;

        /* Grabbing the handle takes the value as it stands, pressing anywhere
         * else on the track puts it under the cursor first */
        if (this.hasSliderRange() && !this.handleArea.isInside(context))
        {
            this.applyValue(this.getValueFromMouse(context.mouseX));
        }

        this.anchorValue = this.value;

        try
        {
            this.emitDragStart();
        }
        catch (RuntimeException | Error exception)
        {
            this.stopDragging();
            this.setValue(this.startValue);

            throw exception;
        }
    }

    protected void stopDragging()
    {
        this.dragOwnership.release(0, this.dragGeneration);
        this.dragGeneration = 0L;
        this.dragging = false;
        this.wasInside = false;
    }

    protected void cancelDragging()
    {
        if (!this.isDragging())
        {
            return;
        }

        double restoreValue = this.startValue;

        this.stopDragging();
        this.setValueAndNotify(restoreValue);
    }

    /* Input */

    @Override
    public void resize()
    {
        super.resize();
        this.updateHandleArea();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        /* The left button belongs to the track, so typing the value by hand
         * lives on the middle one. The number comes up selected: reaching for
         * this button means replacing it, not editing a digit of it */
        if (context.mouseButton == 2 && !this.isDragging() && this.area.isInside(context))
        {
            context.focus(this);
            this.selectAll(context);

            return true;
        }

        if (context.mouseButton == 0)
        {
            this.wasInside = this.area.isInside(context);
            this.updateHandleArea();

            if (this.textbox.isFocused())
            {
                if (this.wasInside)
                {
                    /* The track owns the left button even while the value is
                     * being typed — submit the text and take the click */
                    context.focus(null);
                }
                else
                {
                    this.textbox.mouseClicked(context.mouseX, context.mouseY, context.mouseButton);

                    if (!this.textbox.isFocused())
                    {
                        context.focus(null);
                    }
                }
            }

            if (this.wasInside && !this.textbox.isFocused() && !this.isDragging())
            {
                this.beginDragging(context);
            }
        }

        return context.mouseButton == 0 && this.wasInside;
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        boolean wasDragging = this.isDragging();
        long releasedGeneration = this.dragGeneration;

        if (wasDragging && !this.dragOwnership.isOwnedBy(context.mouseButton, releasedGeneration))
        {
            return false;
        }

        if (wasDragging)
        {
            this.stopDragging();
        }

        this.textbox.mouseReleased(context.mouseX, context.mouseY, context.mouseButton);

        /* Not gated on drag time like a trackpad's: a press alone already moves
         * the value here, so even the shortest click has something to submit */
        if (this.delayedInput && wasDragging)
        {
            this.accept(this.value, this.startValue);
        }

        if (wasDragging)
        {
            this.emitDragEnd();
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

    @Override
    protected boolean subMouseScrolled(UIContext context)
    {
        if (this.isDragging() && context.mouseWheel != 0D)
        {
            updateAmplifier(context);

            return true;
        }

        if (context.mouseWheel != 0D && this.area.isInside(context)
            && context.hasNotScrolledForMore(500) && BBSSettings.enableTrackpadScrolling.get())
        {
            if (context.mouseWheel > 0)
            {
                this.setValueAndNotify(this.value + this.getValueModifier());
            }
            else
            {
                this.setValueAndNotify(this.value - this.getValueModifier());
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
            this.cancelDragging();

            return true;
        }

        return super.subKeyPressed(context);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.isDragging())
        {
            if (this.isFocused())
            {
                context.unfocus();
            }

            this.updateDragging(context.mouseX);
        }

        this.updateHandleArea();

        if (this.textbox.isFocused())
        {
            this.textbox.render(context);

            if (BBSSettings.cornerWidget() <= 0)
            {
                context.batcher.box(this.area.x, this.area.ey() - 1, this.area.ex(), this.area.ey(), Colors.opaque(BBSSettings.accentColorRGB()));
            }
        }
        else
        {
            int primary = BBSSettings.accentColorRGB();
            int fillX = MathUtils.clamp(this.getHandleCenter(), this.area.x, this.area.ex());
            int fillColor = Colors.setA(primary, this.dragging ? DRAG_VALUE_ALPHA : VALUE_ALPHA);
            int handleColor = this.dragging ? Colors.WHITE : Colors.setA(Colors.WHITE, this.handleArea.isInside(context) ? HANDLE_HOVER_ALPHA : HANDLE_ALPHA);
            int radius = BBSSettings.cornerWidget();

            if (radius > 0)
            {
                context.batcher.roundedBox(this.area.x, this.area.y, this.area.w, this.area.h, radius, BBSSettings.inputSurface());
            }
            else
            {
                this.area.render(context.batcher, BBSSettings.inputSurface());
            }

            if (this.hasSliderRange())
            {
                if (radius > 0)
                {
                    context.batcher.roundedBox(this.area.x, this.area.y, fillX - this.area.x, this.area.h, radius, fillColor);
                    context.batcher.roundedBox(fillX - 1, this.area.y, 2, this.area.h, 1F, Colors.setA(primary, MARKER_ALPHA));
                    context.batcher.roundedBox(this.handleArea.x, this.handleArea.y, this.handleArea.w, this.handleArea.h, radius, handleColor);
                }
                else
                {
                    context.batcher.box(this.area.x, this.area.y, fillX, this.area.ey(), fillColor);
                    context.batcher.box(fillX - 1, this.area.y, fillX + 1, this.area.ey(), Colors.setA(primary, MARKER_ALPHA));
                    context.batcher.box(this.handleArea.x, this.handleArea.y, this.handleArea.ex(), this.handleArea.ey(), handleColor);
                }
            }

            FontRenderer font = context.batcher.getFont();
            String label = this.forcedLabel == null ? format(this.value) : this.forcedLabel.get();

            /* The value text follows the textbox's color (white by default), so a
             * caller can axis-tint a slider the way transform trackpads are tinted. */
            int base = this.textbox.getColor();
            int textColor = this.dragging ? Colors.opaque(base) : Colors.setA(base, VALUE_ALPHA);
            int lx = this.area.ex() - 6 - font.getWidth(label);
            int ly = this.area.my() - font.getHeight() / 2;

            context.batcher.text(label, lx, ly, textColor);
        }

        this.renderLockedArea(context);

        super.render(context);
    }
}
