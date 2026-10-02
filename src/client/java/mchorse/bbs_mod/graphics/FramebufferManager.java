package mchorse.bbs_mod.graphics;

import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class FramebufferManager
{
    public final Map<Link, Framebuffer> framebuffers = new HashMap<>();

    public Framebuffer getFramebuffer(Link key, Consumer<Framebuffer> setup)
    {
        Framebuffer framebuffer = this.framebuffers.get(key);

        if (framebuffer == null)
        {
            framebuffer = new Framebuffer();

            setup.accept(framebuffer);

            this.framebuffers.put(key, framebuffer);
        }

        return framebuffer;
    }

    /**
     * Same cache, but the caller also states the size it is about to render at. A cached
     * framebuffer is reused as-is and only re-sized when the request differs, which is what keeps
     * repeated renders of one form from allocating a target per frame. The size has to be
     * reconciled here rather than by the caller after {@link Framebuffer#apply()}: apply() sets
     * the GL viewport from the current texture size, so re-sizing behind its back would leave the
     * viewport and the attachment disagreeing.
     */
    public Framebuffer getFramebuffer(Link key, int width, int height, Consumer<Framebuffer> setup)
    {
        Framebuffer framebuffer = this.getFramebuffer(key, setup);

        if (width > 0 && height > 0)
        {
            Texture texture = framebuffer.getMainTexture();

            if (texture.width != width || texture.height != height)
            {
                framebuffer.resize(width, height);
            }
        }

        return framebuffer;
    }

    public void delete()
    {
        for (Framebuffer framebuffer : this.framebuffers.values())
        {
            framebuffer.delete();
        }

        this.framebuffers.clear();
    }
}