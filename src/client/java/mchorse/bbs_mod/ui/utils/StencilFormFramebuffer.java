package mchorse.bbs_mod.ui.utils;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.Framebuffer;
import mchorse.bbs_mod.graphics.Renderbuffer;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import mchorse.bbs_mod.utils.Pair;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

public class StencilFormFramebuffer
{
    private Framebuffer framebuffer;

    private int index;
    private Map<Integer, Pair<Form, String>> indexMap = new HashMap<>();
    private FloatBuffer pickBuffer;

    public Framebuffer getFramebuffer()
    {
        return this.framebuffer;
    }

    public int getIndex()
    {
        return this.index;
    }

    public Map<Integer, Pair<Form, String>> getIndexMap()
    {
        return this.indexMap;
    }

    public Pair<Form, String> getPicked()
    {
        return this.indexMap.get(this.index);
    }

    public void setup(Link id)
    {
        if (this.framebuffer != null)
        {
            return;
        }

        this.framebuffer = BBSModClient.getFramebuffers().getFramebuffer(id, (framebuffer) ->
        {
            Texture texture = new Texture();

            texture.setSize(2, 2);
            texture.setFilter(GL11.GL_NEAREST);
            texture.setWrap(GL13.GL_CLAMP_TO_EDGE);

            Renderbuffer renderbuffer = new Renderbuffer();

            renderbuffer.resize(2, 2);

            framebuffer.deleteTextures().attach(texture, GL30.GL_COLOR_ATTACHMENT0);
            framebuffer.attach(renderbuffer);
            framebuffer.unbind();
        });
    }

    public void resizeGUI(int w, int h)
    {
        this.resize(w, h, BBSModClient.getGUIScale());
    }

    public void resize(int w, int h, int scale)
    {
        this.resize(w * scale, h * scale);
    }

    public void resize(int w, int h)
    {
        if (this.framebuffer != null)
        {
            this.framebuffer.resize(w, h);
        }
    }

    public void apply()
    {
        this.framebuffer.applyClear();
    }

    public void pickGUI(UIContext context, Area area)
    {
        this.pickGUI(context.mouseX - area.x, area.h - context.mouseY + area.y);
    }

    public void pickGUI(UIContext context, Area area, int radius, int handleMax)
    {
        int scale = BBSModClient.getGUIScale();
        int x = (context.mouseX - area.x) * scale;
        int y = (area.h - context.mouseY + area.y) * scale;

        this.pick(x, y, radius * scale, handleMax);
    }

    public void pickGUI(int x, int y)
    {
        int scale = BBSModClient.getGUIScale();

        this.pick(x * scale, y * scale);
    }

    public void pick(int x, int y)
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            FloatBuffer floats = stack.mallocFloat(4);

            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, floats);

            /* Only RGB + alpha selection is implemented; extra channels (e.g., stencil-derived masks) fall through. */
            int r = (int) (floats.get() * 255F);
            int g = (int) (floats.get() * 255F);
            int b = (int) (floats.get() * 255F);
            int a = (int) (floats.get() * 255F);

            this.index = a < 1F ? 0 : r | (g << 8) | (b << 16);
        }
    }

    /**
     * Resolve the nearest gizmo handle in a framebuffer-pixel tolerance disc,
     * while form/bone ids continue to require an exact center-pixel hit.
     */
    public void pick(int x, int y, int radius, int handleMax)
    {
        if (radius <= 0 || this.framebuffer == null)
        {
            this.pick(x, y);

            return;
        }

        Texture texture = this.framebuffer.getMainTexture();
        int x0 = Math.max(0, x - radius);
        int y0 = Math.max(0, y - radius);
        int x1 = Math.min(texture.width - 1, x + radius);
        int y1 = Math.min(texture.height - 1, y + radius);
        int w = x1 - x0 + 1;
        int h = y1 - y0 + 1;

        if (w <= 0 || h <= 0)
        {
            this.index = 0;

            return;
        }

        int needed = w * h * 4;

        if (this.pickBuffer == null || this.pickBuffer.capacity() < needed)
        {
            this.pickBuffer = BufferUtils.createFloatBuffer(needed);
        }

        FloatBuffer floats = this.pickBuffer;

        floats.clear();
        GL11.glReadPixels(x0, y0, w, h, GL11.GL_RGBA, GL11.GL_FLOAT, floats);

        int centerId = 0;
        int nearestHandle = 0;
        long nearestDist = Long.MAX_VALUE;
        long radiusSq = (long) radius * radius;

        for (int py = 0; py < h; py++)
        {
            for (int px = 0; px < w; px++)
            {
                int base = (py * w + px) * 4;

                if ((int) (floats.get(base + 3) * 255F) < 1)
                {
                    continue;
                }

                int id = (int) (floats.get(base) * 255F)
                    | ((int) (floats.get(base + 1) * 255F) << 8)
                    | ((int) (floats.get(base + 2) * 255F) << 16);
                int fx = x0 + px;
                int fy = y0 + py;

                if (fx == x && fy == y)
                {
                    centerId = id;
                }

                if (id >= 1 && id <= handleMax)
                {
                    long dx = fx - x;
                    long dy = fy - y;
                    long dist = dx * dx + dy * dy;

                    if (dist <= radiusSq && dist < nearestDist)
                    {
                        nearestDist = dist;
                        nearestHandle = id;
                    }
                }
            }
        }

        this.index = nearestHandle != 0 ? nearestHandle : centerId;
    }

    public void unbind(StencilMap map)
    {
        this.unbind();

        this.indexMap.clear();
        this.indexMap.putAll(map.indexMap);
    }

    public void unbind()
    {
        this.framebuffer.unbind();
    }

    public void clearPicking()
    {
        this.index = 0;
        this.indexMap.clear();
    }

    public boolean hasPicked()
    {
        return this.index > 0;
    }
}
