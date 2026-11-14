package mchorse.bbs_mod.cubic.physics;

import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.utils.FormBone;
import org.joml.Vector3f;

/** Direct typed compilation keeps FSR topology reuse and validated simulation settings. */
public final class ModelPhysicsCacheTest
{
    private ModelPhysicsCacheTest()
    {
    }

    public static void runAll(ModelForm form, IModel model)
    {
        FormBone root = form.bones.getOrCreate("root");
        root.physicsEnd.set("tip");
        root.physicsTargetBone.set("target");
        root.physicsIterations.set(0);
        root.physicsRadius.set(-1F);
        root.physicsGravityRotateZ.set(90F);
        ModelPhysicsCache.Compiled first = ModelPhysicsCache.compile(model, form);

        require(first == ModelPhysicsCache.compile(model, form), "unchanged physics topology was rebuilt");
        require(first.chains().size() == 1, "typed physics chain did not compile");
        ModelPhysicsCache.CompiledChain chain = first.chains().get(0);
        require(chain.iterations() == 1 && chain.radius() == 0F, "direct compilation lost validated setting bounds");
        require(chain.restDirections().length == 2 && chain.restLengths().length == 2,
            "direct compilation lost precomputed rest geometry");
        require(first.wantedBones().containsAll(java.util.List.of("root", "tip", "target"))
            && first.chainIds().contains("root:tip"), "direct compilation lost precomputed frame requirements");
        Vector3f direction = new Vector3f(1F, 0F, 0F);
        chain.applyGravityRotation(direction);
        require(Math.abs(direction.y - 1F) < 0.001F, "typed gravity rotation did not reach compiled settings");

        root.physicsRadius.set(0.4F);
        ModelPhysicsCache.Compiled edited = ModelPhysicsCache.compile(model, form);
        require(edited != first && edited.chains().get(0).radius() == 0.4F,
            "same-frame setting edit retained old physics topology");
        PhysicsControl runtime = new PhysicsControl();
        runtime.gravity = 0F;
        root.physics.setRuntimeValue(runtime);
        require(ModelPhysicsCache.compile(model, form) == edited, "live scalar track rebuilt physics topology");
        root.physicsEnd.set("missing");
        require(ModelPhysicsCache.compile(model, form).chains().isEmpty(), "invalid typed end bone compiled");
        root.physicsEnd.set("target");
        require(ModelPhysicsCache.compile(model, form).chains().isEmpty(), "non-descendant typed end bone compiled");

        root.physicsEnd.set("tip");
        ModelPhysicsCache.Compiled beforeRebuild = ModelPhysicsCache.compile(model, form);
        Model cubic = (Model) model;
        ModelGroup tip = cubic.getGroup("tip");
        Vector3f initial = new Vector3f(tip.initial.translate);
        long revision = form.bones.getRevision();
        ModelInstance instance = new ModelInstance("edited_physics", model, null, null);
        instance.config.onCpu.set(true);

        try
        {
            tip.initial.translate.add(32F, 16F, 0F);
            cubic.refreshGeometry(java.util.List.of(tip));
            instance.setup();
            ModelPhysicsCache.Compiled rebuilt = ModelPhysicsCache.compile(model, form);
            require(form.bones.getRevision() == revision, "model rebuild unexpectedly edited form properties");
            require(rebuilt != beforeRebuild, "same-model editor rebuild retained stale compiled topology");
            require(Math.abs(rebuilt.chains().get(0).restLengths()[0]
                    - beforeRebuild.chains().get(0).restLengths()[0]) > 0.01F,
                "same-model editor rebuild retained stale rest length");
            require(!rebuilt.chains().get(0).restDirections()[0]
                    .equals(beforeRebuild.chains().get(0).restDirections()[0]),
                "same-model editor rebuild retained stale rest direction");
        }
        finally
        {
            tip.initial.translate.set(initial);
        }

        ModelPhysicsCache.clear();
    }

    private static void require(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
