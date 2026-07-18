package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.api.client.ui.BBSUiClipPop;
import mchorse.bbs_mod.api.client.ui.BBSUiClipPush;
import mchorse.bbs_mod.api.client.ui.BBSUiColoredMesh;
import mchorse.bbs_mod.api.client.ui.BBSUiCursor;
import mchorse.bbs_mod.api.client.ui.BBSUiCursorShape;
import mchorse.bbs_mod.api.client.ui.BBSUiAssetRef;
import mchorse.bbs_mod.api.client.ui.BBSUiDrawCommand;
import mchorse.bbs_mod.api.client.ui.BBSUiFrame;
import mchorse.bbs_mod.api.client.ui.BBSUiGlyphRun;
import mchorse.bbs_mod.api.client.ui.BBSUiQuad;
import mchorse.bbs_mod.api.client.ui.BBSUiSessionInfo;
import mchorse.bbs_mod.api.client.ui.BBSUiSurfaceQuad;
import mchorse.bbs_mod.api.client.ui.BBSUiTextureQuad;
import mchorse.bbs_mod.api.client.ui.BBSUiTexturedMesh;
import mchorse.bbs_mod.api.client.ui.BBSUiTexturedMeshVertex;
import mchorse.bbs_mod.api.client.ui.BBSUiTexturedVertex;
import mchorse.bbs_mod.api.client.ui.BBSUiUnsupported;
import mchorse.bbs_mod.api.client.ui.BBSUiUnsupportedReason;
import mchorse.bbs_mod.api.client.ui.BBSUiVertex;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import mchorse.bbs_mod.graphics.texture.Texture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Render-thread recorder. It is active only inside an explicit UIScreen frame
 * scope, so unrelated Batcher2D users are not mirrored.
 */
public final class BBSUiFrameRecorder
{
    private static final int MAX_COMMANDS_PER_FRAME = 100_000;
    private static final int MAX_DRAW_COMMANDS_PER_FRAME = MAX_COMMANDS_PER_FRAME - BBSUiUnsupportedReason.values().length - 1;
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(1L);
    private static final AtomicLong STANDALONE_WORLD_REPLAY_SESSION_ID = new AtomicLong();
    private static final Map<Long, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<FrameBuilder> ACTIVE_FRAME = new ThreadLocal<>();
    private static final Object SESSION_LIFECYCLE_LOCK = new Object();
    private static long latestSessionId;

    private BBSUiFrameRecorder()
    {}

    public static long openSession(int width, int height, int framebufferWidth, int framebufferHeight)
    {
        return openSession(
            width,
            height,
            framebufferWidth,
            framebufferHeight,
            BBSRenderSurfaceRuntime::invalidateSession,
            BBSUiMirrorRegistry::openSession,
            BBSUiMirrorRegistry::closeSession
        );
    }

    static long openSession(
        int width,
        int height,
        int framebufferWidth,
        int framebufferHeight,
        Runnable surfaceInvalidation,
        Consumer<BBSUiSessionInfo> registryOpen,
        LongConsumer registryClose
    )
    {
        long sessionId;
        BBSUiSessionInfo info;
        SessionState state;

        synchronized (SESSION_LIFECYCLE_LOCK)
        {
            sessionId = NEXT_SESSION_ID.getAndIncrement();
            info = sessionInfo(sessionId, width, height, framebufferWidth, framebufferHeight);
            state = new SessionState(info);
            latestSessionId = sessionId;

            try
            {
                surfaceInvalidation.run();

                if (latestSessionId != sessionId)
                {
                    throw new IllegalStateException("UI mirror session was superseded during surface invalidation");
                }

                SESSIONS.put(sessionId, state);
            }
            catch (RuntimeException | Error failure)
            {
                SESSIONS.remove(sessionId, state);

                if (latestSessionId == sessionId)
                {
                    latestSessionId = 0L;
                }

                throw failure;
            }
        }

        Throwable openFailure = null;

        synchronized (SESSION_LIFECYCLE_LOCK)
        {
            if (latestSessionId != sessionId || SESSIONS.get(sessionId) != state)
            {
                openFailure = new IllegalStateException("UI mirror session was superseded before registry attachment");
            }
            else
            {
                try
                {
                    registryOpen.accept(info);
                }
                catch (RuntimeException | Error failure)
                {
                    openFailure = failure;
                }

                if (openFailure == null && (latestSessionId != sessionId || SESSIONS.get(sessionId) != state))
                {
                    openFailure = new IllegalStateException("UI mirror session was superseded during registry attachment");
                }
            }
        }

        if (openFailure != null)
        {
            runTeardownStepsAfter(
                openFailure,
                () -> removeSessionOwnership(sessionId, state),
                () -> registryClose.accept(sessionId),
                () -> invalidateSessionIfLatest(sessionId, surfaceInvalidation)
            );
        }

        return sessionId;
    }

