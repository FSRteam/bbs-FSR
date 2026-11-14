package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpin;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.utils.UIMolangExpression;
import mchorse.bbs_mod.ui.utils.icons.Icons;

/**
 * Rotation motion: rotation acceleration/drag (dynamic) or a rotation expression (parametric), plus
 * the initial spin (angle + rate). Its dynamic/parametric toggle is independent from position's — see
 * {@link UIParticleSchemeMotionSection}.
 */
public class UIParticleSchemeRotationSection extends UIParticleSchemeMotionAxisSection
{
    public UIMolangExpression angle;
    public UIMolangExpression rate;
    public UIMolangExpression acceleration;
    public UIMolangExpression drag;

    private ParticleComponentInitialSpin spin;

    public UIParticleSchemeRotationSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.angle = new UIMolangExpression(() -> this.spin == null ? null : this.spin.rotation,
            (str) -> this.spin.rotation = this.parse(str, this.spin.rotation));
        this.angle.editor(parent, "motion.angle");
        this.angle.icon(Icons.ARC).tooltip(UIKeys.SNOWSTORM_MOTION_ROTATION_ANGLE);
        this.rate = new UIMolangExpression(() -> this.spin == null ? null : this.spin.rate,
            (str) -> this.spin.rate = this.parse(str, this.spin.rate));
        this.rate.editor(parent, "motion.angle_speed");
        this.rate.icon(Icons.ORBIT).tooltip(UIKeys.SNOWSTORM_MOTION_ROTATION_SPEED);
        this.acceleration = new UIMolangExpression(() -> this.rotation(), this::editRotation);
        this.acceleration.editor(parent, "motion.rotation");
        this.acceleration.icon(Icons.REFRESH).tooltip(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION);
        this.drag = new UIMolangExpression(() ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            return dynamic == null ? null : dynamic.rotationDrag;
        }, (str) ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            dynamic.rotationDrag = this.parse(str, dynamic.rotationDrag);
        });
        this.drag.editor(parent, "motion.angle_drag");
        this.drag.icon(Icons.REVERSE).tooltip(UIKeys.SNOWSTORM_MOTION_ROTATION_DRAG);
    }

    private MolangExpression rotation()
    {
        if (this.scheme == null)
        {
            return null;
        }

        ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

        if (parametric != null && parametric.drivesRotation)
        {
            return parametric.rotation;
        }

        ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

        return dynamic == null ? null : dynamic.rotationAcceleration;
    }

    private void editRotation(String string)
    {
        if (MotionComponents.isRotationParametric(this.scheme))
        {
            ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

            parametric.rotation = this.parse(string, parametric.rotation);
        }
        else
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            dynamic.rotationAcceleration = this.parse(string, dynamic.rotationAcceleration);
        }
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

        this.angle.refresh();
        this.rate.refresh();
        this.acceleration.refresh();
        this.drag.refresh();

        this.angle.removeFromParent();
        this.rate.removeFromParent();
        this.acceleration.removeFromParent();
        this.drag.removeFromParent();

        if (this.isParametric())
        {
            /* Parametric drives rotation directly — only the expression matters here. */
            this.acceleration.icon(Icons.CODE).tooltip(UIKeys.SNOWSTORM_EXPRESSION);
            this.fields.add(this.acceleration);
        }
        else
        {
            this.acceleration.icon(Icons.REFRESH).tooltip(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION);
            this.fields.add(this.angle, this.rate, this.acceleration, this.drag);
        }
    }
}
