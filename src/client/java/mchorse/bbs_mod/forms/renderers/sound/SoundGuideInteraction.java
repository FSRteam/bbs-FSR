package mchorse.bbs_mod.forms.renderers.sound;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.data.types.FloatType;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeForm;
import mchorse.bbs_mod.forms.forms.sound.SoundConeGeometry;
import mchorse.bbs_mod.forms.forms.sound.SoundKeyframeValue;
import mchorse.bbs_mod.forms.forms.sound.SoundSphereForm;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.editors.panels.UIAbstractSoundFormPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UISoundKeyframeFactory;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Selection, picking and drag ownership for sound-form editing guides. */
public final class SoundGuideInteraction
{
    public static final String HANDLE_SPHERE_RADIUS = "$sound_sphere_radius";
    public static final String HANDLE_CONE_RANGE = "$sound_cone_range";
    public static final String HANDLE_CONE_OUTER = "$sound_cone_outer";
    public static final String HANDLE_CONE_INNER = "$sound_cone_inner";

    private static final float EPSILON = 1E-5F;
    private static final WeakIdentityMap<AbstractSoundForm, Matrix4f> GUIDE_MATRICES = new WeakIdentityMap<>();

    private static WeakReference<UIAbstractSoundFormPanel<?>> formPanel = new WeakReference<>(null);
    private static WeakReference<UISoundKeyframeFactory> keyframePanel = new WeakReference<>(null);
    private static WeakReference<UIFilmPanel> filmPanelCache = new WeakReference<>(null);

    private static AbstractSoundForm dragForm;
    private static String dragHandle;
    private static Object dragHost;
    private static Camera dragCamera;
    private static Area dragViewport;
    private static Keyframe<SoundKeyframeValue> dragGroupedKeyframe;
    private static Keyframe<Float> dragLegacyKeyframe;
    private static KeyframeChannel<SoundKeyframeValue> dragGroupedChannel;
    private static KeyframeChannel<Float> dragLegacyChannel;
    private static Replay dragReplay;
    private static SoundKeyframeValue dragInitialGroupedValue;
    private static Float dragInitialLegacyValue;
    private static ValueFloat dragStaticProperty;
    private static Float dragInitialStaticValue;
    private static Float dragInitialStaticRuntime;
    private static ValueFloat dragCoupledProperty;
    private static Float dragInitialCoupledRuntime;
    private static boolean dragChanged;

    private SoundGuideInteraction()
    {}

    public static void bindFormPanel(UIAbstractSoundFormPanel<?> panel)
    {
        formPanel = new WeakReference<>(panel);
    }

    public static void bindKeyframePanel(UISoundKeyframeFactory panel)
    {
        keyframePanel = new WeakReference<>(panel);
    }

    /** Route one sound renderer through visible-guide or picking behavior. */
    public static void render(FormRenderingContext context, AbstractSoundForm form)
    {
        if (BBSRendering.isIrisShadowPass())
        {
            return;
        }

        if (context.isPicking())
        {
            boolean previewPick = context.modelRenderer && context.stencilMap.increment;
            boolean filmPick = context.type == FormRenderType.ENTITY
                && context.stencilMap.increment
                && isReplayEditorActive();

            if ((previewPick || filmPick) && form.showGuide.get())
            {
                captureGuideMatrix(form, context.stack.last().pose());
                SoundGuideRenderer.renderStencilHandles(context.stack, form, context.stencilMap);
            }

            return;
        }

        boolean preview = context.modelRenderer;
        boolean world = !context.ui
            && (context.type == FormRenderType.ENTITY || context.type == FormRenderType.MODEL_BLOCK)
            && (!isReplayEditorActive() || showAllGuides() || isFilmSelected(context, form));

        if (form.showGuide.get() && (preview || world))
        {
            captureGuideMatrix(form, context.stack.last().pose());
            SoundGuideRenderer.render(context.stack, form);
        }
    }

    private static boolean showAllGuides()
    {
        return BBSSettings.editorShowAllSoundGuides != null
            && BBSSettings.editorShowAllSoundGuides.get();
    }

    private static void captureGuideMatrix(AbstractSoundForm form, Matrix4f matrix)
    {
        GUIDE_MATRICES.put(form, new Matrix4f(matrix));
    }

