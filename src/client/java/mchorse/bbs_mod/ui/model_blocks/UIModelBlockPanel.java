package mchorse.bbs_mod.ui.model_blocks;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.blocks.entities.ModelProperties;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.IFlightSupported;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.forms.UINestedEdit;
import mchorse.bbs_mod.ui.forms.UIToggleEditorEvent;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.events.UIRemovedEvent;
import mchorse.bbs_mod.ui.framework.elements.input.UIPropTransform;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.ui.model_blocks.camera.ImmersiveModelBlockCameraController;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.GizmoInteraction;
import mchorse.bbs_mod.ui.utils.GizmoViewport;

import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.AABB;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.RayTracing;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.pose.Transform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UIModelBlockPanel extends UIDashboardPanel implements IFlightSupported, GizmoViewport
{
    public static boolean toggleRendering;

    public UIScrollView scrollView;
    public UIElement editor;
    public UIModelBlockEntityList modelBlocks;
    public UINestedEdit pickEdit;
    public UIToggle enabled;
    public UIToggle shadow;
    public UIToggle global;
    public UIToggle lookAt;
    public UIPropTransform transform;

    private final StencilFormFramebuffer gizmoStencil = new StencilFormFramebuffer();
    private final StencilMap gizmoStencilMap = new StencilMap();
    private final GizmoInteraction gizmo = new GizmoInteraction(this);
    private final mchorse.bbs_mod.camera.Camera gizmoCamera = new mchorse.bbs_mod.camera.Camera();
    private final Matrix4f gizmoProjection = new Matrix4f();

    private ModelBlockEntity modelBlock;
    private ModelBlockEntity hovered;
    private Vector3f mouseDirection = new Vector3f();

    private Set<ModelBlockEntity> toSave = new HashSet<>();

    private ImmersiveModelBlockCameraController cameraController;
    private UIElement keyDude;

    public UIModelBlockPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.keyDude = new UIElement().noCulling();
        this.keyDude.keys().register(Keys.MODEL_BLOCKS_MOVE_TO, () ->
        {
            Minecraft mc = Minecraft.getInstance();
            Camera camera = mc.gameRenderer.getMainCamera();
            BlockHitResult blockHitResult = RayTracing.rayTrace(mc.level, camera.getPosition(), RayTracing.fromVector3f(this.mouseDirection), 512F);

            if (blockHitResult.getType() != HitResult.Type.MISS)
            {
                Vec3 hit = blockHitResult.getLocation();
                BlockPos pos = this.modelBlock.getBlockPos();

                this.modelBlock.getProperties().getTransform().translate.set(hit.x - pos.getX() - 0.5F, hit.y - pos.getY(), hit.z - pos.getZ() - 0.5F);
                this.fillData();
            }
        }).active(() -> this.modelBlock != null);

        this.modelBlocks = new UIModelBlockEntityList((l) -> this.fill(l.get(0), false));
        this.modelBlocks.context((menu) ->
        {
            if (this.modelBlock != null) menu.action(UIKeys.MODEL_BLOCKS_KEYS_TELEPORT, this::teleport);
        });
        this.modelBlocks.background();
        this.modelBlocks.h(UIStringList.DEFAULT_HEIGHT * 9);

        this.pickEdit = new UINestedEdit((editing) ->
        {
            UIFormPalette palette = UIFormPalette.open(this, editing, this.modelBlock.getProperties().getForm(), (f) ->
            {
                this.pickEdit.setForm(f);
                this.modelBlock.getProperties().setForm(f);
            });

            palette.immersive();
            palette.editor.keys().register(Keys.MODEL_BLOCKS_TOGGLE_RENDERING, () -> toggleRendering = !toggleRendering);
            palette.editor.renderer.full(dashboard.getRoot());
            palette.editor.renderer.setTarget(this.modelBlock.getEntity());
            palette.editor.renderer.setRenderForm(() -> !toggleRendering);
            palette.getEvents().register(UIToggleEditorEvent.class, (e) ->
            {
                if (e.editing)
                {
                    this.addCameraController(palette);
                }
                else
                {
                    this.removeCameraController();
                }
            });
            palette.getEvents().register(UIRemovedEvent.class, (e) ->
            {
                this.scrollView.setVisible(true);
            });

            palette.resize();

            if (editing)
            {
                this.addCameraController(palette);
            }

            this.scrollView.setVisible(false);
        });
        this.pickEdit.keybinds();

        this.enabled = new UIToggle(UIKeys.CAMERA_PANELS_ENABLED, (b) -> this.modelBlock.getProperties().setEnabled(b.getValue()));
        this.shadow = new UIToggle(UIKeys.MODEL_BLOCKS_SHADOW, (b) -> this.modelBlock.getProperties().setShadow(b.getValue()));
        this.global = new UIToggle(UIKeys.MODEL_BLOCKS_GLOBAL, (b) ->
        {
            this.modelBlock.getProperties().setGlobal(b.getValue());
            Minecraft.getInstance().levelRenderer.allChanged();
        });
        this.lookAt = new UIToggle(UIKeys.CAMERA_PANELS_LOOK_AT, (b) -> this.modelBlock.getProperties().setLookAt(b.getValue()));

        this.transform = new UIPropTransform();
        this.transform.enableHotkeys();
        this.transform.hotkeyDrag(this::buildGizmoDrag);

        this.editor = UI.column(this.pickEdit, this.enabled, this.shadow, this.global, this.lookAt, this.transform);

        this.scrollView = UI.scrollView(UIConstants.MARGIN, UIConstants.SCROLL_PADDING, this.modelBlocks, this.editor);
        this.scrollView.scroll.opposite().cancelScrolling();
        this.scrollView.relative(this).w(200).h(1F);

        this.fill(null, false);

        this.keys().register(Keys.MODEL_BLOCKS_TELEPORT, this::teleport);

        this.add(this.scrollView);
    }

    private void teleport()
    {
        if (this.modelBlock != null)
        {
            BlockPos pos = this.modelBlock.getBlockPos();

            PlayerUtils.teleport(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            UIUtils.playClick();
        }
    }

    @Override
    public boolean supportsRollFOVControl()
    {
        return false;
    }

    @Override
    public void appear()
    {
        super.appear();

        this.getContext().menu.main.add(this.keyDude);
        this.dashboard.orbitKeysUI.setEnabled(() -> this.getChildren(UIFormPalette.class).isEmpty());

        if (this.cameraController != null)
        {
            BBSModClient.getCameraController().add(this.cameraController);
        }
    }

    @Override
    public void disappear()
    {
        super.disappear();

        this.keyDude.removeFromParent();
        this.dashboard.orbitKeysUI.setEnabled(null);
        this.gizmo.stop();

        if (this.cameraController != null)
        {
            BBSModClient.getCameraController().remove(this.cameraController);
        }
    }

    public ModelBlockEntity getModelBlock()
    {
        return this.modelBlock;
    }

    @Override
    public StencilFormFramebuffer getGizmoStencil()
    {
        return this.gizmoStencil;
    }

    @Override
    public Matrix4f getGizmoProjection()
    {
        return this.gizmoProjection;
    }

    /**
     * The screen region the gizmo actually renders into: the full UI viewport, NOT this panel's own
     * area. The dashboard shrinks a panel by the 20px taskbar ({@code h(1F, -20)}), but the block
     * gizmo is drawn straight onto Minecraft's full-screen world — so its on-screen projection,
     * trackball sphere highlight and sphere pick must map against the whole screen. Using the shorter
     * panel area squishes/shifts them by that 20px (a constant, camera-independent offset).
     */
    @Override
    public Area getGizmoArea()
    {
        UIContext context = this.getContext();

        return context != null ? context.menu.viewport : this.area;
    }

    @Override
    public boolean startGizmo(UIContext context, int stencilIndex)
    {
        if (this.modelBlock == null)
        {
            return false;
        }

        return Gizmo.INSTANCE.start(stencilIndex, context.mouseX, context.mouseY, this.transform, this.buildGizmoDrag());
    }

    @Override
    public void pickGizmoForm(UIContext context, Form form, String bone)
    {}

    private GizmoDrag buildGizmoDrag()
    {
        if (this.modelBlock == null)
        {
            return null;
        }

        BlockPos pos = this.modelBlock.getBlockPos();
        Transform transform = this.modelBlock.getProperties().getTransform();
        GizmoDrag drag = new GizmoDrag().setup(
            this.gizmoCamera,
            this.getGizmoArea(),
            pos.getX() + 0.5D + transform.translate.x,
            pos.getY() + transform.translate.y,
            pos.getZ() + 0.5D + transform.translate.z
        );

        Matrix3f axes = new Matrix3f();

        if (this.transform.isLocal())
        {
            axes.set(transform.createRotationMatrix());
        }

        drag.gizmoWorldAxes.set(axes);
        drag.setJacobian(GizmoDrag.computeTranslateJacobian(
            transform,
            () -> new Vector3f(
                pos.getX() + 0.5F + transform.translate.x,
                pos.getY() + transform.translate.y,
                pos.getZ() + 0.5F + transform.translate.z
            )
        ));
        drag.setRotateAxes(GizmoDrag.computeRotateAxes(
            transform,
            () -> MatrixStackUtils.stripScale(new Matrix4f(transform.createMatrix()))
        ));

        return drag;
    }

    public boolean isShowingGizmo(ModelBlockEntity entity)
    {
        return this.modelBlock == entity && this.canShowGizmo();
    }

    private boolean canShowGizmo()
    {
        return this.modelBlock != null
            && BBSSettings.gizmos.get()
            && !UIBaseMenu.isHideGizmoHeld()
            && this.getChildren(UIFormPalette.class).isEmpty();
    }

    private void renderGizmo(IBbsWorldRenderContext context, Vec3 cameraPos)
    {
        if (!this.canShowGizmo())
        {
            return;
        }

        PoseStack stack = context.matrixStack();

        /* Capture the on-screen camera frame for the drag math and the deferred UI
         * passes: the gizmo's visual and its pick stencil are both drawn later in
         * the UI pass (GizmoInteraction.renderGizmo / renderGizmoStencilInterface)
         * from this frame, so rendering, picking and dragging share one coordinate
         * frame. */
        this.gizmoProjection.set(context.projectionMatrix());
        this.gizmoCamera.projection.set(this.gizmoProjection);
        this.gizmoCamera.view.set(context.modelViewMatrix());
        this.gizmoCamera.position.set(cameraPos.x, cameraPos.y, cameraPos.z);

        /* Record the model-view at the block's gizmo origin so the UI-pass visual
         * and stencil (both read Gizmo#lastRenderMatrix) draw at the right place. */
        stack.pushPose();

        try
        {
            this.applyGizmoOrigin(stack, cameraPos);
            Gizmo.INSTANCE.setViewport(this.getGizmoArea());
            Gizmo.INSTANCE.captureVisual(stack);
        }
        finally
        {
            stack.popPose();
        }
    }

    private void applyGizmoOrigin(PoseStack stack, Vec3 cameraPos)
    {
        BlockPos pos = this.modelBlock.getBlockPos();
        Transform transform = this.modelBlock.getProperties().getTransform();

        stack.translate(
            pos.getX() + 0.5D + transform.translate.x - cameraPos.x,
            pos.getY() + transform.translate.y - cameraPos.y,
            pos.getZ() + 0.5D + transform.translate.z - cameraPos.z
        );

        if (this.transform.isLocal())
        {
            MatrixStackUtils.multiply(stack, new Matrix4f().set(transform.createRotationMatrix()));
        }
    }

    /**
     * Render the gizmo handles into the picking framebuffer in the UI pass (the
     * new way) and read the handle under the cursor. Uses the same area /
     * projection / captured matrix as the visual ({@link Gizmo#renderInterface}),
     * so the picked pixel matches the drawn handle. The main framebuffer is handed
     * back afterwards so the rest of the UI keeps rendering normally.
     */
    private void renderGizmoStencilInterface(UIContext context)
    {
        if (!this.canShowGizmo())
        {
            this.gizmoStencil.clearPicking();

            return;
        }

        Minecraft mc = Minecraft.getInstance();

        this.gizmoStencil.setup(Link.bbs("stencil_model_block"));

        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        Texture texture = this.gizmoStencil.getFramebuffer().getMainTexture();

        if (texture.width != w || texture.height != h)
        {
            this.gizmoStencil.resize(w, h);
        }

        boolean applied = false;

        try
        {
            this.gizmoStencilMap.setup();

            /* Flush queued UI before binding the pick buffer, so pending batches go to
             * the screen and not into the stencil. */
            context.batcher.flush();
            this.gizmoStencil.apply();
            applied = true;

            Gizmo.INSTANCE.renderStencilInterface(context, this.gizmoProjection, this.getGizmoArea(), this.gizmoStencilMap);

            int mouseX = scaleCoordinate(context.mouseX, context.menu.width, w);
            int mouseY = scaleCoordinate(context.mouseY, context.menu.height, h);

            this.gizmoStencil.pick(mouseX, h - mouseY, Math.round(BBSSettings.gizmoHoverTolerance.get() * BBSModClient.getGUIScale()), Gizmo.STENCIL_MAX);
        }
        finally
        {
            if (applied)
            {
                this.gizmoStencil.unbind(this.gizmoStencilMap);
            }

            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    private void addCameraController(UIFormPalette palette)
    {
        if (this.cameraController == null)
        {
            this.cameraController = new ImmersiveModelBlockCameraController(palette.editor.renderer, this.modelBlock);

            BBSModClient.getCameraController().add(this.cameraController);

            Transform transform = this.modelBlock.getProperties().getTransform().copy();

            transform.translate.set(0F, 0F, 0F);
            palette.editor.renderer.setTransform(new Matrix4f(transform.createMatrix()));
        }
    }

    private void removeCameraController()
    {
        if (this.cameraController != null)
        {
            BBSModClient.getCameraController().remove(this.cameraController);

            this.cameraController = null;
        }
    }

    @Override
    public boolean needsBackground()
    {
        return false;
    }

    @Override
    public boolean canPause()
    {
        return false;
    }

    @Override
    public void open()
    {
        super.open();

        this.updateList();

        if (this.modelBlock != null && this.modelBlock.isRemoved())
        {
            this.fill(null, true);
        }
    }

    @Override
    public void close()
    {
        super.close();

        this.gizmo.stop();
        this.removeCameraController();

        for (ModelBlockEntity entity : this.toSave)
        {
            this.save(entity);
        }

        this.toSave.clear();
    }

    private void updateList()
    {
        this.modelBlocks.clear();

        for (ModelBlockEntity modelBlock : BBSRendering.capturedModelBlocks)
        {
            this.modelBlocks.add(modelBlock);
        }

        this.fill(this.modelBlock, true);
    }

    public void fill(ModelBlockEntity modelBlock, boolean select)
    {
        if (modelBlock != null)
        {
            this.toSave.add(modelBlock);
        }

        this.modelBlock = modelBlock;

        if (modelBlock != null)
        {
            this.fillData();
        }

        this.editor.setVisible(modelBlock != null);

        if (select)
        {
            this.modelBlocks.setCurrentScroll(modelBlock);
        }
    }

    private void fillData()
    {
        ModelProperties properties = this.modelBlock.getProperties();

        this.pickEdit.setForm(properties.getForm());
        this.transform.setTransform(properties.getTransform());
        this.enabled.setValue(properties.isEnabled());
        this.shadow.setValue(properties.isShadow());
        this.global.setValue(properties.isGlobal());
        this.lookAt.setValue(properties.isLookAt());
    }

    private void save(ModelBlockEntity modelBlock)
    {
        if (modelBlock != null)
        {
            ClientNetwork.sendModelBlockForm(modelBlock.getBlockPos(), modelBlock);
        }
    }

    @Override
    protected boolean subMouseClicked(UIContext context)
    {
        if (super.subMouseClicked(context))
        {
            return true;
        }

        /* Gizmo handles first. The trackball sphere is otherwise deferred so its screen
         * disc doesn't block a click on the model block under it - but while the sphere is hovered the
         * block fill is skipped, so the rotate disc can actually be grabbed (the gizmo sits on the
         * selected block, so this.hovered is almost always non-null and would steal the click). */
        if (this.canShowGizmo() && this.gizmo.mouseClicked(context))
        {
            return true;
        }

        boolean sphereHovered = this.canShowGizmo() && this.gizmo.isSphereHovered();

        if (this.hovered != null && context.mouseButton == 0 && BBSSettings.clickModelBlocks.get() && !sphereHovered)
        {
            this.fill(this.hovered, true);
        }

        return false;
    }

    @Override
    protected boolean subMouseReleased(UIContext context)
    {
        boolean consumed = this.gizmo.mouseReleased(context);

        return super.subMouseReleased(context) || consumed;
    }

    @Override
    protected void subMouseCanceled(UIContext context)
    {
        this.gizmo.stop();
        super.subMouseCanceled(context);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        return super.subKeyPressed(context);
    }

    @Override
    public void render(UIContext context)
    {
        /* Pick first (UI pass): the stencil must be read before the visual's hover
         * (gizmo.update / renderGizmoHover both consume the picked index). */
        this.renderGizmoStencilInterface(context);

        if (this.canShowGizmo())
        {
            this.gizmo.renderGizmo(context);
            this.gizmo.update(context);
        }

        String label = UIKeys.FILM_CONTROLLER_SPEED.format(this.dashboard.orbit.speed.getValue()).get();
        FontRenderer font = context.batcher.getFont();
        int w = font.getWidth(label);
        int x = this.area.w - w - 5;
        int y = this.area.ey() - font.getHeight() - 5;

        context.batcher.textCard(label, x, y, Colors.WHITE, Colors.A50);
        super.render(context);

        this.renderGizmoHover(context);
        this.gizmo.renderSphereHighlight(context);
        this.gizmo.renderReadout(context);
    }

    private void renderGizmoHover(UIContext context)
    {
        if (!this.canShowGizmo() || !this.gizmoStencil.hasPicked())
        {
            return;
        }

        Texture texture = this.gizmoStencil.getFramebuffer().getMainTexture();
        int w = texture.width;
        int h = texture.height;

        ShaderInstance previewProgram = BBSShaders.getPickerPreviewProgram();
        Uniform target = previewProgram.getUniform("Target");

        if (target != null)
        {
            target.set(this.gizmoStencil.getIndex());
        }

        Uniform highlight = previewProgram.getUniform("HighlightColor");

        if (highlight != null)
        {
            int color = BBSSettings.stencilHighlightColor.get();

            highlight.set(Colors.getR(color), Colors.getG(color), Colors.getB(color), Colors.getA(color));
        }

        RenderSystem.enableBlend();
        context.batcher.texturedBox(BBSShaders::getPickerPreviewProgram, texture.id, Colors.WHITE, 0, 0, context.menu.width, context.menu.height, 0, h, w, 0, w, h);
    }

    @Override
    public void renderInWorld(IBbsWorldRenderContext context)
    {
        super.renderInWorld(context);

        Camera camera = context.camera();
        Vec3 pos = camera.getPosition();

        Minecraft mc = Minecraft.getInstance();
        int x = this.getOwnerFramebufferMouseX(mc);
        int y = this.getOwnerFramebufferMouseY(mc);

        this.mouseDirection.set(CameraUtils.getMouseDirection(
            context.projectionMatrix(),
            context.modelViewMatrix(),
            x, y, 0, 0, mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight()
        ));
        this.hovered = this.getClosestObject(new Vector3d(pos.x, pos.y, pos.z), this.mouseDirection);

        RenderSystem.enableDepthTest();

        for (ModelBlockEntity entity : this.modelBlocks.getList())
        {
            BlockPos blockPos = entity.getBlockPos();

            if (!this.isEditing(entity))
            {
                context.matrixStack().pushPose();
                context.matrixStack().translate(blockPos.getX() - pos.x, blockPos.getY() - pos.y, blockPos.getZ() - pos.z);

                if (this.hovered == entity || entity == this.modelBlock)
                {
                    Draw.renderBox(context.matrixStack(), 0D, 0D, 0D, 1D, 1D, 1D, 0, 0.5F, 1F);
                }
                else
                {
                    Draw.renderBox(context.matrixStack(), 0D, 0D, 0D, 1D, 1D, 1D);
                }

                context.matrixStack().popPose();
            }
        }

        RenderSystem.disableDepthTest();

        this.renderGizmo(context, pos);
    }

    private int getOwnerFramebufferMouseX(Minecraft mc)
    {
        return mc.screen instanceof UIScreen screen
            ? screen.getOwnerFramebufferMouseX()
            : (int) mc.mouseHandler.xpos();
    }

    private int getOwnerFramebufferMouseY(Minecraft mc)
    {
        return mc.screen instanceof UIScreen screen
            ? screen.getOwnerFramebufferMouseY()
            : (int) mc.mouseHandler.ypos();
    }

    private static int scaleCoordinate(int coordinate, int sourceSize, int targetSize)
    {
        return sourceSize <= 0 ? coordinate : (int) Math.round(coordinate * (double) targetSize / sourceSize);
    }

    private ModelBlockEntity getClosestObject(Vector3d finalPosition, Vector3f mouseDirection)
    {
        ModelBlockEntity closest = null;

        for (ModelBlockEntity object : this.modelBlocks.getList())
        {
            AABB aabb = this.getHitbox(object);

            if (aabb.intersectsRay(finalPosition, mouseDirection))
            {
                if (closest == null)
                {
                    closest = object;
                }
                else
                {
                    AABB aabb2 = this.getHitbox(closest);

                    if (finalPosition.distanceSquared(aabb.x, aabb.y, aabb.z) < finalPosition.distanceSquared(aabb2.x, aabb2.y, aabb2.z))
                    {
                        closest = object;
                    }
                }
            }
        }
        return closest;
    }

    private AABB getHitbox(ModelBlockEntity closest)
    {
        BlockPos pos = closest.getBlockPos();

        return new AABB(pos.getX(), pos.getY(), pos.getZ(), 1D, 1D, 1D);
    }

    public boolean isEditing(ModelBlockEntity entity)
    {
        if (this.modelBlock == entity)
        {
            List<UIFormPalette> children = this.getChildren(UIFormPalette.class);

            if (!children.isEmpty())
            {
                return children.get(0).editor.isEditing();
            }
        }

        return false;
    }
}
