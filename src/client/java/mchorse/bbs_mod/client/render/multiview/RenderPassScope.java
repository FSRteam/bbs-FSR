package mchorse.bbs_mod.client.render.multiview;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import mchorse.bbs_mod.graphics.InverseView;
import mchorse.bbs_mod.mixin.client.EntityRenderDispatcherAccessor;
import mchorse.bbs_mod.mixin.client.GameRendererCameraAccessor;
import mchorse.bbs_mod.mixin.client.LevelRendererAccessor;
import mchorse.bbs_mod.mixin.client.MinecraftAccessor;
import mchorse.bbs_mod.mixin.client.RenderSystemViewAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;

/** Exception-safe ownership of all state changed by an optional off-screen world pass. */
public final class RenderPassScope implements AutoCloseable
{
    private static RenderPassScope active;

    private final Minecraft minecraft;
    private final GameRendererCameraAccessor gameRenderer;
    private final LevelRendererAccessor levelRenderer;
    private final RenderStateRestorer restorations = new RenderStateRestorer();
    private final RenderGlState glState;
    private final WorldTargets previousWorldTargets;
    private boolean closed;

    public static RenderPassScope capture(Minecraft minecraft)
    {
        if (active != null)
        {
            throw new IllegalStateException("Nested off-screen render scopes are not supported");
        }

        if (minecraft == null
            || !(minecraft instanceof MinecraftAccessor)
            || !(minecraft.gameRenderer instanceof GameRendererCameraAccessor)
            || !(minecraft.getEntityRenderDispatcher() instanceof EntityRenderDispatcherAccessor)
            || !(minecraft.levelRenderer instanceof LevelRendererAccessor))
        {
            throw new IllegalStateException("World render state accessors are unavailable");
        }

        /* All snapshots and accessor checks precede the first render-state mutation. */
        RenderPassScope scope = new RenderPassScope(minecraft);
        active = scope;

        try
        {
            scope.glState.prepareAllocation();
            RenderSystemViewAccessor.bbs$setModelViewStack(new Matrix4fStack(16));
            RenderSystem.applyModelViewMatrix();
            return scope;
        }
        catch (RuntimeException | Error failure)
        {
            try
            {
                scope.close();
            }
            catch (RuntimeException | Error cleanup)
            {
                failure.addSuppressed(cleanup);
            }

            throw failure;
        }
    }

    public static RenderPassScope open(Minecraft minecraft, RenderTarget target, Camera camera)
    {
        validateCamera(target, camera);
        RenderPassScope scope = capture(minecraft);

        try
        {
            scope.use(target, camera, minecraft.smartCull);
            return scope;
        }
        catch (RuntimeException | Error failure)
        {
            try
            {
                scope.close();
            }
            catch (RuntimeException | Error cleanup)
            {
                failure.addSuppressed(cleanup);
            }

            throw failure;
        }
    }

    private RenderPassScope(Minecraft minecraft)
    {
        this.minecraft = minecraft;
        this.gameRenderer = (GameRendererCameraAccessor) minecraft.gameRenderer;
        this.levelRenderer = (LevelRendererAccessor) minecraft.levelRenderer;
        this.glState = new RenderGlState();
        this.previousWorldTargets = WorldTargets.capture(minecraft.levelRenderer, this.levelRenderer);

        RenderTarget previousTarget = minecraft.getMainRenderTarget();
        Camera previousCamera = this.gameRenderer.bbs$getMainCamera();
        EntityRenderDispatcherAccessor dispatcher = (EntityRenderDispatcherAccessor) minecraft.getEntityRenderDispatcher();
        Camera previousEntityCamera = dispatcher.bbs$getCamera();
        Quaternionf orientation = dispatcher.bbs$getCameraOrientation();
        Quaternionf previousOrientation = orientation == null ? null : new Quaternionf(orientation);
        Camera previousBlockCamera = minecraft.getBlockEntityRenderDispatcher().camera;
        Frustum previousFrustum = this.levelRenderer.bbs$getCullingFrustum();
        boolean smartCull = minecraft.smartCull;
        Matrix4fStack previousStack = RenderSystem.getModelViewStack();
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        Matrix3f inverseView = new Matrix3f(InverseView.get());
        Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        float[] fogColor = RenderSystem.getShaderFogColor().clone();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        FogShape fogShape = RenderSystem.getShaderFogShape();
        float lineWidth = RenderSystem.getShaderLineWidth();
        float glintAlpha = RenderSystem.getShaderGlintAlpha();
        Vector3f light0 = RenderSystem.shaderLightDirections[0] == null ? null : new Vector3f(RenderSystem.shaderLightDirections[0]);
        Vector3f light1 = RenderSystem.shaderLightDirections[1] == null ? null : new Vector3f(RenderSystem.shaderLightDirections[1]);

        this.restorations.add(this.glState::restore);
        this.restorations.add(() ->
        {
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.lineWidth(lineWidth);
            RenderSystem.setShaderGlintAlpha(glintAlpha);
            RenderSystem.setShaderLights(light0, light1);
        });
        this.restorations.add(() ->
        {
            RenderSystemViewAccessor.bbs$setModelViewStack(previousStack);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            InverseView.set(inverseView);
        });
        this.restorations.add(() ->
        {
            ((MinecraftAccessor) minecraft).bbs$setMainRenderTarget(previousTarget);
            previousTarget.bindWrite(false);
        });
        this.restorations.add(() ->
        {
            this.gameRenderer.bbs$setMainCamera(previousCamera);
            dispatcher.bbs$setCamera(previousEntityCamera);
            dispatcher.bbs$setCameraOrientation(previousOrientation);
            minecraft.getBlockEntityRenderDispatcher().camera = previousBlockCamera;
        });
        this.restorations.add(() ->
        {
            this.levelRenderer.bbs$setCullingFrustum(previousFrustum);
            minecraft.smartCull = smartCull;
            this.invalidateCulling();
        });
        this.restorations.add(() -> this.previousWorldTargets.restore(this.levelRenderer));
    }

