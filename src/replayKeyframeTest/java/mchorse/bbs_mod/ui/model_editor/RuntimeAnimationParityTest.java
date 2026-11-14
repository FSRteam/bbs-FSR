package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.cubic.IModel;
import mchorse.bbs_mod.cubic.IModelInstance;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.animation.ActionsConfig;
import mchorse.bbs_mod.cubic.animation.Animator;
import mchorse.bbs_mod.cubic.animation.ProceduralAnimator;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.Animations;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.forms.entities.StubEntity;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.utils.pose.Pose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/** Executes state selection and procedural posing through the actual production animators. */
final class RuntimeAnimationParityTest
{
    static void runAll()
    {
        testStateSelection();
        testProceduralPoses();
    }

    private static void testStateSelection()
    {
        MolangParser parser = new MolangParser();
        Animations animations = new Animations(parser);
        Animator animator = new Animator();
        for (String id : animator.getActions())
        {
            Animation animation = new Animation(id, parser);
            animation.setLength(2D);
            animations.add(animation);
        }
        Model model = new Model(parser);
        model.initialize();
        animator.setup(new ModelInstance("states", model, animations, null), new ActionsConfig(), false);
        require(animator.swimming != null && animator.swimmingIdle != null && animator.riding != null
            && animator.ridingIdle != null && animator.flying != null && animator.flyingIdle != null,
            "new action names did not create actual playbacks");
        StubEntity entity = new StubEntity();
        entity.setOnGround(true);
        entity.setSwimming(true);
        entity.setRiding(true);
        entity.setFlying(true);
        animator.update(entity);
        require(animator.active == animator.ridingIdle, "riding did not take priority over swimming and flight");
        entity.setPosition(1D, 0D, 0D);
        animator.update(entity);
        require(animator.active == animator.riding, "moving rider did not select the moving action");
        entity.setRiding(false);
        animator.update(entity);
        require(animator.active == animator.swimmingIdle, "stationary swimmer did not select swimming_idle");
        animator.swimmingIdle = null;
        animator.update(entity);
        require(animator.active == animator.swimming, "missing half of a state pair did not fall back to its authored half");
        entity.setSwimming(false);
        animator.update(entity);
        require(animator.active == animator.flyingIdle, "stationary creative flight did not select flying_idle");
        entity.setFlying(false);
        entity.setFallFlying(true);
        entity.setPosition(2D, 0D, 0D);
        animator.update(entity);
        require(animator.active == animator.flying, "elytra flight did not select the moving flight action");
        animator.flying = null;
        animator.flyingIdle = null;
        entity.setVelocity(0F, -1F, 0F);
        entity.setOnGround(false);
        entity.setFallDistance(5F);
        entity.setPosition(3D, 0D, 0D);
        animator.update(entity);
        require(animator.active == animator.running, "a model without flight clips entered the falling action while gliding");
        entity.setFallFlying(false);
        animator.update(entity);
        require(animator.active == animator.falling, "normal falling stopped selecting the falling clip");
    }

    private static void testProceduralPoses()
    {
        Model cubic = new Model(new MolangParser());
        BOBJArmature armature = new BOBJArmature("procedural");
        int index = 0;
        for (String id : List.of("anchor", "head", "torso", "right_arm", "left_arm", "right_leg", "left_leg"))
        {
            cubic.topGroups.add(new ModelGroup(id));
            armature.addBone(new BOBJBone(index++, id, "", new Matrix4f()));
        }
        cubic.initialize();
        armature.initArmature();
        BOBJModel bobj = new BOBJModel(armature, List.of(), false);
        Pose cubicSneak = new Pose();
        /* Saved pose angles are radians even though a cubic group's current pose uses degrees. */
        cubicSneak.getOrCreate("head").rotate.set((float) Math.toRadians(15F), (float) Math.toRadians(20F), 0F);
        Pose bobjSneak = new Pose();
        bobjSneak.getOrCreate("head").rotate.set(0.25F, 0.35F, 0F);
        ProceduralAnimator animator = new ProceduralAnimator();
        StubEntity entity = new StubEntity()
        {
            @Override
            public float getLimbSpeed(float transition) { return 1F; }
            @Override
            public float getLimbPos(float transition) { return 0F; }
        };
        entity.setRiding(true);
        animator.applyActions(entity, instance(cubic, cubicSneak), 1F);
        require(close(cubic.getGroup("right_leg").current.rotate.x, 81F)
            && close(cubic.getGroup("right_leg").current.rotate.y, -18F)
            && close(cubic.getGroup("left_leg").current.rotate.z, -4.5F), "cubic rider did not receive vanilla seated hips and knees");
        animator.applyActions(entity, instance(bobj, bobjSneak), 1F);
        require(close(armature.bones.get("right_leg").transform.rotate.x, 1.4137167F)
            && close(armature.bones.get("right_leg").transform.rotate.y, 0.31415927F)
            && close(armature.bones.get("left_leg").transform.rotate.z, 0.07853982F), "BOBJ rider did not receive the seated pose in its own units");
        entity.setRiding(false);
        entity.setSneaking(true);
        animator.applyActions(entity, instance(cubic, cubicSneak), 1F);
        require(close(cubic.getGroup("head").current.rotate.x, 15F) && close(cubic.getGroup("head").current.rotate.y, 20F),
            "look direction erased the authored cubic sneaking head offset");
        animator.applyActions(entity, instance(bobj, bobjSneak), 1F);
        require(close(armature.bones.get("head").transform.rotate.x, 0.25F) && close(armature.bones.get("head").transform.rotate.y, 0.35F),
            "look direction erased the authored BOBJ sneaking head offset");
        entity.setSneaking(false);
        entity.setRoll(6);
        entity.setVelocity(0F, 0F, 0F);
        animator.applyActions(entity, instance(cubic, cubicSneak), 1F);
        animator.applyActions(entity, instance(bobj, bobjSneak), 1F);
        require(Float.isFinite(cubic.getGroup("right_leg").current.rotate.x)
            && Float.isFinite(armature.bones.get("right_leg").transform.rotate.x), "stationary rolling produced non-finite limb angles");
        entity.setVelocity(10F, 0F, 0F);
        animator.applyActions(entity, instance(cubic, cubicSneak), 1F);
        require(Math.abs(cubic.getGroup("right_leg").current.rotate.x) < 0.001F,
            "fast flight did not attenuate the walking stride");
    }

    private static IModelInstance instance(IModel model, Pose sneak)
    {
        return new IModelInstance()
        {
            public IModel getModel() { return model; }
            public Pose getSneakingPose() { return sneak; }
            public Animations getAnimations() { return null; }
            public Map<String, String> getProceduralBones() { return Map.of("torso", "torso"); }
        };
    }

    private static boolean close(float first, float second)
    {
        return Math.abs(first - second) < 0.001F;
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }

    private RuntimeAnimationParityTest()
    {}
}
