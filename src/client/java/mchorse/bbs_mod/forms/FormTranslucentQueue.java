package mchorse.bbs_mod.forms;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.cubic.render.vao.IModelVAO;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.VertexBuffer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Frame-local deferred translucent form draws. Commands capture the active model-view matrix
 * and replay at the translucent world boundary in back-to-front order. UI, picking, Iris shadow
 * passes and nested framebuffer renders stay on the immediate path.
 */
public final class FormTranslucentQueue
{
    public static final int PASS_SINGLE = 0;
    public static final int PASS_OPAQUE = 1;
    public static final int PASS_TRANSLUCENT = 2;

    private static final List<DrawCommand> commands = new ArrayList<>();
    private static boolean active;
    private static int suspensionDepth;
    private static Vector3f sortOrigin;
    private static GroupCommand group;

    private FormTranslucentQueue() {}

    public static boolean isActive()
    {
        return active && suspensionDepth == 0 && !BBSRendering.isIrisShadowPass();
    }

    public static void begin()
    {
        release();
        active = true;
    }

    /** Start a scope lazily for NeoForge's AFTER_ENTITIES form callbacks. */
    public static void ensureStarted()
    {
        if (!active && suspensionDepth == 0)
        {
            begin();
        }
    }

    public static void end()
    {
        flush();
    }

    public static boolean suspend()
    {
        boolean previous = active;
        suspensionDepth++;
        active = false;
        return previous;
    }

    public static void restore(boolean previous)
    {
        if (suspensionDepth > 0) suspensionDepth--;
        if (suspensionDepth == 0) active = previous;
    }

    public static void setSortOrigin(Vector3f origin)
    {
        sortOrigin = origin;
    }

    public static Vector3f getSortOrigin()
    {
        return sortOrigin;
    }

    public static boolean isGroupOpen()
    {
        return group != null;
    }

    public static void beginGroup(Vector3f origin, boolean cull)
    {
        group = new GroupCommand(origin, cull);
        sortOrigin = new Vector3f(origin);
    }

    public static void endGroup()
    {
        GroupCommand finished = group;
        group = null;
        sortOrigin = null;

        if (finished != null && !finished.children.isEmpty())
        {
            add(finished);
        }
    }

    public static boolean needsSplit(ShaderInstance shader, Object stencilMap, Texture texture, float alpha)
    {
        boolean translucent = alpha < 1F || texture != null && texture.hasTranslucency();
        return translucent && isActive() && stencilMap == null && shader != null && shader.getUniform("PassMode") != null;
    }

    public static boolean needsWholeDefer(ShaderInstance shader, Object stencilMap, Texture texture, float alpha)
    {
        boolean translucent = alpha < 1F || texture != null && texture.hasTranslucency();
        return translucent && isActive() && stencilMap == null && shader != null
            && shader.getUniform("PassMode") == null && BBSRendering.isIrisShadersEnabled() && BBSRendering.isRenderingWorld();
    }

    public static void setPassMode(ShaderInstance shader, int mode)
    {
        if (shader != null && shader.getUniform("PassMode") != null)
        {
            shader.getUniform("PassMode").set(mode);
        }
    }

    public static void add(DrawCommand command)
    {
        if (group != null && command != group)
        {
            group.children.add(command);
        }
        else if (active)
        {
            commands.add(command);
        }
        else
        {
            command.draw();
            command.release();
        }
    }

