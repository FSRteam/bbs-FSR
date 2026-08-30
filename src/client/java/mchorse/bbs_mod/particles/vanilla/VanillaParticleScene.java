package mchorse.bbs_mod.particles.vanilla;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.mixin.client.CameraInvoker;
import mchorse.bbs_mod.mixin.client.ParticleEngineInvoker;
import mchorse.bbs_mod.utils.MathUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** A private vanilla particle engine for form-editor previews. */
public final class VanillaParticleScene
{
    private static final ParticleRenderType[] RENDER_ORDER = {
        ParticleRenderType.TERRAIN_SHEET,
        ParticleRenderType.PARTICLE_SHEET_OPAQUE,
        ParticleRenderType.PARTICLE_SHEET_LIT,
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
    };
    private static final int MAX_PARTICLES = 4096;
    private static final int ORIGIN_HEIGHT = 32;
    private static boolean rendering;

    private final List<Particle> particles = new ArrayList<>();
    private final net.minecraft.client.Camera camera = new net.minecraft.client.Camera();
    private final Vector3d origin = new Vector3d();

    private static Minecraft minecraft()
    {
        return Minecraft.getInstance();
    }

    public static boolean isRendering()
    {
        return rendering;
    }

    public void clear()
    {
        this.particles.clear();
    }

    public void spawn(ParticleOptions options, double x, double y, double z,
                      double velocityX, double velocityY, double velocityZ)
    {
        Minecraft mc = minecraft();
        ClientLevel level = mc.level;

        if (level == null || this.particles.size() >= MAX_PARTICLES)
        {
            return;
        }

        if (this.particles.isEmpty())
        {
            Entity anchor = mc.getCameraEntity();
            this.origin.set(anchor == null ? 0D : anchor.getX(), level.getMaxBuildHeight() + ORIGIN_HEIGHT,
                anchor == null ? 0D : anchor.getZ());
        }

        Particle particle = ((ParticleEngineInvoker) mc.particleEngine).bbs$createParticle(options,
            this.origin.x + x, this.origin.y + y, this.origin.z + z,
            velocityX, velocityY, velocityZ);

        if (particle != null)
        {
            this.particles.add(particle);
        }
    }

    public void tick()
    {
        Iterator<Particle> iterator = this.particles.iterator();

        while (iterator.hasNext())
        {
            Particle particle = iterator.next();

            try
            {
                particle.tick();
            }
            catch (RuntimeException ignored)
            {
                particle.remove();
            }

            if (!particle.isAlive())
            {
                iterator.remove();
            }
        }
    }

    public void render(Camera previewCamera, float transition)
    {
        if (this.particles.isEmpty())
        {
            return;
        }

        CameraInvoker standIn = (CameraInvoker) this.camera;
        standIn.bbs$setPosition(this.origin.x + previewCamera.position.x,
            this.origin.y + previewCamera.position.y, this.origin.z + previewCamera.position.z);
        standIn.bbs$setRotation(MathUtils.toDeg(previewCamera.rotation.y),
            -MathUtils.toDeg(previewCamera.rotation.x));

        Matrix4f previousModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        this.applyModelView(previewCamera.view);

        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        float[] shaderColor = RenderSystem.getShaderColor();
        float previousR = shaderColor[0];
        float previousG = shaderColor[1];
        float previousB = shaderColor[2];
        float previousA = shaderColor[3];

        /* Particle providers multiply their atlas sample by the global shader
         * colour. A Color Pose or a previous form may leave that multiplier
         * dark/transparent, making otherwise valid block and item particles
         * appear black or disappear. Preview particles own a neutral multiplier. */
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        Minecraft mc = minecraft();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.disableCull();
        rendering = true;

        try
        {
            TextureManager textures = minecraft().getTextureManager();
            Tesselator tessellator = Tesselator.getInstance();

            for (ParticleRenderType type : RENDER_ORDER)
            {
                BufferBuilder builder = type.begin(tessellator, textures);

                for (Particle particle : this.particles)
                {
                    if (particle.getRenderType() == type)
                    {
                        try
                        {
                            particle.render(builder, this.camera, transition);
                        }
                        catch (RuntimeException ignored)
                        {
                            particle.remove();
                        }
                    }
                }

                if (builder != null)
                {
                    MeshData mesh = builder.build();

                    if (mesh != null)
                    {
                        BufferUploader.drawWithShader(mesh);
                    }
                }
            }
        }
        finally
        {
            rendering = false;

            RenderSystem.setShaderColor(previousR, previousG, previousB, previousA);
            mc.gameRenderer.lightTexture().turnOffLightLayer();

            if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depthTestEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(depthMask);
            if (culling) RenderSystem.enableCull(); else RenderSystem.disableCull();

            this.applyModelView(previousModelView);
        }
    }

    private void applyModelView(Matrix4f matrix)
    {
        org.joml.Matrix4fStack stack = RenderSystem.getModelViewStack();

        stack.pushMatrix();
        stack.identity();
        stack.mul(matrix);
        RenderSystem.applyModelViewMatrix();
        stack.popMatrix();
    }
}
