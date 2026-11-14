package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.TrackpadRecorder;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.utils.UIBezierHandles;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import org.lwjgl.glfw.GLFW;

/**
 * Shared numeric keyframe fields and transactional value gestures.
 */
public abstract class UINumericKeyframeFactory<T extends Number> extends UIKeyframeFactory<T>
{
    protected UITrackpad value;
    protected UIBezierHandles handles;
    
    private int lastMouseX;
    private boolean editingMode;
    private boolean editingChanged;
    private final UIElement editingOverlay = new AcceptRejectOverlay();

    public UINumericKeyframeFactory(Keyframe<T> keyframe, UIKeyframes editor)
    {
        super(keyframe, editor);

        this.value = new UITrackpad((v) -> this.setValue(v));
        this.value.setValue(this.getNumericValue(keyframe.getValue()));
        this.handles = new UIBezierHandles(keyframe);


        this.keys().register(Keys.TRANSFORMATIONS_TRANSLATE, this::startEditingMode).category(UIKeys.TRANSFORMS_KEYS_CATEGORY);
        this.scroll.add(this.value, this.handles.createColumn());
    }

    /**
     * Convert typed value to double for trackpad display.
     */
    protected abstract double getNumericValue(T value);

    /**
     * Convert double value back to typed value and update keyframe.
     */
    protected abstract void setKeyframeValue(double value);
    
    /**
     * Convert a numeric field value through the factory's existing addon contract.
     */
    protected abstract TrackpadRecorder.ValueConverter createValueConverter();

    /**
     * Override parent's setValue to handle numeric conversion.
     */
    private void setValue(double value)
    {
        this.editor.getGraph().setValue(this.createValueConverter().convert(value), true, true);
    }

    private void startEditingMode()
    {
        UIContext context = this.getContext();

        if (context == null || this.editingMode)
        {
            return;
        }

        /* Snapshot the channels before the first sample. The drag itself writes without notifying
         * (see applyEditingValue), so this snapshot is the gesture's only history record: accepting
         * submits one edit out of it and cancelling puts the document back without one. */
        this.editor.cacheKeyframes();
        this.update();
        this.lastMouseX = context.mouseX;
        this.editingChanged = false;
        this.editingMode = true;
        context.menu.overlay.add(this.editingOverlay);
    }

    private void stopEditingMode(boolean accept)
    {
        if (!this.editingMode)
        {
            return;
        }

        this.editingMode = false;
        this.editingOverlay.removeFromParent();

        if (accept && this.editingChanged)
        {
            this.editor.submitKeyframes();
        }
        else
        {
            /* Rejected, or accepted without a single sample: restore the snapshot instead of
             * writing a compensating edit, which would leave the rejected gesture in history. */
            this.editor.cancelCachedKeyframes();
        }

        this.editingChanged = false;
        this.update();
    }

    @Override
    protected void onRemove(UIElement parent)
    {
        /* A selection change rebuilds this panel mid-gesture; an interrupted gesture must not
         * outlive its factory as a half-applied edit. */
        this.stopEditingMode(false);
        super.onRemove(parent);
    }

    /**
     * One sample of the numeric drag, applied without notifying. Notifying per sample would leave a
     * pending undo entry that accepting would then push a second time and cancelling could not take
     * back; the gesture's snapshot owns the record instead.
     */
    private void applyEditingValue(double value)
    {
        this.editor.getGraph().setValue(this.createValueConverter().convert(value), false, true);
    }

    /**
     * Handle the gesture before the fields, timelines and their context menus: whichever click ends
     * the drag — including one that lands outside this panel — accepts or rejects it instead of
     * reaching what is under the pointer.
     */
    private class AcceptRejectOverlay extends UIElement
    {
        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (context.mouseButton == 0 || context.mouseButton == 1)
            {
                UINumericKeyframeFactory.this.stopEditingMode(context.mouseButton == 0);
            }

            return true;
        }

        @Override
        protected boolean subKeyPressed(UIContext context)
        {
            if (context.isPressed(GLFW.GLFW_KEY_ENTER) || context.isPressed(GLFW.GLFW_KEY_KP_ENTER))
            {
                UINumericKeyframeFactory.this.stopEditingMode(true);
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                UINumericKeyframeFactory.this.stopEditingMode(false);
            }

            return true;
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            UITrackpad.updateAmplifier(context);

            return true;
        }
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        if (this.editingMode)
        {
            int dx = context.mouseX - this.lastMouseX;

            if (dx != 0)
            {
                double modifier = this.value.getValueModifier();
                double newValue = MathUtils.clamp(this.value.getValue() + dx * modifier, this.value.min, this.value.max);

                if (this.value.integer)
                {
                    newValue = (int) newValue;
                }

                this.value.setValue(newValue);
                this.applyEditingValue(newValue);
                this.editingChanged = true;
                this.lastMouseX = context.mouseX;
            }
        }
        
        if (this.editingMode)
        {
            String label = UIKeys.TRANSFORMS_EDITING.get();
            FontRenderer font = context.batcher.getFont();
            int x = this.area.mx(font.getWidth(label));
            int y = this.area.my(font.getHeight());

            context.batcher.textCard(label, x, y, BBSSettings.textColor(), Colors.A50);
        }
    }
    
    @Override
    protected boolean followsPlayhead()
    {
        return !this.isBusy() && super.followsPlayhead();
    }

    private boolean isBusy()
    {
        return this.editingMode || this.value.isDragging() || this.value.textbox.isFocused();
    }

    @Override
    public void update()
    {
        super.update();

        if (!this.isBusy())
        {
            this.value.setValue(this.getNumericValue(this.getDisplayValue()));
        }
        this.handles.update();
    }
}
