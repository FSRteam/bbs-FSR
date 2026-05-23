package mchorse.bbs_mod.client.rendering.context;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Immutable world render context implementation.
 */
public final class BbsWorldRenderContext implements IBbsWorldRenderContext
{
    private final Camera camera;
    private final PoseStack matrixStack;
    private final MultiBufferSource.BufferSource consumers;
    private final float tickDelta;
    private final Matrix4f modelViewMatrix;
    private final Matrix4f projectionMatrix;

    public BbsWorldRenderContext(
        Camera camera,
        PoseStack matrixStack,
        MultiBufferSource.BufferSource consumers,
        float tickDelta
    )
    {
        this(camera, matrixStack, consumers, tickDelta, matrixStack.last().pose(), RenderSystem.getProjectionMatrix());
    }

    public BbsWorldRenderContext(
        Camera camera,
        PoseStack matrixStack,
        MultiBufferSource.BufferSource consumers,
        float tickDelta,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix
    )
    {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.matrixStack = Objects.requireNonNull(matrixStack, "matrixStack");
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.tickDelta = tickDelta;
        this.modelViewMatrix = new Matrix4f(Objects.requireNonNull(modelViewMatrix, "modelViewMatrix"));
        this.projectionMatrix = new Matrix4f(Objects.requireNonNull(projectionMatrix, "projectionMatrix"));
    }

    @Override
    public Camera camera()
    {
        return this.camera;
    }

    @Override
    public PoseStack matrixStack()
    {
        return this.matrixStack;
    }

    @Override
    public MultiBufferSource.BufferSource consumers()
    {
        return this.consumers;
    }

    @Override
    public float tickDelta()
    {
        return this.tickDelta;
    }

    @Override
    public Matrix4f modelViewMatrix()
    {
        return this.modelViewMatrix;
    }

    @Override
    public Matrix4f projectionMatrix()
    {
        return this.projectionMatrix;
    }
}
