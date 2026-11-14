package mchorse.bbs_mod.utils.iris;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.resources.Pixels;
import net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.irisshaders.iris.pbr.loader.PBRTextureLoader;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.LinkedHashMap;
import org.lwjgl.opengl.GL11;

/**
 * Serves LabPBR maps for the material tab's PBR sliders — no files needed. The specular map is a
 * 1&times;1 constant baked from the sliders (a single texel samples the same at every UV); the
 * normal map is either generated from the albedo's luminance when "relief" is set, or falls back
 * to the regular {@code _n} file next to the texture.
 *
 * <p>LabPBR channel packing (what packs like Complementary and Photon read):
 * R = perceptual smoothness, G = F0 (230+ = metal), B = 65+ = subsurface scattering,
 * A = emission (255 = none).</p>
 */
public class IrisPbrConstLoader implements PBRTextureLoader
{
    private NativeImageBackedSingleColorTexture defaultNormalTexture;

    /**
     * Derived normal maps by (albedo, quantized relief). Animated sliders rebuild the PBR holder
     * per change (see {@code IrisUtils#trackPbrVariant}); without this cache every rebuild would
     * re-derive the sobel normal even when only a specular slider moved. Entries are wrapped in
     * {@link SharedTexture} so a closing holder does NOT delete them.
     */
    private static final int MAX_IDLE_NORMALS = 64;
    private final LinkedHashMap<String, NormalEntry> normalCache = new LinkedHashMap<>(16, 0.75F, true);

    private static class NormalEntry
    {
        private final Texture texture;
        private int references;

        private NormalEntry(Texture texture)
        {
            this.texture = texture;
        }
    }

    private void trimNormals()
    {
        int idle = 0;
        for (NormalEntry entry : this.normalCache.values()) if (entry.references == 0) idle++;
        Iterator<NormalEntry> iterator = this.normalCache.values().iterator();

        while (idle > MAX_IDLE_NORMALS && iterator.hasNext())
        {
            NormalEntry entry = iterator.next();
            if (entry.references != 0) continue;
            iterator.remove();
            entry.texture.delete();
            idle--;
        }
    }

