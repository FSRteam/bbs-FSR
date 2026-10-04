package mchorse.bbs_mod.forms.structure;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pre-tesselated structure geometry: all blocks + fluids are run through the vanilla renderer
 * ONCE (smooth AO and biome tint get baked into vertex colors), the resulting vertex data is
 * kept per render layer and replayed every frame with just a matrix transform — the same idea
 * as Create's SuperByteBuffer, minus the dependency.
 *
 * <p>A bake is valid for one (structure, biome) pair — the renderer rebakes when its
 * {@link StructureRenderWorld} instance changes — and for one resource generation:
 * {@link #invalidateAll()} is hooked to the resource-reload invalidation (resource pack
 * switch, F3+T), because baked sprite UVs go stale when atlases rebuild.</p>
 */
public class BakedStructure
{
    private static int globalGeneration;

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Baked layers in draw order: one entry per non-empty terrain layer. */
    private final List<BakedLayer> layers = new ArrayList<>();

    /** Sprites referenced by the baked geometry — marked active for Sodium every frame. */
    private final Set<TextureAtlasSprite> sprites = new HashSet<>();

    private final StructureRenderWorld world;
    private final int generation;

    private record BakedLayer(RenderType layer, ByteBuffer data, int vertexCount) {}

    private BakedStructure(StructureRenderWorld world)
    {
        this.world = world;
        this.generation = globalGeneration;
    }

    public static void invalidateAll()
    {
        globalGeneration += 1;
    }

    public boolean isValidFor(StructureRenderWorld world)
    {
        return this.world == world && this.generation == globalGeneration;
    }

    public static BakedStructure bake(StructureRenderData data, StructureRenderWorld world)
    {
        BakedStructure result = new BakedStructure(world);

        BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
        RandomSource random = RandomSource.create();
        PoseStack matrices = new PoseStack();
        TransformingVertexConsumer fluidConsumer = new TransformingVertexConsumer(new Matrix4f(), new Matrix3f());

        for (Map.Entry<BlockPos, BlockState> e : data.getBlocks().entrySet())
        {
            result.collectSprites(renderer, world, e.getKey(), e.getValue(), random);
        }

        for (RenderType layer : RenderType.chunkBufferLayers())
        {
            BufferBuilder builder = Tesselator.getInstance().begin(layer.mode(), layer.format());

            for (Map.Entry<BlockPos, BlockState> e : data.getBlocks().entrySet())
            {
                BlockPos pos = e.getKey();
                BlockState state = e.getValue();
                FluidState fluid = state.getFluidState();

                if (!fluid.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluid) == layer)
                {
                    fluidConsumer.target(builder, pos.getX() & ~15, pos.getY() & ~15, pos.getZ() & ~15);
                    renderer.renderLiquid(pos, world, fluidConsumer, state, fluid);
                }

                if (state.getRenderShape() == RenderShape.MODEL && ItemBlockRenderTypes.getChunkRenderType(state) == layer)
                {
                    matrices.pushPose();
                    matrices.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderer.renderBatched(state, pos, world, matrices, builder, true, random);
                    matrices.popPose();
                }
            }

            /* End + repack into a tight POSITION_COLOR_TEXTURE_LIGHT_NORMAL buffer. */
            BakedBuffer baked = endAndNormalize(builder);

            if (baked == null)
            {
                continue;
            }

            if (baked.data() == null)
            {
                /* Format missed standard block attributes — skip the layer */
                continue;
            }

            ByteBuffer copy = baked.data();
            int vertexCount = baked.vertexCount();

            /* Re-impose vanilla's opaque-block invariant on non-translucent layers (see forceOpaque). */
            if (!isTranslucent(layer))
            {
                forceOpaque(copy, vertexCount);
            }

            result.layers.add(new BakedLayer(layer, copy, vertexCount));
        }

