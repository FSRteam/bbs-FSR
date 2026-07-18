package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.forms.forms.AnchorForm;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.MouseGestureOwnership;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

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

    @FunctionalInterface
    private interface FieldSetter
    {
        void set(Field field) throws ReflectiveOperationException;
    }
}