    public static boolean tryStartPreview(Object host, StencilFormFramebuffer stencil,
        Camera camera, Area viewport, UIContext context)
    {
        return tryStart(host, stencil, camera, viewport, context, null, 0);
    }

    public static boolean tryStartFilm(Object host, StencilFormFramebuffer stencil,
        Camera camera, Area viewport, UIContext context, Replay replay, int cursor)
    {
        return tryStart(host, stencil, camera, viewport, context, replay, cursor);
    }

    private static boolean tryStart(Object host, StencilFormFramebuffer stencil,
        Camera camera, Area viewport, UIContext context, Replay replay, int cursor)
    {
        if (dragForm != null || context.mouseButton != 0 || stencil == null || !stencil.hasPicked())
        {
            return false;
        }

        Pair<Form, String> pair = stencil.getPicked();

        if (pair == null || !(pair.a instanceof AbstractSoundForm sound)
            || !isHandle(pair.b) || !GUIDE_MATRICES.containsKey(sound))
        {
            return false;
        }

        dragForm = sound;
        dragHandle = pair.b;
        dragHost = host;
        dragCamera = camera;
        dragViewport = viewport;

        if (replay != null)
        {
            dragReplay = replay;

            if (!resolveFilmKeyframe(replay, cursor))
            {
                stop();

                return false;
            }
        }
        else
        {
            beginStaticDrag();
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean resolveFilmKeyframe(Replay replay, int cursor)
    {
        String groupedId = SoundKeyframeValue.channelId(dragForm, SoundKeyframeValue.Group.SHAPE);
        KeyframeChannel<SoundKeyframeValue> grouped = replay.properties.properties.get(groupedId);

        if (grouped != null && !grouped.isEmpty())
        {
            KeyframeSegment<SoundKeyframeValue> segment = grouped.findSegment(cursor);

            if (segment != null)
            {
                dragGroupedChannel = grouped;
                dragGroupedKeyframe = segment.a;
                dragInitialGroupedValue = segment.a.getValue().copy();

                return true;
            }
        }

        ValueFloat property = draggedProperty();
        String legacyId = property == null ? null : FormUtils.getPropertyPath(property);
        KeyframeChannel<Float> legacy = legacyId == null ? null : replay.properties.properties.get(legacyId);

        if (legacy != null && !legacy.isEmpty())
        {
            KeyframeSegment<Float> segment = legacy.findSegment(cursor);

            if (segment != null)
            {
                dragLegacyChannel = legacy;
                dragLegacyKeyframe = segment.a;
                dragInitialLegacyValue = segment.a.getValue();

                return true;
            }
        }

        return false;
    }

    private static void beginStaticDrag()
    {
        dragStaticProperty = draggedProperty();

        if (dragStaticProperty != null)
        {
            dragInitialStaticValue = dragStaticProperty.getOriginalValue();
            dragInitialStaticRuntime = dragStaticProperty.getRuntimeValue();
        }

        if (dragForm instanceof SoundConeForm cone && HANDLE_CONE_OUTER.equals(dragHandle))
        {
            dragCoupledProperty = cone.innerAngle;
            dragInitialCoupledRuntime = cone.innerAngle.getRuntimeValue();
        }
    }

    public static boolean mouseReleased(Object host, int mouseButton)
    {
        if (dragForm == null || dragHost != host || mouseButton != 0)
        {
            return false;
        }

        finish(isDragTargetCurrent());

        return true;
    }

    public static void cancel(Object host, int mouseButton)
    {
        if (dragForm != null && dragHost == host && mouseButton == 0)
        {
            finish(false);
        }
    }

    public static void stop()
    {
        finish(false);
    }

    private static void finish(boolean commit)
    {
        if (dragForm == null)
        {
            clearDrag();

            return;
        }

        try
        {
            if (dragGroupedKeyframe != null)
            {
                finishGroupedKeyframe(commit);
            }
            else if (dragLegacyKeyframe != null)
            {
                finishLegacyKeyframe(commit);
            }
            else
            {
                finishStaticProperty(commit);
            }
        }
        finally
        {
            clearDrag();
        }
    }

    private static void finishGroupedKeyframe(boolean commit)
    {
        SoundKeyframeValue original = dragInitialGroupedValue;

        if (original == null)
        {
            return;
        }

        SoundKeyframeValue current = dragGroupedKeyframe.getValue().copy();

        if (commit && dragChanged)
        {
            dragGroupedKeyframe.setValue(original.copy(), false);
            dragGroupedKeyframe.preNotify(IValueListener.FLAG_UNMERGEABLE);
            dragGroupedKeyframe.setValue(current, false);
            dragGroupedKeyframe.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }
        else if (!commit)
        {
            dragGroupedKeyframe.setValue(original.copy(), false);
            current = original;
        }

        UISoundKeyframeFactory factory = keyframePanel.get();

        if (factory != null)
        {
            factory.syncShapeFromGuide(dragGroupedKeyframe, current);
        }
    }

    private static void finishLegacyKeyframe(boolean commit)
    {
        if (dragInitialLegacyValue == null)
        {
            return;
        }

        Float current = dragLegacyKeyframe.getValue();

        if (commit && dragChanged)
        {
            dragLegacyKeyframe.setValue(dragInitialLegacyValue, false);
            dragLegacyKeyframe.preNotify(IValueListener.FLAG_UNMERGEABLE);
            dragLegacyKeyframe.setValue(current, false);
            dragLegacyKeyframe.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }
        else if (!commit)
        {
            dragLegacyKeyframe.setValue(dragInitialLegacyValue, false);
        }
    }

    private static void finishStaticProperty(boolean commit)
    {
        if (dragStaticProperty == null || dragInitialStaticValue == null)
        {
            return;
        }

        float value = dragStaticProperty.get();
        Float coupled = dragCoupledProperty == null ? null : dragCoupledProperty.get();

        dragStaticProperty.setRuntimeValue(dragInitialStaticRuntime);

        if (dragCoupledProperty != null)
        {
            dragCoupledProperty.setRuntimeValue(dragInitialCoupledRuntime);
        }

        if (commit && dragChanged)
        {
            Form root = FormUtils.getRoot(dragForm);
            BaseValue notificationOwner = root == null ? dragForm : root;

            notificationOwner.preNotify(IValueListener.FLAG_UNMERGEABLE);
            dragStaticProperty.fromData(new FloatType(value));

            if (dragCoupledProperty != null && coupled != null)
            {
                dragCoupledProperty.fromData(new FloatType(coupled));
            }

            notificationOwner.postNotify(IValueListener.FLAG_UNMERGEABLE);
        }

        UIAbstractSoundFormPanel<?> panel = formPanel.get();

        if (panel != null)
        {
            panel.syncShapeFromGuide(dragForm);
        }
    }

    private static void clearDrag()
    {
        dragForm = null;
        dragHandle = null;
        dragHost = null;
        dragCamera = null;
        dragViewport = null;
        dragGroupedKeyframe = null;
        dragLegacyKeyframe = null;
        dragGroupedChannel = null;
        dragLegacyChannel = null;
        dragReplay = null;
        dragInitialGroupedValue = null;
        dragInitialLegacyValue = null;
        dragStaticProperty = null;
        dragInitialStaticValue = null;
        dragInitialStaticRuntime = null;
        dragCoupledProperty = null;
        dragInitialCoupledRuntime = null;
        dragChanged = false;
    }

    /** Update the active drag from the host's current pointer position. */
    public static void update(Object host, UIContext context)
    {
        if (dragForm == null || dragHost != host || dragCamera == null || dragViewport == null)
        {
            return;
        }

        if (!isDragTargetCurrent())
        {
            finish(false);

            return;
        }

        Matrix4f guide = GUIDE_MATRICES.get(dragForm);

        if (guide == null)
        {
            finish(false);

            return;
        }

        Matrix4f localToWorld = new Matrix4f(dragCamera.view).invert().mul(guide);

        if (Math.abs(localToWorld.determinant()) < 1E-12F)
        {
            return;
        }

        Matrix4f worldToLocal = localToWorld.invert();
        Vector3f direction = CameraUtils.getMouseDirection(
            dragCamera.projection,
            dragCamera.view,
            context.mouseX,
            context.mouseY,
            dragViewport.x,
            dragViewport.y,
            dragViewport.w,
            dragViewport.h
        );
        Vector3f origin = worldToLocal.transformPosition(new Vector3f());

        worldToLocal.transformDirection(direction);

        if (direction.lengthSquared() < EPSILON * EPSILON)
        {
            return;
        }

        if (HANDLE_SPHERE_RADIUS.equals(dragHandle))
        {
            updateSphereRadius(origin, direction);
        }
        else if (HANDLE_CONE_RANGE.equals(dragHandle))
        {
            updateConeRange(origin, direction);
        }
        else
        {
            updateConeAngle(origin, direction, HANDLE_CONE_INNER.equals(dragHandle));
        }
    }

    private static void updateSphereRadius(Vector3f origin, Vector3f direction)
    {
        float t = -origin.dot(direction) / direction.lengthSquared();

        if (t <= 0F)
        {
            return;
        }

        float radius = new Vector3f(direction).mul(t).add(origin).length();

        writeShape(radius);
    }

    private static void updateConeRange(Vector3f origin, Vector3f direction)
    {
        float a = direction.lengthSquared();
        float b = direction.z;
        float denominator = a - b * b;

        if (denominator < EPSILON)
        {
            return;
        }

        float axisZ = (a * origin.z - b * origin.dot(direction)) / denominator;

        writeShape(axisZ);
    }

    private static void updateConeAngle(Vector3f origin, Vector3f direction, boolean inner)
    {
        if (!(dragForm instanceof SoundConeForm cone) || Math.abs(direction.z) < EPSILON)
        {
            return;
        }

        float cap = SoundConeGeometry.capDistance(cone.range.get());
        float t = (cap - origin.z) / direction.z;

        if (t <= 0F)
        {
            return;
        }

        float x = origin.x + direction.x * t;
        float y = origin.y + direction.y * t;
        float angle = SoundConeGeometry.angleForRadius(cap, (float) Math.sqrt(x * x + y * y));

        writeShape(angle, inner);
    }

    private static void writeShape(float value)
    {
        writeShape(value, false);
    }

    private static void writeShape(float value, boolean innerAngle)
    {
        ValueFloat property = draggedProperty();

        if (property == null)
        {
            return;
        }

        float clamped = Math.max(property.getMin(), Math.min(property.getMax(), value));

        if (dragGroupedKeyframe != null)
        {
            SoundKeyframeValue shape = dragGroupedKeyframe.getValue().copy();
            SoundKeyframeValue before = dragGroupedKeyframe.getValue();

            if (HANDLE_SPHERE_RADIUS.equals(dragHandle) || HANDLE_CONE_RANGE.equals(dragHandle))
            {
                shape.extent = clamped;
            }
            else if (innerAngle)
            {
                shape.innerAngle = SoundConeGeometry.clampInnerAngle(clamped, shape.outerAngle);
            }
            else
            {
                shape.outerAngle = clamped;
                shape.innerAngle = SoundConeGeometry.clampInnerAngle(shape.innerAngle, shape.outerAngle);
            }

            dragChanged |= shape.extent != before.extent
                || shape.innerAngle != before.innerAngle
                || shape.outerAngle != before.outerAngle;
            dragGroupedKeyframe.setValue(shape, false);

            UISoundKeyframeFactory factory = keyframePanel.get();

            if (factory != null)
            {
                factory.syncShapeFromGuide(dragGroupedKeyframe, shape);
            }

            return;
        }

        if (dragLegacyKeyframe != null)
        {
            dragChanged |= Float.compare(dragLegacyKeyframe.getValue(), clamped) != 0;
            dragLegacyKeyframe.setValue(clamped, false);

            return;
        }

        dragChanged |= Float.compare(property.get(), clamped) != 0;
        property.setRuntimeValue(clamped);

        if (dragForm instanceof SoundConeForm cone && HANDLE_CONE_OUTER.equals(dragHandle)
            && cone.innerAngle.get() > clamped)
        {
            dragChanged = true;
            cone.innerAngle.setRuntimeValue(clamped);
        }

        UIAbstractSoundFormPanel<?> panel = formPanel.get();

        if (panel != null)
        {
            panel.syncShapeFromGuide(dragForm);
        }
    }

    private static boolean isDragTargetCurrent()
    {
        if (dragReplay == null)
        {
            UIAbstractSoundFormPanel<?> panel = formPanel.get();

            return panel != null && panel.isEditingForm(dragForm);
        }

        UIFilmPanel film = filmPanel();

        if (film == null || film.replayEditor == null || film.replayEditor.getReplay() != dragReplay)
        {
            return false;
        }

        if (dragGroupedChannel != null)
        {
            KeyframeChannel<?> current = dragReplay.properties.properties.get(dragGroupedChannel.getId());

            return current == dragGroupedChannel && containsIdentity(dragGroupedChannel, dragGroupedKeyframe);
        }

        if (dragLegacyChannel != null)
        {
            KeyframeChannel<?> current = dragReplay.properties.properties.get(dragLegacyChannel.getId());

            return current == dragLegacyChannel && containsIdentity(dragLegacyChannel, dragLegacyKeyframe);
        }

        return false;
    }

    private static boolean containsIdentity(KeyframeChannel<?> channel, Keyframe<?> keyframe)
    {
        for (Keyframe<?> candidate : channel.getKeyframes())
        {
            if (candidate == keyframe)
            {
                return true;
            }
        }

        return false;
    }

    private static ValueFloat draggedProperty()
    {
        if (dragForm instanceof SoundSphereForm sphere)
        {
            return sphere.radius;
        }

        if (dragForm instanceof SoundConeForm cone)
        {
            if (HANDLE_CONE_RANGE.equals(dragHandle)) return cone.range;
            if (HANDLE_CONE_INNER.equals(dragHandle)) return cone.innerAngle;

            return cone.outerAngle;
        }

        return null;
    }

    public static boolean isHandle(String id)
    {
        return HANDLE_SPHERE_RADIUS.equals(id)
            || HANDLE_CONE_RANGE.equals(id)
            || HANDLE_CONE_OUTER.equals(id)
            || HANDLE_CONE_INNER.equals(id);
    }

    private static boolean isFilmSelected(FormRenderingContext context, Form form)
    {
        UIFilmPanel film = filmPanel();

        if (film == null || film.replayEditor == null)
        {
            return false;
        }

        Replay selected = film.replayEditor.getReplay();

        if (selected == null)
        {
            return false;
        }

        if (context.timelineProperties != null)
        {
            return context.timelineProperties == selected.properties;
        }

        Form selectedForm = selected.form.get();

        return selectedForm != null && FormUtils.getRoot(form) == FormUtils.getRoot(selectedForm);
    }

    public static boolean isReplayEditorActive()
    {
        UIFilmPanel film = filmPanel();

        return film != null
            && film.replayEditor != null
            && film.replayEditor.isVisible()
            && (film.actionEditor == null || !film.actionEditor.isVisible());
    }

    private static UIFilmPanel filmPanel()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null || UIScreen.getCurrentMenu() != dashboard)
        {
            return null;
        }

        UIFilmPanel film = filmPanelCache.get();

        if (film == null)
        {
            film = dashboard.getPanel(UIFilmPanel.class);
            filmPanelCache = new WeakReference<>(film);
        }

        return film;
    }

