package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.expiration.ParticleComponentParticleLifetime;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeLooping;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeOnce;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentInitialization;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentLocalSpace;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpeed;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionCollision;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateInstant;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateSteady;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeBase;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeDisc;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeSphere;
import mchorse.bbs_mod.particles.components.shape.directions.ShapeDirectionInwards;
import mchorse.bbs_mod.particles.components.shape.directions.ShapeDirectionVector;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UI;

public class UIParticleSchemeQuickSetupSection extends UIParticleSchemeSection
{
    public UIParticleSchemeQuickSetupSection(UIParticleSchemePanel parent)
    {
        super(parent);

        /* Shape & Motion presets */
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_SHAPE, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SPHERE, this::applySphere);
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_RAIN, this::applyRain);
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_RING, this::applyRing);

        /* Timing presets */
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_TIMING, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_BURST, this::applyBurst);
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_STEADY, this::applySteady);

        /* Physics presets */
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_PHYSICS, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_NONE, this::applyPhysicsNone);
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SOLID, this::applyPhysicsSolid);
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SMOKE, this::applyPhysicsSmoke);
    }

    private void addPresetButton(IKey label, Runnable action)
    {
        UIButton button = new UIButton(label, (b) ->
        {
            if (this.scheme != null)
            {
                action.run();
                this.scheme.setup();
                this.editor.dirty();
            }
        });
        this.fields.add(button);
    }

    private MolangExpression expr(String value)
    {
        return this.scheme.parser.parse(value);
    }

    private void setVector(MolangExpression[] vector, String x, String y, String z)
    {
        vector[0] = this.expr(x);
        vector[1] = this.expr(y);
        vector[2] = this.expr(z);
    }

    /* ===== Shape & Motion presets ===== */

    private void applySphere()
    {
        ParticleComponentShapeSphere shape = this.scheme.getOrCreate(ParticleComponentShapeSphere.class);
        shape.radius = this.expr("1");
        shape.surface = false;
        shape.direction = ShapeDirectionInwards.OUTWARDS;

        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "0", "0");
        motion.motionDrag = MolangParser.ZERO;

        ParticleComponentInitialSpeed speed = this.scheme.getOrCreate(ParticleComponentInitialSpeed.class);
        speed.speed = this.expr("3");
    }

    private void applyRain()
    {
        ParticleComponentShapeDisc shape = this.scheme.replace(ParticleComponentShapeBase.class, ParticleComponentShapeDisc.class);
        shape.radius = this.expr("6");
        shape.surface = false;
        shape.direction = new ShapeDirectionVector(this.expr("0"), this.expr("-1"), this.expr("0"));
        this.setVector(shape.offset, "0", "6", "0");

        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "-6", "0");
        motion.motionDrag = MolangParser.ZERO;

        ParticleComponentInitialSpeed speed = this.scheme.getOrCreate(ParticleComponentInitialSpeed.class);
        speed.speed = this.expr("5");
    }

    private void applyRing()
    {
        ParticleComponentShapeDisc shape = this.scheme.replace(ParticleComponentShapeBase.class, ParticleComponentShapeDisc.class);
        shape.radius = this.expr("3");
        shape.surface = true;
        shape.direction = new ShapeDirectionVector(this.expr("0"), this.expr("1"), this.expr("0"));
        this.setVector(shape.offset, "0", "0.5", "0");

        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "0", "0");
        motion.motionDrag = MolangParser.ZERO;

        ParticleComponentInitialSpeed speed = this.scheme.getOrCreate(ParticleComponentInitialSpeed.class);
        speed.speed = this.expr("math.random(1, 4)");
    }

    /* ===== Timing presets ===== */

    private void applyBurst()
    {
        ParticleComponentRateInstant rate = this.scheme.replace(ParticleComponentRateInstant.class, ParticleComponentRateInstant.class);
        rate.particles = this.expr("100");

        this.scheme.replace(ParticleComponentLifetimeLooping.class, ParticleComponentLifetimeOnce.class);
        ParticleComponentLifetimeOnce lifetime = this.scheme.getOrCreate(ParticleComponentLifetimeOnce.class);
        lifetime.activeTime = this.expr("1");
    }

    private void applySteady()
    {
        ParticleComponentRateSteady rate = this.scheme.replace(ParticleComponentRateInstant.class, ParticleComponentRateSteady.class);
        rate.spawnRate = this.expr("60");
        rate.particles = this.expr("400");

        this.scheme.replace(ParticleComponentLifetimeLooping.class, ParticleComponentLifetimeOnce.class);
        ParticleComponentLifetimeOnce lifetime = this.scheme.getOrCreate(ParticleComponentLifetimeOnce.class);
        lifetime.activeTime = this.expr("1");
    }

    /* ===== Physics presets ===== */

    private void applyPhysicsNone()
    {
        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "0", "0");
        motion.motionDrag = MolangParser.ZERO;

        ParticleComponentMotionCollision collision = this.scheme.getOrCreate(ParticleComponentMotionCollision.class);
        collision.radius = 0.01F;
        collision.collisionDrag = 0;
        collision.bounciness = 1;
        collision.expireOnImpact = false;
    }

    private void applyPhysicsSolid()
    {
        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "-10", "0");
        motion.motionDrag = this.expr("0.1");

        ParticleComponentMotionCollision collision = this.scheme.getOrCreate(ParticleComponentMotionCollision.class);
        collision.radius = 0.2F;
        collision.collisionDrag = 1F;
        collision.bounciness = 0.3F;
        collision.expireOnImpact = false;
    }

    private void applyPhysicsSmoke()
    {
        ParticleComponentMotionDynamic motion = this.scheme.getOrCreate(ParticleComponentMotionDynamic.class);
        this.setVector(motion.motionAcceleration, "0", "1", "0");
        motion.motionDrag = this.expr("4");

        ParticleComponentMotionCollision collision = this.scheme.getOrCreate(ParticleComponentMotionCollision.class);
        collision.radius = 0.2F;
        collision.collisionDrag = 0.4F;
        collision.bounciness = 0F;
        collision.expireOnImpact = false;
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_QUICK_SETUP_TITLE;
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);
    }
}
