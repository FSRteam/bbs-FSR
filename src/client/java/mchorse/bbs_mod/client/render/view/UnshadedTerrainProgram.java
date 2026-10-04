package mchorse.bbs_mod.client.render.view;

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
import java.nio.charset.StandardCharsets;

/** Uses Sodium's compact prefix and the renderer's actual stride for Iris-built chunks. */
public final class UnshadedTerrainProgram
{
    private static final ResourceLocation VERTEX_SHADER = ResourceLocation.fromNamespaceAndPath("bbs", "camera/unshaded_terrain.vsh");

    private UnshadedTerrainProgram()
    {}

    public static GlProgram<ChunkShaderInterface> create(ChunkShaderOptions options)
    {
        GlShader vertex = new GlShader(ShaderType.VERTEX, VERTEX_SHADER, loadVertexSource(options.constants()));

        try
        {
            GlShader fragment = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                ResourceLocation.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque.fsh"), options.constants());

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

    private static String loadVertexSource(ShaderConstants constants)
    {
        String path = "/assets/" + VERTEX_SHADER.getNamespace() + "/shaders/" + VERTEX_SHADER.getPath();

        /* NeoForge isolates mod resources; Sodium's loader cannot read BBS-owned assets. */
        try (InputStream stream = UnshadedTerrainProgram.class.getResourceAsStream(path))
        {
            if (stream == null)
            {
                throw new IllegalStateException("Missing bundled terrain shader: " + path);
            }

            return ShaderParser.parseShader(new String(stream.readAllBytes(), StandardCharsets.UTF_8), constants);
        }
        catch (IOException exception)
        {
            throw new UncheckedIOException("Failed to read bundled terrain shader: " + path, exception);
        }
    }
}
