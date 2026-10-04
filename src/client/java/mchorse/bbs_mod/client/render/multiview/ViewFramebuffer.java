package mchorse.bbs_mod.client.render.multiview;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.graphics.texture.Texture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/** A local preview target and any vanilla post-processing targets which refer to it. */
public final class ViewFramebuffer implements AutoCloseable
{
    private final RenderTarget target;
    private final Texture texture;
    private PostChain transparency;
    private PostChain outline;
    private boolean closed;

    public static ViewFramebuffer create(int width, int height)
    {
        RenderTarget target = new PreviewTarget();

        try
        {
            target.resize(width, height, Minecraft.ON_OSX);
            return new ViewFramebuffer(target);
        }
        catch (RuntimeException | Error failure)
        {
            try
            {
                target.destroyBuffers();
            }
            catch (RuntimeException | Error cleanup)
            {
                failure.addSuppressed(cleanup);
            }

            throw failure;
        }
    }

    private ViewFramebuffer(RenderTarget target)
    {
        this.target = target;
        this.texture = new AttachmentTexture(target);
    }

    public RenderTarget target()
    {
        return this.target;
    }

    public Texture texture()
    {
        return this.texture;
    }

    /** Finish an opaque world image inside its owning render-state scope. */
    public void finishForPresentation()
    {
        this.target.bindWrite(false);
        RenderSystem.disableScissor();
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.clearColor(0F, 0F, 0F, 1F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        /* The scope restores the mask, clear color, scissor and framebuffer.
         * Preserve world/post alpha while drawing; only its final GUI image is opaque. */
    }

    public PostChain transparency(boolean needed) throws IOException
    {
        if (needed && this.transparency == null)
        {
            this.transparency = this.createChain("transparency");
        }

        return needed ? this.transparency : null;
    }

    public PostChain outline(boolean needed) throws IOException
    {
        if (needed && this.outline == null)
        {
            this.outline = this.createChain("entity_outline");
        }

        return needed ? this.outline : null;
    }

    private PostChain createChain(String name) throws IOException
    {
        Minecraft minecraft = Minecraft.getInstance();
        PostChain chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(), this.target,
            ResourceLocation.withDefaultNamespace("shaders/post/" + name + ".json"));

        try
        {
            chain.resize(this.target.viewWidth, this.target.viewHeight);
            return chain;
        }
        catch (RuntimeException | Error failure)
        {
            chain.close();
            throw failure;
        }
    }

    @Override
    public void close()
    {
        if (this.closed)
        {
            return;
        }

        this.closed = true;
        PostChain retiredTransparency = this.transparency;
        PostChain retiredOutline = this.outline;
        this.transparency = null;
        this.outline = null;

        try (RenderStateRestorer releases = new RenderStateRestorer())
        {
            releases.add(this.target::destroyBuffers);

            if (retiredOutline != null)
            {
                releases.add(retiredOutline::close);
            }

            if (retiredTransparency != null)
            {
                releases.add(retiredTransparency::close);
            }
        }
        finally
        {
            this.texture.id = -1;
        }
    }

    private static final class PreviewTarget extends RenderTarget
    {
        private PreviewTarget()
        {
            super(true);
        }
    }

    /** Texture is a borrowed attachment; the RenderTarget owns its GL lifetime. */
    private static final class AttachmentTexture extends Texture
    {
        private AttachmentTexture(RenderTarget target)
        {
            super();
            super.delete();
            this.id = target.getColorTextureId();
            this.width = target.viewWidth;
            this.height = target.viewHeight;
            this.bind();
            this.setFilter(GL11.GL_LINEAR);
        }

        @Override
        public void delete()
        {}
    }
}
