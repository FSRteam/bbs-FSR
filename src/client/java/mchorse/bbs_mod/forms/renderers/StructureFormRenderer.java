package mchorse.bbs_mod.forms.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.client.BBSShaders;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.resources.AssetProvider;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import mchorse.bbs_mod.utils.colors.Color;
import mchorse.bbs_mod.utils.joml.Vectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class StructureFormRenderer extends FormRenderer<StructureForm>
{
    private static final Color COLOR = new Color();

    private String lastStructure;
    private StructureRenderData renderData;

    public StructureFormRenderer(StructureForm form)
    {
        super(form);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        StructureRenderData data = this.getRenderData();

        if (data == null || data.blocks.isEmpty())
        {
            return;
        }

        context.batcher.getContext().flush();

        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        PoseStack matrices = context.batcher.getContext().pose();
        Matrix4f uiMatrix = ModelFormRenderer.getUIMatrix(context, x1, y1, x2, y2);
        float max = Math.max(1F, Math.max(data.size.getX(), Math.max(data.size.getY(), data.size.getZ())));
        Color color = this.form.color.get();

        matrices.pushPose();
        MatrixStackUtils.multiply(matrices, uiMatrix);
        matrices.scale(this.form.uiScale.get() / max, this.form.uiScale.get() / max, this.form.uiScale.get() / max);

        matrices.last().normal().getScale(Vectors.EMPTY_3F);
        matrices.last().normal().scale(1F / Vectors.EMPTY_3F.x, -1F / Vectors.EMPTY_3F.y, 1F / Vectors.EMPTY_3F.z);

        consumers.setSubstitute(BBSRendering.getColorConsumer(color));
        consumers.setUI(true);
        this.renderBlocks(data, matrices, consumers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, true);
        consumers.draw();
        consumers.setUI(false);
        consumers.setSubstitute(null);

        matrices.popPose();
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        StructureRenderData data = this.getRenderData();

        if (data == null || data.blocks.isEmpty())
        {
            return;
        }

        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();
        int light = context.light;

        context.stack.pushPose();

        if (context.isPicking())
        {
            CustomVertexConsumerProvider.hijackVertexFormat((layer) ->
            {
                this.setupTarget(context, BBSShaders.getPickerModelsProgram());
                RenderSystem.setShader(BBSShaders::getPickerModelsProgram);
            });

            light = 0;
        }
        else
        {
            CustomVertexConsumerProvider.hijackVertexFormat((layer) -> RenderSystem.enableBlend());
        }

        COLOR.set(context.color);
        COLOR.mul(this.form.color.get());

        consumers.setSubstitute(BBSRendering.getColorConsumer(COLOR));
        this.renderBlocks(data, context.stack, consumers, light, context.overlay, false);
        consumers.draw();
        consumers.setSubstitute(null);

        CustomVertexConsumerProvider.clearRunnables();
        context.stack.popPose();

        RenderSystem.enableDepthTest();
    }

    private void renderBlocks(StructureRenderData data, PoseStack matrices, CustomVertexConsumerProvider consumers, int light, int overlay, boolean centerY)
    {
        matrices.pushPose();
        matrices.translate(-data.size.getX() / 2F, centerY ? -data.size.getY() / 2F : 0F, -data.size.getZ() / 2F);

        for (StructureBlock block : data.blocks)
        {
            matrices.pushPose();
            matrices.translate(block.pos.getX(), block.pos.getY(), block.pos.getZ());
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(block.state, matrices, consumers, light, overlay);
            matrices.popPose();
        }

        matrices.popPose();
    }

    private StructureRenderData getRenderData()
    {
        String structure = this.form.structureFile.get();

        if (Objects.equals(this.lastStructure, structure))
        {
            return this.renderData;
        }

        this.lastStructure = structure;
        this.renderData = this.loadRenderData(structure);

        return this.renderData;
    }

    private StructureRenderData loadRenderData(String structure)
    {
        if (structure == null || structure.isBlank())
        {
            return null;
        }

        try
        {
            StructureTemplate template = this.loadTemplate(structure);

            return template == null ? null : this.toRenderData(template);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private StructureTemplate loadTemplate(String structure) throws IOException
    {
        StructureTemplateManager manager = getStructureManager();
        ResourceLocation id = toStructureId(structure);

        if (manager != null && id != null)
        {
            StructureTemplate template = manager.get(id).orElse(null);

            if (template != null)
            {
                return template;
            }
        }

        return this.loadFromProvider(structure, manager);
    }

    private StructureTemplate loadFromProvider(String structure, StructureTemplateManager manager) throws IOException
    {
        AssetProvider provider = BBSMod.getProvider();

        if (provider == null)
        {
            return null;
        }

        for (Link link : createProviderLinks(structure))
        {
            try (InputStream stream = provider.getAsset(link))
            {
                CompoundTag tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());

                if (manager != null)
                {
                    return manager.readStructure(tag);
                }

                Minecraft mc = Minecraft.getInstance();

                if (mc.level == null)
                {
                    return null;
                }

                StructureTemplate template = new StructureTemplate();

                template.load(mc.level.holderLookup(Registries.BLOCK), tag);

                return template;
            }
            catch (IOException e)
            {}
        }

        return null;
    }

    private StructureRenderData toRenderData(StructureTemplate template)
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return null;
        }

        CompoundTag tag = template.save(new CompoundTag());
        ListTag paletteTag = getPaletteTag(tag);
        ListTag blockTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        HolderGetter<Block> blocksRegistry = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);
        List<BlockState> palette = new ArrayList<>();
        List<StructureBlock> blocks = new ArrayList<>();

        for (int i = 0; i < paletteTag.size(); i++)
        {
            palette.add(NbtUtils.readBlockState(blocksRegistry, paletteTag.getCompound(i)));
        }

        for (int i = 0; i < blockTag.size(); i++)
        {
            CompoundTag block = blockTag.getCompound(i);
            int stateId = block.getInt("state");

            if (stateId < 0 || stateId >= palette.size())
            {
                continue;
            }

            BlockState state = palette.get(stateId);

            if (state.isAir())
            {
                continue;
            }

            ListTag posTag = block.getList("pos", Tag.TAG_INT);

            if (posTag.size() < 3)
            {
                continue;
            }

            blocks.add(new StructureBlock(new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)), state));
        }

        return new StructureRenderData(template.getSize(), blocks);
    }

    private static ListTag getPaletteTag(CompoundTag tag)
    {
        if (tag.contains("palettes", Tag.TAG_LIST))
        {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);

            return palettes.isEmpty() ? new ListTag() : palettes.getList(0);
        }

        return tag.getList("palette", Tag.TAG_COMPOUND);
    }

    private static StructureTemplateManager getStructureManager()
    {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();

        return server == null ? null : server.getStructureManager();
    }

    private static ResourceLocation toStructureId(String structure)
    {
        String value = normalizePath(structure);

        if (value.isEmpty())
        {
            return null;
        }

        Link link = Link.create(value);

        if (!value.contains(Link.SOURCE_SEPARATOR))
        {
            return ResourceLocation.tryParse(stripStructurePath(value));
        }

        String path = stripStructurePath(link.path);

        if ("world".equals(link.source))
        {
            return ResourceLocation.tryBuild("minecraft", path);
        }

        return ResourceLocation.tryBuild(link.source, path);
    }

    private static List<Link> createProviderLinks(String structure)
    {
        List<Link> links = new ArrayList<>();
        String value = normalizePath(structure);
        Link link = Link.create(value);

        addProviderLink(links, link);

        if (!link.path.toLowerCase().endsWith(".nbt"))
        {
            addProviderLink(links, new Link(link.source, link.path + ".nbt"));
        }

        String path = stripStructurePath(link.path);

        if (!path.isEmpty() && !link.path.startsWith("structures/"))
        {
            addProviderLink(links, new Link(link.source, "structures/" + path));
            addProviderLink(links, new Link(link.source, "structures/" + path + ".nbt"));
        }

        return links;
    }

    private static void addProviderLink(List<Link> links, Link link)
    {
        if (!links.contains(link))
        {
            links.add(link);
        }
    }

    private static String normalizePath(String value)
    {
        return value == null ? "" : value.trim().replace('\\', '/');
    }

    private static String stripStructurePath(String path)
    {
        String value = normalizePath(path);

        if (value.startsWith("/"))
        {
            value = value.substring(1);
        }

        if (value.startsWith("structures/"))
        {
            value = value.substring("structures/".length());
        }

        if (value.toLowerCase().endsWith(".nbt"))
        {
            value = value.substring(0, value.length() - 4);
        }

        return value;
    }

    private static final class StructureRenderData
    {
        public final Vec3i size;
        public final List<StructureBlock> blocks;

        public StructureRenderData(Vec3i size, List<StructureBlock> blocks)
        {
            this.size = size;
            this.blocks = blocks;
        }
    }

    private static final class StructureBlock
    {
        public final BlockPos pos;
        public final BlockState state;

        public StructureBlock(BlockPos pos, BlockState state)
        {
            this.pos = pos;
            this.state = state;
        }
    }
}
