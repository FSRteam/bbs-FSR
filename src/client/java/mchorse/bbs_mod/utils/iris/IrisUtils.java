package mchorse.bbs_mod.utils.iris;

import joptsimple.internal.Strings;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.DataPath;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shaderpack.LanguageMap;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuContainer;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElementScreen;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuLinkElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuOptionElement;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.pbr.TextureTracker;
import net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.FloatCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.IntCachedUniform;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisExtendedBufferBuilder;
import net.irisshaders.iris.vertices.NormI8;
import net.irisshaders.iris.vertices.NormalHelper;
import net.irisshaders.iris.vertices.views.TriView;
import com.mojang.blaze3d.vertex.BufferBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IrisUtils
{
    private static Set<Texture> textureSet = new HashSet<>();
    private static ShaderProperties properties;

    /** Nesting depth of {@link #renderOffscreen(Runnable)}: only the outermost call flips Iris. */
    private static int offscreenDepth;

    public static void setShaderProperties(ShaderProperties shaderProperties)
    {
        properties = shaderProperties;
    }

    public static List<String> getSliderProperties()
    {
        return properties == null ? Collections.emptyList() : properties.getSliderOptions();
    }

    public static Map<String, String> getShadersLanguageMap(String language)
    {
        if (Iris.getCurrentPack().isPresent())
        {
            Map<String, String> map = new HashMap<>();
            ShaderPack shaderPack = Iris.getCurrentPack().get();
            LanguageMap languageMap = shaderPack.getLanguageMap();

            Map<String, String> target = languageMap.getTranslations(language);
            Map<String, String> fallback = languageMap.getTranslations("en_us");
            final String prefix = "option.";

            Map<String, DataPath> pathMap = new HashMap<>();

            collectPaths(pathMap, shaderPack.getMenuContainer(), shaderPack.getMenuContainer().mainScreen, Collections.emptyList());
            fillInPaths(map, fallback, pathMap, prefix);
            fillInPaths(map, target, pathMap, prefix);

            return map;
        }

        return Collections.emptyMap();
    }

    private static void fillInPaths(Map<String, String> map, Map<String, String> language, Map<String, DataPath> pathMap, String prefix)
    {
        if (language == null)
        {
            return;
        }

        for (Map.Entry<String, String> entry : language.entrySet())
        {
            if (entry.getKey().startsWith(prefix))
            {
                String optionId = entry.getKey().substring(prefix.length());
                DataPath path = pathMap.get(optionId);
                String value = entry.getValue();

                if (path != null)
                {
                    List<String> translations = new ArrayList<>();

                    for (int i = 0, c = path.strings.size(); i < c; i++)
                    {
                        String string = path.strings.get(i);

                        if (i == c - 1) translations.add(value);
                        else translations.add(language.getOrDefault("screen." + string, string));
                    }

                    value = Strings.join(translations, " > ");
                }

                map.put(entry.getKey(), value);
            }
        }
    }

    private static void collectPaths(Map<String, DataPath> pathMap, OptionMenuContainer container, OptionMenuElementScreen mainScreen, List<String> prefix)
    {
        for (OptionMenuElement element : mainScreen.elements)
        {
            if (element instanceof OptionMenuOptionElement option)
            {
                ArrayList<String> strings = new ArrayList<>(prefix);

                strings.add(option.optionId);
                pathMap.put(option.optionId, new DataPath(strings));
            }
            else if (element instanceof OptionMenuLinkElement link)
            {
                OptionMenuElementScreen screen = container.subScreens.get(link.targetScreenId);

                if (screen != null)
                {
                    ArrayList<String> strings = new ArrayList<>(prefix);

                    strings.add(link.targetScreenId);
                    collectPaths(pathMap, container, screen, strings);
                }
            }
        }
    }

    public static void setup()
    {
        PBRTextureLoaderRegistry.INSTANCE.register(IrisTextureWrapper.class, new IrisTextureWrapperLoader());
    }

    public static void trackTexture(Texture texture)
    {
        TextureManager textures = BBSModClient.getTextures();
        Texture error = textures.getError();

        if (texture != error && !textureSet.contains(texture))
        {
            Link key = CollectionUtils.getKey(textures.textures, texture);

            if (key == null && texture.getParent() != null)
            {
                key = CollectionUtils.getKey(textures.animatedTextures, texture.getParent());
            }

            if (key != null)
            {
                int index = -1;

                if (texture.getParent() != null)
                {
                    index = texture.getParent().textures.indexOf(texture);
                }

                TextureTracker.INSTANCE.trackTexture(texture.id, new IrisTextureWrapper(key, index));
            }

            textureSet.add(texture);
        }
    }

    public static boolean isShaderPackEnabled()
    {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    public static void setMainBound(boolean bound)
    {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

        if (pipeline != null)
        {
            /* Iris 1.8.8 NeoForge exposes this directly on the pipeline. */
            pipeline.setIsMainBound(bound);
        }
    }

    /**
     * Whether the pack currently replaces the game's own programs. Iris 1.8.8 spells this as
     * {@code isRenderingWorld && isMainBound} on {@link ShaderRenderingPipeline}, so this says no
     * while the main framebuffer isn't bound — that is, while something renders off-screen — and
     * a caller can tell "a pack is loaded" apart from "the pack is shading this very draw".
     */
    public static boolean shouldOverrideShaders()
    {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

        return pipeline instanceof ShaderRenderingPipeline shaders && shaders.shouldOverrideShaders();
    }

    /**
     * Run a render that goes into a framebuffer of ours instead of the world's: the pack is told
     * the main target is gone (so it stops overriding programs) and the shadow pass is turned off
     * for the duration. Only the outermost call flips the pack's state — nested off-screen renders
     * would otherwise hand the main target back while the outer one is still drawing.
     *
     * <p>Restoring {@code true} on the way out is exact rather than a guess: the flip only happens
     * when the pack was overriding shaders, which Iris reports as main-bound plus rendering-world,
     * so the state we interrupted was always "bound".</p>
     */
    public static void renderOffscreen(Runnable render)
    {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        boolean override = offscreenDepth == 0
            && pipeline instanceof ShaderRenderingPipeline shaders
            && shaders.shouldOverrideShaders();
        boolean shadow = ShadowRenderer.ACTIVE;

        try
        {
            if (override)
            {
                /* Upstream flips this through Iris' render-target-state listener; Iris 1.8.8
                 * NeoForge has no such listener, and the pipeline setter writes the very flag
                 * shouldOverrideShaders() reads. */
                pipeline.setIsMainBound(false);
            }

            offscreenDepth += 1;
            ShadowRenderer.ACTIVE = false;

            render.run();
        }
        finally
        {
            offscreenDepth -= 1;
            ShadowRenderer.ACTIVE = shadow;

            if (override)
            {
                pipeline.setIsMainBound(true);
            }
        }
    }

    public static boolean isShadowPass()
    {
        return IrisApi.getInstance().isRenderingShadowPass();
    }

    /**
     * Keep Iris' upload-time vertex format in sync with the actual builder. Iris 1.8.8
     * temporarily enables its extended entity stride while the world is rendered, but
     * vanilla builders still contain the 36-byte entity format. Uploading one of those
     * builders with the extended flag set makes the later VAO read the wrong offsets,
     * which presents as black/missing materials or torn geometry.
     */
    public static boolean beginBufferUpload(BufferBuilder builder)
    {
        boolean previous = ImmediateState.renderWithExtendedVertexFormat;

        if (builder instanceof IrisExtendedBufferBuilder extended && !extended.iris$extending())
        {
            ImmediateState.renderWithExtendedVertexFormat = false;
        }

        return previous;
    }

    public static void endBufferUpload(boolean previous)
    {
        ImmediateState.renderWithExtendedVertexFormat = previous;
    }

    /**
     * Read the vertex layout Iris currently pins around a buffer being flushed. Iris picks that
     * layout twice: the buffer's own format when the render layer begins (extended — tangents and
     * mid-texture coordinates — only while the level renders), and the vertex array layout again
     * at draw time, where a plain entity format is set up with the <em>extended</em> stride
     * whenever {@code ImmediateState.renderWithExtendedVertexFormat} is up. The two agree inside
     * the layer's own flush (Iris drops the flag there for plain buffers), so a snapshot taken
     * there describes the mesh honestly.
     */
    public static boolean captureBufferLayout()
    {
        return ImmediateState.renderWithExtendedVertexFormat;
    }

    /**
     * Force the layout flag for a deferred draw and return the previous value. Anything that
     * uploads and draws a captured mesh outside the flush it was captured in has to keep the
     * pair honest, or the vertex array reads vanilla 36 byte vertices at the extended stride and
     * the geometry tears into a fan of stretched triangles.
     */
    public static boolean applyBufferLayout(boolean extended)
    {
        boolean previous = ImmediateState.renderWithExtendedVertexFormat;

        ImmediateState.renderWithExtendedVertexFormat = extended;

        return previous;
    }

    public static float[] calculateTangents(float[] v, float[] n, float[] u)
    {
        int min = Math.min(v.length / 9, Math.min(u.length / 6, n.length / 9));
        int max = Math.max(v.length / 9, Math.max(u.length / 6, n.length / 9));

        if (min != max)
        {
            return v;
        }

        return calculateTangents(new float[v.length / 3 * 4], v, n, u);
    }

    public static float[] calculateTangents(float[] t, float[] v, float[] n, float[] u)
    {
        int min = Math.min(v.length / 9, Math.min(u.length / 6, n.length / 9));
        int max = Math.max(v.length / 9, Math.max(u.length / 6, n.length / 9));

        if (min != max)
        {
            return t;
        }

        SuperTriangle triangle = new SuperTriangle();

        for (int i = 0, c = v.length / 9; i < c; i++)
        {
            int ot = i * 12;
            int oi = i * 9;
            int ou = i * 6;
            float x0 = v[oi + 0], y0 = v[oi + 1], z0 = v[oi + 2], u0 = u[ou + 0], v0 = u[ou + 1],
                  x1 = v[oi + 3], y1 = v[oi + 4], z1 = v[oi + 5], u1 = u[ou + 2], v1 = u[ou + 3],
                  x2 = v[oi + 6], y2 = v[oi + 7], z2 = v[oi + 7], u2 = u[ou + 4], v2 = u[ou + 5];

            int t1 = NormalHelper.computeTangent(n[oi + 0], n[oi + 1], n[oi + 2], triangle.set(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2));
            int t2 = NormalHelper.computeTangent(n[oi + 3], n[oi + 4], n[oi + 5], triangle.set(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2));
            int t3 = NormalHelper.computeTangent(n[oi + 6], n[oi + 7], n[oi + 8], triangle.set(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2));

            t[ot + 0] = NormI8.unpackX(t1);
            t[ot + 1] = NormI8.unpackY(t1);
            t[ot + 2] = NormI8.unpackZ(t1);
            t[ot + 3] = NormI8.unpackW(t1);

            t[ot + 4] = NormI8.unpackX(t2);
            t[ot + 5] = NormI8.unpackY(t2);
            t[ot + 6] = NormI8.unpackZ(t2);
            t[ot + 7] = NormI8.unpackW(t2);

            t[ot + 8] = NormI8.unpackX(t3);
            t[ot + 9] = NormI8.unpackY(t3);
            t[ot + 10] = NormI8.unpackZ(t3);
            t[ot + 11] = NormI8.unpackW(t3);
        }

        return t;
    }

    public static void addUniforms(List<CachedUniform> list, Map<String, ShaderCurves.ShaderVariable> variableMap)
    {
        for (ShaderCurves.ShaderVariable value : variableMap.values())
        {
            if (value.integer)
            {
                list.add(new IntCachedUniform(value.uniformName, UniformUpdateFrequency.PER_FRAME, () -> (int) value.getValue()));
            }
            else
            {
                list.add(new FloatCachedUniform(value.uniformName, UniformUpdateFrequency.PER_FRAME, value::getValue));
            }
        }
    }

    public static class SuperTriangle implements TriView
    {
        private float x0;
        private float y0;
        private float z0;
        private float u0;
        private float v0;

        private float x1;
        private float y1;
        private float z1;
        private float u1;
        private float v1;

        private float x2;
        private float y2;
        private float z2;
        private float u2;
        private float v2;

        public SuperTriangle set(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1, float x2, float y2, float z2, float u2, float v2)
        {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.u0 = u0;
            this.v0 = v0;

            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.u1 = u1;
            this.v1 = v1;

            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.u2 = u2;
            this.v2 = v2;

            return this;
        }

        @Override
        public float x(int i)
        {
            return i == 0 ? this.x0 : (i == 1 ? this.x1 : this.x2);
        }

        @Override
        public float y(int i)
        {
            return i == 0 ? this.y0 : (i == 1 ? this.y1 : this.y2);
        }

        @Override
        public float z(int i)
        {
            return i == 0 ? this.z0 : (i == 1 ? this.z1 : this.z2);
        }

        @Override
        public float u(int i)
        {
            return i == 0 ? this.u0 : (i == 1 ? this.u1 : this.u2);
        }

        @Override
        public float v(int i)
        {
            return i == 0 ? this.v0 : (i == 1 ? this.v1 : this.v2);
        }
    }
}
