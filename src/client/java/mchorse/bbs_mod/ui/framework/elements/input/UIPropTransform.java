package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.MathBuilder;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.IValueNotifier;
import mchorse.bbs_mod.settings.values.ui.ValueOrder;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.events.UITrackpadDragEndEvent;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import org.joml.Intersectiond;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class UIPropTransform extends UITransform
{
    private static final double[] CURSOR_X = new double[1];
    private static final double[] CURSOR_Y = new double[1];
    private static final float STEP_MODIFIER = 5F;
    private static final float DEPTH_WHEEL_FACTOR = 0.05F;
    private static final float TRACKBALL_WHEEL_DEG = 5F;
    private static final float FINE_DRAG_FACTOR = 0.1F;

    private Transform transform;
    private Runnable preCallback;
    private Runnable postCallback;
    private Runnable endCallback;

    private boolean editing;
    private int mode;
    private Axis axis = Axis.X;
    private Axis axis2;
    private boolean hotkeyMode;
    private boolean scaleAll;
    private int lastX;
    private int lastY;
    private Transform cache = new Transform();
    private Timer checker = new Timer(30);

    private boolean model;
    private boolean local;

    private UITransformHandler handler;
    private boolean gesturePollOnly;

    private Supplier<GizmoDrag> hotkeyDragSupplier;
    private final Gizmo.DragContext gizmoDragContext = new Gizmo.DragContext();
    private final Matrix4f gizmoDragModel = new Matrix4f();
    private GizmoDrag drag;
    private final Matrix3f dragWorldBasis = new Matrix3f();
    private final Matrix3f dragTranslateBasis = new Matrix3f();
    private final Matrix3f dragScreenInverseJacobian = new Matrix3f();
    private final Vector3f dragPlaneNormal = new Vector3f();
    private final Vector3d dragStartHit = new Vector3d();
    private final Vector3f dragStartTranslate = new Vector3f();
    private final Vector3f dragStartScale = new Vector3f();
    private final Vector3f dragStartRotateDeg = new Vector3f();
    private final Vector3f initialDragRingVec = new Vector3f();
    private float accumulatedRotateDeg;
    private final Vector3f dragAxisDir = new Vector3f();
    private final Vector2f dragScreenCenter = new Vector2f();
    private float dragLastScreenAngle;
    private float dragRotateSign = 1F;
    private float viewGrabScreenAngle;
    private boolean dragRotateGizmoSpace;
    private boolean dragHasStart;
    private final Vector3f viewLocalAxis = new Vector3f();
    private final Vector3f trackballRightLocal = new Vector3f();
    private final Vector3f trackballUpLocal = new Vector3f();
    private final Vector3f trackballViewLocal = new Vector3f();
    private Axis trackballAxis = Axis.X;
    private int trackballLastX;
    private int trackballLastY;
    private float trackballAccumX;
    private float trackballAccumY;
    private float trackballRollDeg;
    private final Vector3f arcballViewWorld = new Vector3f();
    private final Matrix3f arcballParentInverse = new Matrix3f();
    private final Vector3f arcballStartLocal = new Vector3f();
    private final Vector3f arcballCurrentLocal = new Vector3f();
    private final Quaternionf arcballAccum = new Quaternionf();
    private float arcballRadius;
    private boolean arcballAnchored;
    private boolean gizmoDrag;
    private final Matrix4f gizmoMvp = new Matrix4f();
    private final Matrix4f gizmoInvMvp = new Matrix4f();
    private int gizmoViewportX;
    private int gizmoViewportY;
    private int gizmoViewportW;
    private int gizmoViewportH;
    private final Vector2f gizmoOrigin2D = new Vector2f();
    private final Vector2f gizmoAxisX = new Vector2f();
    private final Vector2f gizmoAxisY = new Vector2f();
    private final Vector2f gizmoAxisZ = new Vector2f();
    private float gizmoAxisXLenSq;
    private float gizmoAxisYLenSq;
    private float gizmoAxisZLenSq;
    private boolean gizmoInvReady;
    private final Vector2f gizmoTmp2D = new Vector2f();
    private final Vector4f gizmoTmp4D = new Vector4f();
    private final Vector4f gizmoNear4D = new Vector4f();
    private final Vector4f gizmoFar4D = new Vector4f();
    private final Vector3d gizmoRayStart = new Vector3d();
    private final Vector3d gizmoRayEnd = new Vector3d();
    private final Vector3d gizmoP0 = new Vector3d();
    private final Vector3d gizmoP1 = new Vector3d();
    private final Vector3d gizmoCross = new Vector3d();
    private final Vector3d gizmoTangent = new Vector3d();
    private final Vector3d gizmoAxisNormal = new Vector3d();
    private final Vector2f gizmoP2D = new Vector2f();
    private final Vector2f gizmoP2DNext = new Vector2f();
    private DragKind dragKind = DragKind.AXIS;
    private final StringBuilder numericInput = new StringBuilder();
    private boolean numericActive;
    private float fineOffsetX;
    private float fineOffsetY;
    private int fineLastX;
    private int fineLastY;
    private boolean fineHasLast;

    public UIPropTransform()
    {
        this.handler = new UITransformHandler(this);
        this.local = BBSSettings.defaultLocalTransform.get();

        this.context((menu) ->
        {
            menu.action(
                this.local ? Icons.FULLSCREEN : Icons.MINIMIZE,
                this.local ? UIKeys.TRANSFORMS_CONTEXT_SWITCH_GLOBAL : UIKeys.TRANSFORMS_CONTEXT_SWITCH_LOCAL,
                this::toggleLocal
            );

            menu.actions.add(0, menu.actions.remove(menu.actions.size() - 1));
        });

        this.iconT.callback = (b) -> this.toggleLocal();
        this.iconT.hoverColor = Colors.LIGHTEST_GRAY;
        this.iconT.setEnabled(true);
        this.updateLocalUI();

        /* Each finished value-field drag closes the current undo block, so dragging a
         * field several times in a row undoes one drag at a time (see endGesture). */
        for (UITrackpad field : new UITrackpad[]{this.tx, this.ty, this.tz, this.sx, this.sy, this.sz, this.rx, this.ry, this.rz, this.r2x, this.r2y, this.r2z})
        {
            field.getEvents().register(UITrackpadDragEndEvent.class, (e) -> this.endGesture());
        }

        this.noCulling();
    }

    public UIPropTransform callbacks(Supplier<IValueNotifier> notifier)
    {
        return this.callbacks(
            () -> notifier.get().preNotify(),
            () -> notifier.get().postNotify(),
            () -> notifier.get().preNotify(IValueListener.FLAG_UNMERGEABLE)
        );
    }

    public UIPropTransform callbacks(Runnable pre, Runnable post)
    {
        return this.callbacks(pre, post, null);
    }

    public UIPropTransform callbacks(Runnable pre, Runnable post, Runnable end)
    {
        this.preCallback = pre;
        this.postCallback = post;
        this.endCallback = end;

        return this;
    }

    public void preCallback()
    {
        if (this.preCallback != null) this.preCallback.run();
    }

    public void postCallback()
    {
        if (this.postCallback != null) this.postCallback.run();
    }

    /**
     * Close the current undo block so the next transform gesture starts a fresh,
     * separately-undoable entry. Fired at each gesture boundary — a value-field drag
     * end and the gizmo commit — rather than per value change, so one continuous drag
     * still merges into a single undo while consecutive drags stay distinct.
     */
    public void endGesture()
    {
        if (this.endCallback != null) this.endCallback.run();
    }

    public void setModel()
    {
        this.model = true;
    }

    public UIPropTransform hotkeyDrag(Supplier<GizmoDrag> supplier)
    {
        this.hotkeyDragSupplier = supplier;

        return this;
    }

    public GizmoDrag getHotkeyDrag()
    {
        return this.hotkeyDragSupplier == null ? null : this.hotkeyDragSupplier.get();
    }

    public boolean isEditing()
    {
        return this.editing;
    }

    public int getMode()
    {
        return this.mode;
    }

    public Axis getAxis()
    {
        return this.axis;
    }

    public Axis getAxis2()
    {
        return this.axis2;
    }

    public boolean isLocal()
    {
        return this.local;
    }

    @Override
    protected Transform getEditedTransform()
    {
        return this.transform;
    }

    public boolean isTrackball()
    {
        return this.isSphereRotate();
    }

    public boolean isSphereRotate()
    {
        return this.dragKind == DragKind.TRACKBALL || this.dragKind == DragKind.ARCBALL;
    }

    public boolean isViewRotate()
    {
        return this.dragKind == DragKind.VIEW;
    }

    public Vector3f getInitialDragRingVec()
    {
        return this.initialDragRingVec;
    }

    public float getAccumulatedRotateDeg()
    {
        return this.accumulatedRotateDeg;
    }

    public float getViewGrabScreenAngle()
    {
        return this.viewGrabScreenAngle;
    }

    public float getViewScreenSweepRad()
    {
        return MathUtils.toRad(this.accumulatedRotateDeg) * this.dragRotateSign;
    }

    /** Short on-screen summary of the currently accumulated transform drag. */
    public String getDragReadout()
    {
        if (!this.editing || this.transform == null)
        {
            return null;
        }

        if (this.mode == 2)
        {
            if (this.dragKind == DragKind.AXIS)
            {
                return String.format("%.1f°", this.accumulatedRotateDeg);
            }

            Vector3f start = this.dragRotateGizmoSpace ? this.cache.rotate2 : this.cache.rotate;
            Vector3f now = this.dragRotateGizmoSpace ? this.transform.rotate2 : this.transform.rotate;

            return String.format("X %+.1f°  Y %+.1f°  Z %+.1f°",
                MathUtils.toDeg(now.x - start.x),
                MathUtils.toDeg(now.y - start.y),
                MathUtils.toDeg(now.z - start.z));
        }

        Vector3f delta;
        boolean allAxes;

        if (this.mode == 0)
        {
            delta = new Vector3f(this.transform.translate).sub(this.cache.translate);
            allAxes = this.dragKind == DragKind.SCREEN;
        }
        else if (this.mode == 1)
        {
            delta = new Vector3f(this.transform.scale).sub(this.cache.scale);
            allAxes = this.scaleAll;
        }
        else
        {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        if (allAxes || this.axis == Axis.X || this.axis2 == Axis.X) this.appendReadoutAxis(builder, "X", delta.x);
        if (allAxes || this.axis == Axis.Y || this.axis2 == Axis.Y) this.appendReadoutAxis(builder, "Y", delta.y);
        if (allAxes || this.axis == Axis.Z || this.axis2 == Axis.Z) this.appendReadoutAxis(builder, "Z", delta.z);

        return builder.isEmpty() ? null : builder.toString();
    }

    private void appendReadoutAxis(StringBuilder builder, String label, float value)
    {
        if (!builder.isEmpty())
        {
            builder.append("  ");
        }

        builder.append(label).append(' ').append(String.format("%+.3f", value));
    }

    public GizmoDrag getDrag()
    {
        return this.drag;
    }

    public int getDebugLineStencilIndex()
    {
        if (!BBSSettings.hideInactiveHandles.get() || !this.editing || this.dragKind == DragKind.SCREEN)
        {
            return -1;
        }

        if (this.axis2 != null)
        {
            if ((this.axis == Axis.X && this.axis2 == Axis.Z) || (this.axis == Axis.Z && this.axis2 == Axis.X))
            {
                return Gizmo.STENCIL_XZ;
            }

            if ((this.axis == Axis.X && this.axis2 == Axis.Y) || (this.axis == Axis.Y && this.axis2 == Axis.X))
            {
                return Gizmo.STENCIL_XY;
            }

            if ((this.axis == Axis.Z && this.axis2 == Axis.Y) || (this.axis == Axis.Y && this.axis2 == Axis.Z))
            {
                return Gizmo.STENCIL_ZY;
            }
        }

        if (this.axis == Axis.X) return Gizmo.STENCIL_X;
        if (this.axis == Axis.Y) return Gizmo.STENCIL_Y;
        if (this.axis == Axis.Z) return Gizmo.STENCIL_Z;

        return -1;
    }

    /** Old-logic no-op: kept so hosts that gave the spaces bar a backdrop still compile. */
    public UIPropTransform barBackground()
    {
        return this;
    }

    protected boolean supportsMirror()
    {
        return false;
    }

    public boolean isMirrorEdit()
    {
        return BBSSettings.poseMirrorEdit.get();
    }

    public boolean isAlternateInvert()
    {
        return BBSSettings.poseAlternateInvert.get();
    }

    private void toggleLocal()
    {
        this.local = !this.local;

        if (!this.local && this.transform != null)
        {
            this.fillT(this.transform.translate.x, this.transform.translate.y, this.transform.translate.z);
        }

        this.updateLocalUI();
    }

    private void updateLocalUI()
    {
        this.tx.forcedLabel(this.local ? UIKeys.GENERAL_X : null);
        this.ty.forcedLabel(this.local ? UIKeys.GENERAL_Y : null);
        this.tz.forcedLabel(this.local ? UIKeys.GENERAL_Z : null);
        this.tx.relative(this.local);
        this.ty.relative(this.local);
        this.tz.relative(this.local);
        this.iconT.tooltip(this.local ? UIKeys.TRANSFORMS_CONTEXT_SWITCH_GLOBAL : UIKeys.TRANSFORMS_CONTEXT_SWITCH_LOCAL);
    }

    private Vector3f calculateLocalVector(double factor, Axis axis)
    {
        if (this.transform == null)
        {
            return new Vector3f();
        }

        Vector3f vector3f = new Vector3f(
            (float) (axis == Axis.X ? factor : 0D),
            (float) (axis == Axis.Y ? factor : 0D),
            (float) (axis == Axis.Z ? factor : 0D)
        );
        /* I have no fucking idea why I have to rotate it 180 degrees by X axis... but it works! */
        Matrix3f matrix = new Matrix3f()
            .rotateX(this.model ? MathUtils.PI : 0F)
            .mul(this.transform.createRotationMatrix());

        matrix.transform(vector3f);

        return vector3f;
    }

    public UIPropTransform enableHotkeys()
    {
        return this.enableHotkeys(() -> true);
    }

    public UIPropTransform enableHotkeys(Supplier<Boolean> enabled)
    {
        IKey category = UIKeys.TRANSFORMS_KEYS_CATEGORY;
        Supplier<Boolean> active = () -> enabled.get() && this.editing;

        this.keys().register(Keys.TRANSFORMATIONS_TRANSLATE, () -> this.enableMode(0)).active(enabled).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_SCALE, () -> this.enableMode(1)).active(enabled).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_ROTATE, () -> this.enableMode(2)).active(enabled).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_COMBINED, () -> Gizmo.INSTANCE.toggleCombined()).strict().active(enabled).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_X, () -> this.setEditingAxis(Axis.X)).active(active).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_Y, () -> this.setEditingAxis(Axis.Y)).active(active).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_Z, () -> this.setEditingAxis(Axis.Z)).active(active).category(category);
        this.keys().register(Keys.TRANSFORMATIONS_TOGGLE_LOCAL, () ->
        {
            this.toggleLocal();
            UIUtils.playClick();
        }).active(enabled).category(category);

        return this;
    }

    public Transform getTransform()
    {
        return this.transform;
    }

    public void refillTransform()
    {
        this.setTransform(this.getTransform());
    }

    public void setTransform(Transform transform)
    {
        this.transform = transform;

        if (transform == null)
        {
            this.disable();
            this.fillT(0, 0, 0);
            this.fillS(1, 1, 1);
            this.fillR(0, 0, 0);
            this.fillR2(0, 0, 0);

            return;
        }

        /* Uniform-scale synchronization rebuilds the scale row. Never mutate that UI
         * tree while a drag is being rendered; disable()/acceptChanges() performs the
         * final synchronization after the gesture has ended. */
        if (!this.editing && BBSSettings.uniformScale.get())
        {
            float minScale = Math.min(transform.scale.x, Math.min(transform.scale.y, transform.scale.z));
            float maxScale = Math.max(transform.scale.x, Math.max(transform.scale.y, transform.scale.z));

            if (
                (minScale == maxScale && !this.isUniformScale()) ||
                (minScale != maxScale && this.isUniformScale())
            ) {
                this.toggleUniformScale();
            }
        }

        this.fillT(transform.translate.x, transform.translate.y, transform.translate.z);
        this.fillS(transform.scale.x, transform.scale.y, transform.scale.z);
        this.fillR(MathUtils.toDeg(transform.rotate.x), MathUtils.toDeg(transform.rotate.y), MathUtils.toDeg(transform.rotate.z));
        this.fillR2(MathUtils.toDeg(transform.rotate2.x), MathUtils.toDeg(transform.rotate2.y), MathUtils.toDeg(transform.rotate2.z));
    }

    public void enableMode(int mode)
    {
        GizmoDrag drag = this.getHotkeyDrag();
        boolean ray = BBSSettings.transformHotkeys3dRay.get() && drag != null;

        HotkeyTarget target = this.nextHotkeyTarget(mode, ray);

        if (target == HotkeyTarget.VIEW)
        {
            this.enableViewRotate(drag, true);
        }
        else if (target == HotkeyTarget.SPHERE)
        {
            this.enableSphereRotate(drag, true);
        }
        else if (target == HotkeyTarget.SCREEN)
        {
            this.enableScreenTranslate(drag, true);
        }
        else
        {
            this.enableHotkeyAxis(mode, target.axis, drag);
        }
    }

    private HotkeyTarget currentHotkeyTarget(int mode)
    {
        if (!this.editing || this.mode != mode)
        {
            return null;
        }

        if (this.dragKind == DragKind.VIEW) return HotkeyTarget.VIEW;
        if (this.isSphereRotate()) return HotkeyTarget.SPHERE;
        if (this.dragKind == DragKind.SCREEN) return HotkeyTarget.SCREEN;
        if (this.axis == Axis.Y) return HotkeyTarget.Y;
        if (this.axis == Axis.Z) return HotkeyTarget.Z;

        return HotkeyTarget.X;
    }

    private HotkeyTarget nextHotkeyTarget(int mode, boolean ray)
    {
        ValueOrder order = mode == 0 ? BBSSettings.translateHotkeyOrder : (mode == 1 ? BBSSettings.scaleHotkeyOrder : BBSSettings.rotateHotkeyOrder);
        List<HotkeyTarget> steps = new ArrayList<>();

        for (String token : order.get())
        {
            HotkeyTarget target = HotkeyTarget.byToken(token);

            if (target == null || (target.needsRay && !ray))
            {
                continue;
            }

            if (target == HotkeyTarget.SPHERE && !BBSSettings.rotate3dSphere.get())
            {
                continue;
            }

            steps.add(target);
        }

        if (steps.isEmpty())
        {
            return HotkeyTarget.X;
        }

        int index = steps.indexOf(this.currentHotkeyTarget(mode));

        return steps.get((index + 1) % steps.size());
    }

    private void enableHotkeyAxis(int mode, Axis axis, GizmoDrag drag)
    {
        if (Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.values()[mode]))
        {
            return;
        }

        UIContext context = this.getContext();

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = mode;
        this.dragKind = DragKind.AXIS;
        this.axis = axis;
        this.axis2 = null;
        this.hotkeyMode = true;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;

        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        if (this.useRayDrag())
        {
            this.beginRayDrag(context.mouseX, context.mouseY);
        }

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public void enableMode(int mode, Axis axis)
    {
        this.enableMode(mode, axis, null, null);
    }

    public void enableMode(int mode, Axis axis, Axis axis2, GizmoDrag drag)
    {
        DragKind previousKind = this.dragKind;

        this.clearGizmoDrag();

        if (axis == null && Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.values()[mode]))
        {
            return;
        }

        UIContext context = this.getContext();

        if (context == null)
        {
            return;
        }

        if (this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            if (axis == null)
            {
                if (previousKind == DragKind.SCREEN)
                {
                    this.axis = Axis.X;
                }
                else
                {
                    Axis[] values = Axis.values();

                    this.axis = values[MathUtils.cycler(this.axis != null ? this.axis.ordinal() + 1 : 0, 0, values.length - 1)];
                }

                this.axis2 = null;
            }
            else
            {
                this.axis = axis;
                this.axis2 = axis2;
            }

            this.dragKind = DragKind.AXIS;
            this.drag = drag;
            this.restore(true);
        }
        else
        {
            if (axis == null && mode == 0 && BBSSettings.transformHotkeys3dRay.get() && drag != null)
            {
                this.axis = Axis.X;
                this.axis2 = Axis.Y;
                this.dragKind = DragKind.SCREEN;
            }
            else
            {
                this.axis = axis == null ? Axis.X : axis;
                this.axis2 = axis2;
                this.dragKind = DragKind.AXIS;
            }

            this.drag = drag;
            this.lastX = context.mouseX;
            this.lastY = context.mouseY;
        }

        this.editing = true;
        this.mode = mode;
        this.hotkeyMode = axis == null;

        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        if (this.useRayDrag())
        {
            this.beginRayDrag(context.mouseX, context.mouseY);
        }
        else if (drag != null && !this.hotkeyMode)
        {
            this.beginGizmoDrag(drag);
        }

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public void enableTrackball(GizmoDrag drag)
    {
        this.enableTrackball(drag, false);
    }

    public void enableTrackball(GizmoDrag drag, boolean hotkeyMode)
    {
        UIContext context = this.getContext();

        if (hotkeyMode && Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.ROTATE))
        {
            return;
        }

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = 2;
        this.axis = null;
        this.axis2 = null;
        this.dragKind = DragKind.TRACKBALL;
        this.trackballAxis = Axis.X;
        this.hotkeyMode = hotkeyMode;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        this.trackballAccumX = 0F;
        this.trackballAccumY = 0F;
        this.trackballRollDeg = 0F;
        this.beginRayRotateTrackball(context.mouseX, context.mouseY);

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public void enableArcball(GizmoDrag drag)
    {
        this.enableArcball(drag, false);
    }

    public void enableArcball(GizmoDrag drag, boolean hotkeyMode)
    {
        UIContext context = this.getContext();

        if (hotkeyMode && Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.ROTATE))
        {
            return;
        }

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = 2;
        this.axis = null;
        this.axis2 = null;
        this.dragKind = DragKind.ARCBALL;
        this.trackballAxis = Axis.X;
        this.hotkeyMode = hotkeyMode;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        this.trackballRollDeg = 0F;
        this.arcballAccum.identity();
        this.arcballAnchored = false;
        this.beginRayRotateArcball(context.mouseX, context.mouseY);

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public void enableViewRotate(GizmoDrag drag)
    {
        this.enableViewRotate(drag, false);
    }

    public void enableViewRotate(GizmoDrag drag, boolean hotkeyMode)
    {
        UIContext context = this.getContext();

        if (hotkeyMode && Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.ROTATE))
        {
            return;
        }

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = 2;
        this.axis = null;
        this.axis2 = null;
        this.dragKind = DragKind.VIEW;
        this.hotkeyMode = hotkeyMode;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);
        this.beginRayRotateView(context.mouseX, context.mouseY);

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public void enableUniformScale(GizmoDrag drag)
    {
        this.enableUniformScale(drag, false);
    }

    /** Start a uniform three-axis scale from the center gizmo handle. */
    public void enableUniformScale(GizmoDrag drag, boolean hotkeyMode)
    {
        if (hotkeyMode && Gizmo.INSTANCE.getMode() != Gizmo.Mode.COMBINED && Gizmo.INSTANCE.setMode(Gizmo.Mode.SCALE))
        {
            return;
        }

        UIContext context = this.getContext();

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = 1;
        this.dragKind = DragKind.AXIS;
        this.scaleAll = true;
        this.axis = Axis.X;
        this.axis2 = null;
        this.hotkeyMode = hotkeyMode;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    public boolean isScaleAll()
    {
        return this.scaleAll;
    }

    public void enableScreenTranslate(GizmoDrag drag)
    {
        this.enableScreenTranslate(drag, false);
    }

    public void enableScreenTranslate(GizmoDrag drag, boolean hotkeyMode)
    {
        UIContext context = this.getContext();

        if (context == null || this.transform == null)
        {
            return;
        }

        this.clearNumericInput();

        if (this.editing)
        {
            this.restore(true);
        }

        this.clearGizmoDrag();
        this.editing = true;
        this.mode = 0;
        this.axis = Axis.X;
        this.axis2 = Axis.Y;
        this.dragKind = DragKind.SCREEN;
        this.hotkeyMode = hotkeyMode;
        this.drag = drag;
        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.cache.copy(this.transform);
        Gizmo.INSTANCE.trackTransform(this);

        if (this.useRayDrag())
        {
            this.beginRayDrag(context.mouseX, context.mouseY);
        }
        else if (drag != null && !this.hotkeyMode)
        {
            this.beginGizmoDrag(drag);
        }

        if (!this.handler.hasParent())
        {
            context.menu.overlay.add(this.handler);
        }
    }

    private void setEditingAxis(Axis axis)
    {
        this.axis = axis;
        this.axis2 = null;
        this.dragKind = DragKind.AXIS;
        this.scaleAll = false;

        if (!this.editing)
        {
            return;
        }

        this.restore(true);

        if (this.useRayDrag())
        {
            UIContext context = this.getContext();

            if (context != null)
            {
                this.beginRayDrag(context.mouseX, context.mouseY);
            }
        }

        if (this.numericActive)
        {
            this.applyNumericInput();
        }
    }

    public void enableSphereRotate(GizmoDrag drag)
    {
        this.enableSphereRotate(drag, false);
    }

    public void enableSphereRotate(GizmoDrag drag, boolean hotkeyMode)
    {
        if (BBSSettings.rotate3dSphereMode.get() == 1) this.enableArcball(drag, hotkeyMode);
        else this.enableTrackball(drag, hotkeyMode);
    }

    private Vector3f getValue()
    {
        if (this.transform == null)
        {
            return new Vector3f();
        }

        if (this.mode == 1)
        {
            return this.transform.scale;
        }
        else if (this.mode == 2)
        {
            return this.local && BBSSettings.gizmos.get() ? this.transform.rotate2 : this.transform.rotate;
        }

        return this.transform.translate;
    }

    private void restore(boolean fully)
    {
        if (this.mode == 0 || fully) this.setT(null, this.cache.translate.x, this.cache.translate.y, this.cache.translate.z);
        if (this.mode == 1 || fully) this.setS(null, this.cache.scale.x, this.cache.scale.y, this.cache.scale.z);
        if (this.mode == 2 || fully)
        {
            this.setR(null, MathUtils.toDeg(this.cache.rotate.x), MathUtils.toDeg(this.cache.rotate.y), MathUtils.toDeg(this.cache.rotate.z));
            this.setR2(null, MathUtils.toDeg(this.cache.rotate2.x), MathUtils.toDeg(this.cache.rotate2.y), MathUtils.toDeg(this.cache.rotate2.z));
        }
    }

    private void disable()
    {
        this.editing = false;
        this.hotkeyMode = false;
        this.clearGizmoDrag();
        this.clearNumericInput();
        Gizmo.INSTANCE.clearTrackedTransform(this);

        if (this.handler.hasParent())
        {
            this.handler.removeFromParent();
        }
    }

    public void acceptChanges()
    {
        this.disable();
        this.setTransform(this.transform);
        this.endGesture();
    }

    public void rejectChanges()
    {
        this.disable();

        if (this.transform == null)
        {
            return;
        }

        this.restore(true);
        this.setTransform(this.transform);
    }

    private void clearNumericInput()
    {
        this.numericInput.setLength(0);
        this.numericActive = false;
    }

    private void stopNumericInput(UIContext context)
    {
        this.clearNumericInput();
        this.restore(true);

        this.lastX = context.mouseX;
        this.lastY = context.mouseY;
        this.resetFineCursor(context.mouseX, context.mouseY);
        this.setTransform(this.transform);
    }

    private boolean handleNumericInputKey(UIContext context)
    {
        if (!this.acceptsNumericInput())
        {
            return false;
        }

        if (this.mode == 2 && this.isSphereRotate())
        {
            if (context.isPressed(GLFW.GLFW_KEY_X) || context.isRepeated(GLFW.GLFW_KEY_X))
            {
                this.trackballAxis = Axis.X;

                if (this.numericActive)
                {
                    this.applyNumericInput();
                }

                return true;
            }

            if (context.isPressed(GLFW.GLFW_KEY_Y) || context.isRepeated(GLFW.GLFW_KEY_Y))
            {
                this.trackballAxis = Axis.Y;

                if (this.numericActive)
                {
                    this.applyNumericInput();
                }

                return true;
            }
        }

        if (context.isPressed(GLFW.GLFW_KEY_BACKSPACE) || context.isRepeated(GLFW.GLFW_KEY_BACKSPACE))
        {
            if (!this.numericActive)
            {
                return false;
            }

            if (this.numericInput.length() > 0)
            {
                this.numericInput.deleteCharAt(this.numericInput.length() - 1);
            }

            if (this.numericInput.length() == 0)
            {
                this.stopNumericInput(context);
            }
            else
            {
                this.applyNumericInput();
            }

            return true;
        }

        return false;
    }

    private boolean isNumericInputCharacter(char character)
    {
        return (character >= '0' && character <= '9')
            || character == '.'
            || character == '-'
            || character == '+'
            || character == '*'
            || character == '/'
            || character == '('
            || character == ')';
    }

    private boolean acceptsNumericInput()
    {
        if (!this.editing || !this.hotkeyMode || this.transform == null)
        {
            return false;
        }

        if (this.dragKind == DragKind.SCREEN)
        {
            return false;
        }

        return this.mode != 2 || this.dragKind != DragKind.AXIS || this.axis != null;
    }

    private Double evaluateNumericInput()
    {
        String text = this.numericInput.toString().trim();

        if (text.isEmpty())
        {
            return null;
        }

        try
        {
            MathBuilder builder = new MathBuilder();

            return builder.parse(text).get().doubleValue();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private String numericInputDisplay()
    {
        return this.numericInput.length() == 0 ? "0" : this.numericInput.toString();
    }

    private void applyNumericInput()
    {
        if (this.transform == null)
        {
            return;
        }

        Double value = this.evaluateNumericInput();

        if (value == null || !Double.isFinite(value))
        {
            return;
        }

        if (this.mode == 0)
        {
            this.applyNumericTranslate(value);
        }
        else if (this.mode == 1)
        {
            this.applyNumericScale(value);
        }
        else if (this.mode == 2)
        {
            if (this.dragKind == DragKind.VIEW)
            {
                this.applyNumericAxisRotation(value, this.viewLocalAxis);
            }
            else if (this.isSphereRotate())
            {
                this.applyNumericAxisRotation(value, this.trackballAxis == Axis.Y ? this.trackballRightLocal : this.trackballUpLocal);
            }
            else
            {
                this.applyNumericRotate(value);
            }
        }

        this.setTransform(this.transform);
    }

    private void applyNumericTranslate(double value)
    {
        if (this.local)
        {
            Vector3f offset = this.calculateLocalVector(value, this.axis);

            if (this.axis2 != null)
            {
                offset.add(this.calculateLocalVector(value, this.axis2));
            }

            this.setT(null,
                this.cache.translate.x + offset.x,
                this.cache.translate.y + offset.y,
                this.cache.translate.z + offset.z
            );

            return;
        }

        Vector3f translate = new Vector3f(this.cache.translate);

        if (this.axis == Axis.X || this.axis2 == Axis.X) translate.x = this.cache.translate.x + (float) (double) value;
        if (this.axis == Axis.Y || this.axis2 == Axis.Y) translate.y = this.cache.translate.y + (float) (double) value;
        if (this.axis == Axis.Z || this.axis2 == Axis.Z) translate.z = this.cache.translate.z + (float) (double) value;

        this.setT(null, translate.x, translate.y, translate.z);
    }

    private void applyNumericScale(double value)
    {
        boolean all = this.scaleAll || Window.isCtrlPressed();
        Vector3f scale = new Vector3f(this.cache.scale);

        if (all || this.axis == Axis.X || this.axis2 == Axis.X) scale.x = (float) (this.cache.scale.x * value);
        if (all || this.axis == Axis.Y || this.axis2 == Axis.Y) scale.y = (float) (this.cache.scale.y * value);
        if (all || this.axis == Axis.Z || this.axis2 == Axis.Z) scale.z = (float) (this.cache.scale.z * value);

        this.setS(null, scale.x, scale.y, scale.z);
    }

    private void applyNumericRotate(double value)
    {
        boolean gizmoSpace = this.local && BBSSettings.gizmos.get();
        Vector3f source = gizmoSpace ? this.cache.rotate2 : this.cache.rotate;
        float x = MathUtils.toDeg(source.x);
        float y = MathUtils.toDeg(source.y);
        float z = MathUtils.toDeg(source.z);

        if (this.axis == Axis.X || this.axis2 == Axis.X) x += value;
        if (this.axis == Axis.Y || this.axis2 == Axis.Y) y += value;
        if (this.axis == Axis.Z || this.axis2 == Axis.Z) z += value;

        if (gizmoSpace) this.setR2(null, x, y, z);
        else this.setR(null, x, y, z);
    }

    private void applyNumericAxisRotation(double degrees, Vector3f localAxis)
    {
        if (localAxis.lengthSquared() < 1.0E-8F)
        {
            return;
        }

        boolean gizmoSpace = this.dragRotateGizmoSpace;
        Vector3f source = gizmoSpace ? this.cache.rotate2 : this.cache.rotate;
        Vector3f euler = new Matrix3f()
            .rotation(MathUtils.toRad((float) degrees), localAxis)
            .mul(new Matrix3f().rotationZ(source.z).rotateY(source.y).rotateX(source.x))
            .getEulerAnglesZYX(new Vector3f());
        float x = unwrapDeg(MathUtils.toDeg(euler.x), MathUtils.toDeg(source.x));
        float y = unwrapDeg(MathUtils.toDeg(euler.y), MathUtils.toDeg(source.y));
        float z = unwrapDeg(MathUtils.toDeg(euler.z), MathUtils.toDeg(source.z));

        if (gizmoSpace) this.setR2(null, x, y, z);
        else this.setR(null, x, y, z);
    }

    private boolean shouldSnap(int mode)
    {
        return this.editing && this.mode == mode && Window.isCtrlPressed() && !this.numericActive;
    }

    private static double snap(double value, float step)
    {
        return step <= 0F ? value : Math.round(value / step) * (double) step;
    }

    private double snapGizmoValue(double value)
    {
        if (this.dragKind != DragKind.AXIS || !this.shouldSnap(2))
        {
            return value;
        }

        return snap(value, BBSSettings.snapRotate.get());
    }

    @Override
    protected void internalSetT(double x, Axis axis)
    {
        if (this.transform == null)
        {
            return;
        }

        if (this.local)
        {
            try
            {
                Vector3f vector3f = this.calculateLocalVector(x, axis);

                this.setT(null,
                    this.transform.translate.x + vector3f.x,
                    this.transform.translate.y + vector3f.y,
                    this.transform.translate.z + vector3f.z
                );
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        else
        {
            super.internalSetT(x, axis);
        }
    }

    @Override
    public void setT(Axis axis, double x, double y, double z)
    {
        if (this.transform == null)
        {
            return;
        }

        this.preCallback();
        this.transform.translate.set((float) x, (float) y, (float) z);
        this.postCallback();
    }

    @Override
    public void setS(Axis axis, double x, double y, double z)
    {
        if (this.transform == null)
        {
            return;
        }

        this.preCallback();
        this.transform.scale.set((float) x, (float) y, (float) z);
        this.postCallback();
    }

    @Override
    public void setR(Axis axis, double x, double y, double z)
    {
        if (this.transform == null)
        {
            return;
        }

        this.preCallback();
        this.transform.rotate.set(MathUtils.toRad((float) x), MathUtils.toRad((float) y), MathUtils.toRad((float) z));
        this.postCallback();
    }

    @Override
    public void setR2(Axis axis, double x, double y, double z)
    {
        if (this.transform == null)
        {
            return;
        }

        this.preCallback();
        this.transform.rotate2.set(MathUtils.toRad((float) x), MathUtils.toRad((float) y), MathUtils.toRad((float) z));
        this.postCallback();
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (this.editing)
        {
            if (context.isPressed(GLFW.GLFW_KEY_ENTER))
            {
                this.acceptChanges();

                return true;
            }
            else if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
            {
                this.rejectChanges();

                return true;
            }
            else if (this.handleNumericInputKey(context))
            {
                return true;
            }
        }

        return super.subKeyPressed(context);
    }

    @Override
    protected boolean subTextInput(UIContext context)
    {
        if (!this.acceptsNumericInput())
        {
            return super.subTextInput(context);
        }

        char character = context.getInputCharacter();

        if (!this.isNumericInputCharacter(character))
        {
            return super.subTextInput(context);
        }

        this.numericInput.append(character);
        this.numericActive = true;
        this.applyNumericInput();

        return true;
    }

    private void updateFineCursor(int mouseX, int mouseY)
    {
        if (!this.fineHasLast)
        {
            this.resetFineCursor(mouseX, mouseY);

            return;
        }

        if (Window.isShiftPressed())
        {
            float keep = 1F - FINE_DRAG_FACTOR;

            this.fineOffsetX += (mouseX - this.fineLastX) * keep;
            this.fineOffsetY += (mouseY - this.fineLastY) * keep;
        }

        this.fineLastX = mouseX;
        this.fineLastY = mouseY;
    }

    private void resetFineCursor(int mouseX, int mouseY)
    {
        this.fineOffsetX = 0F;
        this.fineOffsetY = 0F;
        this.fineLastX = mouseX;
        this.fineLastY = mouseY;
        this.fineHasLast = true;
    }

    private int fineX(int mouseX)
    {
        return Math.round(mouseX - this.fineOffsetX);
    }

    private int fineY(int mouseY)
    {
        return Math.round(mouseY - this.fineOffsetY);
    }

    @Override
    public void render(UIContext context)
    {
        if (this.editing && !this.numericActive && this.checker.isTime())
        {
            /* UIContext.mouseX can't be used because when cursor is outside of window
             * its position stops being updated. That's why it has to be queried manually
             * through GLFW...
             *
             * It gets updated outside the window only when one of mouse buttons is
             * being held! */
            boolean remoteInput = BBSUiRemoteHeldState.isActive();

            if (!remoteInput)
            {
                GLFW.glfwGetCursorPos(Window.getWindow(), CURSOR_X, CURSOR_Y);
            }

            Minecraft mc = Minecraft.getInstance();
            int w = mc.getWindow().getScreenWidth();
            int h = mc.getWindow().getScreenHeight();

            double rawX = remoteInput ? w / 2D : CURSOR_X[0];
            double rawY = remoteInput ? h / 2D : CURSOR_Y[0];
            double fx = Math.ceil(w / (double) context.menu.width);
            double fy = Math.ceil(h / (double) context.menu.height);
            int border = 5;
            int borderPadding = border + 1;

            this.updateFineCursor(context.mouseX, context.mouseY);

            if (!remoteInput && rawX <= border)
            {
                Window.moveCursor(w - borderPadding, (int) mc.mouseHandler.ypos());

                this.lastX = context.menu.width - (int) (borderPadding / fx);
                this.checker.mark();
                this.resetFineCursor(this.lastX, context.mouseY);

                if (this.useRayDrag()) this.beginRayDrag(this.lastX, context.mouseY);
            }
            else if (!remoteInput && rawX >= w - border)
            {
                Window.moveCursor(borderPadding, (int) mc.mouseHandler.ypos());

                this.lastX = (int) (borderPadding / fx);
                this.checker.mark();
                this.resetFineCursor(this.lastX, context.mouseY);

                if (this.useRayDrag()) this.beginRayDrag(this.lastX, context.mouseY);
            }
            else if (!remoteInput && rawY <= border)
            {
                Window.moveCursor((int) mc.mouseHandler.xpos(), h - borderPadding);

                this.lastY = context.menu.height - (int) (borderPadding / fy);
                this.checker.mark();
                this.resetFineCursor(context.mouseX, this.lastY);

                if (this.useRayDrag()) this.beginRayDrag(context.mouseX, this.lastY);
            }
            else if (!remoteInput && rawY >= h - border)
            {
                Window.moveCursor((int) mc.mouseHandler.xpos(), borderPadding);

                this.lastY = (int) (borderPadding / fy);
                this.checker.mark();
                this.resetFineCursor(context.mouseX, this.lastY);

                if (this.useRayDrag()) this.beginRayDrag(context.mouseX, this.lastY);
            }
            else
            {
                int dx = context.mouseX - this.lastX;
                int dy = context.mouseY - this.lastY;
                Vector3f vector = this.getValue();
                boolean all = this.mode == 1 && (this.scaleAll || Window.isCtrlPressed());
                UITrackpad reference = this.mode == 0 ? this.tx : (this.mode == 1 ? this.sx : this.rx);
                float factor = (float) reference.getValueModifier() * (Window.isShiftPressed() ? FINE_DRAG_FACTOR : 1F);

                if (this.useRayDrag())
                {
                    this.applyRayDrag(this.fineX(context.mouseX), this.fineY(context.mouseY));
                    this.setTransform(this.transform);
                }
                else if (this.mode == 0 && this.applyGizmoTranslate(dx, dy, factor))
                {
                    this.setTransform(this.transform);
                }
                else if (this.mode == 1 && this.applyGizmoScale(dx, dy, factor, all))
                {
                    this.setTransform(this.transform);
                }
                else if (this.mode == 2 && this.applyGizmoRotate(context.mouseX, context.mouseY, this.lastX, this.lastY, factor))
                {
                    this.setTransform(this.transform);
                }
                else if (this.local && this.mode == 0)
                {
                    Vector3f vector3f = this.calculateLocalVector(factor * dx, this.axis);

                    if (this.axis2 != null)
                    {
                        vector3f.add(this.calculateLocalVector(factor * dx, this.axis2));
                    }

                    float x = vector.x + vector3f.x;
                    float y = vector.y + vector3f.y;
                    float z = vector.z + vector3f.z;

                    if (this.shouldSnap(0))
                    {
                        x = (float) snap(x, BBSSettings.snapTranslate.get());
                        y = (float) snap(y, BBSSettings.snapTranslate.get());
                        z = (float) snap(z, BBSSettings.snapTranslate.get());
                    }

                    this.setT(null, x, y, z);
                    this.setTransform(this.transform);
                }
                else
                {
                    Vector3f vector3f = new Vector3f(vector);

                    if (this.mode == 2)
                    {
                        vector3f.mul(180F / MathUtils.PI);
                    }

                    if (this.axis == Axis.X || all) vector3f.x += factor * dx;
                    if (this.axis == Axis.Y || all) vector3f.y += factor * dx;
                    if (this.axis == Axis.Z || all) vector3f.z += factor * dx;
                    if (!all && this.axis2 == Axis.X) vector3f.x += factor * dx;
                    if (!all && this.axis2 == Axis.Y) vector3f.y += factor * dx;
                    if (!all && this.axis2 == Axis.Z) vector3f.z += factor * dx;

                    if (this.shouldSnap(this.mode))
                    {
                        float step = this.mode == 0 ? BBSSettings.snapTranslate.get()
                            : this.mode == 1 ? BBSSettings.snapScale.get()
                            : BBSSettings.snapRotate.get();

                        vector3f.x = (float) snap(vector3f.x, step);
                        vector3f.y = (float) snap(vector3f.y, step);
                        vector3f.z = (float) snap(vector3f.z, step);
                    }

                    if (this.mode == 0) this.setT(null, vector3f.x, vector3f.y, vector3f.z);
                    if (this.mode == 1) this.setS(null, vector3f.x, vector3f.y, vector3f.z);
                    if (this.mode == 2)
                    {
                        if (this.local && BBSSettings.gizmos.get()) this.setR2(null, vector3f.x, vector3f.y, vector3f.z);
                        else this.setR(null, vector3f.x, vector3f.y, vector3f.z);
                    }

                    this.setTransform(this.transform);
                }

                this.lastX = context.mouseX;
                this.lastY = context.mouseY;
            }
        }

        if (this.gesturePollOnly)
        {
            return;
        }

        super.render(context);

        if (this.editing)
        {
            String label = UIKeys.TRANSFORMS_EDITING.get();
            FontRenderer font = context.batcher.getFont();
            int x = this.area.mx(font.getWidth(label));
            int y = this.area.my(font.getHeight());

            context.batcher.textCard(label, x, y, Colors.WHITE, BBSSettings.primaryColor(Colors.A50));

            if (this.numericActive)
            {
                String numericLabel = this.numericInputDisplay();
                int nx = this.area.mx(font.getWidth(numericLabel));
                int ny = y + font.getHeight() + 8;

                context.batcher.textCard(numericLabel, nx, ny, Colors.WHITE, BBSSettings.primaryColor(Colors.A50));
                context.batcher.textCard(numericLabel, context.mouseX + 12, context.mouseY + 12, Colors.WHITE, Colors.A50);
            }
        }
    }

    /** Poll an active modal transform even when its property panel is hidden or clipped. */
    private void pollGesture(UIContext context)
    {
        this.gesturePollOnly = true;

        try
        {
            this.render(context);
        }
        finally
        {
            this.gesturePollOnly = false;
        }
    }

    public void beginGizmoDrag(Gizmo.DragContext context)
    {
        this.gizmoDrag = false;
        this.gizmoInvReady = false;

        if (context == null || !context.ready)
        {
            return;
        }

        this.gizmoViewportX = context.viewportX;
        this.gizmoViewportY = context.viewportY;
        this.gizmoViewportW = context.viewportW;
        this.gizmoViewportH = context.viewportH;

        this.gizmoMvp.set(context.projection).mul(context.modelView);
        this.gizmoMvp.invert(this.gizmoInvMvp);
        this.gizmoInvReady = true;

        if (!this.projectGizmoPoint(0F, 0F, 0F, this.gizmoOrigin2D))
        {
            return;
        }

        if (this.projectGizmoPoint(1F, 0F, 0F, this.gizmoTmp2D))
        {
            this.gizmoAxisX.set(this.gizmoTmp2D).sub(this.gizmoOrigin2D);
            this.gizmoAxisXLenSq = this.gizmoAxisX.lengthSquared();
        }
        else
        {
            this.gizmoAxisXLenSq = 0F;
        }

        if (this.projectGizmoPoint(0F, 1F, 0F, this.gizmoTmp2D))
        {
            this.gizmoAxisY.set(this.gizmoTmp2D).sub(this.gizmoOrigin2D);
            this.gizmoAxisYLenSq = this.gizmoAxisY.lengthSquared();
        }
        else
        {
            this.gizmoAxisYLenSq = 0F;
        }

        if (this.projectGizmoPoint(0F, 0F, 1F, this.gizmoTmp2D))
        {
            this.gizmoAxisZ.set(this.gizmoTmp2D).sub(this.gizmoOrigin2D);
            this.gizmoAxisZLenSq = this.gizmoAxisZ.lengthSquared();
        }
        else
        {
            this.gizmoAxisZLenSq = 0F;
        }

        this.gizmoDrag = true;
    }

    public void beginGizmoDrag(GizmoDrag drag)
    {
        if (drag == null)
        {
            this.clearGizmoDrag();

            return;
        }

        this.drag = drag;

        UIContext context = this.getContext();

        if (context != null)
        {
            this.beginRayDrag(context.mouseX, context.mouseY);
        }
    }

    private void clearGizmoDrag()
    {
        this.gizmoDrag = false;
        this.gizmoInvReady = false;
        this.drag = null;
        this.dragHasStart = false;
        this.arcballAnchored = false;
        this.fineHasLast = false;
        this.scaleAll = false;
        this.axis2 = null;
        this.dragKind = DragKind.AXIS;
    }

    private boolean useRayDrag()
    {
        if (this.hotkeyMode && !BBSSettings.transformHotkeys3dRay.get())
        {
            return false;
        }

        if (this.scaleAll)
        {
            return false;
        }

        return this.editing
            && this.transform != null
            && this.drag != null
            && (this.mode != 2 || this.axis2 == null || this.isSphereRotate());
    }

    public boolean isScreenTranslate()
    {
        return this.mode == 0 && this.dragKind == DragKind.SCREEN;
    }

    private void beginRayDrag(int mouseX, int mouseY)
    {
        if (this.drag == null || this.transform == null)
        {
            this.dragHasStart = false;

            return;
        }

        if (this.mode == 0)
        {
            this.beginRayTranslate(mouseX, mouseY);
        }
        else if (this.mode == 1)
        {
            this.beginRayScale(mouseX, mouseY);
        }
        else if (this.mode == 2)
        {
            if (this.dragKind == DragKind.TRACKBALL) this.beginRayRotateTrackball(mouseX, mouseY);
            else if (this.dragKind == DragKind.ARCBALL) this.beginRayRotateArcball(mouseX, mouseY);
            else if (this.dragKind == DragKind.VIEW) this.beginRayRotateView(mouseX, mouseY);
            else this.beginRayRotate(mouseX, mouseY);
        }
        else
        {
            this.dragHasStart = false;
        }
    }

    private void applyRayDrag(int mouseX, int mouseY)
    {
        if (!this.dragHasStart || this.transform == null)
        {
            return;
        }

        if (this.mode == 2)
        {
            if (this.dragKind == DragKind.TRACKBALL) this.applyRayRotateTrackball(mouseX, mouseY);
            else if (this.dragKind == DragKind.ARCBALL) this.applyRayRotateArcball(mouseX, mouseY);
            else if (this.dragKind == DragKind.VIEW) this.applyRayRotateView(mouseX, mouseY);
            else this.applyScreenRotate(mouseX, mouseY);

            return;
        }

        Vector3d hit = new Vector3d();

        if (!this.drag.intersectPlane(mouseX, mouseY, this.dragPlaneNormal, hit))
        {
            return;
        }

        if (this.mode == 0)
        {
            this.applyRayTranslate(hit);
        }
        else if (this.mode == 1)
        {
            this.applyRayScale(hit);
        }
    }

    private void beginRayTranslate(int mouseX, int mouseY)
    {
        if (this.isScreenTranslate())
        {
            this.beginRayTranslateScreen(mouseX, mouseY);

            return;
        }

        Matrix3f jacobian = new Matrix3f(this.drag.translateJacobian);

        if (this.local)
        {
            Matrix3f rotation = new Matrix3f()
                .rotateX(this.model ? MathUtils.PI : 0F)
                .mul(this.transform.createRotationMatrix());

            this.dragTranslateBasis.set(rotation);
            this.dragWorldBasis.set(jacobian).mul(rotation);
        }
        else
        {
            Matrix3f inverse = new Matrix3f(jacobian);

            if (Math.abs(inverse.determinant()) < 1.0E-8F) inverse.identity();
            else inverse.invert();

            this.dragTranslateBasis.set(inverse).mul(this.drag.gizmoWorldAxes);
            this.dragWorldBasis.set(this.drag.gizmoWorldAxes);
        }

        if (this.axis2 == null) this.drag.planeNormalForAxis(mouseX, mouseY, this.dragWorldBasis, this.axis, this.dragPlaneNormal);
        else this.drag.planeNormalForPlane(this.dragWorldBasis, this.axis, this.axis2, this.dragPlaneNormal);

        this.dragStartTranslate.set(this.transform.translate);
        this.dragHasStart = this.drag.intersectPlane(mouseX, mouseY, this.dragPlaneNormal, this.dragStartHit);
    }

    private void beginRayTranslateScreen(int mouseX, int mouseY)
    {
        Matrix3f invView = this.drag.view.get3x3(new Matrix3f());

        if (Math.abs(invView.determinant()) < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        invView.invert();

        Vector3f right = invView.getColumn(0, new Vector3f());
        Vector3f up = invView.getColumn(1, new Vector3f());
        Vector3f forward = invView.getColumn(2, new Vector3f());

        if (right.lengthSquared() < 1.0E-8F || up.lengthSquared() < 1.0E-8F || forward.lengthSquared() < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        right.normalize();
        up.normalize();
        forward.normalize();

        Matrix3f cameraBasis = new Matrix3f();

        cameraBasis.setColumn(0, right);
        cameraBasis.setColumn(1, up);
        cameraBasis.setColumn(2, forward);

        Matrix3f inverse = new Matrix3f(this.drag.translateJacobian);

        if (Math.abs(inverse.determinant()) < 1.0E-8F) inverse.identity();
        else inverse.invert();

        this.dragTranslateBasis.set(inverse).mul(cameraBasis);
        this.dragScreenInverseJacobian.set(inverse);
        this.dragWorldBasis.set(cameraBasis);
        this.dragPlaneNormal.set(forward);

        this.dragStartTranslate.set(this.transform.translate);
        this.dragHasStart = this.drag.intersectPlane(mouseX, mouseY, this.dragPlaneNormal, this.dragStartHit);
    }

    private void applyRayTranslate(Vector3d hit)
    {
        Vector3f delta = new Vector3f(
            (float) (hit.x - this.dragStartHit.x),
            (float) (hit.y - this.dragStartHit.y),
            (float) (hit.z - this.dragStartHit.z)
        );
        Vector3f result = new Vector3f();

        this.accumulateAlongAxis(delta, this.axis, result);

        if (this.axis2 != null)
        {
            this.accumulateAlongAxis(delta, this.axis2, result);
        }

        float x = this.dragStartTranslate.x + result.x;
        float y = this.dragStartTranslate.y + result.y;
        float z = this.dragStartTranslate.z + result.z;

        if (this.shouldSnap(0))
        {
            x = (float) snap(x, BBSSettings.snapTranslate.get());
            y = (float) snap(y, BBSSettings.snapTranslate.get());
            z = (float) snap(z, BBSSettings.snapTranslate.get());
        }

        this.setT(null, x, y, z);
    }

    private void accumulateAlongAxis(Vector3f delta, Axis axis, Vector3f out)
    {
        Vector3f worldAxis = this.dragWorldBasis.getColumn(axis.ordinal(), new Vector3f());
        float lenSq = worldAxis.lengthSquared();

        if (lenSq < 1.0E-12F)
        {
            return;
        }

        float t = worldAxis.dot(delta) / lenSq;
        Vector3f translateAxis = this.dragTranslateBasis.getColumn(axis.ordinal(), new Vector3f());

        out.add(translateAxis.mul(t));
    }

    private void beginRayScale(int mouseX, int mouseY)
    {
        this.dragWorldBasis.set(this.drag.gizmoWorldAxes);

        if (this.axis2 == null) this.drag.planeNormalForAxis(mouseX, mouseY, this.dragWorldBasis, this.axis, this.dragPlaneNormal);
        else this.drag.planeNormalForPlane(this.dragWorldBasis, this.axis, this.axis2, this.dragPlaneNormal);

        if (!this.drag.intersectPlane(mouseX, mouseY, this.dragPlaneNormal, this.dragStartHit))
        {
            this.dragHasStart = false;

            return;
        }

        this.dragStartScale.set(this.transform.scale);
        this.dragHasStart = true;
    }

    private void applyRayScale(Vector3d hit)
    {
        boolean all = this.scaleAll || Window.isCtrlPressed();
        Vector3f scale = new Vector3f(this.dragStartScale);

        this.applyRayScaleAxis(hit, this.axis, all, scale);

        if (this.axis2 != null)
        {
            this.applyRayScaleAxis(hit, this.axis2, all, scale);
        }

        if (this.shouldSnap(1))
        {
            scale.x = (float) snap(scale.x, BBSSettings.snapScale.get());
            scale.y = (float) snap(scale.y, BBSSettings.snapScale.get());
            scale.z = (float) snap(scale.z, BBSSettings.snapScale.get());
        }

        this.setS(null, scale.x, scale.y, scale.z);
    }

    private void applyRayScaleAxis(Vector3d hit, Axis currentAxis, boolean all, Vector3f scale)
    {
        Vector3f axisDir = this.dragWorldBasis.getColumn(currentAxis.ordinal(), new Vector3f());

        if (axisDir.lengthSquared() < 1.0E-8F)
        {
            return;
        }

        axisDir.normalize();

        float currentProj = (float) ((hit.x - this.drag.gizmoOrigin.x) * axisDir.x
            + (hit.y - this.drag.gizmoOrigin.y) * axisDir.y
            + (hit.z - this.drag.gizmoOrigin.z) * axisDir.z);
        float startProj = (float) ((this.dragStartHit.x - this.drag.gizmoOrigin.x) * axisDir.x
            + (this.dragStartHit.y - this.drag.gizmoOrigin.y) * axisDir.y
            + (this.dragStartHit.z - this.drag.gizmoOrigin.z) * axisDir.z);
        float delta = currentProj - startProj;

        if (Math.abs(startProj) < 1.0E-4F)
        {
            if (all || currentAxis == Axis.X) scale.x += delta;
            if (all || currentAxis == Axis.Y) scale.y += delta;
            if (all || currentAxis == Axis.Z) scale.z += delta;
        }
        else
        {
            float ratio = currentProj / startProj;

            if (all || currentAxis == Axis.X) scale.x *= ratio;
            if (all || currentAxis == Axis.Y) scale.y *= ratio;
            if (all || currentAxis == Axis.Z) scale.z *= ratio;
        }
    }

    private void beginRayRotate(int mouseX, int mouseY)
    {
        if (this.axis == null)
        {
            this.dragHasStart = false;

            return;
        }

        Vector3f axisDir = this.drag.rotateAxes.getColumn(this.axis.ordinal(), new Vector3f());

        if (axisDir.lengthSquared() < 1.0E-8F || !this.drag.projectToScreen(this.drag.gizmoOrigin, this.dragScreenCenter))
        {
            this.dragHasStart = false;

            return;
        }

        axisDir.normalize();
        this.dragAxisDir.set(axisDir);
        this.dragLastScreenAngle = this.screenAngle(mouseX, mouseY);

        Vector3f intoScreen = new Vector3f(
            (float) (this.drag.gizmoOrigin.x - this.drag.cameraOrigin.x),
            (float) (this.drag.gizmoOrigin.y - this.drag.cameraOrigin.y),
            (float) (this.drag.gizmoOrigin.z - this.drag.cameraOrigin.z)
        );

        this.dragRotateSign = Math.signum(axisDir.dot(intoScreen));

        if (this.dragRotateSign == 0F)
        {
            this.dragRotateSign = 1F;
        }

        this.initialDragRingVec.set(this.computeStartRingVec(mouseX, mouseY, axisDir));
        this.accumulatedRotateDeg = 0F;
        this.dragRotateGizmoSpace = this.local && BBSSettings.gizmos.get();
        Vector3f source = this.dragRotateGizmoSpace ? this.transform.rotate2 : this.transform.rotate;

        this.dragStartRotateDeg.set(
            MathUtils.toDeg(source.x),
            MathUtils.toDeg(source.y),
            MathUtils.toDeg(source.z)
        );
        this.dragHasStart = true;
    }

    private void applyScreenRotate(int mouseX, int mouseY)
    {
        float current = this.screenAngle(mouseX, mouseY);
        float delta = current - this.dragLastScreenAngle;

        if (delta > MathUtils.PI) delta -= MathUtils.PI * 2F;
        else if (delta < -MathUtils.PI) delta += MathUtils.PI * 2F;

        this.dragLastScreenAngle = current;

        float angleDeg = MathUtils.toDeg(delta) * this.dragRotateSign;
        this.accumulatedRotateDeg += angleDeg;
        float x = this.dragStartRotateDeg.x;
        float y = this.dragStartRotateDeg.y;
        float z = this.dragStartRotateDeg.z;

        if (this.axis == Axis.X) x += angleDeg;
        else if (this.axis == Axis.Y) y += angleDeg;
        else if (this.axis == Axis.Z) z += angleDeg;

        this.dragStartRotateDeg.set(x, y, z);

        if (this.axis != null)
        {
            switch (this.axis)
            {
                case X: x = (float) this.snapGizmoValue(x); break;
                case Y: y = (float) this.snapGizmoValue(y); break;
                case Z: z = (float) this.snapGizmoValue(z); break;
            }
        }

        if (this.dragRotateGizmoSpace) this.setR2(null, x, y, z);
        else this.setR(null, x, y, z);
    }

    private void beginRayRotateView(int mouseX, int mouseY)
    {
        if (this.drag == null)
        {
            this.dragHasStart = false;

            return;
        }

        Vector3f viewAxis = new Vector3f(
            (float) (this.drag.cameraOrigin.x - this.drag.gizmoOrigin.x),
            (float) (this.drag.cameraOrigin.y - this.drag.gizmoOrigin.y),
            (float) (this.drag.cameraOrigin.z - this.drag.gizmoOrigin.z)
        );

        if (viewAxis.lengthSquared() < 1.0E-8F || !this.drag.projectToScreen(this.drag.gizmoOrigin, this.dragScreenCenter))
        {
            this.dragHasStart = false;

            return;
        }

        this.dragAxisDir.set(viewAxis.normalize());
        this.dragLastScreenAngle = this.screenAngle(mouseX, mouseY);
        this.viewGrabScreenAngle = this.dragLastScreenAngle;
        this.dragRotateSign = -1F;
        this.accumulatedRotateDeg = 0F;
        this.dragRotateGizmoSpace = this.local && BBSSettings.gizmos.get();

        Vector3f source = this.dragRotateGizmoSpace ? this.transform.rotate2 : this.transform.rotate;
        Matrix3f parentInverse = this.computeParentInverse(source);

        if (parentInverse == null)
        {
            this.dragHasStart = false;

            return;
        }

        parentInverse.transform(this.dragAxisDir, this.viewLocalAxis);

        if (this.viewLocalAxis.lengthSquared() < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        this.viewLocalAxis.normalize();
        this.dragHasStart = true;
    }

    private void applyRayRotateView(int mouseX, int mouseY)
    {
        float current = this.screenAngle(mouseX, mouseY);
        float delta = current - this.dragLastScreenAngle;

        if (delta > MathUtils.PI) delta -= MathUtils.PI * 2F;
        else if (delta < -MathUtils.PI) delta += MathUtils.PI * 2F;

        this.dragLastScreenAngle = current;
        float angle = delta * this.dragRotateSign;

        if (angle == 0F)
        {
            return;
        }

        this.accumulatedRotateDeg += MathUtils.toDeg(angle);
        this.applyAxisRotationRadians(angle, this.viewLocalAxis);
    }

    private void beginRayRotateTrackball(int mouseX, int mouseY)
    {
        if (this.drag == null)
        {
            this.dragHasStart = false;

            return;
        }

        this.dragRotateGizmoSpace = this.local && BBSSettings.gizmos.get();
        Vector3f source = this.dragRotateGizmoSpace ? this.cache.rotate2 : this.cache.rotate;
        Matrix3f parentInverse = this.computeParentInverse(source);
        Matrix3f invView = this.drag.view.get3x3(new Matrix3f());

        if (parentInverse == null || Math.abs(invView.determinant()) < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        invView.invert();
        parentInverse.transform(invView.getColumn(0, new Vector3f()).normalize(), this.trackballRightLocal);
        parentInverse.transform(invView.getColumn(1, new Vector3f()).normalize(), this.trackballUpLocal);
        parentInverse.transform(invView.getColumn(2, new Vector3f()).normalize(), this.trackballViewLocal);

        if (this.trackballRightLocal.lengthSquared() < 1.0E-8F || this.trackballUpLocal.lengthSquared() < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        this.trackballRightLocal.normalize();
        this.trackballUpLocal.normalize();
        this.trackballViewLocal.normalize();
        this.trackballLastX = mouseX;
        this.trackballLastY = mouseY;
        this.dragHasStart = true;
    }

    private void applyRayRotateTrackball(int mouseX, int mouseY)
    {
        int dx = mouseX - this.trackballLastX;
        int dy = mouseY - this.trackballLastY;

        this.trackballLastX = mouseX;
        this.trackballLastY = mouseY;

        if (dx == 0 && dy == 0)
        {
            return;
        }

        this.trackballAccumX += dx;
        this.trackballAccumY += dy;
        this.updateTrackballRotation();
    }

    private void updateTrackballRotation()
    {
        Vector3f source = this.dragRotateGizmoSpace ? this.cache.rotate2 : this.cache.rotate;
        Matrix3f startRotation = new Matrix3f()
            .rotationZ(source.z)
            .rotateY(source.y)
            .rotateX(source.x);
        float sensitivity = BBSSettings.trackballSensitivity.get();
        float yaw = MathUtils.toRad(this.trackballAccumX * sensitivity);
        float pitch = MathUtils.toRad(this.trackballAccumY * sensitivity);
        float roll = MathUtils.toRad(this.trackballRollDeg);
        Vector3f euler = new Matrix3f()
            .rotation(roll, this.trackballViewLocal)
            .rotate(yaw, this.trackballUpLocal.x, this.trackballUpLocal.y, this.trackballUpLocal.z)
            .rotate(pitch, this.trackballRightLocal.x, this.trackballRightLocal.y, this.trackballRightLocal.z)
            .mul(startRotation)
            .getEulerAnglesZYX(new Vector3f());
        Vector3f live = this.dragRotateGizmoSpace ? this.transform.rotate2 : this.transform.rotate;
        float x = unwrapDeg(MathUtils.toDeg(euler.x), MathUtils.toDeg(live.x));
        float y = unwrapDeg(MathUtils.toDeg(euler.y), MathUtils.toDeg(live.y));
        float z = unwrapDeg(MathUtils.toDeg(euler.z), MathUtils.toDeg(live.z));

        if (this.dragRotateGizmoSpace) this.setR2(null, x, y, z);
        else this.setR(null, x, y, z);
    }

    private float getSphereWorldRadius()
    {
        if (this.drag == null)
        {
            return 0F;
        }

        double dx = this.drag.gizmoOrigin.x - this.drag.cameraOrigin.x;
        double dy = this.drag.gizmoOrigin.y - this.drag.cameraOrigin.y;
        double dz = this.drag.gizmoOrigin.z - this.drag.cameraOrigin.z;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        return 0.22F * BBSSettings.axesScale.get() * BBSSettings.getAxesDistanceScale(distance);
    }

    private void beginRayRotateArcball(int mouseX, int mouseY)
    {
        if (this.arcballAnchored)
        {
            this.arcballAccum.premul(new Quaternionf().rotationTo(this.arcballStartLocal, this.arcballCurrentLocal));
            this.arcballAnchored = false;
        }

        Matrix3f parentInverse = this.computeParentInverse(this.cache.rotate);
        float radius = this.getSphereWorldRadius();

        if (parentInverse == null || radius <= 0F)
        {
            this.dragHasStart = false;

            return;
        }

        Vector3f view = new Vector3f(
            (float) (this.drag.cameraOrigin.x - this.drag.gizmoOrigin.x),
            (float) (this.drag.cameraOrigin.y - this.drag.gizmoOrigin.y),
            (float) (this.drag.cameraOrigin.z - this.drag.gizmoOrigin.z)
        );

        if (view.lengthSquared() < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        this.arcballViewWorld.set(view.normalize());
        this.arcballRadius = radius;
        this.arcballParentInverse.set(parentInverse);

        Matrix3f invView = this.drag.view.get3x3(new Matrix3f());

        if (Math.abs(invView.determinant()) < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        invView.invert();
        parentInverse.transform(invView.getColumn(0, new Vector3f()).normalize(), this.trackballRightLocal);
        parentInverse.transform(invView.getColumn(1, new Vector3f()).normalize(), this.trackballUpLocal);
        parentInverse.transform(invView.getColumn(2, new Vector3f()).normalize(), this.trackballViewLocal);

        if (this.trackballRightLocal.lengthSquared() < 1.0E-8F || this.trackballUpLocal.lengthSquared() < 1.0E-8F)
        {
            this.dragHasStart = false;

            return;
        }

        this.trackballRightLocal.normalize();
        this.trackballUpLocal.normalize();
        this.trackballViewLocal.normalize();

        Vector3f grab = this.mapArcball(mouseX, mouseY, new Vector3f());

        if (grab == null)
        {
            this.dragHasStart = false;

            return;
        }

        this.arcballParentInverse.transform(grab, this.arcballStartLocal).normalize();
        this.arcballCurrentLocal.set(this.arcballStartLocal);
        this.arcballAnchored = true;
        this.dragHasStart = true;
    }

    private Vector3f mapArcball(int mouseX, int mouseY, Vector3f out)
    {
        Vector3d hit = new Vector3d();

        if (!this.drag.intersectPlane(mouseX, mouseY, this.arcballViewWorld, hit))
        {
            return null;
        }

        float px = (float) (hit.x - this.drag.gizmoOrigin.x);
        float py = (float) (hit.y - this.drag.gizmoOrigin.y);
        float pz = (float) (hit.z - this.drag.gizmoOrigin.z);
        float r = this.arcballRadius;
        float dSq = px * px + py * py + pz * pz;
        float z = dSq < r * r / 2F
            ? (float) Math.sqrt(r * r - dSq)
            : r * r / (2F * (float) Math.sqrt(dSq));

        out.set(this.arcballViewWorld).mul(z).add(px, py, pz);

        return out.normalize();
    }

    private void applyRayRotateArcball(int mouseX, int mouseY)
    {
        Vector3f current = this.mapArcball(mouseX, mouseY, new Vector3f());

        if (current == null)
        {
            return;
        }

        this.arcballParentInverse.transform(current, this.arcballCurrentLocal).normalize();
        this.updateArcballRotation();
    }

    private void updateArcballRotation()
    {
        Vector3f source = this.cache.rotate;
        Matrix3f startRotation = new Matrix3f()
            .rotationZ(source.z)
            .rotateY(source.y)
            .rotateX(source.x);
        Quaternionf arc = new Quaternionf()
            .rotationTo(this.arcballStartLocal, this.arcballCurrentLocal)
            .mul(this.arcballAccum);
        Vector3f euler = new Matrix3f()
            .rotation(MathUtils.toRad(this.trackballRollDeg), this.trackballViewLocal)
            .rotate(arc)
            .mul(startRotation)
            .getEulerAnglesZYX(new Vector3f());
        Vector3f live = this.transform.rotate;
        float x = unwrapDeg(MathUtils.toDeg(euler.x), MathUtils.toDeg(live.x));
        float y = unwrapDeg(MathUtils.toDeg(euler.y), MathUtils.toDeg(live.y));
        float z = unwrapDeg(MathUtils.toDeg(euler.z), MathUtils.toDeg(live.z));

        this.setR(null, x, y, z);
    }

    private void applyAxisRotationRadians(float radians, Vector3f localAxis)
    {
        if (localAxis.lengthSquared() < 1.0E-8F)
        {
            return;
        }

        Vector3f source = this.dragRotateGizmoSpace ? this.transform.rotate2 : this.transform.rotate;
        Vector3f euler = new Matrix3f()
            .rotation(radians, localAxis)
            .mul(new Matrix3f().rotationZ(source.z).rotateY(source.y).rotateX(source.x))
            .getEulerAnglesZYX(new Vector3f());
        float x = unwrapDeg(MathUtils.toDeg(euler.x), MathUtils.toDeg(source.x));
        float y = unwrapDeg(MathUtils.toDeg(euler.y), MathUtils.toDeg(source.y));
        float z = unwrapDeg(MathUtils.toDeg(euler.z), MathUtils.toDeg(source.z));

        if (this.dragRotateGizmoSpace) this.setR2(null, x, y, z);
        else this.setR(null, x, y, z);
    }

    private Matrix3f computeParentInverse(Vector3f sourceRadians)
    {
        if (this.drag == null)
        {
            return null;
        }

        Matrix3f rotateAxesInverse = new Matrix3f(this.drag.rotateAxes);

        if (Math.abs(rotateAxesInverse.determinant()) < 1.0E-4F)
        {
            return null;
        }

        return this.eulerAxes(sourceRadians).mul(rotateAxesInverse.invert());
    }

    private Matrix3f eulerAxes(Vector3f rotateRadians)
    {
        Matrix3f axes = new Matrix3f();

        axes.setColumn(0, new Matrix3f().rotationZ(rotateRadians.z).rotateY(rotateRadians.y).transform(new Vector3f(1F, 0F, 0F)));
        axes.setColumn(1, new Matrix3f().rotationZ(rotateRadians.z).transform(new Vector3f(0F, 1F, 0F)));
        axes.setColumn(2, new Vector3f(0F, 0F, 1F));

        return axes;
    }

    private float screenAngle(int mouseX, int mouseY)
    {
        return (float) Math.atan2(mouseY - this.dragScreenCenter.y, mouseX - this.dragScreenCenter.x);
    }

    private static float unwrapDeg(float valueDeg, float referenceDeg)
    {
        return valueDeg + Math.round((referenceDeg - valueDeg) / 360F) * 360F;
    }

    private Vector3f computeStartRingVec(int mouseX, int mouseY, Vector3f axisDir)
    {
        Vector3f ring = new Vector3f();
        Vector3d hit = new Vector3d();

        if (this.drag.intersectPlane(mouseX, mouseY, axisDir, hit))
        {
            ring.set(
                (float) (hit.x - this.drag.gizmoOrigin.x),
                (float) (hit.y - this.drag.gizmoOrigin.y),
                (float) (hit.z - this.drag.gizmoOrigin.z)
            );

            float along = ring.dot(axisDir);

            ring.sub(new Vector3f(axisDir).mul(along));
        }

        if (ring.lengthSquared() < 1.0E-8F)
        {
            Vector3f fallback = Math.abs(axisDir.y) < 0.9F ? new Vector3f(0F, 1F, 0F) : new Vector3f(1F, 0F, 0F);

            axisDir.cross(fallback, ring);
        }

        return ring.normalize();
    }

    private static float applyStepModifiers(float step)
    {
        if (Window.isAltPressed()) step /= STEP_MODIFIER;
        if (Window.isCtrlPressed()) step *= STEP_MODIFIER;

        return step;
    }

    public boolean scrollDepth(UIContext context)
    {
        if (!this.editing || !this.isScreenTranslate() || !this.dragHasStart || this.transform == null)
        {
            return false;
        }

        GizmoDrag fresh = this.getHotkeyDrag();

        if (fresh != null)
        {
            this.drag = fresh;
            this.beginRayTranslateScreen(context.mouseX, context.mouseY);
        }

        if (this.drag == null)
        {
            return true;
        }

        Vector3d ray = new Vector3d(this.drag.gizmoOrigin).sub(this.drag.cameraOrigin);
        double distance = ray.length();

        if (distance < 1.0E-4)
        {
            return true;
        }

        ray.div(distance);

        float step = applyStepModifiers((float) (context.mouseWheel * distance * DEPTH_WHEEL_FACTOR));
        Vector3f translateStep = this.dragScreenInverseJacobian.transform(
            new Vector3f((float) (ray.x * step), (float) (ray.y * step), (float) (ray.z * step))
        );

        this.setT(null,
            this.transform.translate.x + translateStep.x,
            this.transform.translate.y + translateStep.y,
            this.transform.translate.z + translateStep.z
        );

        this.drag.gizmoOrigin.add(ray.x * step, ray.y * step, ray.z * step);
        this.dragStartTranslate.set(this.transform.translate);
        this.drag.intersectPlane(context.mouseX, context.mouseY, this.dragPlaneNormal, this.dragStartHit);
        this.setTransform(this.transform);

        return true;
    }

    public boolean scrollTrackballRoll(UIContext context)
    {
        if (!this.editing || !this.isSphereRotate() || !this.dragHasStart || this.transform == null)
        {
            return false;
        }

        this.trackballRollDeg += applyStepModifiers((float) (context.mouseWheel * TRACKBALL_WHEEL_DEG));

        if (this.dragKind == DragKind.ARCBALL) this.updateArcballRotation();
        else this.updateTrackballRotation();

        this.setTransform(this.transform);

        return true;
    }

    private boolean applyGizmoTranslate(int dx, int dy, float factor)
    {
        if (!this.gizmoDrag || this.mode != 0)
        {
            return false;
        }

        if (dx == 0 && dy == 0)
        {
            return true;
        }

        Float units = this.getGizmoAxisDelta(dx, dy, this.axis, factor);
        Float units2 = this.axis2 == null ? null : this.getGizmoAxisDelta(dx, dy, this.axis2, factor);

        if (units == null)
        {
            return false;
        }

        Vector3f vector3f = new Vector3f(this.transform.translate);

        this.applyTranslateDelta(vector3f, this.axis, units);

        if (this.axis2 != null && units2 != null)
        {
            this.applyTranslateDelta(vector3f, this.axis2, units2);
        }

        if (this.shouldSnap(0))
        {
            vector3f.x = (float) snap(vector3f.x, BBSSettings.snapTranslate.get());
            vector3f.y = (float) snap(vector3f.y, BBSSettings.snapTranslate.get());
            vector3f.z = (float) snap(vector3f.z, BBSSettings.snapTranslate.get());
        }

        this.setT(null, vector3f.x, vector3f.y, vector3f.z);

        return true;
    }

    private boolean applyGizmoScale(int dx, int dy, float factor, boolean all)
    {
        if (!this.gizmoDrag || this.mode != 1)
        {
            return false;
        }

        if (dx == 0 && dy == 0)
        {
            return true;
        }

        Float delta = this.getGizmoAxisDelta(dx, dy, this.axis, factor);

        if (delta == null)
        {
            return false;
        }

        Vector3f vector3f = new Vector3f(this.transform.scale);

        if (all)
        {
            vector3f.x += delta;
            vector3f.y += delta;
            vector3f.z += delta;
        }
        else if (this.axis == Axis.X)
        {
            vector3f.x += delta;
        }
        else if (this.axis == Axis.Y)
        {
            vector3f.y += delta;
        }
        else if (this.axis == Axis.Z)
        {
            vector3f.z += delta;
        }

        if (!all && this.axis2 != null)
        {
            Float delta2 = this.getGizmoAxisDelta(dx, dy, this.axis2, factor);

            if (delta2 != null)
            {
                if (this.axis2 == Axis.X) vector3f.x += delta2;
                else if (this.axis2 == Axis.Y) vector3f.y += delta2;
                else if (this.axis2 == Axis.Z) vector3f.z += delta2;
            }
        }

        if (this.shouldSnap(1))
        {
            vector3f.x = (float) snap(vector3f.x, BBSSettings.snapScale.get());
            vector3f.y = (float) snap(vector3f.y, BBSSettings.snapScale.get());
            vector3f.z = (float) snap(vector3f.z, BBSSettings.snapScale.get());
        }

        this.setS(null, vector3f.x, vector3f.y, vector3f.z);

        return true;
    }

    private boolean applyGizmoRotate(int mouseX, int mouseY, int lastX, int lastY, float factor)
    {
        if (!this.gizmoDrag || !this.gizmoInvReady || this.mode != 2)
        {
            return false;
        }

        if (mouseX == lastX && mouseY == lastY)
        {
            return true;
        }

        if (this.isSphereRotate() || this.dragKind == DragKind.VIEW)
        {
            return this.applySpecialGizmoRotate(mouseX, mouseY, lastX, lastY, factor);
        }

        Axis axis = this.axis;

        if (!this.intersectGizmoPlane(lastX, lastY, axis, this.gizmoP0) || !this.intersectGizmoPlane(mouseX, mouseY, axis, this.gizmoP1))
        {
            return false;
        }

        if (this.gizmoP0.lengthSquared() < 1e-6 || this.gizmoP1.lengthSquared() < 1e-6)
        {
            return false;
        }

        this.gizmoCross.set(this.gizmoP0).cross(this.gizmoP1);

        double dot = this.gizmoP0.dot(this.gizmoP1);
        double axisDot = this.getGizmoAxisNormal(axis).dot(this.gizmoCross);
        double angleRad = Math.atan2(axisDot, dot);
        float dragSign = this.getAxisDragDirectionSign(axis, this.gizmoP1, mouseX, mouseY, lastX, lastY);

        if (dragSign == 0F)
        {
            return true;
        }

        float deltaDeg = MathUtils.toDeg((float) Math.abs(angleRad)) * factor * dragSign;
        Vector3f current = this.local && BBSSettings.gizmos.get() ? this.transform.rotate2 : this.transform.rotate;
        Vector3f rotDeg = new Vector3f(
            MathUtils.toDeg(current.x),
            MathUtils.toDeg(current.y),
            MathUtils.toDeg(current.z)
        );

        if (axis == Axis.X) rotDeg.x += deltaDeg;
        else if (axis == Axis.Y) rotDeg.y += deltaDeg;
        else if (axis == Axis.Z) rotDeg.z += deltaDeg;

        if (this.shouldSnap(2))
        {
            rotDeg.x = (float) snap(rotDeg.x, BBSSettings.snapRotate.get());
            rotDeg.y = (float) snap(rotDeg.y, BBSSettings.snapRotate.get());
            rotDeg.z = (float) snap(rotDeg.z, BBSSettings.snapRotate.get());
        }

        if (this.local && BBSSettings.gizmos.get()) this.setR2(null, rotDeg.x, rotDeg.y, rotDeg.z);
        else this.setR(null, rotDeg.x, rotDeg.y, rotDeg.z);

        return true;
    }

    private void applyTranslateDelta(Vector3f vector, Axis axis, float units)
    {
        if (this.local)
        {
            float localUnits = axis == Axis.Z ? -units : units;

            vector.add(this.calculateLocalVector(localUnits, axis));
        }
        else if (axis == Axis.X)
        {
            vector.x += units;
        }
        else if (axis == Axis.Y)
        {
            vector.y += units;
        }
        else if (axis == Axis.Z)
        {
            vector.z -= units;
        }
    }

    private boolean applySpecialGizmoRotate(int mouseX, int mouseY, int lastX, int lastY, float factor)
    {
        Vector3f current = this.local && BBSSettings.gizmos.get() ? this.transform.rotate2 : this.transform.rotate;
        Vector3f rotDeg = new Vector3f(
            MathUtils.toDeg(current.x),
            MathUtils.toDeg(current.y),
            MathUtils.toDeg(current.z)
        );

        int dx = mouseX - lastX;
        int dy = mouseY - lastY;

        if (this.dragKind == DragKind.VIEW)
        {
            rotDeg.z += dx * factor;
        }
        else
        {
            rotDeg.y += dx * factor;
            rotDeg.x += dy * factor;
        }

        if (this.shouldSnap(2))
        {
            rotDeg.x = (float) snap(rotDeg.x, BBSSettings.snapRotate.get());
            rotDeg.y = (float) snap(rotDeg.y, BBSSettings.snapRotate.get());
            rotDeg.z = (float) snap(rotDeg.z, BBSSettings.snapRotate.get());
        }

        if (this.local && BBSSettings.gizmos.get()) this.setR2(null, rotDeg.x, rotDeg.y, rotDeg.z);
        else this.setR(null, rotDeg.x, rotDeg.y, rotDeg.z);

        return true;
    }

    private Float getGizmoAxisDelta(int dx, int dy, Axis axis, float factor)
    {
        Vector2f vector = this.getGizmoAxis(axis);
        float lenSq = this.getGizmoAxisLenSq(axis);
        float len = (float) Math.sqrt(lenSq);

        if (len < 1e-3F)
        {
            return null;
        }

        float units = (dx * vector.x + dy * vector.y) / len;
        units *= factor;

        return Float.isFinite(units) ? units : null;
    }

    private Vector2f getGizmoAxis(Axis axis)
    {
        if (axis == Axis.X) return this.gizmoAxisX;
        if (axis == Axis.Y) return this.gizmoAxisY;

        return this.gizmoAxisZ;
    }

    private float getGizmoAxisLenSq(Axis axis)
    {
        if (axis == Axis.X) return this.gizmoAxisXLenSq;
        if (axis == Axis.Y) return this.gizmoAxisYLenSq;

        return this.gizmoAxisZLenSq;
    }

    private Vector3d getGizmoAxisNormal(Axis axis)
    {
        if (axis == Axis.X) return this.gizmoAxisNormal.set(1, 0, 0);
        if (axis == Axis.Y) return this.gizmoAxisNormal.set(0, 1, 0);

        return this.gizmoAxisNormal.set(0, 0, 1);
    }

    private float getAxisDragDirectionSign(Axis axis, Vector3d pointOnRing, int mouseX, int mouseY, int lastX, int lastY)
    {
        this.gizmoTangent.set(this.getGizmoAxisNormal(axis)).cross(pointOnRing);

        if (this.gizmoTangent.lengthSquared() < 1e-8)
        {
            return 0F;
        }

        this.gizmoTangent.normalize().mul(0.25D);

        if (!this.projectGizmoPoint((float) pointOnRing.x, (float) pointOnRing.y, (float) pointOnRing.z, this.gizmoP2D))
        {
            return 0F;
        }

        if (!this.projectGizmoPoint(
            (float) (pointOnRing.x + this.gizmoTangent.x),
            (float) (pointOnRing.y + this.gizmoTangent.y),
            (float) (pointOnRing.z + this.gizmoTangent.z),
            this.gizmoP2DNext
        ))
        {
            return 0F;
        }

        float tangentX = this.gizmoP2DNext.x - this.gizmoP2D.x;
        float tangentY = this.gizmoP2D.y - this.gizmoP2DNext.y;
        float dragX = mouseX - lastX;
        float dragY = lastY - mouseY;
        float alignment = dragX * tangentX + dragY * tangentY;

        if (Math.abs(alignment) < 1e-6F)
        {
            return 0F;
        }

        float sign = Math.signum(alignment);

        if (axis == Axis.X || axis == Axis.Z)
        {
            sign = -sign;
        }

        return sign;
    }

    private boolean intersectGizmoPlane(int mouseX, int mouseY, Axis axis, Vector3d out)
    {
        if (this.gizmoViewportW <= 0 || this.gizmoViewportH <= 0)
        {
            return false;
        }

        float ndcX = (mouseX - this.gizmoViewportX) / (float) this.gizmoViewportW * 2F - 1F;
        float ndcY = 1F - (mouseY - this.gizmoViewportY) / (float) this.gizmoViewportH * 2F;

        this.gizmoNear4D.set(ndcX, ndcY, -1F, 1F);
        this.gizmoFar4D.set(ndcX, ndcY, 1F, 1F);

        this.gizmoInvMvp.transform(this.gizmoNear4D);
        this.gizmoInvMvp.transform(this.gizmoFar4D);

        if (this.gizmoNear4D.w == 0F || this.gizmoFar4D.w == 0F)
        {
            return false;
        }

        this.gizmoNear4D.div(this.gizmoNear4D.w);
        this.gizmoFar4D.div(this.gizmoFar4D.w);

        this.gizmoRayStart.set(this.gizmoNear4D.x, this.gizmoNear4D.y, this.gizmoNear4D.z);
        this.gizmoRayEnd.set(this.gizmoFar4D.x, this.gizmoFar4D.y, this.gizmoFar4D.z);

        double a = 0;
        double b = 0;
        double c = 0;

        if (axis == Axis.X) a = 1;
        else if (axis == Axis.Y) b = 1;
        else if (axis == Axis.Z) c = 1;

        return Intersectiond.intersectLineSegmentPlane(
            this.gizmoRayStart.x, this.gizmoRayStart.y, this.gizmoRayStart.z,
            this.gizmoRayEnd.x, this.gizmoRayEnd.y, this.gizmoRayEnd.z,
            a, b, c, 0, out
        );
    }

    private boolean projectGizmoPoint(float x, float y, float z, Vector2f out)
    {
        if (this.gizmoViewportW <= 0 || this.gizmoViewportH <= 0)
        {
            return false;
        }

        this.gizmoTmp4D.set(x, y, z, 1F);
        this.gizmoMvp.transform(this.gizmoTmp4D);

        if (this.gizmoTmp4D.w == 0F)
        {
            return false;
        }

        float ndcX = this.gizmoTmp4D.x / this.gizmoTmp4D.w;
        float ndcY = this.gizmoTmp4D.y / this.gizmoTmp4D.w;

        out.x = this.gizmoViewportX + (ndcX * 0.5F + 0.5F) * this.gizmoViewportW;
        out.y = this.gizmoViewportY + (1F - (ndcY * 0.5F + 0.5F)) * this.gizmoViewportH;

        return true;
    }

    private enum DragKind
    {
        AXIS, SCREEN, TRACKBALL, ARCBALL, VIEW
    }

    public enum HotkeyTarget
    {
        VIEW("view", null, true),
        SPHERE("sphere", null, true),
        SCREEN("screen", null, true),
        X("x", Axis.X, false),
        Y("y", Axis.Y, false),
        Z("z", Axis.Z, false);

        public final String token;
        public final Axis axis;
        public final boolean needsRay;

        HotkeyTarget(String token, Axis axis, boolean needsRay)
        {
            this.token = token;
            this.axis = axis;
            this.needsRay = needsRay;
        }

        public static HotkeyTarget byToken(String token)
        {
            for (HotkeyTarget target : values())
            {
                if (target.token.equals(token))
                {
                    return target;
                }
            }

            return null;
        }
    }

    public static class UITransformHandler extends UIElement
    {
        private UIPropTransform transform;

        public UITransformHandler(UIPropTransform transform)
        {
            this.transform = transform;
            this.noCulling();
        }

        @Override
        public void render(UIContext context)
        {
            this.transform.pollGesture(context);
        }

        @Override
        protected boolean subMouseClicked(UIContext context)
        {
            if (this.transform.editing)
            {
                if (context.mouseButton == 0)
                {
                    this.transform.acceptChanges();

                    return true;
                }
                else if (context.mouseButton == 1)
                {
                    this.transform.rejectChanges();

                    return true;
                }
            }
            
            return super.subMouseClicked(context);
        }

        @Override
        protected boolean subMouseScrolled(UIContext context)
        {
            if (this.transform.scrollTrackballRoll(context))
            {
                return true;
            }

            if (this.transform.scrollDepth(context))
            {
                return true;
            }

            UITrackpad.updateAmplifier(context);

            return true;
        }
    }
}
