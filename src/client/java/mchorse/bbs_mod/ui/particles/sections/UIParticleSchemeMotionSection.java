package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpeed;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

/** Position motion, with a mode independent from rotation motion. */
public class UIParticleSchemeMotionSection extends UIParticleSchemeMotionAxisSection
{
    public UITextbox speed;
    public UITextbox x;
    public UITextbox y;
    public UITextbox z;
    public UITextbox drag;

    private UIElement speedRow;
    private UIElement xRow;
    private UIElement yRow;
    private UIElement zRow;
    private UIElement dragRow;

    private ParticleComponentInitialSpeed initialSpeed;

    public UIParticleSchemeMotionSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.speed = this.molangField(UIKeys.SNOWSTORM_MOTION_POSITION_SPEED, null, (str) ->
        {
            this.initialSpeed.speed = this.parse(str, this.initialSpeed.speed);
        }, null);
        this.x = this.axisField(0, UIKeys.GENERAL_X);
        this.y = this.axisField(1, UIKeys.GENERAL_Y);
        this.z = this.axisField(2, UIKeys.GENERAL_Z);
        this.drag = this.molangField(UIKeys.SNOWSTORM_MOTION_POSITION_DRAG, null, (str) ->
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            if (dynamic != null)
            {
                dynamic.motionDrag = this.parse(str, dynamic.motionDrag);
            }
        }, null);

        this.speedRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_POSITION_SPEED, this.speed);
        this.xRow = this.labeledField(UIKeys.GENERAL_X, this.x);
        this.yRow = this.labeledField(UIKeys.GENERAL_Y, this.y);
        this.zRow = this.labeledField(UIKeys.GENERAL_Z, this.z);
        this.dragRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_POSITION_DRAG, this.drag);
    }

    private UITextbox axisField(int index, IKey label)
    {
        return this.molangField(label, null, (str) ->
        {
            if (this.isParametric())
            {
                ParticleComponentMotionParametric parametric = MotionComponents.parametric(this.scheme);

                parametric.position[index] = this.parse(str, parametric.position[index]);
            }
            else
            {
                ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

                dynamic.motionAcceleration[index] = this.parse(str, dynamic.motionAcceleration[index]);
            }
        }, null);
    }

    private MolangExpression position(int index)
    {
        if (this.isParametric())
        {
            return MotionComponents.parametric(this.scheme).position[index];
        }

        return MotionComponents.dynamic(this.scheme).motionAcceleration[index];
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

        this.speedRow.removeFromParent();
        this.xRow.removeFromParent();
        this.yRow.removeFromParent();
        this.zRow.removeFromParent();
        this.dragRow.removeFromParent();

        this.speed.setText(this.initialSpeed.speed == null ? "" : this.initialSpeed.speed.toString());
        this.x.setText(this.text(this.position(0)));
        this.y.setText(this.text(this.position(1)));
        this.z.setText(this.text(this.position(2)));

        if (this.isParametric())
        {
            this.fields.add(this.xRow, this.yRow, this.zRow);
        }
        else
        {
            ParticleComponentMotionDynamic dynamic = MotionComponents.dynamic(this.scheme);

            this.drag.setText(this.text(dynamic.motionDrag));
            this.fields.add(this.speedRow, this.xRow, this.yRow, this.zRow, this.dragRow);
        }
    }

    private String text(MolangExpression expression)
    {
        return expression == null ? "" : expression.toString();
    }
}
