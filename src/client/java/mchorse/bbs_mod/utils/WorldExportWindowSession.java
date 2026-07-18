package mchorse.bbs_mod.utils;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class WorldExportWindowSession
{
    private WindowSnapshot snapshot;
    private boolean changed;

    public void begin(int width, int height)
    {
        Window window = Minecraft.getInstance().getWindow();
        long handle = window.getWindow();

        if (this.snapshot == null)
        {
            this.snapshot = WindowSnapshot.capture(window, handle);
            this.changed = false;
        }

        if (this.snapshot.fullscreen)
        {
            return;
        }

        /* Record rollback ownership before the first window mutation. */
        this.changed = this.snapshot.maximized
            || this.snapshot.width != width
            || this.snapshot.height != height;

        if (this.snapshot.maximized)
        {
            GLFW.glfwRestoreWindow(handle);
        }

        if (window.getScreenWidth() != width || window.getScreenHeight() != height)
        {
            window.setWindowed(width, height);
        }

        this.restoreOriginalPosition(handle);
    }

    public void restore()
    {
        WindowSnapshot snapshot = this.snapshot;

        if (snapshot == null)
        {
            return;
        }

        Throwable failure = null;

        try
        {
            Window window = Minecraft.getInstance().getWindow();
            long handle = window.getWindow();

            if (!snapshot.fullscreen && this.changed)
            {
                int width = Math.max(snapshot.width, 2);
                int height = Math.max(snapshot.height, 2);

                try
                {
                    if (window.getScreenWidth() != width || window.getScreenHeight() != height)
                    {
                        window.setWindowed(width, height);
                    }
                }
                catch (Exception | LinkageError e)
                {
                    failure = appendFailure(failure, e);
                }

                try
                {
                    this.restoreOriginalPosition(handle);
                }
                catch (Exception | LinkageError e)
                {
                    failure = appendFailure(failure, e);
                }

                if (snapshot.maximized)
                {
                    try
                    {
                        GLFW.glfwMaximizeWindow(handle);
                    }
                    catch (Exception | LinkageError e)
                    {
                        failure = appendFailure(failure, e);
                    }
                }
            }
        }
        catch (Exception | LinkageError e)
        {
            failure = appendFailure(failure, e);
        }
        finally
        {
            this.clear();
        }

        rethrow(failure);
    }

    public void clear()
    {
        this.snapshot = null;
        this.changed = false;
    }

    private void restoreOriginalPosition(long handle)
    {
        Position position = Position.capture(handle);

        if (position.x != this.snapshot.x || position.y != this.snapshot.y)
        {
            GLFW.glfwSetWindowPos(handle, this.snapshot.x, this.snapshot.y);
        }
    }

    private static Throwable appendFailure(Throwable current, Throwable next)
    {
        if (current == null)
        {
            return next;
        }

        if (current != next)
        {
            current.addSuppressed(next);
        }

        return current;
    }

    private static void rethrow(Throwable failure)
    {
        if (failure instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }

        if (failure instanceof LinkageError linkageError)
        {
            throw linkageError;
        }

        if (failure != null)
        {
            throw new IllegalStateException("Failed to restore the video export window", failure);
        }
    }

    private static class WindowSnapshot
    {
        private final int width;
        private final int height;
        private final int x;
        private final int y;
        private final boolean maximized;
        private final boolean fullscreen;

        private WindowSnapshot(int width, int height, int x, int y, boolean maximized, boolean fullscreen)
        {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.maximized = maximized;
            this.fullscreen = fullscreen;
        }

        private static WindowSnapshot capture(Window window, long handle)
        {
            Position position = Position.capture(handle);
            boolean maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
            boolean fullscreen = window.isFullscreen();

            return new WindowSnapshot(window.getScreenWidth(), window.getScreenHeight(), position.x, position.y, maximized, fullscreen);
        }
    }

    private static class Position
    {
        private final int x;
        private final int y;

        private Position(int x, int y)
        {
            this.x = x;
            this.y = y;
        }

        private static Position capture(long handle)
        {
            int[] x = new int[1];
            int[] y = new int[1];

            GLFW.glfwGetWindowPos(handle, x, y);

            return new Position(x[0], y[0]);
        }
    }
}
