package mchorse.bbs_mod.ui.film.controller;

import mchorse.bbs_mod.utils.profiler.BBSProfiler;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.joml.Matrix3f;
import org.lwjgl.opengl.GL11;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.render.multiview.ViewRenderState;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.film.FilmControllerContext;
import mchorse.bbs_mod.film.FilmEntityRenderer;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.view.ViewFrameGeometry;
import mchorse.bbs_mod.ui.film.UIFilmPreview;
import mchorse.bbs_mod.ui.film.view.ViewportPickIntent;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import static mchorse.bbs_mod.ui.film.controller.UIFilmController.*;

/** View-local stencil rendering and deferred input intent ownership. */
public class FilmStencilPicker
{
    private final UIFilmController controller;

    public FilmStencilPicker(UIFilmController controller)
    {
        this.controller = controller;
    }

    int hoveredReplayIndex = -1;

    StencilFormFramebuffer stencil = new StencilFormFramebuffer();

    StencilMap stencilMap = new StencilMap();

    long stencilFrame = -1L;

    boolean stencilAlt;

    Replay stencilReplay;

    private TargetPick targetPick;
    private TargetPick stencilTargetPick;
    private TargetPick pendingTargetPick;

    private record TargetPick(UIElement owner, String excludedReplay, BiConsumer<String, Pair<Form, String>> callback)
    {}

    final ViewportPickIntent<ViewportPick> pendingViewportPick = new ViewportPickIntent<>();

    long pendingPickOrbitGeneration;

    long queuedPickGeneration;

    private record ViewportPick(Replay replay, Pair<Form, String> form, boolean empty)
    {}

    public StencilFormFramebuffer getStencil()
    {
        return this.stencil;
    }

    private FilmStencilPicker targetOwner()
    {
        return this.controller.panel.getController().picker;
    }

    private TargetPick targetRequest()
    {
        FilmStencilPicker owner = this.targetOwner();

        if (owner.targetPick != null && !owner.targetPick.owner().canBeSeen())
        {
            owner.cancelTargetPick();
        }

        return owner.targetPick;
    }

    public void toggleTargetPick(UIElement owner, String excludedReplay, BiConsumer<String, Pair<Form, String>> callback)
    {
        FilmStencilPicker picker = this.targetOwner();
        boolean cancel = picker.targetPick != null && picker.targetPick.owner() == owner;

        picker.cancelTargetPick();

        if (!cancel)
        {
            picker.targetPick = new TargetPick(owner, excludedReplay, callback);
        }

        for (UIFilmPreview preview : this.controller.panel.getPreviews())
        {
            preview.requestRefresh();
        }
    }

    public void cancelTargetPick()
    {
        FilmStencilPicker owner = this.targetOwner();

        owner.targetPick = null;
        this.hoveredReplayIndex = -1;
        this.stencil.clearPicking();
        this.cancelPendingViewportPick();
    }

    public boolean isPickingTarget()
    {
        return this.targetRequest() != null;
    }

    public boolean isPickingTarget(UIElement owner)
    {
        TargetPick request = this.targetRequest();

        return request != null && request.owner() == owner;
    }

    /** Target requests belong to the scene; their stencil and click belong to the view. */
    public boolean pickTarget(UIContext context)
    {
        if (!this.isPickingTarget() || this.controller.preview == null || !this.controller.preview.isInsideFrame(context))
        {
            return false;
        }

        if (context.mouseButton == 1)
        {
            this.cancelTargetPick();
        }
        else if (context.mouseButton == 0)
        {
            this.deferViewportPick(context, 0L);
        }

        return true;
    }

    private void acceptTarget(TargetPick request, Pair<Form, String> pair)
    {
        if (request == null || request != this.targetRequest() || pair == null || pair.a == null)
        {
            return;
        }

        Form root = FormUtils.getRoot(pair.a);

        for (Map.Entry<String, IEntity> entry : this.controller.getEntities().entrySet())
        {
            if (entry.getValue().getForm() == root && !entry.getKey().equals(request.excludedReplay()))
            {
                this.cancelTargetPick();
                request.callback().accept(entry.getKey(), pair);

                return;
            }
        }
    }

