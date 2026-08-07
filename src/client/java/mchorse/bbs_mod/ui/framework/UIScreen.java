package mchorse.bbs_mod.ui.framework;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.api.client.render.BBSRenderSurfaceKind;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.client.render.surface.BBSFormPreviewCapture;
import mchorse.bbs_mod.client.render.surface.BBSRenderSurfaceRuntime;
import mchorse.bbs_mod.client.ui.mirror.BBSUiFrameRecorder;
import mchorse.bbs_mod.client.ui.mirror.BBSUiInputDispatcher;
import mchorse.bbs_mod.client.ui.mirror.BBSUiRemoteHeldState;
import mchorse.bbs_mod.importers.IImportPathProvider;
import mchorse.bbs_mod.importers.ImporterContext;
import mchorse.bbs_mod.importers.Importers;
import mchorse.bbs_mod.importers.types.IImporter;
import mchorse.bbs_mod.importers.types.ImportOutcome;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.text.UIBaseTextbox;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextarea;
import mchorse.bbs_mod.ui.utils.IFileDropListener;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.FFMpegUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UIScreen extends Screen implements IFileDropListener
{
    private static final Logger LOGGER = LoggerFactory.getLogger("bbs-ui-screen-input");

    private UIBaseMenu menu;
    private UIRenderingContext context;

    private boolean lastHideGui;
    private long mirrorSessionId;
    private boolean mirrorInputAttached;
    private boolean removing;
    private boolean removed;
    /** Wall-clock time of the last screen tick, used to derive an advancing partial tick while paused. */
    private long lastTickMillis = -1L;
    private final Map<Integer, LocalHeldMouse> localHeldMouseButtons = new LinkedHashMap<>();
    private final Map<Integer, LocalHeldKey> localHeldKeys = new LinkedHashMap<>();
    private boolean releasingLocalInputGestures;
    private long localInputGeneration = 1L;
    private long nextLocalGestureToken;

    public static void open(UIBaseMenu menu)
    {
        Minecraft.getInstance().setScreen(new UIScreen(Component.empty(), menu));
    }

    public static UIBaseMenu getCurrentMenu()
    {
        Screen currentScreen = Minecraft.getInstance().screen;

        if (currentScreen instanceof UIScreen uiScreen)
        {
            return uiScreen.menu;
        }

        return null;
    }

    public UIScreen(Component title, UIBaseMenu menu)
    {
        super(title);

        Minecraft mc = Minecraft.getInstance();

        this.menu = menu;
        this.context = new UIRenderingContext(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));

        this.menu.context.setup(this.context);
    }

    public UIBaseMenu getMenu()
    {
        return this.menu;
    }

    public void update()
    {
        this.lastTickMillis = Util.getMillis();
        this.menu.update();
    }

    public void renderInWorld(IBbsWorldRenderContext context)
    {
        this.menu.renderInWorld(context);
    }

    @Override
    public void onFilesDrop(List<Path> paths)
    {
        super.onFilesDrop(paths);

        String[] filePaths = new String[paths.size()];
        int i = 0;

        for (Path path : paths)
        {
            filePaths[i] = path.toAbsolutePath().toString();

            i += 1;
        }

        this.acceptFilePaths(filePaths);
    }

    @Override
    public void removed()
    {
        if (this.removing || this.removed)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Throwable failure = null;

        this.removing = true;
        this.removed = true;

        failure = runTeardownStep(failure, this::releaseLocalInputGestures);
        failure = runTeardownStep(failure, () -> BBSUiInputDispatcher.detach(this, this.mirrorSessionId));
        failure = runTeardownStep(failure, () -> BBSModClient.setCustomGUIScale(false));
        failure = runTeardownStep(failure, mc::resizeDisplay);
        failure = runTeardownStep(failure, this::removedVanillaScreen);
        failure = runTeardownStep(failure, () -> this.menu.onClose(null));
        failure = runTeardownStep(failure, this.menu::invalidateInputState);
        failure = runTeardownStep(failure, this.menu.context::invalidateContextMenus);

        this.localInputGeneration = this.localInputGeneration == Long.MAX_VALUE
            ? 1L
            : this.localInputGeneration + 1L;

        failure = runTeardownStep(failure, () ->
        {
            if (this.menu.canHideHUD())
            {
                mc.options.hideGui = this.lastHideGui;
            }
        });

        long closingSession = this.mirrorSessionId;

        failure = runTeardownStep(failure, () -> BBSUiFrameRecorder.closeSession(closingSession));
        this.mirrorSessionId = 0L;
        this.mirrorInputAttached = false;
        this.removing = false;

        rethrowTeardownFailure(failure);
    }

    private void removedVanillaScreen()
    {
        super.removed();
    }

    private static Throwable runTeardownStep(Throwable failure, Runnable step)
    {
        try
        {
            step.run();
        }
        catch (RuntimeException | Error exception)
        {
            if (failure == null)
            {
                return exception;
            }

            if (failure != exception)
            {
                failure.addSuppressed(exception);
            }
        }

        return failure;
    }

    private static void rethrowTeardownFailure(Throwable failure)
    {
        if (failure instanceof RuntimeException exception)
        {
            throw exception;
        }
        if (failure instanceof Error error)
        {
            throw error;
        }
    }

    @Override
    public void added()
    {
        Minecraft mc = Minecraft.getInstance();

        this.lastHideGui = mc.options.hideGui;

        BBSModClient.setCustomGUIScale(true);
        mc.resizeDisplay();

        super.added();

        this.menu.onOpen(null);

        if (this.menu.canHideHUD())
        {
            mc.options.hideGui = true;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return this.menu.canPause();
    }

    @Override
    protected void init()
    {
        super.init();

        this.menu.resize(this.width, this.height);
        this.ensureMirrorSession();
    }

    @Override
    public void resize(Minecraft client, int width, int height)
    {
        /* MC 1.21.1: Screen.resize() no longer assigns this.minecraft
         * (only Screen.init() does).  During added() → guiScale().set()
         * → resizeDisplay() → resize(), the field is still null because
         * Minecraft.setScreen() calls init() AFTER added(). */
        this.minecraft = client;

        super.resize(client, width, height);

        this.menu.resize(width, height);
        this.ensureMirrorSession();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures || this.hasRemoteInputLease())
        {
            return true;
        }

        if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST)
        {
            this.localHeldMouseButtons.putIfAbsent(button, new LocalHeldMouse(this.nextLocalGestureToken()));
        }

        return this.dispatchRemoteMouseClicked(mouseX, mouseY, button);
    }

    public boolean dispatchRemoteMouseClicked(double mouseX, double mouseY, int button)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures)
        {
            return true;
        }

        return this.menu.mouseClicked((int) mouseX, (int) mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures || this.hasRemoteInputLease())
        {
            return true;
        }

        return this.dispatchRemoteMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean dispatchRemoteMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures)
        {
            return true;
        }

        return this.menu.mouseScrolled((int) mouseX, (int) mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        LocalHeldMouse held = this.localHeldMouseButtons.remove(button);

        if (this.removed && !this.removing)
        {
            return true;
        }
        if (this.hasRemoteInputLease())
        {
            return true;
        }

        try
        {
            return this.dispatchRemoteMouseReleased(mouseX, mouseY, button);
        }
        finally
        {
            if (held != null)
            {
                this.localHeldMouseButtons.remove(button, held);
            }
        }
    }

    public boolean dispatchRemoteMouseReleased(double mouseX, double mouseY, int button)
    {
        if (this.removed && !this.removing)
        {
            return true;
        }

        return this.menu.mouseReleased((int) mouseX, (int) mouseY, button);
    }

    public boolean dispatchRemoteMouseCanceled(double mouseX, double mouseY, int button)
    {
        if (this.removed && !this.removing)
        {
            return true;
        }

        return this.menu.mouseCanceled((int) mouseX, (int) mouseY, button);
    }

    /** End every locally delivered held gesture before another owner takes over. */
    public void releaseLocalInputGestures()
    {
        if (this.releasingLocalInputGestures)
        {
            return;
        }

        int mouseX = this.menu.context.mouseX;
        int mouseY = this.menu.context.mouseY;
        Map<Integer, LocalHeldMouse> mouseButtons = new LinkedHashMap<>(this.localHeldMouseButtons);
        Map<Integer, LocalHeldKey> keys = new LinkedHashMap<>(this.localHeldKeys);
        long generation = this.localInputGeneration;

        this.localHeldMouseButtons.clear();
        this.localHeldKeys.clear();
        this.releasingLocalInputGestures = true;

        try
        {
            for (int button : mouseButtons.keySet())
            {
                try
                {
                    this.menu.mouseCanceled(mouseX, mouseY, button);
                }
                catch (RuntimeException | Error exception)
                {
                    LOGGER.debug("Failed to cancel a local mouse gesture during input handoff", exception);
                }

                if (!this.isLocalReleaseTargetCurrent(generation))
                {
                    return;
                }
            }

            for (Map.Entry<Integer, LocalHeldKey> entry : keys.entrySet())
            {
                LocalHeldKey key = entry.getValue();

                try
                {
                    this.menu.handleKey(entry.getKey(), key.scanCode, GLFW.GLFW_RELEASE, key.modifiers);
                }
                catch (RuntimeException | Error exception)
                {
                    LOGGER.debug("Failed to synthesize local key release during input handoff", exception);
                }

                if (!this.isLocalReleaseTargetCurrent(generation))
                {
                    return;
                }
            }
        }
        finally
        {
            this.releasingLocalInputGestures = false;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures || this.hasRemoteInputLease())
        {
            return true;
        }

        int action = BBSRendering.lastAction;

        if (action == GLFW.GLFW_PRESS && keyCode >= 0 && keyCode <= GLFW.GLFW_KEY_LAST)
        {
            this.localHeldKeys.putIfAbsent(
                keyCode,
                new LocalHeldKey(scanCode, modifiers, this.nextLocalGestureToken())
            );
        }

        return this.dispatchRemoteKey(keyCode, scanCode, action, modifiers);
    }

    /**
     * Internal remote-input entry that shares the same repeat filtering and
     * menu dispatch path as physical keyboard input.
     */
    public boolean dispatchRemoteKey(int keyCode, int scanCode, int action, int modifiers)
    {
        if (this.removed && !this.removing)
        {
            return true;
        }
        if ((this.removing || this.removed || this.releasingLocalInputGestures)
            && action != GLFW.GLFW_RELEASE)
        {
            return true;
        }

        if (action == GLFW.GLFW_RELEASE)
        {
            return this.menu.handleKey(keyCode, scanCode, GLFW.GLFW_RELEASE, modifiers);
        }

        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT)
        {
            return false;
        }

        /* Disable long-press repeat (GLFW_REPEAT) in BBS panel.
         * MC 1.21.1 calls keyPressed() for both PRESS and REPEAT,
         * but original BBS only processes initial PRESS. */
        if (action == GLFW.GLFW_REPEAT && !this.canFocusedElementRepeatKeys())
        {
            return false;
        }

        return this.menu.handleKey(keyCode, scanCode, action, modifiers);
    }

    private boolean canFocusedElementRepeatKeys()
    {
        return this.menu.context.activeElement instanceof UIBaseTextbox
            || this.menu.context.activeElement instanceof UITextarea;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers)
    {
        LocalHeldKey held = this.localHeldKeys.remove(keyCode);

        if (this.removed && !this.removing)
        {
            return true;
        }
        if (this.hasRemoteInputLease())
        {
            return true;
        }

        try
        {
            return this.menu.handleKey(keyCode, scanCode, GLFW.GLFW_RELEASE, modifiers);
        }
        finally
        {
            if (held != null)
            {
                this.localHeldKeys.remove(keyCode, held);
            }
        }
    }

    @Override
    public boolean charTyped(char chr, int modifiers)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures || this.hasRemoteInputLease())
        {
            return true;
        }

        this.menu.handleTextInput(chr);

        return true;
    }

    /**
     * Internal batched text entry. BBS' current text path consumes UTF-16
     * characters, matching Screen.charTyped(char, int).
     */
    public void dispatchRemoteText(String text, int modifiers)
    {
        if (this.removing || this.removed || this.releasingLocalInputGestures)
        {
            return;
        }

        for (int i = 0; i < text.length(); i++)
        {
            this.menu.handleTextInput(text.charAt(i));
        }
    }

    private boolean hasRemoteInputLease()
    {
        return BBSUiRemoteHeldState.isActive(this.mirrorSessionId);
    }

    /**
     * Resolve the current input owner's X coordinate in framebuffer pixels.
     * The remote branch deliberately does not read or move the physical cursor.
     */
    public int getOwnerFramebufferMouseX()
    {
        Minecraft mc = Minecraft.getInstance();

        if (this.hasRemoteInputLease())
        {
            int logical = BBSUiInputDispatcher.effectiveMouseX(this.mirrorSessionId, this.menu.context.mouseX);

            return scaleCoordinate(logical, this.width, mc.getWindow().getScreenWidth());
        }

        return (int) mc.mouseHandler.xpos();
    }

    /** Remote counterpart of {@link #getOwnerFramebufferMouseX()}. */
    public int getOwnerFramebufferMouseY()
    {
        Minecraft mc = Minecraft.getInstance();

        if (this.hasRemoteInputLease())
        {
            int logical = BBSUiInputDispatcher.effectiveMouseY(this.mirrorSessionId, this.menu.context.mouseY);

            return scaleCoordinate(logical, this.height, mc.getWindow().getScreenHeight());
        }

        return (int) mc.mouseHandler.ypos();
    }

    private static int scaleCoordinate(int coordinate, int sourceSize, int targetSize)
    {
        return sourceSize <= 0 ? coordinate : (int) Math.round(coordinate * (double) targetSize / sourceSize);
    }

    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta)
    {}

    /**
     * Screen rendering hands us the game time delta in ticks, not the 0..1 partial
     * tick the UI transition is defined as. Feeding that straight through pins every
     * interpolated element at a constant fraction between two ticks, so animations
     * only advance once per tick regardless of frame rate. Read the partial tick the
     * world render path uses instead, so both paths interpolate identically.
     */
    private float resolveTransition(float delta)
    {
        Minecraft mc = Minecraft.getInstance();

        try
        {
            float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);

            if (!mc.isPaused())
            {
                return partialTick;
            }

            /* The game timer freezes its partial tick while paused (the residual is
             * snapshotted), so preview animations that advance at the screen tick
             * rate (e.g. the form editor's 3D preview) render with a fixed
             * interpolation offset and visibly step. Derive an advancing partial
             * tick from wall-clock time between screen ticks to keep them smooth. */
            if (this.lastTickMillis < 0L)
            {
                return 0F;
            }

            return Math.min((Util.getMillis() - this.lastTickMillis) / 50F, 1F);
        }
        catch (Exception ignored)
        {}

        return delta;
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta)
    {
        super.render(context, mouseX, mouseY, delta);

        this.ensureMirrorSession();

        long renderingSessionId = this.mirrorSessionId;

        if (!this.isRenderSessionCurrent(renderingSessionId))
        {
            return;
        }

        int effectiveMouseX = BBSUiInputDispatcher.effectiveMouseX(renderingSessionId, mouseX);
        int effectiveMouseY = BBSUiInputDispatcher.effectiveMouseY(renderingSessionId, mouseY);
        boolean recording = false;
        boolean previewStarted = false;
        Throwable renderFailure = null;

        try
        {
            recording = BBSUiFrameRecorder.beginFrame(renderingSessionId, this.width, this.height);

            if (recording)
            {
                BBSFormPreviewCapture.beginFrame(this.width, this.height);
                previewStarted = true;
            }

            this.context.setContext(context);
            this.menu.context.setTransition(this.resolveTransition(delta));

            if (recording && BBSRendering.isWorldReplayActive())
            {
                /* The world Replay is the background below native BBS controls.
                 * Record its placement before the menu emits any UI commands. */
                BBSUiFrameRecorder.recordFullscreenSurface(BBSRenderSurfaceKind.WORLD_REPLAY, this.width, this.height);
            }

            if (recording && BBSRendering.isMorphWorldPreviewActive())
            {
                BBSUiFrameRecorder.recordFullscreenSurface(BBSRenderSurfaceKind.MORPH_WORLD_PREVIEW, this.width, this.height);
            }

            this.menu.renderMenu(this.context, effectiveMouseX, effectiveMouseY);

            if (!this.isRenderSessionCurrent(renderingSessionId))
            {
                return;
            }

            this.menu.context.render.executeRunnables();

            if (!this.isRenderSessionCurrent(renderingSessionId))
            {
                return;
            }

            if (recording)
            {
                /* Submit GuiGraphics before the asynchronous blit so labels and
                 * selection vectors are present in the captured framebuffer. */
                context.flush();

                if (!this.isRenderSessionCurrent(renderingSessionId))
                {
                    return;
                }

                BBSFormPreviewCapture.Region preview = BBSFormPreviewCapture.finishFrame();
                previewStarted = false;

                if (preview != null)
                {
                    BBSUiFrameRecorder.recordFormPreviewAtlas(
                        preview.x(), preview.y(), preview.width(), preview.height(), this.width, this.height
                    );
                    BBSRenderSurfaceRuntime.captureUiRegion(
                        Minecraft.getInstance().getMainRenderTarget(),
                        java.util.EnumSet.of(BBSRenderSurfaceKind.FORM_PREVIEW_ATLAS),
                        preview.x(), preview.y(), preview.width(), preview.height(), this.width, this.height
                    );
                }

                BBSUiFrameRecorder.endFrame(this.menu.context.getCursorShape(), effectiveMouseX, effectiveMouseY);
                recording = false;
            }
        }
        catch (RuntimeException | Error e)
        {
            renderFailure = e;

            throw e;
        }
        finally
        {
            Throwable cleanupFailure = renderFailure;

            if (previewStarted)
            {
                cleanupFailure = runTeardownStep(cleanupFailure, BBSFormPreviewCapture::abortFrame);
            }
            if (recording)
            {
                cleanupFailure = runTeardownStep(cleanupFailure, BBSUiFrameRecorder::abortFrame);
            }

            if (renderFailure == null)
            {
                rethrowTeardownFailure(cleanupFailure);
            }
        }
    }

    private boolean isRenderSessionCurrent(long sessionId)
    {
        return sessionId > 0L
            && !this.removing
            && !this.removed
            && Minecraft.getInstance().screen == this
            && this.mirrorSessionId == sessionId
            && BBSUiFrameRecorder.isSessionOpen(sessionId);
    }

    private void ensureMirrorSession()
    {
        if (this.removing || this.removed)
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int framebufferWidth = mc.getWindow().getWidth();
        int framebufferHeight = mc.getWindow().getHeight();

        /* A real UIScreen owns the mirror session from this point onward. Close
         * the placement-only Replay session first so listeners never observe
         * overlapping synthetic and native UI session ownership. */
        BBSUiFrameRecorder.closeStandaloneWorldReplaySession();

        if (!BBSUiFrameRecorder.isSessionOpen(this.mirrorSessionId))
        {
            long openedSession = BBSUiFrameRecorder.openSession(
                this.width,
                this.height,
                framebufferWidth,
                framebufferHeight
            );
            this.mirrorSessionId = openedSession;

            try
            {
                BBSUiInputDispatcher.attach(this, openedSession);
                this.mirrorInputAttached = true;
            }
            catch (RuntimeException | Error exception)
            {
                try
                {
                    BBSUiFrameRecorder.closeSession(openedSession);
                }
                catch (RuntimeException | Error cleanupFailure)
                {
                    if (exception != cleanupFailure)
                    {
                        exception.addSuppressed(cleanupFailure);
                    }
                }

                this.mirrorSessionId = 0L;
                this.mirrorInputAttached = false;

                throw exception;
            }
        }
        else
        {
            if (!this.mirrorInputAttached)
            {
                BBSUiInputDispatcher.attach(this, this.mirrorSessionId);
                this.mirrorInputAttached = true;
            }

            BBSUiFrameRecorder.resizeSession(
                this.mirrorSessionId,
                this.width,
                this.height,
                framebufferWidth,
                framebufferHeight
            );
        }
    }

    @Override
    public void acceptFilePaths(String[] paths)
    {
        if (this.menu != null)
        {
            File directory = null;
            boolean open = true;

            for (IImportPathProvider provider : this.menu.getRoot().getChildren(IImportPathProvider.class))
            {
                directory = provider.getImporterPath();

                if (directory != null)
                {
                    open = false;

                    break;
                }
            }

            List<File> files = new ArrayList<>();

            for (String path : paths)
            {
                File file = new File(path);

                if (file.exists())
                {
                    files.add(file);
                }
            }

            ImporterContext context = new ImporterContext(files, directory);

            for (IImporter importer : Importers.getImporters())
            {
                if (importer.canImport(context))
                {
                    if (importer.requiresFFmpeg() && !FFMpegUtils.checkFFMPEG())
                    {
                        this.menu.context.notifyError(UIKeys.IMPORTER_FFMPEG_NOTIFICATION);

                        return;
                    }

                    ImportOutcome outcome;

                    try
                    {
                        outcome = importer.importFilesOutcome(context);
                    }
                    catch (Exception e)
                    {
                        String message = e.getMessage();

                        outcome = ImportOutcome.failure(0,
                            message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
                    }

                    if (outcome == null || !outcome.success())
                    {
                        this.menu.context.notifyError(outcome == null || outcome.message() == null
                            ? UIKeys.IMPORTER_FFMPEG_NOTIFICATION
                            : IKey.raw(outcome.message()));

                        return;
                    }

                    if (open)
                    {
                        UIUtils.openFolder(context.getDestination(importer));
                    }

                    this.menu.context.notifySuccess(UIKeys.IMPORTER_SUCCESS_NOTIFICATION.format(importer.getName()));

                    return;
                }
            }
        }
    }

    private boolean isLocalReleaseTargetCurrent(long generation)
    {
        return this.localInputGeneration == generation
            && Minecraft.getInstance().screen == this
            && !this.hasRemoteInputLease();
    }

    private long nextLocalGestureToken()
    {
        this.nextLocalGestureToken = this.nextLocalGestureToken == Long.MAX_VALUE
            ? 1L
            : this.nextLocalGestureToken + 1L;

        return this.nextLocalGestureToken;
    }

    private record LocalHeldMouse(long token)
    {}

    private record LocalHeldKey(int scanCode, int modifiers, long token)
    {}
}
