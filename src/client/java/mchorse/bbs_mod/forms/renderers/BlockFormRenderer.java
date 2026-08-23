package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormTranslucentQueue;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.utils.FluidVertexConsumer;
import mchorse.bbs_mod.forms.renderers.utils.SingleBlockRenderView;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class BlockFormRenderer extends FormRenderer<BlockForm>
{
    public static final Color color = new Color();

    private final SingleBlockRenderView fluidView = new SingleBlockRenderView();

    private BlockEntity blockEntity;
    private BlockState blockEntityState;

    public BlockFormRenderer(BlockForm form)
    {
        super(form);
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        context.batcher.getContext().flush();

        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        PoseStack matrices = context.batcher.getContext().pose();

        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);

        matrices.pushPose();
        MatrixStackUtils.multiply(matrices, uiMatrix);
        matrices.scale(this.form.uiScale.get(), this.form.uiScale.get(), this.form.uiScale.get());
        matrices.translate(-0.5F, 0F, -0.5F);

        matrices.last().normal().getScale(Vectors.EMPTY_3F);
        matrices.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        Color set = this.form.color.get();

        consumers.setSubstitute(BBSRendering.getColorConsumer(set));
        consumers.setUI(true);
        this.renderBlock(matrices, consumers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
        consumers.draw();
        consumers.setUI(false);
        consumers.setSubstitute(null);

        matrices.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        int light = context.light;

        context.stack.pushPose();

        if (context.world != null)
        {
            context.world.pushPose();
        }

        try
        {
            context.stack.translate(-0.5F, 0F, -0.5F);

            if (context.world != null)
            {
                context.world.translate(-0.5F, 0F, -0.5F);
            }

            if (context.isPicking())
            {
                CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
                {
                    this.setupTarget(context, BBSShaders.getPickerModelsProgram());
                    RenderSystem.setShader(BBSShaders::getPickerModelsProgram);
                });

                light = 0;
            }
            else
            {
                CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());
            }

            Color set = this.form.color.get();

            color.set(context.color);
            color.mul(set);

            if (context.canDeferWorldTranslucency())
            {
                Vector3f origin = context.stack.last().pose().getTranslation(new Vector3f());
                FormTranslucentQueue.setSortOrigin(new Matrix4f(RenderSystem.getModelViewMatrix()).transformPosition(origin));
            }

            consumers.setSubstitute(BBSRendering.getColorConsumer(set));
            this.renderBlock(context.stack, consumers, light, context.overlay, context.isPicking());
            consumers.draw();

            if (!context.isPicking())
            {
                int finalLight = light;

                context.stack.pushPose();
                try
                {
                    MatrixStackUtils.applyTransform(context.stack, this.form.glintTransform.get());
                    FormGlintRenderer.renderCaptured(this.form, (capture) ->
                        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(this.form.blockState.get(), context.stack, capture, finalLight, context.overlay));
                }
                finally
                {
                    context.stack.popPose();
                }
            }
        }
        finally
        {
            consumers.setSubstitute(null);
            FormTranslucentQueue.setSortOrigin(null);
            CustomVertexConsumerProvider.clearRunnables();
            context.stack.popPose();

            if (context.world != null)
            {
                context.world.popPose();
            }

            RenderSystem.enableDepthTest();
        }
    }

    /**
     * Draw the block state the way the world would draw it.
     *
     * <p>Vanilla's renderSingleBlock() draws a baked block model and nothing else, so
     * everything the world puts on top of that model, or instead of it, was silently missing
     * here: water and lava, whose geometry the liquid renderer generates per chunk section;
     * signs, banners, skulls and the end portal, which render as
     * {@link RenderShape#INVISIBLE} and are drawn entirely by a block entity renderer;
     * the bell's body, the campfire's food, the lectern's book, which a block entity renderer
     * adds on top of the model; and marker blocks like the barrier, which only ever exist as
     * an item icon. Each of those gets its own path below.</p>
     */
    private void renderBlock(PoseStack matrices, MultiBufferSource consumers, int light, int overlay, boolean picking)
    {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = this.form.blockState.get();
        RenderShape type = state.getRenderShape();
        FluidState fluidState = state.getFluidState();

        /* Not only water and lava: this is also where a waterlogged block gets its water,
         * on top of its own model below. */
        if (!fluidState.isEmpty())
        {
            RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluidState);
            FluidVertexConsumer consumer = new FluidVertexConsumer(consumers.getBuffer(layer), matrices.last(), overlay);

            mc.getBlockRenderer().renderLiquid(BlockPos.ZERO, this.fluidView.set(state, light), consumer, state, fluidState);
        }

        if (type != RenderShape.INVISIBLE)
        {
            mc.getBlockRenderer().renderSingleBlock(state, matrices, consumers, light, overlay);
        }

        if (picking)
        {
            /* Picking stays out of the paths below on purpose: they draw through layers of
             * their own, and a sign's text or an end portal's sides are not even in the
             * entity vertex format the picking shader is compiled for. Such a form gets
             * selected from the outliner instead. */
            return;
        }

        /* An animated block entity block (chest, bed, shulker box) already went through the
         * built-in item renderer above, which runs its block entity renderer itself. */
        if (type != RenderShape.ENTITYBLOCK_ANIMATED && this.renderBlockEntity(mc, state, matrices, consumers, light, overlay))
        {
            return;
        }

        if (type == RenderShape.INVISIBLE && fluidState.isEmpty())
        {
            /* Barrier, light block, structure void: invisible in the world, but they do have
             * an icon, and a form of one should show something. The item model is centered on
             * the origin, while a block model spans 0..1, hence the half block nudge. */
            ItemStack stack = new ItemStack(state.getBlock());

            if (!stack.isEmpty())
            {
                matrices.pushPose();
                matrices.translate(0.5F, 0.5F, 0.5F);
                mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, light, overlay, matrices, consumers, mc.level, 0);
                matrices.popPose();
            }
        }
    }

    /**
     * Run the block state's block entity renderer, keeping the block entity itself around
     * between frames: it is an argument the renderer needs, not state of the form.
     *
     * @return whether there was a renderer to run
     */
    private boolean renderBlockEntity(Minecraft mc, BlockState state, PoseStack matrices, MultiBufferSource consumers, int light, int overlay)
    {
        if (!(state.getBlock() instanceof EntityBlock provider))
        {
            return false;
        }

        if (this.blockEntity == null || this.blockEntityState != state)
        {
            this.blockEntity = provider.newBlockEntity(BlockPos.ZERO, state);
            this.blockEntityState = state;
        }

        if (this.blockEntity == null)
        {
            return false;
        }

        if (this.blockEntity.getLevel() != mc.level)
        {
            /* Renderers of blocks that tick or move (the bell, the beacon) read the level off
             * the block entity, and the client's is the only one a form can offer. */
            this.blockEntity.setLevel(mc.level);
        }

        if (mc.getBlockEntityRenderDispatcher().getRenderer(this.blockEntity) == null)
        {
            return false;
        }

        /* 1.21.1 dispatcher computes the packed light itself from the entity's level position;
         * a detached form entity has none, so it renders at full brightness. */
        mc.getBlockEntityRenderDispatcher().render(this.blockEntity, 0F, matrices, consumers);

        return true;
    }
}