    public static boolean isSessionOpen(long sessionId)
    {
        return sessionId > 0L && SESSIONS.containsKey(sessionId);
    }

    public static void resizeSession(long sessionId, int width, int height, int framebufferWidth, int framebufferHeight)
    {
        SessionState state = SESSIONS.get(sessionId);

        if (state == null)
        {
            return;
        }

        BBSUiSessionInfo info = sessionInfo(sessionId, width, height, framebufferWidth, framebufferHeight);

        state.info = info;
        BBSUiMirrorRegistry.updateSession(info);
    }

    public static void closeSession(long sessionId)
    {
        closeSession(
            sessionId,
            BBSRenderSurfaceRuntime::invalidateSession,
            BBSUiMirrorRegistry::closeSession
        );
    }

    static void closeSession(long sessionId, Runnable surfaceInvalidation, LongConsumer registryClose)
    {
        if (sessionId <= 0L)
        {
            return;
        }

        STANDALONE_WORLD_REPLAY_SESSION_ID.compareAndSet(sessionId, 0L);

        SessionState state = removeSessionOwnership(sessionId);

        if (state == null)
        {
            return;
        }

        runTeardownSteps(
            () -> invalidateSessionIfLatest(sessionId, surfaceInvalidation),
            () -> registryClose.accept(sessionId)
        );
    }

    static void runTeardownSteps(Runnable... steps)
    {
        runTeardownStepsAfter(null, steps);
    }

    private static void runTeardownStepsAfter(Throwable failure, Runnable... steps)
    {
        for (Runnable step : steps)
        {
            try
            {
                step.run();
            }
            catch (RuntimeException | Error exception)
            {
                if (failure == null)
                {
                    failure = exception;
                }
                else if (failure != exception)
                {
                    failure.addSuppressed(exception);
                }
            }
        }

        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    private static SessionState removeSessionOwnership(long sessionId)
    {
        synchronized (SESSION_LIFECYCLE_LOCK)
        {
            SessionState state = SESSIONS.remove(sessionId);

            retireActiveFrame(state);

            return state;
        }
    }

    private static void removeSessionOwnership(long sessionId, SessionState state)
    {
        synchronized (SESSION_LIFECYCLE_LOCK)
        {
            SESSIONS.remove(sessionId, state);
            retireActiveFrame(state);
        }
    }

    private static void retireActiveFrame(SessionState state)
    {
        FrameBuilder active = ACTIVE_FRAME.get();

        if (state != null && active != null && active.session == state)
        {
            ACTIVE_FRAME.remove();
        }
    }

    private static void invalidateSessionIfLatest(long sessionId, Runnable surfaceInvalidation)
    {
        synchronized (SESSION_LIFECYCLE_LOCK)
        {
            if (latestSessionId != sessionId)
            {
                return;
            }

            latestSessionId = 0L;
            surfaceInvalidation.run();
        }
    }

    public static void closeAllSessions()
    {
        ArrayList<Runnable> teardownSteps = new ArrayList<>(SESSIONS.size() + 3);

        teardownSteps.add(ACTIVE_FRAME::remove);

        for (Long sessionId : new ArrayList<>(SESSIONS.keySet()))
        {
            teardownSteps.add(() -> closeSession(sessionId));
        }

        teardownSteps.add(() -> STANDALONE_WORLD_REPLAY_SESSION_ID.set(0L));
        teardownSteps.add(BBSUiAssetPublisher::reset);

        runTeardownSteps(teardownSteps.toArray(Runnable[]::new));
    }

    /**
     * Publishes a placement-only UI frame for world Replay playback when no
     * native BBS {@code UIScreen} exists. The session never exposes the source
     * framebuffer; it contains one painter-ordered logical surface quad.
     */
    public static void publishStandaloneWorldReplayFrame(
        int width,
        int height,
        int framebufferWidth,
        int framebufferHeight
    )
    {
        if (width < 1 || height < 1 || framebufferWidth < 1 || framebufferHeight < 1 || !BBSUiMirrorRegistry.hasActiveDemand())
        {
            closeStandaloneWorldReplaySession();
            return;
        }

        long sessionId = STANDALONE_WORLD_REPLAY_SESSION_ID.get();

        if (!isSessionOpen(sessionId))
        {
            long openedSessionId = openSession(width, height, framebufferWidth, framebufferHeight);

            if (!STANDALONE_WORLD_REPLAY_SESSION_ID.compareAndSet(sessionId, openedSessionId))
            {
                closeSession(openedSessionId);
                sessionId = STANDALONE_WORLD_REPLAY_SESSION_ID.get();
            }
            else
            {
                sessionId = openedSessionId;
            }
        }

        if (!isSessionOpen(sessionId))
        {
            return;
        }

        resizeSession(sessionId, width, height, framebufferWidth, framebufferHeight);

        if (beginFrame(sessionId, width, height))
        {
            recordFullscreenSurface(BBSRenderSurfaceKind.WORLD_REPLAY, width, height);
            endFrame(GLFW.GLFW_ARROW_CURSOR, width / 2F, height / 2F);
        }
    }

    /** Closes only the placement-only Replay session, leaving real UI sessions intact. */
    public static void closeStandaloneWorldReplaySession()
    {
        long sessionId = STANDALONE_WORLD_REPLAY_SESSION_ID.getAndSet(0L);

        if (sessionId > 0L)
        {
            closeSession(sessionId);
        }
    }

    public static boolean beginFrame(long sessionId, int width, int height)
    {
        if (!BBSUiMirrorRegistry.hasActiveDemand() || ACTIVE_FRAME.get() != null)
        {
            return false;
        }

        SessionState session = SESSIONS.get(sessionId);

        if (session == null)
        {
            return false;
        }

        ACTIVE_FRAME.set(new FrameBuilder(session, width, height));

        return true;
    }

    public static void endFrame(int cursorShape, float mouseX, float mouseY)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null)
        {
            return;
        }