        return result;
    }

    /** Remember which sprites the block/fluid at this position uses (for Sodium animation). */
    private void collectSprites(BlockRenderDispatcher renderer, StructureRenderWorld world, BlockPos pos, BlockState state, RandomSource random)
    {
        if (state.getRenderShape() == RenderShape.MODEL)
        {
            BakedModel model = renderer.getBlockModel(state);

            random.setSeed(state.getSeed(pos));

            for (Direction direction : DIRECTIONS)
            {
                for (BakedQuad quad : model.getQuads(state, direction, random))
                {
                    this.sprites.add(quad.getSprite());
                }
            }

            for (BakedQuad quad : model.getQuads(state, null, random))
            {
                this.sprites.add(quad.getSprite());
            }
        }

        FluidState fluid = state.getFluidState();

        if (!fluid.isEmpty())
        {
            /* NeoForge's fluid extensions answer with the still/flowing/overlay textures the
             * fluid renderer is going to use — the Fabric fluid render handler's job upstream */
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
            Function<ResourceLocation, TextureAtlasSprite> atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
            ResourceLocation[] ids =
            {
                extensions.getStillTexture(fluid, world, pos),
                extensions.getFlowingTexture(fluid, world, pos),
                extensions.getOverlayTexture()
            };

            for (ResourceLocation id : ids)
            {
                if (id == null)
                {
                    continue;
                }

                TextureAtlasSprite sprite = atlas.apply(id);

                if (sprite != null)
                {
                    this.sprites.add(sprite);
                }
            }
        }
    }

    /** Re-impose vanilla's opaque-block invariant: set every vertex's color alpha to {@code 0xFF}.
     *
     * <p>Vanilla's {@code ModelBlockRenderer} always writes alpha 1.0 there, but with Continuity
     * installed the bake is serviced by an FRAPI renderer (Indium), and under Iris with separate-AO
     * that path stuffs the AO coefficient into the alpha byte instead of opacity. Our blend-enabled
     * entity-layer replay would otherwise read that as transparency and the whole structure turns
     * see-through. The form/film tint alpha is applied separately at replay, so resetting the baked
     * alpha here is safe (opaque layers carry no meaningful per-vertex alpha anyway).</p> */
    private static void forceOpaque(ByteBuffer copy, int count)
    {
        for (int i = 0; i < count; i++)
        {
            copy.put(i * BakedBuffer.STRIDE + 15, (byte) 0xFF);
        }
    }

    /** Layers whose per-vertex alpha is real opacity; everything else is opaque (alpha ignorable). */
    private static boolean isTranslucent(RenderType layer)
    {
        return layer == RenderType.translucent() || layer == RenderType.tripwire();
    }

    /**
     * Whether the structure's own geometry is routed through entity layers instead of the terrain
     * ones (see {@link #render}). The color overlay follows from it: an entity layer's shader
     * mixes the bound overlay into the fragment in place, while the terrain layers have no overlay
     * channel at all.
     */
    public static boolean usesEntityLayers()
    {
        return BBSRendering.isIrisShadersEnabled();
    }

    private static RenderType getEntityLayer(RenderType blockLayer)
    {
        if (isTranslucent(blockLayer))
        {
            return Sheets.translucentCullBlockSheet();
        }

        return Sheets.cutoutBlockSheet();
    }

    /**
     * Replay the baked vertices into the provider's layer buffers, transforming positions and
     * normals by the given matrices and multiplying colors by {@code tint} (ARGB; the form/film
     * color — applied here instead of a wrapping consumer, which would also have to survive the
     * substitute wrapper the block entities use).
     *
     * <p>Light: the sky component comes from {@code contextLight} (the form's world/entity
     * light, already modulated by the {@code lighting} form property), the block component is
     * the max of context and baked light — so the structure darkens in caves/at night like any
     * other form, while baked emitters (glowstone, lamps) keep glowing. UI previews pass
     * {@code LightTexture.FULL_BRIGHT} which makes everything full-bright.</p>
     */
    public void render(PoseStack.Pose pose, CustomVertexConsumerProvider consumers, int contextLight, int tint)
    {
        /* Sodium only animates sprites it saw this frame — baked geometry bypasses it */
        SodiumSpriteHook.markActive(this.sprites);

        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        int contextBlock = contextLight & 0xFFFF;
        int contextSky = (contextLight >> 16) & 0xFFFF;
        boolean shaders = usesEntityLayers();

        /* Transparency only needs the right draw ORDER against the shared depth buffer: opaque must
         * be flushed (writing depth) before translucent draws over it. We exploit that while also
         * keeping the right SHADING:
         *
         * - opaque layers go to the terrain block layers. The vanilla terrain shader applies no
         *   directional diffuse, so the smooth AO / face-shade already baked into the vertex colors
         *   shows as-is (entity layers would re-shade and darken the whole structure). The provider
         *   flushes each terrain layer as it switches, so their depth lands before the translucent
         *   pass.
         * - translucent goes to BBS's KEYED entity translucent-cull layer, which the provider draws
         *   last (after every terrain layer) — so glass/water/ice composite over the opaque blocks
         *   behind them instead of hiding them.
         *
         * Under a shaderpack Iris owns the terrain pipeline and relights everything itself, so the
         * whole structure is fed through entity layers instead (no double-diffuse there). */
        for (BakedLayer baked : this.layers)
        {
            RenderType target;

            if (shaders)
            {
                target = getEntityLayer(baked.layer());
            }
            else if (isTranslucent(baked.layer()))
            {
                target = Sheets.translucentCullBlockSheet();
            }
            else
            {
                target = baked.layer();
            }

            replay(consumers.getBuffer(target), baked, matrix, normalMatrix, contextBlock, contextSky, tint);
        }
    }

    private static void replay(VertexConsumer out, BakedLayer baked, Matrix4f pose, Matrix3f normalMatrix, int contextBlock, int contextSky, int tint)
    {
        Vector4f position = new Vector4f();
        Vector3f normal = new Vector3f();
        ByteBuffer buf = baked.data();
        int count = baked.vertexCount();

        int tintA = tint >>> 24;
        int tintR = (tint >> 16) & 0xFF;
        int tintG = (tint >> 8) & 0xFF;
        int tintB = tint & 0xFF;

        for (int i = 0; i < count; i++)
        {
            int base = i * BakedBuffer.STRIDE;

            position.set(buf.getFloat(base), buf.getFloat(base + 4), buf.getFloat(base + 8), 1F);
            pose.transform(position);

            int r = (buf.get(base + 12) & 0xFF) * tintR / 255;
            int g = (buf.get(base + 13) & 0xFF) * tintG / 255;
            int b = (buf.get(base + 14) & 0xFF) * tintB / 255;
            int a = (buf.get(base + 15) & 0xFF) * tintA / 255;

            float u = buf.getFloat(base + 16);
            float v = buf.getFloat(base + 20);

            int bakedBlock = buf.getInt(base + 24) & 0xFFFF;

            normal.set(buf.get(base + 28) / 127F, buf.get(base + 29) / 127F, buf.get(base + 30) / 127F);
            normalMatrix.transform(normal);

            emitVertex(out, position.x, position.y, position.z, r, g, b, a, u, v,
                OverlayTexture.NO_OVERLAY, Math.max(bakedBlock, contextBlock), contextSky, normal.x, normal.y, normal.z);
        }
    }

    /** End the builder and copy its vertices into a tight {@code POSITION_COLOR_TEXTURE_LIGHT_NORMAL}
     *  ({@link BakedBuffer#STRIDE}-byte) template. Returns null if the builder was empty; a
     *  {@link BakedBuffer} with null data if the format misses standard block attributes. */
    private static BakedBuffer endAndNormalize(BufferBuilder builder)
    {
        MeshData built = builder.build();

        if (built == null)
        {
            return null;
        }

        MeshData.DrawState parameters = built.drawState();
        int count = parameters.vertexCount();
        ByteBuffer copy = normalize(built.vertexBuffer(), parameters.format(), count);

        built.close();

        return new BakedBuffer(copy, count);
    }

    /**
     * Copy the built vertex data into a tightly packed {@code POSITION_COLOR_TEXTURE_LIGHT_NORMAL}
     * template. With an Iris shaderpack active the builder's actual format is EXTENDED (bigger
     * stride, extra attributes appended), so the vanilla attributes are extracted by their real
     * offsets; returns null if the format misses any of them.
     *
     * <p>The copy lives on the heap. It used to be off-heap for the raw replay path, which
     * bulk-copied it into the builder's own direct buffer; that path is gone and every remaining
     * reader is an absolute {@code get}, which a heap buffer serves just as well. Off-heap would
     * now only buy a second memory budget to exhaust and a {@code Cleaner} to wait on — a bake
     * replaced on every biome change or resource reload is much better left to the GC.</p>
     */
    private static ByteBuffer normalize(ByteBuffer source, VertexFormat format, int count)
    {
        int stride = format.getVertexSize();
        int base = source.position();
        ByteBuffer copy = ByteBuffer.allocate(count * BakedBuffer.STRIDE).order(ByteOrder.nativeOrder());

        if (stride == BakedBuffer.STRIDE && DefaultVertexFormat.BLOCK.equals(format))
        {
            copy.put(0, source, base, count * BakedBuffer.STRIDE);

            return copy;
        }

        /* Since 1.21.1 the elements are constants on VertexFormatElement and the format
         * hands out their offsets itself (-1 when it has no such element). */
        int posOffset = format.getOffset(VertexFormatElement.POSITION);
        int colorOffset = format.getOffset(VertexFormatElement.COLOR);
        int uvOffset = format.getOffset(VertexFormatElement.UV0);
        int lightOffset = format.getOffset(VertexFormatElement.UV2);
        int normalOffset = format.getOffset(VertexFormatElement.NORMAL);

        if (posOffset < 0 || colorOffset < 0 || uvOffset < 0 || lightOffset < 0 || normalOffset < 0)
        {
            return null;
        }

        for (int i = 0; i < count; i++)
        {
            int src = base + i * stride;
            int dst = i * BakedBuffer.STRIDE;

            copy.put(dst, source, src + posOffset, 12);
            copy.put(dst + 12, source, src + colorOffset, 4);
            copy.put(dst + 16, source, src + uvOffset, 8);
            copy.put(dst + 24, source, src + lightOffset, 4);
            copy.put(dst + 28, source, src + normalOffset, 3);
        }

        return copy;
    }

    /** Emit one fully-specified vertex (since 1.21.1 it closes itself at the next one). */
    private static void emitVertex(VertexConsumer out, float x, float y, float z, int r, int g, int b, int a,
        float u, float v, int overlay, int blockLight, int skyLight, float nx, float ny, float nz)
    {
        out.addVertex(x, y, z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setUv1(overlay, overlay)
            .setUv2(blockLight, skyLight)
            .setNormal(nx, ny, nz);
    }
}