    public static void flush()
    {
        active = false;
        commands.sort((a, b) -> Float.compare(b.distanceSq, a.distanceSq));
        List<DrawCommand> pending = new ArrayList<>(commands);
        commands.clear();
        int released = 0;

        try
        {
            if (!pending.isEmpty())
            {
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();

                for (DrawCommand command : pending)
                {
                    RenderSystem.depthMask(command.depthWrite);
                    if (command.cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
                    try
                    {
                        command.draw();
                    }
                    finally
                    {
                        released++;
                        command.release();
                    }
                }
            }
        }
        finally
        {
            for (int i = released; i < pending.size(); i++)
            {
                try { pending.get(i).release(); } catch (RuntimeException ignored) {}
            }
            if (group != null) group.release();
            group = null;
            sortOrigin = null;
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    private static void release()
    {
        for (DrawCommand command : commands) command.release();
        commands.clear();
        if (group != null) group.release();
        group = null;
        sortOrigin = null;
    }

    public abstract static class DrawCommand
    {
        final float distanceSq;
        final boolean cull;
        final boolean depthWrite;

        protected DrawCommand(Vector3f origin, boolean cull, boolean depthWrite)
        {
            this.distanceSq = origin.lengthSquared();
            this.cull = cull;
            this.depthWrite = depthWrite;
        }

        public abstract void draw();
        public void release() {}
    }

    public static final class ModelVAOCommand extends DrawCommand
    {
        private final IModelVAO vao;
        private final Supplier<ShaderInstance> shader;
        private final int passMode;
        private final Texture texture;
        private final Matrix4f modelView;
        private final Matrix3f normalMat;
        private final float r, g, b, a;
        private final int light, overlay;

        public ModelVAOCommand(IModelVAO vao, Texture texture, Matrix4f modelView, Matrix3f normalMat,
            float r, float g, float b, float a, int light, int overlay, boolean cull)
        {
            this(vao, BBSShaders::getModel, PASS_TRANSLUCENT, false, texture, modelView, normalMat,
                r, g, b, a, light, overlay, cull);
        }

        public ModelVAOCommand(IModelVAO vao, Supplier<ShaderInstance> shader, int passMode,
            boolean depthWrite, Texture texture, Matrix4f modelView, Matrix3f normalMat,
            float r, float g, float b, float a, int light, int overlay, boolean cull)
        {
            super(modelView.getTranslation(new Vector3f()), cull, depthWrite);
            this.vao = vao;
            this.shader = shader;
            this.passMode = passMode;
            this.texture = texture;
            this.modelView = modelView;
            this.normalMat = normalMat;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            this.light = light;
            this.overlay = overlay;
        }

        @Override
        public void draw()
        {
            ShaderInstance program = this.shader.get();
            if (program == null) return;
            if (this.texture != null) BBSModClient.getTextures().bindTexture(this.texture);
            setPassMode(program, this.passMode);
            ModelVAORenderer.render(program, this.vao, this.modelView, this.normalMat,
                this.r, this.g, this.b, this.a, this.light, this.overlay);
            setPassMode(program, PASS_SINGLE);
        }
    }

    public static final class BOBJCommand extends DrawCommand
    {
        private final BOBJModelVAO vao;
        private final Supplier<ShaderInstance> shader;
        private final int passMode;
        private final Matrix4f[] armatureSnapshot;
        private final int uploadCount;
        private final Texture texture;
        private final Matrix4f modelView;
        private final Matrix3f normalMat;
        private final float r, g, b, a;
        private final int light, overlay;

        public BOBJCommand(BOBJModelVAO vao, Matrix4f[] armatureSnapshot, int uploadCount,
            Texture texture, Matrix4f modelView, Matrix3f normalMat, float r, float g, float b,
            float a, int light, int overlay, boolean cull)
        {
            this(vao, BBSShaders::getModel, PASS_TRANSLUCENT, false, armatureSnapshot, uploadCount,
                texture, modelView, normalMat, r, g, b, a, light, overlay, cull);
        }

        public BOBJCommand(BOBJModelVAO vao, Supplier<ShaderInstance> shader, int passMode,
            boolean depthWrite, Matrix4f[] armatureSnapshot, int uploadCount, Texture texture,
            Matrix4f modelView, Matrix3f normalMat, float r, float g, float b, float a,
            int light, int overlay, boolean cull)
        {
            super(modelView.getTranslation(new Vector3f()), cull, depthWrite);
            this.vao = vao;
            this.shader = shader;
            this.passMode = passMode;
            this.armatureSnapshot = armatureSnapshot;
            this.uploadCount = uploadCount;
            this.texture = texture;
            this.modelView = modelView;
            this.normalMat = normalMat;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            this.light = light;
            this.overlay = overlay;
        }

        @Override
        public void draw()
        {
            ShaderInstance program = this.shader.get();
            if (program == null) return;
            if (this.texture != null) BBSModClient.getTextures().bindTexture(this.texture);
            if (this.vao.getUploadCount() != this.uploadCount)
            {
                this.vao.updateMesh(null, this.armatureSnapshot);
            }
            setPassMode(program, this.passMode);
            this.vao.render(program, this.modelView, this.normalMat, this.r, this.g, this.b,
                this.a, null, this.light, this.overlay);
            setPassMode(program, PASS_SINGLE);
        }
    }

    public static final class RenderLayerCommand extends DrawCommand
    {
        private final RenderType layer;
        private final VertexBuffer buffer;
        private final Matrix4f modelView;

        public RenderLayerCommand(RenderType layer, VertexBuffer buffer, Matrix4f modelView, Vector3f origin, boolean depthWrite)
        {
            super(origin, true, depthWrite);
            this.layer = layer;
            this.buffer = buffer;
            this.modelView = modelView;
        }

        @Override
        public void draw()
        {
            layer.setupRenderState();
            RenderSystem.depthMask(depthWrite);
            buffer.bind();
            buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
            VertexBuffer.unbind();
            layer.clearRenderState();
        }

        @Override
        public void release()
        {
            buffer.close();
        }
    }

    public static final class VertexBufferCommand extends DrawCommand
    {
        private final VertexBuffer buffer;
        private final Supplier<ShaderInstance> shader;
        private final Texture texture;
        private final Matrix4f modelView;
        private final Matrix3f normalMat;
        private final Runnable preDraw;
        private final Runnable postDraw;

        public VertexBufferCommand(VertexBuffer buffer, Supplier<ShaderInstance> shader,
            Texture texture, Matrix4f modelView, Matrix3f normalMat, Vector3f origin,
            boolean cull, Runnable preDraw, Runnable postDraw)
        {
            super(origin, cull, false);
            this.buffer = buffer;
            this.shader = shader;
            this.texture = texture;
            this.modelView = modelView;
            this.normalMat = normalMat;
            this.preDraw = preDraw;
            this.postDraw = postDraw;
        }

        @Override
        public void draw()
        {
            ShaderInstance program = shader.get();
            if (program == null) return;
            if (texture != null) BBSModClient.getTextures().bindTexture(texture);
            if (preDraw != null) preDraw.run();
            Uniform normalUniform = program.getUniform("NormalMat");
            if (normalUniform != null && this.normalMat != null) normalUniform.set(this.normalMat);
            setPassMode(program, PASS_TRANSLUCENT);
            buffer.bind();
            buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), program);
            VertexBuffer.unbind();
            setPassMode(program, PASS_SINGLE);
            if (postDraw != null) postDraw.run();
        }

        @Override
        public void release()
        {
            buffer.close();
        }
    }

    private static final class GroupCommand extends DrawCommand
    {
        private final List<DrawCommand> children = new ArrayList<>();
        GroupCommand(Vector3f origin, boolean cull) { super(origin, cull, false); }
        @Override public void draw() { for (DrawCommand child : children) child.draw(); }
        @Override public void release() { for (DrawCommand child : children) child.release(); }
    }
}