    public boolean deferViewportPick(UIContext context, long orbitGeneration)
    {
        if (this.controller.preview == null || !this.controller.preview.isInsideFrame(context) || this.controller.isViewFlying() || this.controller.isControlling())
        {
            return false;
        }

        this.pendingTargetPick = this.targetRequest();
        ViewportPickIntent.Input input = new ViewportPickIntent.Input(context.mouseX, context.mouseY, context.mouseButton,
            this.pendingTargetPick == null && Window.isAltPressed(), Window.isCtrlPressed(), Window.isShiftPressed());
        long generation = this.pendingViewportPick.begin(this.viewportPickTarget(), input,
            context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration());

        this.pendingPickOrbitGeneration = orbitGeneration;
        this.queuedPickGeneration = 0L;

        if (generation == 0L)
        {
            return false;
        }

        if (this.pendingTargetPick == null && !input.alt() && (this.controller.getCurrentEntity() == null || this.controller.pov == CAMERA_MODE_FIRST_PERSON))
        {
            this.pendingViewportPick.resolve(generation, new ViewportPick(null, null, true));
        }
        else if (this.hasDisplayedStencil(input.alt()))
        {
            this.readViewStencil(context);
            this.resolveViewportPick();
        }
        else
        {
            this.controller.preview.requestRefresh();
        }

        return true;
    }

    ViewportPickIntent.Target viewportPickTarget()
    {
        Area frame = this.controller.getViewArea();

        TargetPick request = this.targetRequest();

        return new ViewportPickIntent.Target(this.controller.preview, this.controller.panel.getData(), request == null ? this.controller.getReplay() : request,
            this.controller.preview.getViewDescriptor().getHistoryEpoch(), this.controller.pov, this.controller.panel.getCursor(),
            new ViewFrameGeometry(frame.x, frame.y, frame.w, frame.h));
    }

    boolean updatePendingViewportPick(UIContext context)
    {
        if (this.pendingViewportPick.generation() == 0L)
        {
            return false;
        }

        this.pendingViewportPick.move(context.mouseX, context.mouseY);

        return this.pendingViewportPick.validate(this.viewportPickTarget(),
            context.getPointerGestureGeneration(), context.getContextMenuIntentGeneration(),
            this.controller.isViewActive() && this.controller.preview.canBeSeen() && !this.controller.isViewFlying() && !this.controller.isControlling()
                && !context.hasContextMenu() && !UIOverlay.has(context));
    }

    void resolveViewportPick()
    {
        ViewportPickIntent.Input input = this.pendingViewportPick.input();

        if (input == null || this.pendingViewportPick.isResolved() || !this.hasDisplayedStencil(input.alt()))
        {
            return;
        }

        Replay replay = null;
        Pair<Form, String> form = null;

        if (this.stencil.hasPicked())
        {
            int index = this.stencil.getIndex() - REPLAY_STENCIL_OFFSET;
            List<Replay> replays = this.controller.panel.getData().replays.getList();

            if (this.pendingTargetPick == null && input.alt() && input.button() == 0 && index >= 0 && index < replays.size()
                && index != this.controller.getCurrentReplayIndex())
            {
                replay = replays.get(index);
            }
            else
            {
                Pair<Form, String> picked = this.stencil.getPicked();

                if (picked != null && picked.a != null)
                {
                    form = new Pair<>(picked.a, picked.b);
                }
            }
        }

        this.pendingViewportPick.resolve(this.pendingViewportPick.generation(), new ViewportPick(replay, form, !this.stencil.hasPicked()));
    }

    public boolean releaseViewportPick(UIContext context, boolean dragged, long orbitGeneration)
    {
        if (this.pendingPickOrbitGeneration != orbitGeneration || !this.pendingViewportPick.isOwnedBy(context.mouseButton))
        {
            return false;
        }

        long generation = this.pendingViewportPick.generation();
        boolean released = this.pendingViewportPick.release(context.mouseButton, generation, context.mouseX, context.mouseY, dragged);

        if (this.updatePendingViewportPick(context))
        {
            this.commitViewportPick(context, generation);
        }

        return released;
    }

    public void cancelViewportPick(long orbitGeneration)
    {
        if (this.pendingPickOrbitGeneration == orbitGeneration)
        {
            this.cancelPendingViewportPick();
        }
    }

    public void cancelPendingViewportPick()
    {
        this.pendingViewportPick.cancel();
        this.pendingTargetPick = null;
        this.pendingPickOrbitGeneration = 0L;
        this.queuedPickGeneration = 0L;
    }

