package mchorse.bbs_mod.client.renderer;

import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.client.BBSRendering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame-local render-last queue for model blocks (the FSR redesign of Blockbuster's
 * {@code IRenderLast}/{@code RenderingHandler.renderLastEntities}).
 *
 * <p>During the vanilla block-entity pass, a model block flagged {@code render_last} renders
 * nothing and registers itself here — capturing the exact camera-relative PoseStack the
 * dispatcher had already translated to its block position. At NeoForge's
 * {@code AFTER_BLOCK_ENTITIES} stage — after every entity and block entity has drawn, before
 * the translucent terrain boundary — {@link #flush(MultiBufferSource)} replays the collected
 * blocks far-to-near through the captured stacks, so a model hidden behind a semi-transparent
 * model block still draws: the translucent block's pixels blend over it instead of
 * depth-rejecting it.</p>
 *
 * <p>Capturing the pose at enqueue time (rather than rebuilding it at flush time) reproduces
 * the exact transform the immediate path would have used: the AFTER_BLOCK_ENTITIES event's
 * PoseStack is camera-relative without per-block translations, and the vanilla dispatcher's
 * own translate-to-block prefix is what the captured stack already contains.</p>
 *
 * <p>Scoping mirrors {@link mchorse.bbs_mod.forms.FormTranslucentQueue}: only the world
 * block-entity pass defers. Iris shadow passes, picking, editor previews, the item/held-form
 * path and the secondary multiview pass render immediately ({@link #shouldDefer} false), and
 * entries never survive past the frame's flush.</p>
 */
public final class ModelBlockRenderLastQueue
{
    private static final List<Entry> entries = new ArrayList<>();
    private static boolean active;

    private ModelBlockRenderLastQueue() {}

    public static boolean isActive()
    {
        return active;
    }

    /** Open the frame scope before the world block-entity pass (at AFTER_ENTITIES). */
    public static void begin()
    {
        entries.clear();
        active = true;
    }

    /**
     * Whether a render-last model block should defer into the queue instead of drawing now.
     * Only inside the world block-entity pass — never during Iris shadow passes (the shadow map
     * needs the geometry in its own phase), nor in the detached item/held-form path
     * ({@code BlockEntityRenderDispatcher.renderItem} renders a model block as an item inside
     * the entity pass, before this scope opens). A BlockForm's model-block state takes that
     * same detached path, so it also never defers here.
     */
    public static boolean shouldDefer(ModelBlockEntity entity)
    {
        return active && !BBSRendering.isIrisShadowPass() && entity.getProperties().isRenderLast();
    }

    /**
     * Queue a block for the deferred replay. The passed {@code matrices} is the dispatcher's
     * current stack (already translated to the block's position, camera-relative) — the entry
     * snapshots it, so the replay draws through the exact transform the immediate path would
     * have used.
     */
    public static void add(ModelBlockEntity entity, float tickDelta, PoseStack matrices, int light, int overlay)
    {
        entries.add(new Entry(entity, tickDelta, matrices, light, overlay));
    }

    /**
     * Replay the deferred model blocks far-to-near through their captured pose stacks. Sorting
     * matches Blockbuster's {@code renderLastEntities}: farthest first, so nearer translucent
     * blocks blend over farther ones.
     */
    public static void flush(MultiBufferSource consumers)
    {
        active = false;

        if (entries.isEmpty())
        {
            return;
        }

        entries.sort((a, b) -> Float.compare(b.distanceSq, a.distanceSq));

        List<Entry> pending = new ArrayList<>(entries);

        entries.clear();

        for (Entry entry : pending)
        {
            entry.render(consumers);
        }
    }

    /** Drop any leftover entry without rendering (a frame whose flush point never ran). */
    public static void release()
    {
        active = false;
        entries.clear();
    }

    private static final class Entry
    {
        final ModelBlockEntity entity;
        final float tickDelta;
        final PoseStack pose = new PoseStack();
        final int light;
        final int overlay;
        final float distanceSq;

        Entry(ModelBlockEntity entity, float tickDelta, PoseStack matrices, int light, int overlay)
        {
            this.entity = entity;
            this.tickDelta = tickDelta;
            this.light = light;
            this.overlay = overlay;

            this.pose.last().pose().set(matrices.last().pose());
            this.pose.last().normal().set(matrices.last().normal());

            var cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            var blockPos = entity.getBlockPos();
            double dx = blockPos.getX() + 0.5 - cameraPos.x;
            double dy = blockPos.getY() - cameraPos.y;
            double dz = blockPos.getZ() + 0.5 - cameraPos.z;

            this.distanceSq = (float) (dx * dx + dy * dy + dz * dz);
        }

        void render(MultiBufferSource consumers)
        {
            if (this.entity.isRemoved())
            {
                return;
            }

            ModelBlockEntityRenderer.renderDeferred(this.entity, this.tickDelta, this.pose, consumers, this.light, this.overlay);
        }
    }
}
