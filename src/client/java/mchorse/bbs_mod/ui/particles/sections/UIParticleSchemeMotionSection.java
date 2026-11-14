package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpeed;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.utils.UIMolangExpression;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * Position motion: linear acceleration/drag (dynamic) or relative position (parametric), plus the
 * initial speed. The dynamic/parametric toggle here only affects position — see
 * {@link UIParticleSchemeRotationSection} for the independent rotation axis.
 */
public class UIParticleSchemeMotionSection extends UIParticleSchemeMotionAxisSection
{
    public UIMolangExpression speed;
    public UIMolangExpression x;
    public UIMolangExpression y;
    public UIMolangExpression z;
    public UIMolangExpression drag;

    private ParticleComponentInitialSpeed initialSpeed;

    public UIParticleSchemeMotionSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.speed = new UIMolangExpression(() -> this.initialSpeed == null ? null : this.initialSpeed.speed,
            (str) -> this.initialSpeed.speed = this.parse(str, this.initialSpeed.speed));
        this.speed.editor(parent, "motion.speed");
        this.speed.icon(Icons.ALL_DIRECTIONS).tooltip(UIKeys.SNOWSTORM_MOTION_POSITION_SPEED);
        this.x = new UIMolangExpression(() -> this.position(0), (str) -> this.editPosition(0, str));
        this.x.editor(parent, "motion.x");
        this.x.icon(Icons.X).barColor(Colors.RED).tooltip(UIKeys.GENERAL_X);
        this.y = new UIMolangExpression(() -> this.position(1), (str) -> this.editPosition(1, str));
        this.y.editor(parent, "motion.y");
        this.y.icon(Icons.Y).barColor(Colors.GREEN).tooltip(UIKeys.GENERAL_Y);
        this.z = new UIMolangExpression(() -> this.position(2), (str) -> this.editPosition(2, str));
        this.z.editor(parent, "motion.z");
        this.z.icon(Icons.Z).barColor(Colors.BLUE).tooltip(UIKeys.GENERAL_Z);
        this.drag = new UIMolangExpression(() ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            return dynamic == null ? null : dynamic.motionDrag;
        }, (str) ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            dynamic.motionDrag = this.parse(str, dynamic.motionDrag);
        });
        this.drag.editor(parent, "motion.drag");
        this.drag.icon(Icons.REVERSE).tooltip(UIKeys.SNOWSTORM_MOTION_POSITION_DRAG);
    }

    private MolangExpression position(int index)
    {
        if (this.scheme == null)
        {
            return null;
        }

        ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

        if (parametric != null && parametric.drivesPosition)
        {
            return parametric.position[index];
        }

        ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

        return dynamic == null ? null : dynamic.motionAcceleration[index];
    }

    private void editPosition(int index, String string)
    {
        if (MotionComponents.isPositionParametric(this.scheme))
        {
            ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

            parametric.position[index] = this.parse(string, parametric.position[index]);
        }
        else
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            dynamic.motionAcceleration[index] = this.parse(string, dynamic.motionAcceleration[index]);
        }
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_MOTION_TITLE;
    }

    @Override
    protected boolean isParametric()
    {
        return MotionComponents.isPositionParametric(this.scheme);
    }

    @Override
    protected void applyMode(boolean parametric)
    {
        MotionComponents.setModes(this.scheme, parametric, MotionComponents.isRotationParametric(this.scheme));
    }

    @Override
    protected void fillFields()
    {
        this.initialSpeed = this.scheme.getOrCreate(ParticleComponentInitialSpeed.class);

        this.speed.refresh();
        this.x.refresh();
        this.y.refresh();
        this.z.refresh();
        this.drag.refresh();

        this.speed.removeFromParent();
        this.x.removeFromParent();
        this.y.removeFromParent();
        this.z.removeFromParent();
        this.drag.removeFromParent();

        if (this.isParametric())
        {
            /* Parametric sets position directly — initial speed and drag don't apply. */
            this.fields.add(this.x, this.y, this.z);
        }
        else
        {
            this.fields.add(this.speed, this.x, this.y, this.z, this.drag);
        }
    }
}