    void queueViewportPick(UIContext context)
    {
        long generation = this.pendingViewportPick.generation();

        if (this.pendingViewportPick.isReady() && this.queuedPickGeneration != generation)
        {
            this.queuedPickGeneration = generation;
            context.render.postRunnable(() -> this.commitViewportPick(context, generation));
        }
    }

    void commitViewportPick(UIContext context, long generation)
    {
        if (!this.updatePendingViewportPick(context))
        {
            return;
        }

        ViewportPickIntent.Completion<ViewportPick> completion = this.pendingViewportPick.take(generation);

        if (completion == null || completion.result() == null)
        {
            return;
        }

        ViewportPick pick = completion.result();
        ViewportPickIntent.Input input = completion.input();
        TargetPick request = this.pendingTargetPick;

        this.pendingTargetPick = null;

        context.withPointerState(input.x(), input.y(), input.button(), () ->
        {
            if (request != null)
            {
                this.acceptTarget(request, pick.form());
            }
            else if (pick.replay() != null)
            {
                int index = CollectionUtils.getIndex(this.controller.panel.getData().replays.getList(), pick.replay());

                if (index >= 0)
                {
                    this.controller.pickReplay(index);
                }
            }
            else if (pick.form() != null)
            {
                this.controller.panel.replayEditor.pickViewportFormWithOffers(context, pick.form(), input);
            }
            else if (pick.empty() && input.button() == 1)
            {
                this.controller.panel.replayEditor.openViewportContextMenu(context, this.controller.getViewArea(), this.controller, input.shift());
            }
        });
    }

    void renderPickingPreview(UIContext context, Area area)
    {
        BBSProfiler.begin(BBSProfiler.Timer.PICK_PREVIEW);

        try
        {
            boolean pending = this.updatePendingViewportPick(context);

            if (this.controller.isViewFlying() || this.controller.worldRenderContext == null || !this.controller.isViewActive()
                || (this.controller.preview != null && !this.controller.preview.isInsideFrame(context) && !pending))
            {
                this.hoveredReplayIndex = -1;

                return;
            }

            boolean altPressed = !this.isPickingTarget() && (pending ? this.pendingViewportPick.input().alt() : Window.isAltPressed());

            context.batcher.flush();
            RenderSystem.depthFunc(GL11.GL_LESS);

            /* Cache the global stuff */
            MatrixStackUtils.cacheMatrices();
            Matrix3f previousInverseView = new Matrix3f(InverseView.get());

            try
            {
                RenderSystem.setProjectionMatrix(this.controller.getViewCamera().projection, VertexSorting.DISTANCE_TO_ORIGIN);
                InverseView.set(new Matrix3f(this.controller.getViewCamera().view).invert());

                /* Render the stencil */
                PoseStack worldStack = this.controller.worldRenderContext.matrixStack();

                worldStack.pushPose();

                try
                {
                    worldStack.setIdentity();
                    MatrixStackUtils.multiply(worldStack, this.controller.getViewCamera().view);
                    this.updateViewStencil(context, altPressed);
                }
                finally
                {
                    worldStack.popPose();
                }
            }
            finally
            {
                InverseView.set(previousInverseView);
                /* Return back to orthographic projection */
                MatrixStackUtils.restoreMatrices();
            }

            RenderSystem.depthFunc(GL11.GL_ALWAYS);

            this.hoveredReplayIndex = -1;

            if (pending)
            {
                this.resolveViewportPick();
                this.queueViewportPick(context);

                if (!this.controller.preview.isInsideFrame(context) || altPressed != Window.isAltPressed())
                {
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);

                    return;
                }
            }

            if (this.controller.canShowGizmo())
            {
                this.controller.gizmo.update(context);
                this.controller.gizmo.renderSphereHighlight(context);
                this.controller.gizmo.renderReadout(context);
            }

            if (!this.stencil.hasPicked())
            {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);

                return;
            }

            int index = this.stencil.getIndex();
            Texture texture = this.stencil.getFramebuffer().getMainTexture();
            Pair<Form, String> pair = this.stencil.getPicked();
            int w = texture.width;
            int h = texture.height;

            ShaderInstance previewProgram = BBSShaders.getPickerPreviewProgram();
            Supplier<ShaderInstance> getPickerPreviewProgram = BBSShaders::getPickerPreviewProgram;
            Uniform target = previewProgram.getUniform("Target");

