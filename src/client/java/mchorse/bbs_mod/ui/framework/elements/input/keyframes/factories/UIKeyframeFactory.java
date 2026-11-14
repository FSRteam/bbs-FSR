package mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.ScrollMemory;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.context.UIInterpolationContextMenu;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragEndEvent;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragStartEvent;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.IKeyframeShapeRenderer;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.KeyframeShapeRenderers;
import mchorse.bbs_mod.ui.framework.tooltips.InterpolationTooltip;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.interps.Interpolation;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.HashMap;
import java.util.Map;

public abstract class UIKeyframeFactory <T> extends UIElement
{
    private static final Map<IKeyframeFactory, IUIKeyframeFactoryFactory> FACTORIES = new HashMap<>();
    private static final Map<String, IUIKeyframeFactoryFactory> PROPERTIES = new HashMap<>();
    private static final ScrollMemory<IKeyframeFactory> SCROLLS = new ScrollMemory<>();
    private static boolean initialized;

    public UIScrollView scroll;
    public UITrackpad tick;
    public UITrackpad duration;
    public UITrackpad motionShift;
    private boolean draggingMotionShift;
    public UIIcon interp;

    public UIIcon shape;
    public UIColor color;

    protected Keyframe<T> keyframe;
    protected UIKeyframes editor;

    /** Register built-in editors before addons contribute their replacements. */
    public static void setup()
    {
        if (initialized)
        {
            return;
        }

        initialized = true;
        register(KeyframeFactories.ANCHOR, UIAnchorKeyframeFactory::new);
        register(KeyframeFactories.BOOLEAN, UIBooleanKeyframeFactory::new);
        register(KeyframeFactories.COLOR, UIColorKeyframeFactory::new);
        register(KeyframeFactories.FLOAT, UIFloatKeyframeFactory::new);
        register(KeyframeFactories.DOUBLE, UIDoubleKeyframeFactory::new);
        register(KeyframeFactories.INTEGER, UIIntegerKeyframeFactory::new);
        register(KeyframeFactories.LINK, UILinkKeyframeFactory::new);
        register(KeyframeFactories.POSE, UIPoseKeyframeFactory::new);
        register(KeyframeFactories.GLINT, UIGlintKeyframeFactory::new);
        register(KeyframeFactories.IK, UIIKKeyframeFactory::new);
        register(KeyframeFactories.PHYSICS, UIPhysicsKeyframeFactory::new);
        register(KeyframeFactories.WIND, UIWindKeyframeFactory::new);
        register(KeyframeFactories.BONE_CONSTRAINT, UIBoneConstraintKeyframeFactory::new);
        register(KeyframeFactories.POSE_TRANSFORM, UIPoseTransformKeyframeFactory::new);
        register(KeyframeFactories.STRING, UIStringKeyframeFactory::new);
        register(KeyframeFactories.TRANSFORM, UITransformKeyframeFactory::new);
        register(KeyframeFactories.VECTOR3F, UIVector3fKeyframeFactory::new);
        register(KeyframeFactories.VECTOR3F_SCALE, UIVector3fKeyframeFactory::new);
        register(KeyframeFactories.VECTOR4F, UIVector4fKeyframeFactory::new);
        register(KeyframeFactories.BLOCK_STATE, UIBlockStateKeyframeFactory::new);
        register(KeyframeFactories.ITEM_STACK, UIItemStackKeyframeFactory::new);
        register(KeyframeFactories.ACTIONS_CONFIG, UIActionsConfigKeyframeFactory::new);
        register(KeyframeFactories.SHAPE_KEYS, UIShapeKeysKeyframeFactory::new);
        register(KeyframeFactories.PARTICLE_SETTINGS, UIParticleSettingsKeyframeFactory::new);
        registerProperty("model", UIModelKeyframeFactory::new);
        register(KeyframeFactories.SOUND, UISoundKeyframeFactory::new);
        register(KeyframeFactories.SOUND_SHAPE, UISoundKeyframeFactory::new);
        register(KeyframeFactories.SOUND_VISUALIZATION, UISoundKeyframeFactory::new);
        register(KeyframeFactories.SOUND_FALLOFF, UISoundKeyframeFactory::new);
        register(KeyframeFactories.SOUND_REFLECTIONS, UISoundKeyframeFactory::new);
    }

