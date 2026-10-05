package mchorse.bbs_mod.client.render.view;

import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderType;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.DefaultShaderInterface;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Uses Sodium's compact prefix and the renderer's actual stride for Iris-built chunks.
 * Bridges both Sodium lines that ship for MC 1.21.1: 0.6.x (the options own the shader
 * defines, parseShader yields a String) and 0.8.x (the renderer assembles the defines,
 * parseShader yields a ParsedShader record, chunk materials pass a flat uint varying).
 * Any failure surfaces to the caller, which falls back to Sodium's own chunk program.
 */
public final class UnshadedTerrainProgram
{
    private static final ResourceLocation VERTEX_SHADER = ResourceLocation.fromNamespaceAndPath("bbs", "camera/unshaded_terrain.vsh");
    private static final ResourceLocation VERTEX_SHADER_MODERN = ResourceLocation.fromNamespaceAndPath("bbs", "camera/unshaded_terrain_v8.vsh");
    private static final ResourceLocation FRAGMENT_SHADER = ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh");

    /** Sodium 0.8.x splits parseShader's result into a record and reworked chunk materials. */
    private static final boolean SODIUM_MODERN = isModernSodium();

    private UnshadedTerrainProgram()
    {}

    public static GlProgram<ChunkShaderInterface> create(ChunkShaderOptions options)
    {
        ShaderConstants constants = createShaderConstants(options);
        GlShader vertex = newVertexShader(loadVertexShader(constants));

        try
        {
            GlShader fragment = ShaderLoader.loadShader(ShaderType.FRAGMENT, FRAGMENT_SHADER, constants);

            try
            {
                return GlProgram.builder(ResourceLocation.fromNamespaceAndPath("bbs", "unshaded_view"))
                    .attachShader(vertex)
                    .attachShader(fragment)
                    .bindAttribute("a_Position", ChunkShaderBindingPoints.ATTRIBUTE_POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.ATTRIBUTE_COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE)
                    .bindAttribute("a_LightAndData", ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX)
                    .bindFragmentData("fragColor", ChunkShaderBindingPoints.FRAG_COLOR)
                    .link(shader -> new DefaultShaderInterface(shader, options));
            }
            finally
            {
                fragment.delete();
            }
        }
        finally
        {
            vertex.delete();
        }
    }

    private static boolean isModernSodium()
    {
        try
        {
            Class.forName("net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser$ParsedShader", false, UnshadedTerrainProgram.class.getClassLoader());

            return true;
        }
        catch (ClassNotFoundException notPresent)
        {
            return false;
        }
    }

    /** Sodium 0.8.x moved define assembly out of ChunkShaderOptions into its renderer. */
    private static ShaderConstants createShaderConstants(ChunkShaderOptions options)
    {
        if (!SODIUM_MODERN)
        {
            return options.constants();
        }

        ShaderConstants.Builder builder = ShaderConstants.builder();

        builder.addAll(options.fog().getDefines());

        if (options.pass().supportsFragmentDiscard())
        {
            builder.add("USE_FRAGMENT_DISCARD");
        }

        builder.add("USE_VERTEX_COMPRESSION");
        builder.add("MAX_TEXTURE_LOD_BIAS", Integer.toString(getMaxTextureLodBias()));

        return builder.build();
    }

    /** RenderDevice gained getMaxTextureLodBias() only in the 0.8.x line. */
    private static int getMaxTextureLodBias()
    {
        try
        {
            return (Integer) RenderDevice.class.getMethod("getMaxTextureLodBias").invoke(RenderDevice.INSTANCE);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IllegalStateException("This Sodium build is missing RenderDevice.getMaxTextureLodBias", exception);
        }
    }

    /**
     * Parses the BBS-owned vertex source through Sodium's parser. Returns whatever that
     * Sodium line produces: a String on 0.6.x, a ParsedShader record on 0.8.x.
     */
    private static Object loadVertexShader(ShaderConstants constants)
    {
        ResourceLocation shader = SODIUM_MODERN ? VERTEX_SHADER_MODERN : VERTEX_SHADER;
        String path = "/assets/" + shader.getNamespace() + "/shaders/" + shader.getPath();

        /* NeoForge isolates mod resources; Sodium's loader cannot read BBS-owned assets. */
        try (InputStream stream = UnshadedTerrainProgram.class.getResourceAsStream(path))
        {
            if (stream == null)
            {
                throw new IllegalStateException("Missing bundled terrain shader: " + path);
            }

            return parseShader(new String(stream.readAllBytes(), StandardCharsets.UTF_8), constants);
        }
        catch (IOException exception)
        {
            throw new UncheckedIOException("Failed to read bundled terrain shader: " + path, exception);
        }
    }

    /** GlShader's constructor takes the parser result, whose type differs between lines. */
    private static GlShader newVertexShader(Object parsed)
    {
        try
        {
            Constructor<?> constructor = GlShader.class.getConstructor(ShaderType.class, ResourceLocation.class, parsed.getClass());

            return (GlShader) constructor.newInstance(ShaderType.VERTEX, SODIUM_MODERN ? VERTEX_SHADER_MODERN : VERTEX_SHADER, parsed);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IllegalStateException("Sodium's GlShader constructor does not accept its own parsed shader", exception);
        }
    }

    /**
     * Both Sodium lines expose the same static parseShader(String, ShaderConstants) entry
     * point, but with different return types, so the call has to go through reflection.
     */
    private static Object parseShader(String source, ShaderConstants constants)
    {
        try
        {
            Method parse = ShaderParser.class.getMethod("parseShader", String.class, ShaderConstants.class);

            return parse.invoke(null, source, constants);
        }
        catch (InvocationTargetException exception)
        {
            Throwable cause = exception.getCause();

            if (cause instanceof RuntimeException runtime)
            {
                throw runtime;
            }

            if (cause instanceof Error error)
            {
                throw error;
            }

            throw new IllegalStateException("Sodium's shader parser failed", cause);
        }
        catch (ReflectiveOperationException exception)
        {
            throw new IllegalStateException("Sodium's ShaderParser.parseShader(String, ShaderConstants) is unavailable", exception);
        }
    }
}