    public void forgetAlbedo(int id)
    {
        String prefix = id + "#";
        Iterator<Map.Entry<String, NormalEntry>> iterator = this.normalCache.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<String, NormalEntry> item = iterator.next();
            if (!item.getKey().startsWith(prefix) || item.getValue().references != 0) continue;
            iterator.remove();
            item.getValue().texture.delete();
        }
    }

    @Override
    public void load(AbstractTexture abstractTexture, ResourceManager resourceManager, PBRTextureConsumer pbrTextureConsumer)
    {
        if (!(abstractTexture instanceof IrisPbrConstWrapper wrapper))
        {
            return;
        }

        pbrTextureConsumer.acceptSpecularTexture(new StaticTexture(this.makeSpecular(wrapper)));

        if (wrapper.relief > 0F)
        {
            String cacheKey = wrapper.glId + "#" + Math.round(wrapper.relief * 255F);
            NormalEntry normal = this.normalCache.get(cacheKey);

            if (normal == null)
            {
                Texture texture = this.makeNormal(wrapper);

                if (texture != null)
                {
                    normal = new NormalEntry(texture);
                    this.normalCache.put(cacheKey, normal);
                }
            }

            if (normal != null)
            {
                normal.references++;
                NormalEntry retained = normal;
                pbrTextureConsumer.acceptNormalTexture(new SharedTexture(normal.texture, () ->
                {
                    retained.references--;
                    this.trimNormals();
                }));
                this.trimNormals();

                return;
            }
        }

        /* No relief — keep the file-based normal map path (the `_n.png` next to the texture). */
        if (this.defaultNormalTexture == null)
        {
            this.defaultNormalTexture = new NativeImageBackedSingleColorTexture(PBRType.NORMAL.getDefaultValue());
        }

        Link normalKey = new Link(wrapper.albedo.source, StringUtils.removeExtension(wrapper.albedo.path) + "_n.png");

        pbrTextureConsumer.acceptNormalTexture(new IrisTextureWrapper(normalKey, this.defaultNormalTexture, -1));
    }

    private Texture makeSpecular(IrisPbrConstWrapper wrapper)
    {
        int r = Math.round(MathUtils.clamp(wrapper.smoothness, 0F, 1F) * 255F);
        int g = wrapper.metallic <= 0F ? 10
            : wrapper.metallic >= 1F ? 255
            : Math.round(10F + wrapper.metallic * 219F);
        int b = wrapper.sss <= 0F ? 0 : Math.round(65F + MathUtils.clamp(wrapper.sss, 0F, 1F) * 190F);
        int a = wrapper.emission <= 0F ? 255 : Math.round(MathUtils.clamp(wrapper.emission, 0F, 1F) * 254F);

        Pixels pixels = Pixels.fromSize(1, 1);

        pixels.setColor(0, 0, new Color(r / 255F, g / 255F, b / 255F, a / 255F));
        pixels.rewindBuffer();

        return Texture.textureFromPixels(pixels, GL11.GL_NEAREST);
    }

    /**
     * A normal map from the albedo's luminance (a sobel-ish slope of the brightness), scaled by
     * the relief slider. LabPBR layout: RG = the normal's XY, B = ambient occlusion (none),
     * A = the heightmap (the luminance itself, so parallax packs get something sensible).
     */
    private Texture makeNormal(IrisPbrConstWrapper wrapper)
    {
        Pixels albedo = null;
        try
        {
            albedo = wrapper.texture == null ? BBSModClient.getTextures().getPixels(wrapper.albedo)
                : Texture.pixelsFromTexture(wrapper.texture);

            if (albedo == null)
            {
                return null;
            }

            int w = albedo.width;
            int h = albedo.height;
            float strength = MathUtils.clamp(wrapper.relief, 0F, 1F) * 4F;
            float[] height = new float[w * h];

            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    Color color = albedo.getColor(x, y);

                    height[x + y * w] = (color.r + color.g + color.b) / 3F;
                }
            }

            Pixels normal = Pixels.fromSize(w, h);

            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    float hl = height[Math.max(x - 1, 0) + y * w];
                    float hr = height[Math.min(x + 1, w - 1) + y * w];
                    float hu = height[x + Math.max(y - 1, 0) * w];
                    float hd = height[x + Math.min(y + 1, h - 1) * w];

                    float nx = (hl - hr) * strength;
                    float ny = (hd - hu) * strength;
                    float nz = 1F;
                    float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

                    normal.setColor(x, y, new Color(
                        nx / length * 0.5F + 0.5F,
                        ny / length * 0.5F + 0.5F,
                        1F,
                        height[x + y * w]
                    ));
                }
            }

            normal.rewindBuffer();

            return Texture.textureFromPixels(normal, GL11.GL_NEAREST);
        }
        catch (Exception e)
        {
            return null;
        }
        finally
        {
            if (albedo != null) albedo.delete();
        }
    }

    /** Like {@link StaticTexture}, but NOT owned by the holder — cached normal maps outlive holder rebuilds. */
    public static class SharedTexture extends AbstractTexture
    {
        private final Texture texture;
        private Runnable release;

        public SharedTexture(Texture texture, Runnable release)
        {
            this.texture = texture;
            this.release = release;
        }

        @Override
        public void load(ResourceManager manager) throws IOException
        {}

        @Override
        public int getId()
        {
            return this.texture.id;
        }

        @Override
        public void close()
        {
            if (this.release != null)
            {
                Runnable release = this.release;
                this.release = null;
                release.run();
            }
        }
    }

    /**
     * An {@link AbstractTexture} over one of our generated GL textures. The PBR holder owns it:
     * when Iris closes the holder (the albedo variant died), the GL texture goes with it.
     */
    public static class StaticTexture extends AbstractTexture
    {
        private final Texture texture;

        public StaticTexture(Texture texture)
        {
            this.texture = texture;
        }

        @Override
        public void load(ResourceManager manager) throws IOException
        {}

        @Override
        public int getId()
        {
            return this.texture.id;
        }

        @Override
        public void close()
        {
            this.texture.delete();
        }
    }
}