            if (target != null)
            {
                target.set(index);
            }

            Uniform highlight = previewProgram.getUniform("HighlightColor");

            if (highlight != null)
            {
                int color = BBSSettings.stencilHighlightColor.get();

                highlight.set(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
            }

            RenderSystem.enableBlend();
            context.batcher.texturedBox(getPickerPreviewProgram, texture.id, Colors.WHITE, area.x, area.y, area.w, area.h, 0, h, w, 0, w, h);

            if (altPressed)
            {
                int selectedReplayIndex = this.controller.getCurrentReplayIndex();
                int stencilIndex = index - REPLAY_STENCIL_OFFSET;

                if (stencilIndex >= 0 && stencilIndex < this.controller.panel.getData().replays.getList().size() && stencilIndex != selectedReplayIndex)
                {
                    this.hoveredReplayIndex = stencilIndex;

                    String label = this.controller.panel.getData().replays.getList().get(stencilIndex).getName();

                    context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
                }
                else if (pair != null && pair.a != null)
                {
                    String label = pair.a.getFormIdOrName();

                    if (!pair.b.isEmpty())
                    {
                        label += " - " + FormUtilsClient.getBoneLabel(pair.a, pair.b);
                    }

                    context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
                }
            }
            else if (pair != null && pair.a != null)
            {
                String label = pair.a.getFormIdOrName();

                if (!pair.b.isEmpty())
                {
                    label += " - " + FormUtilsClient.getBoneLabel(pair.a, pair.b);
                }

                context.batcher.textCard(label, context.mouseX + 12, context.mouseY + 8);
            }

            RenderSystem.depthFunc(GL11.GL_LEQUAL);
        }
        finally
        {
            BBSProfiler.end(BBSProfiler.Timer.PICK_PREVIEW);
        }
    }

    void updateViewStencil(UIContext context, boolean altPressed)
    {
        ViewRenderState state = this.controller.preview == null ? null : BBSRendering.getViewRenderState(this.controller.preview.getViewDescriptor().getId());
        long sample = state == null ? this.controller.worldContextFrame : state.getSampleFrameId();

        TargetPick request = this.targetRequest();

        if (sample != this.stencilFrame || this.stencilAlt != altPressed || this.stencilReplay != this.controller.getReplay()
            || this.stencilTargetPick != request)
        {
            if (sample != this.controller.worldContextFrame || sample != BBSRendering.getSceneFrameId())
            {
                if (this.controller.preview != null)
                {
                    this.controller.preview.requestRefresh();
                }

                this.stencil.clearPicking();
                this.stencilFrame = -1L;

                return;
            }

            boolean rendered = this.renderStencil(this.controller.worldRenderContext, context, altPressed);

            this.stencilFrame = rendered ? sample : -1L;
            this.stencilAlt = altPressed;
            this.stencilReplay = this.controller.getReplay();
            this.stencilTargetPick = request;

            return;
        }

        this.readViewStencil(context);
    }

    boolean hasDisplayedStencil(boolean altPressed)
    {
        ViewRenderState state = this.controller.preview == null ? null : BBSRendering.getViewRenderState(this.controller.preview.getViewDescriptor().getId());

        return state != null && state.hasFrame() && this.stencilFrame >= 0L
            && this.stencilFrame == state.getSampleFrameId() && this.stencilAlt == altPressed
            && this.stencilReplay == this.controller.getReplay() && this.stencilTargetPick == this.targetRequest();
    }

    public boolean isViewportPickReady()
    {
        return this.hasDisplayedStencil(!this.isPickingTarget() && Window.isAltPressed());
    }

    void prepareViewportPick(UIContext context)
    {
        boolean altPressed = !this.isPickingTarget() && Window.isAltPressed();

        this.hoveredReplayIndex = -1;

        if (!this.hasDisplayedStencil(altPressed))
        {
            this.stencil.clearPicking();
            this.stencilFrame = -1L;
            this.controller.preview.requestRefresh();

            return;
        }

        this.readViewStencil(context);

        if (altPressed && this.stencil.hasPicked() && this.controller.panel.getData() != null)
        {
            int index = this.stencil.getIndex() - REPLAY_STENCIL_OFFSET;

            if (index >= 0 && index < this.controller.panel.getData().replays.getList().size() && index != this.controller.getCurrentReplayIndex())
            {
                this.hoveredReplayIndex = index;
            }
        }
    }

    void readViewStencil(UIContext context)
    {
        ViewportPickIntent.Input input = this.pendingViewportPick.input();
        int mouseX = input == null ? context.mouseX : input.x();
        int mouseY = input == null ? context.mouseY : input.y();

        if (this.stencil.getFramebuffer() != null)
        {
            Area viewport = this.controller.getViewArea();
            Texture texture = this.stencil.getFramebuffer().getMainTexture();
            int x = (int) ((mouseX - viewport.x) / (float) viewport.w * texture.width);
            int y = (int) ((1F - (mouseY - viewport.y) / (float) viewport.h) * texture.height);
            int radius = Math.round(BBSSettings.gizmoHoverTolerance.get() * texture.width / (float) viewport.w);
            int[] previousViewport = UIUtils.currentViewport();

            try
            {
                this.stencil.getFramebuffer().bind();
                this.stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);
            }
            finally
            {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                UIUtils.restoreViewport(previousViewport);
            }
        }
    }

    boolean renderStencil(IBbsWorldRenderContext renderContext, UIContext context, boolean altPressed)
    {
        BBSProfiler.begin(BBSProfiler.Timer.STENCIL_PASS);

        try
        {
            BBSProfiler.count(BBSProfiler.Section.STENCIL_PASS);

            Area viewport = this.controller.getViewArea();
            ViewportPickIntent.Input input = this.pendingViewportPick.input();
            int mouseX = input == null ? context.mouseX : input.x();
            int mouseY = input == null ? context.mouseY : input.y();

            if (!viewport.isInside(mouseX, mouseY) || this.controller.isControlling())
            {
                this.stencil.clearPicking();

                return false;
            }

            IEntity entity = this.controller.getCurrentEntity();
            TargetPick request = this.targetRequest();

            if (request == null && (entity == null || this.controller.pov == CAMERA_MODE_FIRST_PERSON) && !altPressed)
            {
                this.stencil.clearPicking();

                return false;
            }

            Replay selectedReplay = this.controller.panel.replayEditor.getReplay();

            if (request == null && !altPressed && selectedReplay == null)
            {
                this.stencil.clearPicking();

                return false;
            }

            this.ensureStencilFramebuffer();

            /* Match the visual gizmo's on-screen size compensation (see
             * Gizmo#setViewportScale) so the pick handles line up with what is drawn. */
            Gizmo.INSTANCE.setViewportScale(context.menu.height / (float) viewport.h);

            boolean isPlaying = this.controller.isPlaying();
            Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
            boolean applied = false;

            context.batcher.flush();

            int[] previousViewport = UIUtils.currentViewport();
            boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            int[] scissor = new int[4];

            if (scissorEnabled)
            {
                GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
            }

            try
            {
                this.stencilMap.setup();
                RenderSystem.disableScissor();
                this.stencil.apply();
                applied = true;

                Gizmo.INSTANCE.setViewport(viewport);

                if (request != null)
                {
                    this.stencilMap.setIncrement(true);

                    for (Replay replay : this.controller.panel.getData().replays.getList())
                    {
                        IEntity replayEntity = this.controller.getEntities().get(replay.getId());

                        if (replayEntity != null && !replay.getId().equals(request.excludedReplay()))
                        {
                            FilmEntityRenderer.renderEntity(FilmControllerContext.instance
                                .setup(this.controller.getEntities(), replayEntity, replay, renderContext)
                                .transition(isPlaying ? renderContext.tickDelta() : 0)
                                .stencil(this.stencilMap)
                                .relative(replay.relative.get()));
                        }
                    }
                }
                else if (altPressed)
                {
                    List<Replay> replays = this.controller.panel.getData().replays.getList();
                    int selectedReplayIndex = this.controller.getCurrentReplayIndex();
                    Pair<String, Boolean> bone = this.controller.getBone();

                    for (int i = 0; i < replays.size(); i++)
                    {
                        Replay replay = replays.get(i);
                        IEntity replayEntity = this.controller.getEntities().get(replay.getId());

                        if (replayEntity == null)
                        {
                            continue;
                        }

                        FilmControllerContext filmContext = FilmControllerContext.instance
                            .setup(this.controller.getEntities(), replayEntity, replay, renderContext)
                            .transition(isPlaying ? renderContext.tickDelta() : 0)
                            .stencil(this.stencilMap)
                            .relative(replay.relative.get());

                        if (i == selectedReplayIndex)
                        {
                            this.stencilMap.objectIndex = replays.size() + REPLAY_STENCIL_OFFSET;
                            this.stencilMap.setIncrement(true);

                            filmContext
                                .bone(bone == null ? null : bone.a, bone != null && bone.b)
                                .gizmoSpace(this.controller.getBoneSpace(), this.controller.getGizmoView())
                                .anchorGizmo(this.controller.isAnchorGizmo(), this.controller.getAnchorLocal())
                                .gizmoTarget(this.controller.getEditTarget());
                        }
                        else
                        {
                            /* The stencil object index IS the replay's position in the list — never its
                             * stable id. The pick pass hands that number back to the replay list. */
                            this.stencilMap.objectIndex = i + REPLAY_STENCIL_OFFSET;
                            this.stencilMap.setIncrement(false);
                        }

                        FilmEntityRenderer.renderEntity(filmContext);
                    }
                }
                else
                {
                    Pair<String, Boolean> bone = this.controller.getBone();

                    this.stencilMap.setIncrement(true);

                    FilmEntityRenderer.renderEntity(FilmControllerContext.instance
                        .setup(this.controller.getEntities(), entity, selectedReplay, renderContext)
                        .transition(isPlaying ? renderContext.tickDelta() : 0)
                        .stencil(this.stencilMap)
                        .relative(selectedReplay.relative.get())
                        .bone(bone == null ? null : bone.a, bone != null && bone.b)
                        .gizmoSpace(this.controller.getBoneSpace(), this.controller.getGizmoView())
                        .anchorGizmo(this.controller.isAnchorGizmo(), this.controller.getAnchorLocal())
                        .gizmoTarget(this.controller.getEditTarget()));
                }

                int x = (int) ((mouseX - viewport.x) / (float) viewport.w * mainTexture.width);
                int y = (int) ((1F - (mouseY - viewport.y) / (float) viewport.h) * mainTexture.height);
                int radius = Math.round(BBSSettings.gizmoHoverTolerance.get() * mainTexture.width / (float) viewport.w);

                this.stencil.pick(x, y, radius, Gizmo.STENCIL_MAX);

                return true;
            }
            finally
            {
                /* Drain any vertices the form renderers left in the shared buffer source
                 * while the stencil FBO is still bound, so they can't leak into a later
                 * flush on the main render target. */
                try
                {
                    renderContext.consumers().endBatch();
                }
                catch (Exception e)
                {
                    LOGGER.warn("renderStencil: endBatch during cleanup failed", e);
                }

                if (applied)
                {
                    this.stencil.unbind(this.stencilMap);
                }

                /* Each Film view owns its viewport independently of the output target. */
                Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                UIUtils.restoreViewport(previousViewport);

                /* Defensive restore of every global write/test state a form renderer or
                 * vanilla RenderType could have left behind inside the picking pass.
                 * UI rendering below assumes all of these are at their defaults; a stale
                 * colorMask/scissor/shaderColor here blacks out whole UI batches and
                 * survives into other screens (observed: leftovers on the title screen). */
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.depthMask(true);
                if (scissorEnabled)
                {
                    RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
                }
                else
                {
                    RenderSystem.disableScissor();
                }
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            }
        }
        finally
        {
            BBSProfiler.end(BBSProfiler.Timer.STENCIL_PASS);
        }
    }

    void ensureStencilFramebuffer()
    {
        this.stencil.setup(Link.bbs("stencil_film_" + (this.controller.preview == null ? "preview" : this.controller.preview.getViewDescriptor().getId())));

        Texture mainTexture = this.stencil.getFramebuffer().getMainTexture();
        ViewRenderState state = this.controller.preview == null ? null : BBSRendering.getViewRenderState(this.controller.preview.getViewDescriptor().getId());
        int w = state == null ? BBSRendering.getVideoWidth() : state.getWidth();
        int h = state == null ? BBSRendering.getVideoHeight() : state.getHeight();

        if (mainTexture.width != w || mainTexture.height != h)
        {
            this.stencil.resize(w, h);
        }
    }
}
