package mchorse.bbs_mod.api.client.render;

import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;
import mchorse.bbs_mod.forms.structure.StructureRenderData;
import mchorse.bbs_mod.utils.MatrixStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/** Independently cached structure geometry using the normal block, entity and picking paths. */
public final class StructureRenderPart
{
    private final PartRenderer renderer;

    public StructureRenderPart(StructureForm owner, StructureRenderData data, String biome, Vector3f offset)
    {
        this.renderer = new PartRenderer(owner, data, biome, offset);
    }

    /** Copies block positions, size and block-entity NBT before handing ownership to the part. */
    public static StructureRenderData createData(String id, Vec3i size, Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> entities)
    {
        return StructureRenderData.create(id, size, blocks, entities);
    }

    public void render(FormRenderingContext context, Matrix4f transform)
    {
        context.stack.pushPose();

        if (context.world != null)
        {
            context.world.pushPose();
        }

        try
        {
            if (transform != null)
            {
                MatrixStackUtils.multiply(context.stack, transform);

                if (context.world != null)
                {
                    MatrixStackUtils.multiply(context.world, transform);
                }
            }

            this.renderer.draw(context);
        }
        finally
        {
            context.stack.popPose();

            if (context.world != null)
            {
                context.world.popPose();
            }
        }
    }

    private static final class PartRenderer extends StructureFormRenderer
    {
        PartRenderer(StructureForm form, StructureRenderData data, String biome, Vector3f offset)
        {
            super(form, data, biome, offset);
        }

        void draw(FormRenderingContext context)
        {
            super.render3D(context);
        }
    }
}
