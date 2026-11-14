package mchorse.bbs_mod.ui.framework.elements.input.drag;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.forms.renderers.utils.RenderFrame;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.GizmoJacobian;
import mchorse.bbs_mod.ui.utils.GizmoSize;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Executes the extracted gesture through its real widget and checks the shared projection math. */
public final class TransformGestureParityTest
{
    private TransformGestureParityTest()
    {}

    public static void runAll()
    {
        testWidgetSession();
        testHiddenPump();
        testJacobians();
        testProjectionSize();
        testAxisWalkAndMasks();
        testFineCursor();
        testFreshSamplingAndFrameAxes();
        testParentRingTurnsAboutItsDrawnAxis();
        testBakedRotationAcrossRenderPasses();
    }

    private static void testBakedRotationAcrossRenderPasses()
    {
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();

        Matrix4f global = RenderSystem.getModelViewMatrix();
        Matrix4f previous = new Matrix4f(global);
        Matrix4f view = new Matrix4f().rotateXYZ(0.4F, -0.7F, 0.2F);
        Matrix4f local = new Matrix4f().translation(2F, -1F, -5F).rotateY(0.6F);
        Matrix4f expected = new Matrix4f(view).mul(local);
        UIBaseMenu menu = new UIBaseMenu() {};
        UIPropTransform widget = new UIPropTransform();
        widget.setTransform(new Transform());
        menu.main.add(widget);

        try
        {
            Method apply = Gizmo.class.getDeclaredMethod("applyBakedRotation", PoseStack.class);
            apply.setAccessible(true);

            for (boolean visualFirst : new boolean[] {true, false})
            {
                widget.enableMode(TransformOp.ROTATE, Axis.X, null, null);
                PoseStack stack = new PoseStack();
                global.set(visualFirst ? view : new Matrix4f());
                stack.last().pose().set(visualFirst ? local : expected);
                apply.invoke(Gizmo.INSTANCE, stack);
                check(new Matrix4f(global).mul(stack.last().pose()).equals(expected, 1.0E-5F),
                    "Initial rotation bake changed the drawn frame");

                global.set(visualFirst ? new Matrix4f() : view);
                stack.last().pose().identity().translate(9F, 8F, -7F).rotateX(1F);
                apply.invoke(Gizmo.INSTANCE, stack);
                check(new Matrix4f(global).mul(stack.last().pose()).equals(expected, 1.0E-5F),
                    "Visual and stencil rotation passes did not share the baked model-view");
                widget.rejectChanges();
                apply.invoke(Gizmo.INSTANCE, stack);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
        finally
        {
            if (widget.isEditing()) widget.rejectChanges();
            global.set(previous);
        }
    }

    private static void testWidgetSession()
    {
        UIBaseMenu menu = new UIBaseMenu() {};
        UIPropTransform widget = new UIPropTransform();
        Transform transform = new Transform();
        transform.translate.set(10F, 20F, 30F);
        transform.scale.set(2F, 3F, 4F);
        List<String> events = new ArrayList<>();
        widget.gestureCallbacks(() -> events.add("start"), accepted -> events.add(accepted ? "accept" : "reject"));
        widget.callbacks(() -> {}, () -> {}, () -> events.add("end"));
        widget.setTransform(transform);
        menu.main.add(widget);
        TransformGesture gesture = widget.getGesture();

        widget.enableMode(TransformOp.TRANSLATE, null, null, null);
        check(gesture.isEditing() && Gizmo.INSTANCE.getTrackedGesture() == gesture, "Widget did not publish its session");
        key(gesture, menu.context, GLFW.GLFW_KEY_1);
        key(gesture, menu.context, GLFW.GLFW_KEY_2);
        key(gesture, menu.context, GLFW.GLFW_KEY_SLASH);
        key(gesture, menu.context, GLFW.GLFW_KEY_4);
        check(transform.translate.equals(new Vector3f(13F, 20F, 30F)), "Typed fraction did not reach virtual field writes");
        gesture.setAxis(Axis.Y);
        check(transform.translate.equals(new Vector3f(10F, 23F, 30F)), "Axis change accumulated the previous typed delta");
        key(gesture, menu.context, GLFW.GLFW_KEY_ESCAPE);
        check(transform.translate.equals(new Vector3f(10F, 20F, 30F)), "Reject did not restore the start snapshot");
        check(events.equals(List.of("start", "reject")), "Reject closed an accepted undo block");
        check(!gesture.isEditing() && Gizmo.INSTANCE.getTrackedGesture() == null && menu.overlay.getChildren().isEmpty(),
            "Rejected session retained its overlay or Gizmo ownership");

        events.clear();
        widget.enableUniformScale(null, true);
        key(gesture, menu.context, GLFW.GLFW_KEY_2);
        check(transform.scale.equals(new Vector3f(4F, 6F, 8F)), "Uniform typed scale did not preserve axis ratios");
        check(gesture.getDebugLineStencilIndex() == -1, "Uniform scale exposed its internal X sampling axis");
        key(gesture, menu.context, GLFW.GLFW_KEY_ENTER);
        check(events.equals(List.of("start", "accept", "end")), "Accept did not close one gesture block");
        check(!widget.isEditing() && Gizmo.INSTANCE.getTrackedGesture() == null, "Accepted session retained ownership");
    }

    private static void testHiddenPump()
    {
        UIPropTransform widget = new UIPropTransform();
        PumpProbe gesture = new PumpProbe(widget);
        UIContext context = new UIContext(null);
        gesture.pumpIfHidden(context);
        check(gesture.pumps == 0, "Visible field editor was pumped twice by the viewport");
        widget.setVisible(false);
        gesture.pumpIfHidden(context);
        check(gesture.pumps == 1, "Hidden field editor stopped its viewport gesture");
    }

    private static void testJacobians()
    {
        Matrix3f tiny = new Matrix3f().scaling(1.0E-8F, 2.0E-8F, 3.0E-8F);
        check(new Matrix3f(tiny).mul(GizmoJacobian.inverse(tiny)).equals(new Matrix3f(), 1.0E-5F),
            "Tiny but usable transforms lost their inverse");
        Matrix3f flat = new Matrix3f().scaling(0.5F, 0.25F, 0F);
        Matrix3f inverse = TranslateDrag.invertedJacobian(GizmoDrag.resolveTranslateJacobian(flat, false), false);
        Vector3f moved = inverse.transform(new Vector3f(1F, 2F, 0F));
        check(moved.equals(new Vector3f(2F, 8F, 0F), 1.0E-4F), "Flattened drag did not solve its visible motion");
        check(GizmoJacobian.inverse(new Matrix3f().zero()).isFinite(), "Zero transform produced non-finite drag math");
        check(TranslateDrag.invertedJacobian(new Matrix3f().zero(), true).equals(new Matrix3f().scaling(16F)),
            "Absent model-part derivative lost the pixel-to-block fallback");
    }

    private static void testProjectionSize()
    {
        for (float height : new float[]{100F, 720F})
        {
            for (float depth : new float[]{-0.01F, -3F, -100F})
            {
                for (float fov : new float[]{0.7F, 1.4F})
                {
                    Matrix4f view = new Matrix4f().translation(0.3F, -0.4F, depth).rotateXYZ(0.4F, 0.8F, -0.2F).scale(2F, 3F, 4F);
                    assertPixels(view, new Matrix4f().perspective(fov, 1.7F, 0.001F, 1000F), height);
                }
            }
            Matrix4f offset = new Matrix4f().translation(1F, 2F, -3F);
            assertPixels(offset, new Matrix4f().ortho(-4F, 6F, -2F, 8F, 0.1F, 100F), height);
            assertPixels(offset, new Matrix4f().frustum(-0.1F, 0.3F, -0.15F, 0.2F, 0.1F, 100F), height);
        }
    }

    private static void assertPixels(Matrix4f view, Matrix4f projection, float height)
    {
        float scale = GizmoSize.projectedScale(view, projection, height, 80F);
        Vector4f origin = projection.transform(new Vector4f(view.m30(), view.m31(), view.m32(), 1F));
        Vector4f edge = projection.transform(new Vector4f(view.m30(), view.m31() + scale, view.m32(), 1F));
        float pixels = Math.abs(edge.y / edge.w - origin.y / origin.w) * height * 0.5F;
        check(Float.isFinite(scale) && Math.abs(pixels - 80F) < 0.02F,
            "Gizmo size drifted with projection, depth, object scale or viewport height: " + pixels);
    }

    private static void testAxisWalkAndMasks()
    {
        check(AxisSpaceCycle.spaces(TransformOp.TRANSLATE, TransformSpace.PARENT)
            .equals(List.of(TransformSpace.PARENT, TransformSpace.LOCAL)), "Translate axis walk lost its picked frame");
        check(AxisSpaceCycle.spaces(TransformOp.ROTATE, TransformSpace.LOCAL)
            .equals(List.of(TransformSpace.LOCAL, TransformSpace.GLOBAL)), "Local axis walk lost its global alternative");
        check(AxisSpaceCycle.spaces(TransformOp.SCALE, TransformSpace.VIEW)
            .equals(List.of(TransformSpace.VIEW)), "Scale walks through unsupported alternate frames");
        Gizmo.HandleMask moveOnly = Gizmo.HandleMask.of(EnumSet.of(Gizmo.Op.MOVE), EnumSet.noneOf(Axis.class));
        check(HotkeyTarget.next(TransformOp.ROTATE, true, null, moveOnly) == null,
            "Restricted transform host accepted a forbidden rotate hotkey");
        check(HotkeyTarget.next(TransformOp.TRANSLATE, false, null, moveOnly).axis != null,
            "Move-only host lost keyboard editing without a rendered gizmo");
    }

    private static void testFreshSamplingAndFrameAxes()
    {
        Transform transform = new Transform();
        transform.translate.set(4F, 5F, 6F);
        Vector3f saved = new Vector3f(transform.translate);
        long[] sampledEpoch = {Long.MIN_VALUE};
        Vector3f cached = new Vector3f();
        Matrix3f jacobian = GizmoDrag.computeTranslateJacobian(transform, () ->
        {
            if (sampledEpoch[0] != RenderFrame.getEpoch())
            {
                sampledEpoch[0] = RenderFrame.getEpoch();
                cached.set(transform.translate).mul(2F, 3F, 4F);
            }
            return cached;
        });
        check(jacobian.equals(new Matrix3f().scaling(2F, 3F, 4F), 1.0E-6F),
            "Render epoch reused the unperturbed sample for every derivative");
        check(transform.translate.equals(saved) && sampledEpoch[0] != RenderFrame.getEpoch(),
            "Probe restoration left a perturbed matrix in the render epoch");

        GizmoDrag drag = new GizmoDrag();
        Matrix4f local = new Matrix4f().rotateZ(0.7F).scale(-2F, 3F, 0F);
        Matrix4f parent = new Matrix4f().rotateY(0.4F).scale(3F, 4F, 5F);
        drag.setFrameAxes(local, parent);
        check(drag.hasFrameAxes() && !drag.frameBasis(TransformSpace.LOCAL).equals(drag.frameBasis(TransformSpace.PARENT), 0.01F),
            "The axis walk reused one drawn frame for both LOCAL and PARENT");
        Matrix3f frame = drag.frameBasis(TransformSpace.LOCAL);
        check(frame.isFinite() && Math.abs(frame.determinant()) > 0.99F,
            "Mirrored flattened form did not reconstruct an orthonormal gesture frame");
        check(frame.getColumn(0, new Vector3f()).dot(local.get3x3(new Matrix3f()).getColumn(0, new Vector3f())) > 0F,
            "Frame reconstruction reversed a visible mirrored axis");
    }

    private static void testParentRingTurnsAboutItsDrawnAxis()
    {
        UIBaseMenu menu = new UIBaseMenu() {};
        UIPropTransform widget = new UIPropTransform()
        {
            @Override
            public TransformSpace pickedSpace()
            {
                return TransformSpace.PARENT;
            }
        };
        Transform transform = new Transform();
        transform.rotate.set(0.31F, 0.47F, 0.69F);
        Vector3f saved = new Vector3f(transform.rotate);
        widget.setTransform(transform);
        menu.main.add(widget);
        GizmoDrag drag = new GizmoDrag();
        drag.projection.perspective(1.2F, 1F, 0.01F, 100F);
        drag.viewportW = drag.viewportH = 400;
        drag.gizmoOrigin.set(0D, 0D, -3D);
        drag.setRotateAxes(GizmoDrag.computeRotateAxes(transform,
            () -> new Matrix4f().rotateZYX(transform.rotate.z, transform.rotate.y, transform.rotate.x)));
        drag.setFrameAxes(new Matrix4f().rotateZYX(saved.z, saved.y, saved.x), new Matrix4f());
        widget.enableMode(TransformOp.ROTATE, Axis.X, null, drag);
        widget.getGesture().getStrategy().applyNumeric(45D);
        Matrix3f expected = new Matrix3f().rotationX((float) Math.toRadians(45D)).mul(RotationDragMath.eulerZYX(saved));
        check(RotationDragMath.eulerZYX(transform.rotate).equals(expected, 1.0E-4F),
            "PARENT ring changed one gimbal channel instead of turning around the drawn parent axis");
        widget.rejectChanges();
        check(transform.rotate.equals(saved, 1.0E-6F), "Parent-frame rotation cancellation failed");
    }

    private static void testFineCursor()
    {
        try
        {
            Field snapshot = BBSUiRemoteHeldState.class.getDeclaredField("snapshot");
            Method install = BBSUiRemoteHeldState.class.getDeclaredMethod("install", long.class, BBSUiRemoteInputState.class);
            snapshot.setAccessible(true);
            install.setAccessible(true);
            Object previous = snapshot.get(null);
            try
            {
                install.invoke(null, 701L, new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), BBSUiRemoteInputState.MOD_SHIFT));
                FineCursor cursor = new FineCursor();
                cursor.reset(0, 0);
                cursor.update(100, 50);
                check(cursor.x(100) == Math.round(100F * DragStrategy.FINE_DRAG_FACTOR), "Fine cursor did not slow X");
                check(cursor.y(50) == Math.round(50F * DragStrategy.FINE_DRAG_FACTOR), "Fine cursor did not slow Y");
                int x = cursor.x(100);
                install.invoke(null, 701L, new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
                cursor.update(110, 60);
                check(cursor.x(110) == x + 10, "Releasing Shift jumped the virtual cursor");
                cursor.reset(4, 5);
                check(cursor.x(4) == 4 && cursor.y(5) == 5, "Window wrap retained fine-cursor lag");
                cursor.forget();
                cursor.update(400, 500);
                check(cursor.x(400) == 400 && cursor.y(500) == 500, "Next gesture inherited the last cursor anchor");
            }
            finally
            {
                snapshot.set(null, previous);
            }
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError(exception);
        }
    }

    private static void key(TransformGesture gesture, UIContext context, int key)
    {
        context.setKeyEvent(key, 0, GLFW.GLFW_PRESS);
        check(gesture.keyPressed(context), "Session did not consume its numeric/accept/reject key");
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class PumpProbe extends TransformGesture
    {
        private int pumps;

        PumpProbe(Host host)
        {
            super(host);
        }

        @Override
        public void pump(UIContext context)
        {
            this.pumps++;
        }
    }
}