        ACTIVE_FRAME.remove();

        long sequence = ++builder.session.sequence;
        BBSUiCursor cursor = new BBSUiCursor(mouseX, mouseY, cursorShape(cursorShape));
        BBSUiFrame frame = new BBSUiFrame(
            builder.session.sessionId,
            sequence,
            System.nanoTime(),
            builder.width,
            builder.height,
            cursor,
            builder.finishCommands(),
            builder.truncated
        );

        BBSUiMirrorRegistry.publish(frame);
    }

    public static void abortFrame()
    {
        ACTIVE_FRAME.remove();
    }

    public static void recordClipPush(int x, int y, int width, int height)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder != null)
        {
            builder.add(new BBSUiClipPush(x, y, width, height));
        }
    }

    public static void recordClipPop()
    {
        add(BBSUiClipPop.INSTANCE);
    }

    public static void recordQuad(
        Matrix4f matrix,
        float x,
        float y,
        float width,
        float height,
        int topLeft,
        int topRight,
        int bottomLeft,
        int bottomRight
    )
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null)
        {
            return;
        }

        builder.add(new BBSUiQuad(
            vertex(matrix, x, y, topLeft),
            vertex(matrix, x, y + height, bottomLeft),
            vertex(matrix, x + width, y + height, bottomRight),
            vertex(matrix, x + width, y, topRight)
        ));
    }

    public static void recordGlyphRun(Matrix4f matrix, String text, float x, float y, float width, float height, int color, boolean shadow)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null || text == null || text.isEmpty())
        {
            return;
        }

        builder.add(new BBSUiGlyphRun(
            text,
            vertex(matrix, x, y, color),
            vertex(matrix, x, y + height, color),
            vertex(matrix, x + width, y + height, color),
            vertex(matrix, x + width, y, color),
            shadow
        ));
    }

    public static void recordTextureQuad(
        Matrix4f matrix,
        Texture texture,
        float x,
        float y,
        float width,
        float height,
        float u1,
        float v1,
        float u2,
        float v2,
        int textureWidth,
        int textureHeight,
        int tint
    )
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null || texture == null || textureWidth <= 0 || textureHeight <= 0)
        {
            return;
        }

        if (builder.isFull())
        {
            builder.markTruncated();

            return;
        }

        BBSUiAssetRef asset = BBSUiAssetPublisher.reference(texture);

        if (asset == null)
        {
            builder.unsupported(BBSUiUnsupportedReason.ASSET_UNAVAILABLE, 1);

            return;
        }

        float normalizedU1 = u1 / textureWidth;
        float normalizedV1 = v1 / textureHeight;
        float normalizedU2 = u2 / textureWidth;
        float normalizedV2 = v2 / textureHeight;

        builder.add(new BBSUiTextureQuad(
            asset,
            textureVertex(matrix, x, y, normalizedU1, normalizedV1),
            textureVertex(matrix, x, y + height, normalizedU1, normalizedV2),
            textureVertex(matrix, x + width, y + height, normalizedU2, normalizedV2),
            textureVertex(matrix, x + width, y, normalizedU2, normalizedV1),
            tint
        ));
    }

    /**
     * Records an already-triangulated colored mesh. Input positions are local
     * to {@code matrix}; the published vertices are transformed screen-space
     * values.
     */
    public static void recordColoredMesh(Matrix4f matrix, List<BBSUiVertex> vertices)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null || matrix == null || vertices == null || vertices.isEmpty())
        {
            return;
        }
        if (vertices.size() % 3 != 0 || vertices.size() > BBSUiColoredMesh.MAX_VERTICES)
        {
            builder.unsupported(BBSUiUnsupportedReason.DIRECT_DRAW, 1);

            return;
        }

        ArrayList<BBSUiVertex> transformed = new ArrayList<>(vertices.size());

        for (BBSUiVertex value : vertices)
        {
            if (value == null)
            {
                builder.unsupported(BBSUiUnsupportedReason.DIRECT_DRAW, 1);

                return;
            }

            transformed.add(vertex(matrix, value.x(), value.y(), value.color()));
        }

        builder.add(new BBSUiColoredMesh(transformed));
    }

    /**
     * Records a textured triangle list. Input UVs use native texture pixels;
     * the public command receives normalized top-down texture coordinates.
     */
    public static void recordTexturedMesh(
        Matrix4f matrix,
        Texture texture,
        List<BBSUiTexturedMeshVertex> vertices,
        int textureWidth,
        int textureHeight
    )
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null || matrix == null || texture == null || vertices == null || vertices.isEmpty())
        {
            return;
        }
        if (textureWidth <= 0 || textureHeight <= 0 || vertices.size() % 3 != 0 || vertices.size() > BBSUiTexturedMesh.MAX_VERTICES)
        {
            builder.unsupported(BBSUiUnsupportedReason.DIRECT_DRAW, 1);

            return;
        }
        if (builder.isFull())
        {
            builder.markTruncated();

            return;
        }

        BBSUiAssetRef asset = BBSUiAssetPublisher.reference(texture);

        if (asset == null)
        {
            builder.unsupported(BBSUiUnsupportedReason.ASSET_UNAVAILABLE, 1);

            return;
        }

        ArrayList<BBSUiTexturedMeshVertex> transformed = new ArrayList<>(vertices.size());

        for (BBSUiTexturedMeshVertex value : vertices)
        {
            if (value == null)
            {
                builder.unsupported(BBSUiUnsupportedReason.DIRECT_DRAW, 1);

                return;
            }

            transformed.add(texturedMeshVertex(
                matrix,
                value.x(),
                value.y(),
                value.u() / textureWidth,
                value.v() / textureHeight,
                value.color()
            ));
        }

        builder.add(new BBSUiTexturedMesh(asset, transformed));
    }

    /** Aggregates one native draw that cannot be represented by this mirror. */
    public static void recordUnsupported(BBSUiUnsupportedReason reason)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder != null && reason != null)
        {
            builder.unsupported(reason, 1);
        }
    }

    public static void recordSurfaceQuad(
        Matrix4f matrix,
        BBSRenderSurfaceKind surfaceKind,
        float x,
        float y,
        float width,
        float height,
        float u1,
        float v1,
        float u2,
        float v2,
        int tint
    )
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder == null || surfaceKind == null)
        {
            return;
        }

        builder.add(new BBSUiSurfaceQuad(
            surfaceKind,
            textureVertex(matrix, x, y, u1, 1F - v1),
            textureVertex(matrix, x, y + height, u1, 1F - v2),
            textureVertex(matrix, x + width, y + height, u2, 1F - v2),
            textureVertex(matrix, x + width, y, u2, 1F - v1),
            tint
        ));
    }

    /** Records a full logical-frame surface at the current painter position. */
    public static void recordFullscreenSurface(BBSRenderSurfaceKind surfaceKind, int width, int height)
    {
        if (surfaceKind == null || width < 1 || height < 1)
        {
            return;
        }

        recordSurfaceQuad(
            new Matrix4f(),
            surfaceKind,
            0F,
            0F,
            width,
            height,
            0F,
            1F,
            1F,
            0F,
            0xffffffff
        );
    }

    public static void recordFormPreviewAtlas(int x, int y, int width, int height, int screenWidth, int screenHeight)
    {
        if (width < 1 || height < 1 || screenWidth < 1 || screenHeight < 1)
        {
            return;
        }

        recordSurfaceQuad(new Matrix4f(), BBSRenderSurfaceKind.FORM_PREVIEW_ATLAS,
            x, y, width, height, 0F, 1F, 1F, 0F, 0xffffffff);
    }

    private static void add(BBSUiDrawCommand command)
    {
        FrameBuilder builder = ACTIVE_FRAME.get();

        if (builder != null)
        {
            builder.add(command);
        }
    }

    private static BBSUiVertex vertex(Matrix4f matrix, float x, float y, int color)
    {
        Vector3f point = new Vector3f(x, y, 0F);

        matrix.transformPosition(point);

        return new BBSUiVertex(point.x, point.y, color);
    }

    private static BBSUiTexturedVertex textureVertex(Matrix4f matrix, float x, float y, float u, float v)
    {
        Vector3f point = new Vector3f(x, y, 0F);

        matrix.transformPosition(point);

        return new BBSUiTexturedVertex(point.x, point.y, u, v);
    }

    private static BBSUiTexturedMeshVertex texturedMeshVertex(Matrix4f matrix, float x, float y, float u, float v, int color)
    {
        Vector3f point = new Vector3f(x, y, 0F);

        matrix.transformPosition(point);

        return new BBSUiTexturedMeshVertex(point.x, point.y, u, v, color);
    }

    private static BBSUiSessionInfo sessionInfo(long sessionId, int width, int height, int framebufferWidth, int framebufferHeight)
    {
        return new BBSUiSessionInfo(
            sessionId,
            Math.max(0, width),
            Math.max(0, height),
            Math.max(0, framebufferWidth),
            Math.max(0, framebufferHeight)
        );
    }

    private static BBSUiCursorShape cursorShape(int shape)
    {
        if (shape == GLFW.GLFW_IBEAM_CURSOR) return BBSUiCursorShape.TEXT;
        if (shape == GLFW.GLFW_CROSSHAIR_CURSOR) return BBSUiCursorShape.CROSSHAIR;
        if (shape == GLFW.GLFW_HAND_CURSOR) return BBSUiCursorShape.HAND;
        if (shape == GLFW.GLFW_HRESIZE_CURSOR) return BBSUiCursorShape.HORIZONTAL_RESIZE;
        if (shape == GLFW.GLFW_VRESIZE_CURSOR) return BBSUiCursorShape.VERTICAL_RESIZE;

        return BBSUiCursorShape.DEFAULT;
    }

    private static final class SessionState
    {
        private final long sessionId;
        private volatile BBSUiSessionInfo info;
        private long sequence;

        private SessionState(BBSUiSessionInfo info)
        {
            this.sessionId = info.sessionId();
            this.info = info;
        }
    }

    private static final class FrameBuilder
    {
        private final SessionState session;
        private final int width;
        private final int height;
        private final ArrayList<BBSUiDrawCommand> commands = new ArrayList<>();
        private final EnumMap<BBSUiUnsupportedReason, Integer> unsupported = new EnumMap<>(BBSUiUnsupportedReason.class);
        private boolean truncated;

        private FrameBuilder(SessionState session, int width, int height)
        {
            this.session = session;
            this.width = width;
            this.height = height;
        }

        private void add(BBSUiDrawCommand command)
        {
            if (this.commands.size() >= MAX_DRAW_COMMANDS_PER_FRAME)
            {
                this.markTruncated();

                return;
            }

            this.commands.add(command);
        }

        private boolean isFull()
        {
            return this.commands.size() >= MAX_DRAW_COMMANDS_PER_FRAME;
        }

        private void markTruncated()
        {
            this.truncated = true;
            this.unsupported(BBSUiUnsupportedReason.FRAME_COMMAND_LIMIT, 1);
        }

        private void unsupported(BBSUiUnsupportedReason reason, int count)
        {
            if (reason == null || count <= 0)
            {
                return;
            }

            this.unsupported.merge(reason, count, (left, right) -> left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right);
        }

        private List<BBSUiDrawCommand> finishCommands()
        {
            if (this.unsupported.isEmpty())
            {
                return this.commands;
            }

            ArrayList<BBSUiDrawCommand> result = new ArrayList<>(this.commands.size() + this.unsupported.size());

            result.addAll(this.commands);
            this.unsupported.forEach((reason, count) -> result.add(new BBSUiUnsupported(reason, count)));

            return result;
        }
    }
}