    /* Preserve early FSR addon and standalone callers of createPanel. */
    static
    {
        setup();
    }

    public static <T> void register(IKeyframeFactory<T> clazz, IUIKeyframeFactoryFactory<T> factory)
    {
        FACTORIES.put(clazz, factory);
    }

    public static <T> void registerProperty(String property, IUIKeyframeFactoryFactory<T> factory)
    {
        PROPERTIES.put(property, factory);
    }

    public static void saveScroll(UIKeyframeFactory editor)
    {
        if (editor != null)
        {
            SCROLLS.save(editor.keyframe.getFactory(), editor.scroll);
        }
    }

    /** Restore after the property panel has a laid-out viewport, so clamping uses its real size. */
    public void restoreScroll()
    {
        SCROLLS.restore(this.keyframe.getFactory(), this.scroll);
    }

    public static <T> UIKeyframeFactory createPanel(Keyframe<T> keyframe, UIKeyframes editor)
    {
        IUIKeyframeFactoryFactory<T> factory = null;
        IUIKeyframeGraph graph = editor == null ? null : editor.getGraph();
        UIKeyframeSheet sheet = graph == null ? null : graph.getSheet(keyframe);

        if (sheet != null && sheet.property != null && !sheet.isBoneTrack)
        {
            factory = PROPERTIES.get(StringUtils.fileName(sheet.channel.getId()));
        }

        if (factory == null)
        {
            factory = FACTORIES.get(keyframe.getFactory());
        }

        return factory == null ? null : factory.create(keyframe, editor);
    }

