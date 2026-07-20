package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.Axis;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Reentrant release must not retire the replacement Gizmo generation. */
public final class GizmoInteractionGenerationTest
{
    private GizmoInteractionGenerationTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    static void runAll()
    {
        AtomicReference<GizmoInteraction> interactionRef = new AtomicReference<>();
        AtomicLong replacementGeneration = new AtomicLong();
        GizmoViewport viewport = new GizmoViewport()
        {
            @Override
            public StencilFormFramebuffer getGizmoStencil()
            {
                return null;
            }

            @Override
            public Matrix4f getGizmoProjection()
            {
                return null;
            }

            @Override
            public Area getGizmoArea()
            {
                return null;
            }

            @Override
            public boolean startGizmo(UIContext context, int stencilIndex)
            {
                return false;
            }

            @Override
            public void pickGizmoForm(UIContext context, Form form, String bone)
            {
                GizmoInteraction interaction = interactionRef.get();
                MouseGestureOwnership ownership = ownership(interaction);
                long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

                setLong(interaction, "gestureGeneration", generation);
                setBoolean(interaction, "gizmoActive", true);
                replacementGeneration.set(generation);
            }
        };
        GizmoInteraction interaction = new GizmoInteraction(viewport);
        MouseGestureOwnership ownership = ownership(interaction);
        long oldGeneration = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        interactionRef.set(interaction);
        setLong(interaction, "gestureGeneration", oldGeneration);
        setInt(interaction, "pendingButton", GLFW.GLFW_MOUSE_BUTTON_LEFT);
        setObject(interaction, "pendingPickForm", createHeadlessForm());
        setObject(interaction, "pendingPickBone", "root");

        UIContext context = new UIContext(null);

        context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(interaction.mouseReleased(context), "pending Gizmo pick release was not handled");

        long replacement = replacementGeneration.get();

        check(replacement != 0L && replacement != oldGeneration,
            "pending pick callback did not install a replacement generation");
        check(ownership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT, replacement),
            "old pending release retired the callback's replacement owner");
        check(booleanField(interaction, "gizmoActive"),
            "old pending release stopped the callback's replacement Gizmo");

        ownership.cancel();
        setBoolean(interaction, "gizmoActive", false);

