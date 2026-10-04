package mchorse.bbs_mod.client.rendering.context;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

/**
 * Minimal world render context contract.
 */
public interface IBbsWorldRenderContext
{
    Camera camera();

    PoseStack matrixStack();

    MultiBufferSource.BufferSource consumers();

    float tickDelta();

    /**
     * The frustum of the pass being drawn right now, as it was handed to the render-stage event.
     *
     * <p>Every viewport does its own world render, and that render rebuilds the culling frustum
     * from its own camera and projection, so this belongs to this pass alone. It is therefore read
     * fresh per call and must never be cached across passes. Defaults to {@code null} — "no frustum
     * at hand", which callers must treat as "draw everything" — so that contexts built without one
     * (the addon bridge, tests) keep working.</p>
     */
    default Frustum frustum()
    {
        return null;
    }

    default Matrix4f modelViewMatrix()
    {
        return this.matrixStack().last().pose();
    }

    default Matrix4f projectionMatrix()
    {
        return RenderSystem.getProjectionMatrix();
    }
}