    /** Weak keys with identity equality, required because ValueGroup equality is structural. */
    private static final class WeakIdentityMap<K, V>
    {
        private final ReferenceQueue<K> queue = new ReferenceQueue<>();
        private final Map<IdentityReference<K>, V> values = new HashMap<>();

        public void put(K key, V value)
        {
            this.drain();
            this.values.put(new IdentityReference<>(key, this.queue), value);
        }

        public V get(K key)
        {
            this.drain();

            return this.values.get(new IdentityReference<>(key, null));
        }

        public boolean containsKey(K key)
        {
            return this.get(key) != null;
        }

        @SuppressWarnings("unchecked")
        private void drain()
        {
            IdentityReference<K> reference;

            while ((reference = (IdentityReference<K>) this.queue.poll()) != null)
            {
                this.values.remove(reference);
            }
        }
    }

    private static final class IdentityReference<K> extends WeakReference<K>
    {
        private final int hash;

        public IdentityReference(K value, ReferenceQueue<K> queue)
        {
            super(value, queue);
            this.hash = System.identityHashCode(value);
        }

        @Override
        public int hashCode()
        {
            return this.hash;
        }

        @Override
        public boolean equals(Object object)
        {
            if (this == object)
            {
                return true;
            }

            if (!(object instanceof IdentityReference<?> reference))
            {
                return false;
            }

            Object value = this.get();

            return value != null && value == reference.get();
        }
    }
}