        physicalReleaseCommitsAndLifecycleCancelRollsBack();
        buttonScopedCancellationHonorsOwnerAndGeneration();
        uniformScaleHasNoSingleAxisDebugGuide();
    }

    private static void physicalReleaseCommitsAndLifecycleCancelRollsBack()
    {
        ValueBoolean previousDefaultLocal = BBSSettings.defaultLocalTransform;

        try
        {
            BBSSettings.defaultLocalTransform = new ValueBoolean("default_local", false);

            UIContext context = new UIContext(null);

            context.setMouse(5, 5, GLFW.GLFW_MOUSE_BUTTON_LEFT);

            TrackingTransform releasedTransform = allocate(TrackingTransform.class);
            GizmoInteraction releasedInteraction = new GizmoInteraction(noOpViewport());
            MouseGestureOwnership releasedOwnership = ownership(releasedInteraction);
            long releasedGeneration = releasedOwnership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

            setLong(releasedInteraction, "gestureGeneration", releasedGeneration);
            setBoolean(releasedInteraction, "gizmoActive", true);
            setObject(Gizmo.INSTANCE, "currentTransform", releasedTransform);

            check(releasedInteraction.mouseReleased(context), "physical Gizmo release was not handled");
            check(releasedTransform.accepted && !releasedTransform.rejected,
                "physical Gizmo release did not commit exactly once");
            check(!releasedOwnership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT, releasedGeneration),
                "physical Gizmo release retained its gesture owner");

            TrackingTransform canceledTransform = allocate(TrackingTransform.class);
            GizmoInteraction canceledInteraction = new GizmoInteraction(noOpViewport());
            MouseGestureOwnership canceledOwnership = ownership(canceledInteraction);
            long canceledGeneration = canceledOwnership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);

            setLong(canceledInteraction, "gestureGeneration", canceledGeneration);
            setBoolean(canceledInteraction, "gizmoActive", true);
            setObject(Gizmo.INSTANCE, "currentTransform", canceledTransform);
            canceledInteraction.cancel();

            check(canceledTransform.rejected && !canceledTransform.accepted,
                "lifecycle Gizmo cancellation committed instead of rolling back");
            check(!canceledOwnership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT, canceledGeneration),
                "lifecycle Gizmo cancellation retained its gesture owner");
            check(!booleanField(canceledInteraction, "gizmoActive"),
                "lifecycle Gizmo cancellation retained active state");
        }
        finally
        {
            setObject(Gizmo.INSTANCE, "currentTransform", null);
            BBSSettings.defaultLocalTransform = previousDefaultLocal;
        }
    }

    private static void buttonScopedCancellationHonorsOwnerAndGeneration()
    {
        ValueBoolean previousDefaultLocal = BBSSettings.defaultLocalTransform;

        try
        {
            BBSSettings.defaultLocalTransform = new ValueBoolean("default_local", false);

            GizmoInteraction interaction = new GizmoInteraction(noOpViewport());
            MouseGestureOwnership ownership = ownership(interaction);
            long generation = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            TrackingTransform transform = allocate(TrackingTransform.class);

            setLong(interaction, "gestureGeneration", generation);
            setBoolean(interaction, "gizmoActive", true);
            setObject(Gizmo.INSTANCE, "currentTransform", transform);

            check(!interaction.cancel(GLFW.GLFW_MOUSE_BUTTON_RIGHT, generation),
                "foreign-button Gizmo cancellation was reported as handled");
            check(!transform.rejected && ownership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT, generation),
                "foreign-button cancellation rejected or retired the active left Gizmo");
            check(booleanField(interaction, "gizmoActive"),
                "foreign-button cancellation cleared the active left Gizmo");

            check(interaction.cancel(GLFW.GLFW_MOUSE_BUTTON_LEFT, generation),
                "matching-button Gizmo cancellation was not handled");
            check(transform.rejected && !transform.accepted,
                "matching-button Gizmo cancellation did not roll back the transform");
            check(!ownership.isActive() && !booleanField(interaction, "gizmoActive"),
                "matching-button Gizmo cancellation retained its owner or active state");

            long replacement = ownership.acquireToken(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            TrackingTransform replacementTransform = allocate(TrackingTransform.class);

            setLong(interaction, "gestureGeneration", replacement);
            setBoolean(interaction, "gizmoActive", true);
            setObject(Gizmo.INSTANCE, "currentTransform", replacementTransform);

            check(!interaction.cancel(GLFW.GLFW_MOUSE_BUTTON_LEFT, generation),
                "stale-generation Gizmo cancellation was reported as handled");
            check(!replacementTransform.rejected
                    && ownership.isOwnedBy(GLFW.GLFW_MOUSE_BUTTON_LEFT, replacement),
                "stale-generation cancellation rejected or retired the replacement Gizmo");
            check(booleanField(interaction, "gizmoActive"),
                "stale-generation cancellation cleared the replacement Gizmo");

            interaction.cancel();
        }
        finally
        {
            setObject(Gizmo.INSTANCE, "currentTransform", null);
            BBSSettings.defaultLocalTransform = previousDefaultLocal;
        }
    }

    private static void uniformScaleHasNoSingleAxisDebugGuide()
    {
        ValueBoolean previousDefaultLocal = BBSSettings.defaultLocalTransform;
        ValueBoolean previousHideInactive = BBSSettings.hideInactiveHandles;

        try
        {
            BBSSettings.defaultLocalTransform = new ValueBoolean("default_local", false);
            BBSSettings.hideInactiveHandles = new ValueBoolean("hide_inactive_handles", true);

            UIPropTransform transform = allocate(UIPropTransform.class);

            setBoolean(transform, "editing", true);
            setBoolean(transform, "scaleAll", true);
            check(transform.getDebugLineStencilIndex() == -1,
                "uniform scale exposed its internal X sampling axis as a debug guide");

            setBoolean(transform, "scaleAll", false);
            setObject(transform, "axis", Axis.X);
            check(transform.getDebugLineStencilIndex() == Gizmo.STENCIL_X,
                "ordinary X-axis editing lost its debug guide");
        }
        finally
        {
            BBSSettings.defaultLocalTransform = previousDefaultLocal;
            BBSSettings.hideInactiveHandles = previousHideInactive;
        }
    }

    private static GizmoViewport noOpViewport()
    {
        return new GizmoViewport()
        {
            @Override
            public StencilFormFramebuffer getGizmoStencil()
            {
                return null;
            }

            @Override
            public Matrix4f getGizmoProjection()
            {
                return null;
            }

            @Override
            public Area getGizmoArea()
            {
                return null;
            }

            @Override
            public boolean startGizmo(UIContext context, int stencilIndex)
            {
                return false;
            }

            @Override
            public void pickGizmoForm(UIContext context, Form form, String bone)
            {}
        };
    }

    private static Form createHeadlessForm()
    {
        ValueInt previous = BBSSettings.recordingPoseTransformOverlays;

        try
        {
            if (previous == null)
            {
                BBSSettings.recordingPoseTransformOverlays = new ValueInt("pose_transform_overlays", 0);
            }

            return new AnchorForm();
        }
        finally
        {
            BBSSettings.recordingPoseTransformOverlays = previous;
        }
    }

    private static MouseGestureOwnership ownership(GizmoInteraction interaction)
    {
        return (MouseGestureOwnership) objectField(interaction, "gestureOwnership");
    }

    private static Object objectField(Object target, String name)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);

            field.setAccessible(true);

            return field.get(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect " + name, exception);
        }
    }

    private static <T> T allocate(Class<T> type)
    {
        try
        {
            return type.cast(UnsafeHolder.INSTANCE.allocateInstance(type));
        }
        catch (InstantiationException exception)
        {
            throw new AssertionError("Could not allocate " + type.getName(), exception);
        }
    }

    private static boolean booleanField(Object target, String name)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);

            field.setAccessible(true);

            return field.getBoolean(target);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not inspect " + name, exception);
        }
    }

    private static void setLong(Object target, String name, long value)
    {
        setField(target, name, (field) -> field.setLong(target, value));
    }

    private static void setInt(Object target, String name, int value)
    {
        setField(target, name, (field) -> field.setInt(target, value));
    }

    private static void setBoolean(Object target, String name, boolean value)
    {
        setField(target, name, (field) -> field.setBoolean(target, value));
    }

    private static void setObject(Object target, String name, Object value)
    {
        setField(target, name, (field) -> field.set(target, value));
    }

    private static void setField(Object target, String name, FieldSetter setter)
    {
        try
        {
            Field field = target.getClass().getDeclaredField(name);

            field.setAccessible(true);
            setter.set(field);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not set " + name, exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static class TrackingTransform extends UIPropTransform
    {
        private boolean accepted;
        private boolean rejected;

        @Override
        public void acceptChanges()
        {
            this.accepted = true;
        }

        @Override
        public void rejectChanges()
        {
            this.rejected = true;
        }
    }

    private static final class UnsafeHolder
    {
        private static final Unsafe INSTANCE;

        static
        {
            try
            {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");

                field.setAccessible(true);
                INSTANCE = (Unsafe) field.get(null);
            }
            catch (ReflectiveOperationException exception)
            {
                throw new ExceptionInInitializerError(exception);
            }
        }
    }

    @FunctionalInterface
    private interface FieldSetter
    {
        void set(Field field) throws ReflectiveOperationException;
    }
}
