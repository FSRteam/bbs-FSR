package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.ITickable;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.ParticleForm;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.level.Level;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.function.Supplier;

public class ParticleFormRenderer extends FormRenderer<ParticleForm> implements ITickable
{
    public static long lastUpdate = 0L;

    private ParticleEmitter emitter;
    private boolean checked;
    private boolean restart;
    private long lastParticleUpdate = lastUpdate;
    private final Matrix4f renderMatrix = new Matrix4f();
    private final Matrix4f cameraViewMatrix = new Matrix4f();
    private final Vector3d renderTranslation = new Vector3d();
    private final Vector3d uiEmitterPosition = new Vector3d();
    private final Matrix3f uiEmitterRotation = new Matrix3f();

    public ParticleFormRenderer(ParticleForm form)
    {
        super(form);
    }

    public ParticleEmitter getEmitter()
    {
        return this.emitter;
    }

    public void ensureEmitter(Level world, float transition)
    {
        if (this.lastParticleUpdate < lastUpdate)
        {
            this.lastParticleUpdate = lastUpdate;
            this.checked = false;
        }

        if (!this.checked)
        {
            ParticleScheme scheme = BBSModClient.getParticles().load(this.form.effect.get());

            if (scheme != null)
            {
                this.emitter = new ParticleEmitter();
                this.emitter.setScheme(scheme);
                this.emitter.setWorld(world);
            }

            this.checked = true;
        }

        if (this.emitter != null && !BBSRendering.isIrisShadowPass())
        {
            boolean lastPaused = this.emitter.paused;

            this.emitter.paused = this.form.paused.get();

            if (lastPaused != this.emitter.paused && !this.emitter.paused && this.emitter.age > 0 && !this.restart)
            {
                this.restart = true;
            }
        }
    }

    @Override
    public void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        this.ensureEmitter(Minecraft.getInstance().level, context.getTransition());

        ParticleEmitter emitter = this.emitter;

        if (emitter != null)
        {
            PoseStack stack = context.batcher.getContext().pose();
            int scale = (y2 - y1) / 2;

            stack.pushPose();
            stack.translate((x2 + x1) / 2, (y2 + y1) / 2, 40);
            MatrixStackUtils.scaleStack(stack, scale, scale, scale);

            this.updateTexture(context.getTransition());
            this.uiEmitterPosition.set(emitter.lastGlobal);
            this.uiEmitterRotation.set(emitter.rotation);

            try
            {
                emitter.lastGlobal.set(0, 0, 0);
                emitter.rotation.identity();
                emitter.renderUI(stack, context.getTransition());
            }
            finally
            {
                emitter.lastGlobal.set(this.uiEmitterPosition);
                emitter.rotation.set(this.uiEmitterRotation);
            }

            stack.popPose();
        }
    }

    @Override
    public void render3D(FormRenderingContext context)
    {
        this.ensureEmitter(Minecraft.getInstance().level, context.transition);

        ParticleEmitter emitter = this.emitter;

        if (emitter != null)
        {
            emitter.setUserVariables(
                this.form.user1.get(),
                this.form.user2.get(),
                this.form.user3.get(),
                this.form.user4.get(),
                this.form.user5.get(),
                this.form.user6.get()
            );

            this.updateTexture(context.getTransition());

            FormRenderSpace renderSpace = context.renderSpace == null
                ? FormRenderSpace.forType(context.type)
                : context.renderSpace;
            Matrix4f matrix = this.renderMatrix;

            if (renderSpace == FormRenderSpace.CAMERA_RELATIVE_WORLD)
            {
                matrix.set(context.stack.last().pose());
            }
            else if (renderSpace == FormRenderSpace.ENTITY_LOCAL && context.world != null)
            {
                matrix.set(context.world.last().pose());
            }
            else
            {
                matrix.set(context.camera.view).invert();
                matrix.mul(context.stack.last().pose());
            }

            Vector3f translation = matrix.getTranslation(Vectors.TEMP_3F);
            this.renderTranslation.set(translation.x, translation.y, translation.z);

            if (renderSpace != FormRenderSpace.ENTITY_LOCAL)
            {
                this.renderTranslation.add(context.camera.position.x, context.camera.position.y, context.camera.position.z);
            }

            GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;

            gameRenderer.lightTexture().turnOnLightLayer();
            gameRenderer.overlayTexture().setupOverlayColor();

            context.stack.pushPose();
            context.stack.setIdentity();

            if (renderSpace == FormRenderSpace.UI_LOCAL)
            {
                context.stack.mulPose(this.cameraViewMatrix.set(context.camera.view));
            }

            emitter.setRootPosition(this.renderTranslation);
            emitter.rotation.set(matrix);

            if (!BBSRendering.isIrisShadowPass())
            {
                boolean shadersEnabled = BBSRendering.isIrisShadersEnabled();

                VertexFormat format = shadersEnabled ? DefaultVertexFormat.NEW_ENTITY : DefaultVertexFormat.PARTICLE;
                Supplier<ShaderInstance> shader = shadersEnabled
                    ? this.getShader(context, GameRenderer::getRendertypeEntityTranslucentShader, BBSShaders::getPickerBillboardProgram)
                    : this.getShader(context, GameRenderer::getParticleShader, BBSShaders::getPickerParticlesProgram);

                emitter.setupCameraProperties(context.camera);
                emitter.render(format, shader, context.stack, context.overlay, context.getTransition());
            }

            context.stack.popPose();

            gameRenderer.lightTexture().turnOffLightLayer();
            gameRenderer.overlayTexture().teardownOverlayColor();
        }
    }

    private void updateTexture(float transition)
    {
        if (this.emitter != null)
        {
            this.emitter.texture = this.form.texture.get();
        }
    }

    @Override
    public void tick(IEntity entity)
    {
        this.ensureEmitter(entity.level(), 0F);

        if (this.emitter != null)
        {
            /* Rewind the emitter if it was paused and resumed in order to make
             * particle effects with once emitter */
            if (this.restart)
            {
                this.emitter.stop();
                this.emitter.start();

                this.restart = false;
            }

            this.emitter.update();
        }
    }
}
