package mchorse.bbs_mod.cubic;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.config.WeldValue;
import mchorse.bbs_mod.cubic.render.CubicCubeRenderer;
import mchorse.bbs_mod.cubic.render.CubicRenderer;
import mchorse.bbs_mod.cubic.weld.ModelWeld;
import mchorse.bbs_mod.cubic.weld.WeldBinding;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.obj.shapes.ShapeKeys;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Measures emitted geometry and normals across a real captured/bent two-bone weld. */
public final class WeldSmoothNormalsTest
{
    private record Vertex(ModelGroup group, Vector3f position, Vector3f normal, float red) {}

    public static void runAll()
    {
        Model model = new Model(new MolangParser());
        ModelGroup upper = new ModelGroup("upper");
        ModelGroup lower = new ModelGroup("lower");
        upper.children.add(lower);
        model.topGroups.add(upper);
        ModelCube top = new ModelCube();
        top.origin.set(-2F, 0F, -2F);
        top.size.set(4F, 8F, 4F);
        top.material = "cloth";
        ModelCube bottom = new ModelCube();
        bottom.origin.set(-2F, -8F, -2F);
        bottom.size.set(4F, 8F, 4F);
        bottom.material = "metal";
        top.setupBoxUV(new Vector2f(), false);
        bottom.setupBoxUV(new Vector2f(), false);
        top.generateQuads(32, 32);
        bottom.generateQuads(32, 32);
        upper.cubes.add(top);
        lower.cubes.add(bottom);
        model.initialize();
        lower.current.rotate.set(35F, 0F, 12F);
        List<Vertex> sharp = bake(model, false);
        List<Vertex> smooth = bake(model, true);
        require(sharp.size() > 72 && sharp.size() == smooth.size(), "Weld tessellation was not flushed or smooth changed topology");
        boolean changed = false;
        int shared = 0;
        for (int i = 0; i < smooth.size(); i++)
        {
            Vertex a = sharp.get(i);
            Vertex b = smooth.get(i);
            require(a.position.distanceSquared(b.position) < 1.0e-12F, "Smooth shading moved the weld geometry");
            require(Math.abs(b.normal.length() - 1F) < 0.0001F, "Weld generated a non-unit normal");
            require(b.red == (b.group == upper ? 0.25F : 0.75F), "Delayed weld emission inherited another material's appearance");
            changed |= a.normal.distanceSquared(b.normal) > 1.0e-6F;
            if (b.group != upper) continue;
            for (Vertex other : smooth)
            {
                if (other.group == lower && other.position.distanceSquared(b.position) < 1.0e-10F
                    && other.normal.distanceSquared(b.normal) < 1.0e-8F) shared++;
            }
        }
        require(changed && shared > 0, "Smooth weld did not share the deformed seam normals");
        WeldValue value = new WeldValue("weld");
        value.smooth.set(true);
        WeldValue restored = new WeldValue("weld");
        restored.fromData(value.toData());
        require(restored.toWeld().smooth, "Smooth weld did not survive config round trip");
        restored = new WeldValue("weld");
        restored.fromData(new MapType());
        require(!restored.toWeld().smooth, "Legacy weld changed its default shading");
        testFailureCleanup(model);
        System.out.println("WeldSmoothNormalsTest passed");
    }

    private static List<Vertex> bake(Model model, boolean smooth)
    {
        ModelWeld value = new ModelWeld("lower", "top", "upper", "bottom", 120F, 0.5F, 0.35F, true, smooth);
        WeldBinding binding = WeldBinding.resolve(model, value);
        require(binding != null, "Weld fixture did not resolve");
        RecordingRenderer capture = new RecordingRenderer();
        capture.setWelds(List.of(binding));
        capture.setCaptureOnly(true);
        CubicRenderer.processRenderModel(capture, null, new PoseStack(), model);
        binding.layers.forEach(WeldBinding.Layer::computeSeam);
        RecordingRenderer draw = new RecordingRenderer();
        draw.setWelds(List.of(binding));
        CubicRenderer.processRenderModel(draw, null, new PoseStack(), model);
        require(model.getGroup("upper").color.r == 1F && model.getGroup("lower").color.r == 1F,
            "Patch flush did not restore the model's appearance");
        return draw.vertices;
    }

    private static void testFailureCleanup(Model model)
    {
        WeldBinding binding = WeldBinding.resolve(model,
            new ModelWeld("lower", "top", "upper", "bottom", 120F, 0.5F, 0.35F, true, true));
        RecordingRenderer capture = new RecordingRenderer();
        capture.setWelds(List.of(binding));
        capture.setCaptureOnly(true);
        CubicRenderer.processRenderModel(capture, null, new PoseStack(), model);
        binding.layers.forEach(WeldBinding.Layer::computeSeam);

        for (int failure = 0; failure < 3; failure++)
        {
            RecordingRenderer draw = new RecordingRenderer();
            draw.setWelds(List.of(binding));
            draw.failTraversal = failure == 0;
            draw.failEmission = failure == 1;
            draw.failImmediate = failure == 2;
            PoseStack stack = new PoseStack();

            try
            {
                CubicRenderer.processRenderModel(draw, null, stack, model);
                throw new AssertionError("Expected interrupted weld render");
            }
            catch (IllegalStateException expected)
            {
                require(expected.getMessage().equals("weld fixture failure"), "Unexpected rendering failure");
            }

            require(stack.clear(), "Interrupted model traversal leaked a pose frame");
            require(model.getGroup("upper").color.r == 1F && model.getGroup("lower").color.r == 1F,
                "Interrupted patch flush leaked material appearance");
            draw.failTraversal = false;
            draw.failEmission = false;
            draw.failImmediate = false;
            draw.vertices.clear();
            draw.finish(null);
            require(draw.vertices.isEmpty(), "Interrupted render retained old patches for the next bake");
        }
    }

    private static final class RecordingRenderer extends CubicCubeRenderer
    {
        private final List<Vertex> vertices = new ArrayList<>();
        private boolean failTraversal;
        private boolean failEmission;
        private boolean failImmediate;
        private boolean flushing;

        RecordingRenderer() { super(0, 0, null, new ShapeKeys()); }

        @Override
        public boolean renderGroup(BufferBuilder builder, PoseStack stack, ModelGroup group, Model model)
        {
            if (this.failTraversal && group.id.equals("lower")) throw new IllegalStateException("weld fixture failure");
            this.setColor(group.id.equals("upper") ? 0.25F : 0.75F, 1F, 1F, 1F);
            return super.renderGroup(builder, stack, group, model);
        }

        @Override
        public void finish(BufferBuilder builder)
        {
            this.flushing = true;

            try
            {
                super.finish(builder);
            }
            finally
            {
                this.flushing = false;
            }
        }

        @Override
        protected void emit(BufferBuilder builder, ModelGroup group, float x, float y, float z, float u, float v, Vector3f normal)
        {
            if ((this.flushing && this.failEmission) || (!this.flushing && this.failImmediate)) throw new IllegalStateException("weld fixture failure");
            this.vertices.add(new Vertex(group, new Vector3f(x, y, z), new Vector3f(normal), this.r));
        }
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
