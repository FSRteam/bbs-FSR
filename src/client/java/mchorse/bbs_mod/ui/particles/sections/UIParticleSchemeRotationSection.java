package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpin;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

/** Rotation motion, with a mode independent from position motion. */
public class UIParticleSchemeRotationSection extends UIParticleSchemeMotionAxisSection
{
    public UITextbox angle;
    public UITextbox rate;
    public UITextbox acceleration;
    public UITextbox drag;

    private UIElement angleRow;
    private UIElement rateRow;
    private UIElement accelerationRow;
    private UIElement dragRow;

    private ParticleComponentInitialSpin spin;

    public UIParticleSchemeRotationSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.angle = this.molangField(UIKeys.SNOWSTORM_MOTION_ROTATION_ANGLE, null, (str) ->
        {
            this.spin.rotation = this.parse(str, this.spin.rotation);
        }, null);
        this.rate = this.molangField(UIKeys.SNOWSTORM_MOTION_ROTATION_SPEED, null, (str) ->
        {
            this.spin.rate = this.parse(str, this.spin.rate);
        }, null);
        this.acceleration = this.molangField(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION, null, (str) ->
        {
            if (this.isParametric())
            {
                ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

                parametric.rotation = this.parse(str, parametric.rotation);
            }
            else
            {
                ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

                dynamic.rotationAcceleration = this.parse(str, dynamic.rotationAcceleration);
            }
        }, null);
        this.drag = this.molangField(UIKeys.SNOWSTORM_MOTION_ROTATION_DRAG, null, (str) ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            if (dynamic != null)
            {
                dynamic.rotationDrag = this.parse(str, dynamic.rotationDrag);
            }
        }, null);

        this.angleRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_ANGLE, this.angle);
        this.rateRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_SPEED, this.rate);
        this.accelerationRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION, this.acceleration);
        this.dragRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_DRAG, this.drag);
    }

    private MolangExpression rotation()
    {
        if (this.isParametric())
        {
            return MotionComponents.parametric(this.scheme).rotation;
        }

        return MotionComponents.dynamic(this.scheme).rotationAcceleration;
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_MOTION_ROTATION;
    }

    @Override
    protected boolean isParametric()
    {
        return MotionComponents.isRotationParametric(this.scheme);
    }

    @Override
    protected void applyMode(boolean parametric)
    {
        MotionComponents.setModes(this.scheme, MotionComponents.isPositionParametric(this.scheme), parametric);
    }

    @Override
    protected void fillFields()
    {
        this.spin = this.scheme.getOrCreate(ParticleComponentInitialSpin.class);

        this.angleRow.removeFromParent();
        this.rateRow.removeFromParent();
        this.accelerationRow.removeFromParent();
        this.dragRow.removeFromParent();

        this.angle.setText(this.text(this.spin.rotation));
        this.rate.setText(this.text(this.spin.rate));
        this.acceleration.setText(this.text(this.rotation()));

        if (this.isParametric())
        {
            this.fields.add(this.accelerationRow);
        }
        else
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            this.drag.setText(this.text(dynamic.rotationDrag));
            this.fields.add(this.angleRow, this.rateRow, this.accelerationRow, this.dragRow);
        }
    }

    private String text(MolangExpression expression)
    {
        return expression == null ? "" : expression.toString();
    }
}