    public void use(ViewFramebuffer framebuffer, Camera camera, boolean smartCull) throws IOException
    {
        this.use(framebuffer.target(), camera, smartCull);
        PostChain transparency = framebuffer.transparency(this.previousWorldTargets.transparency != null);
        PostChain outline = framebuffer.outline(this.previousWorldTargets.outline != null);
        WorldTargets.forChains(transparency, outline).restore(this.levelRenderer);
    }

    private void use(RenderTarget target, Camera camera, boolean smartCull)
    {
        validateCamera(target, camera);
        ((MinecraftAccessor) this.minecraft).bbs$setMainRenderTarget(target);
        this.gameRenderer.bbs$setMainCamera(camera);
        this.minecraft.getBlockEntityRenderDispatcher().camera = camera;
        this.minecraft.smartCull = smartCull;
        this.invalidateCulling();
        target.bindWrite(true);
        RenderSystem.viewport(0, 0, target.viewWidth, target.viewHeight);
    }

    private static void validateCamera(RenderTarget target, Camera camera)
    {
        if (target == null || camera == null || !camera.isInitialized() || camera.getEntity() == null)
        {
            throw new IllegalArgumentException("A world pass requires a target and a Camera bound to its world/entity");
        }
    }

    private void invalidateCulling()
    {
        /* Camera markers and visible lists cannot be restored separately. Force
         * the next vanilla setup to rebuild for its real camera; Sodium likewise
         * runs a full per-view setup and the primary pass is always last. */
        this.levelRenderer.bbs$setPrevCamX(Double.NaN);
        this.levelRenderer.bbs$setPrevCamY(Double.NaN);
        this.levelRenderer.bbs$setPrevCamZ(Double.NaN);
        this.levelRenderer.bbs$setPrevCamRotX(Double.NaN);
        this.levelRenderer.bbs$setPrevCamRotY(Double.NaN);
        this.levelRenderer.bbs$setLastCameraSectionX(Integer.MIN_VALUE);
        this.levelRenderer.bbs$setLastCameraSectionY(Integer.MIN_VALUE);
        this.levelRenderer.bbs$setLastCameraSectionZ(Integer.MIN_VALUE);
        this.minecraft.levelRenderer.needsUpdate();
    }

    public RenderTarget getTarget()
    {
        return this.minecraft.getMainRenderTarget();
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;

        try
        {
            this.restorations.close();
        }
        finally
        {
            active = null;
        }
    }

    private record WorldTargets(PostChain transparency, PostChain outline, RenderTarget entity,
                                RenderTarget translucent, RenderTarget itemEntity, RenderTarget particles,
                                RenderTarget weather, RenderTarget clouds)
    {
        private static WorldTargets capture(LevelRenderer renderer, LevelRendererAccessor accessor)
        {
            return new WorldTargets(accessor.bbs$getTransparencyChain(), accessor.bbs$getEntityEffect(),
                renderer.entityTarget(), renderer.getTranslucentTarget(), renderer.getItemEntityTarget(),
                renderer.getParticlesTarget(), renderer.getWeatherTarget(), renderer.getCloudsTarget());
        }

        private static WorldTargets forChains(PostChain transparency, PostChain outline)
        {
            return new WorldTargets(transparency, outline, target(outline, "final"),
                target(transparency, "translucent"), target(transparency, "itemEntity"),
                target(transparency, "particles"), target(transparency, "weather"), target(transparency, "clouds"));
        }

        private static RenderTarget target(PostChain chain, String name)
        {
            return chain == null ? null : chain.getTempTarget(name);
        }

        private void restore(LevelRendererAccessor accessor)
        {
            accessor.bbs$setTransparencyChain(this.transparency);
            accessor.bbs$setEntityEffect(this.outline);
            accessor.bbs$setEntityTarget(this.entity);
            accessor.bbs$setTranslucentTarget(this.translucent);
            accessor.bbs$setItemEntityTarget(this.itemEntity);
            accessor.bbs$setParticlesTarget(this.particles);
            accessor.bbs$setWeatherTarget(this.weather);
            accessor.bbs$setCloudsTarget(this.clouds);
        }
    }
}
