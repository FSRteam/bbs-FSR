package mchorse.bbs_mod.cubic;

import mchorse.bbs_mod.cubic.data.model.CubeFace;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelCube;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.data.model.ModelUV;
import mchorse.bbs_mod.cubic.weld.ModelWeld;
import mchorse.bbs_mod.cubic.weld.WeldBinding;
import mchorse.bbs_mod.math.molang.MolangParser;
import org.joml.Vector2f;

import java.util.List;

/** Matching cube sizes alone cannot distinguish separate joints on the same bone. */
public final class WeldRestFitTest
{
    public static void runAll()
    {
        sameSizedFacesFollowTheirRestPosition();
        modelingTransformsDecideThePair();
        skinLayersKeepTheirCounterpart();
        legacyFaceFacadeSharesGeometry();
    }

    private static void sameSizedFacesFollowTheirRestPosition()
    {
        Fixture fixture = new Fixture();
        ModelCube left = fixture.source(-10F, 0F);
        ModelCube right = fixture.source(10F, 0F);
        ModelCube spare = fixture.source(100F, 0F);
        ModelCube targetRight = fixture.target(10F, 0F);
        ModelCube targetLeft = fixture.target(-10F, 0F);
        fixture.root.initial.rotate.set(13F, 35F, -17F);
        fixture.model.initialize();
        fixture.source.current.rotate.set(80F, 20F, -60F);
        WeldBinding binding = fixture.resolve();
        check(binding.layers.size() == 2, "Unequal cube counts acquired an extra seam");
        check(paired(binding, left, targetLeft) && paired(binding, right, targetRight),
            "Same-sized cubes were paired by list order instead of their rest-pose world faces");
        check(binding.layers.stream().noneMatch(layer -> layer.sourceCube == spare), "Unmatched source cube was pulled into a distant seam");
    }

    private static void modelingTransformsDecideThePair()
    {
        Fixture fixture = new Fixture();
        ModelCube left = fixture.source(-10F, 0F);
        ModelCube right = fixture.source(10F, 0F);
        left.rotate.y = 180F;
        right.rotate.y = 180F;
        ModelCube targetLeft = fixture.target(-10F, 0F);
        ModelCube targetRight = fixture.target(10F, 0F);
        fixture.model.initialize();
        WeldBinding binding = fixture.resolve();
        check(paired(binding, left, targetRight) && paired(binding, right, targetLeft),
            "Rest fit ignored each cube's modeling rotation");
    }

    private static void skinLayersKeepTheirCounterpart()
    {
        Fixture fixture = new Fixture();
        ModelCube skin = fixture.source(0F, 0F);
        ModelCube jacket = fixture.source(0F, 0.5F);
        ModelCube targetJacket = fixture.target(0F, 0.5F);
        ModelCube targetSkin = fixture.target(0F, 0F);
        fixture.model.initialize();
        WeldBinding binding = fixture.resolve();
        check(paired(binding, skin, targetSkin) && paired(binding, jacket, targetJacket), "Skin and inflated jacket shared a mismatched seam");
    }

    private static void legacyFaceFacadeSharesGeometry()
    {
        ModelCube cube = cube(0F, 0F, 0F);
        ModelUV unwrap = cube.getUV(CubeFace.FRONT);
        cube.hideUV(mchorse.bbs_mod.cubic.weld.CubeFace.FRONT);
        check(cube.getUV(CubeFace.FRONT) == null && cube.getHiddenUV(CubeFace.FRONT) == unwrap,
            "Legacy cube-face methods mutated a parallel UV store");
        cube.showUV(CubeFace.FRONT);
        check(cube.getUV(mchorse.bbs_mod.cubic.weld.CubeFace.FRONT) == unwrap, "Canonical face could not restore the legacy unwrap");
        for (CubeFace face : CubeFace.values())
        {
            check(mchorse.bbs_mod.cubic.weld.CubeFace.fromCanonical(face).toCanonical() == face,
                "Legacy and canonical cube faces disagree");
        }
    }

    private static boolean paired(WeldBinding binding, ModelCube source, ModelCube target)
    {
        return binding.layers.stream().anyMatch(layer -> layer.sourceCube == source && layer.targetCube == target);
    }

    private static ModelCube cube(float x, float y, float inflate)
    {
        ModelCube cube = new ModelCube();
        cube.origin.set(x - 1F, y, -1F);
        cube.size.set(2F, 4F, 2F);
        cube.inflate = inflate;
        cube.setupBoxUV(new Vector2f(), false);
        cube.generateQuads(32, 32);
        return cube;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture
    {
        private final Model model = new Model(new MolangParser());
        private final ModelGroup root = new ModelGroup("root");
        private final ModelGroup source = new ModelGroup("source");
        private final ModelGroup target = new ModelGroup("target");

        private Fixture()
        {
            this.root.children.addAll(List.of(this.source, this.target));
            this.model.topGroups.add(this.root);
        }

        private ModelCube source(float x, float inflate)
        {
            ModelCube cube = cube(x, -4F, inflate);
            this.source.cubes.add(cube);
            return cube;
        }

        private ModelCube target(float x, float inflate)
        {
            ModelCube cube = cube(x, 0F, inflate);
            this.target.cubes.add(cube);
            return cube;
        }

        private WeldBinding resolve()
        {
            WeldBinding binding = WeldBinding.resolve(this.model,
                new ModelWeld("source", "top", "target", "bottom", 120F, 0.5F, 0.35F, true, true));
            check(binding != null, "Rest-fit fixture could not resolve");
            return binding;
        }
    }
}