    public UIKeyframeFactory(Keyframe<T> keyframe, UIKeyframes editor)
    {
        this.keyframe = keyframe;
        this.editor = editor;

        this.scroll = UI.scrollView(UIConstants.MARGIN, Math.max(UIConstants.SCROLL_PADDING, 4));
        this.scroll.scroll.cancelScrolling();
        this.scroll.full(this);

        this.tick = new UITrackpad(this::setTick);
        this.tick.tooltip(UIKeys.KEYFRAMES_TICK);
        this.tick.getEvents().register(UITrackpadDragStartEvent.class, (e) -> this.editor.cacheKeyframes());
        this.tick.getEvents().register(UITrackpadDragEndEvent.class, (e) -> this.editor.submitKeyframes());
        this.duration = new UITrackpad((v) -> this.setDuration(v.floatValue()));
        this.duration.limit(0, Float.MAX_VALUE).tooltip(UIKeys.KEYFRAMES_FORCED_DURATION);
        this.interp = new UIIcon(Icons.GRAPH, (b) ->
        {
            Interpolation interp = this.keyframe.getInterpolation();
            UIInterpolationContextMenu menu = new UIInterpolationContextMenu(interp);

            this.getContext().replaceContextMenu(menu.callback(() -> this.editor.getGraph().setInterpolation(interp)));
        });
        this.interp.wh(UIConstants.CONTROL_HEIGHT, UIConstants.CONTROL_HEIGHT);
        this.interp.tooltip(new InterpolationTooltip(0F, 0.5F, () -> this.keyframe.getInterpolation()));
        this.interp.keys().register(Keys.KEYFRAMES_INTERP, this.interp::clickItself).category(UIKeys.KEYFRAMES_KEYS_CATEGORY);

        this.motionShift = new UITrackpad(value -> this.editor.getGraph().setMotionShift(value.floatValue() / 100F, !this.draggingMotionShift));
        this.motionShift.limit(-49, 49).tooltip(UIKeys.KEYFRAMES_MOTION_SHIFT);
        this.motionShift.getEvents().register(UITrackpadDragStartEvent.class, event ->
        {
            this.draggingMotionShift = true;
            this.editor.cacheKeyframes();
        });
        this.motionShift.getEvents().register(UITrackpadDragEndEvent.class, event ->
        {
            this.draggingMotionShift = false;
            this.editor.submitKeyframes();
            this.editor.getGraph().pickSelected();
        });
        this.motionShift.setValue(keyframe.getMotionShift() * 100F);
        this.motionShift.setEnabled(keyframe.supportsMotionShift());

        this.color = new UIColor((c) ->
        {
            for (UIKeyframeSheet sheet : this.editor.getGraph().getSheets())
            {
                for (Keyframe kf : sheet.selection.getSelected()) kf.setColor(new Color().set(c));
            }
        });
        this.color.setColor(keyframe.getColor() == null ? 0 : keyframe.getColor().getRGBColor());
        this.color.tooltip(UIKeys.KEYFRAMES_CHANGE_COLOR);
        this.color.context((menu) ->
        {
            menu.action(Icons.COLOR, UIKeys.KEYFRAMES_RESET_COLOR, () ->
            {
                for (UIKeyframeSheet sheet : this.editor.getGraph().getSheets())
                {
                    for (Keyframe kf : sheet.selection.getSelected()) kf.setColor(null);
                }

                this.color.setColor(0);
            });
        });

        this.shape = new UIIcon(Icons.SHAPES, (b) -> this.editor.openStyleEditor());
        this.shape.wh(UIConstants.CONTROL_HEIGHT, UIConstants.CONTROL_HEIGHT);
        this.shape.tooltip(UIKeys.KEYFRAMES_CONTEXT_KEYFRAME_STYLE);

        this.scroll.add(UI.column(0, 0, 0,
            UI.row(UIConstants.MARGIN, 0, 0, this.interp, this.tick, this.duration, this.motionShift),
            UI.row(UIConstants.MARGIN, 0, 0, this.shape).marginTop(UIConstants.SECTION_GAP)
        ));

        this.add(this.scroll);

        /* Fill data */
        this.tick.setValue(TimeUtils.toTime(keyframe.getTick()));
        this.duration.setValue(TimeUtils.toTime(keyframe.getDuration()));
    }

    public Keyframe<T> getKeyframe()
    {
        return this.keyframe;
    }

    public Keyframe<T> getEditTarget()
    {
        return this.editor.getGraph().getEditTarget(this.keyframe);
    }

    protected boolean followsPlayhead()
    {
        return this.editor.getAutoKeyframeTick() != null;
    }

    protected T getDisplayValue()
    {
        Float tick = this.editor.getAutoKeyframeTick();
        UIKeyframeSheet sheet = tick == null ? null : this.editor.getGraph().getSheet(this.keyframe);
        T value = sheet == null || sheet.channel.isEmpty() ? null : (T) sheet.channel.interpolate(tick);

        return value == null ? this.keyframe.getValue() : value;
    }

    @Override
    public void render(UIContext context)
    {
        if (this.followsPlayhead()) this.update();
        super.render(context);
    }

    public void setTick(double tick)
    {
        double time = BBSSettings.editorSnapToTicks.get() ? TimeUtils.fromTime(tick)
            : (BBSSettings.editorSeconds.get() ? tick * 20D : tick);

        this.editor.getGraph().setTick((float) time, false);
    }

    public void setDuration(float value)
    {
        this.editor.getGraph().setDuration(value);
    }

    public void setValue(Object value)
    {
        this.editor.getGraph().setValue(value, true, true);
    }

    public void update()
    {
        this.tick.setValue(TimeUtils.toTime(this.keyframe.getTick()));
        this.motionShift.setValue(this.keyframe.getMotionShift() * 100F);
        this.motionShift.setEnabled(this.keyframe.supportsMotionShift());
    }

    public static interface IUIKeyframeFactoryFactory <T>
    {
        public UIKeyframeFactory<T> create(Keyframe<T> keyframe, UIKeyframes editor);
    }
}
