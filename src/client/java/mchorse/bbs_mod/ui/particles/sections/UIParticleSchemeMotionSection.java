package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpeed;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentInitialSpin;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotion;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionDynamic;
import mchorse.bbs_mod.particles.components.motion.ParticleComponentMotionParametric;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.UI;

public class UIParticleSchemeMotionSection extends UIParticleSchemeModeSection<ParticleComponentMotion>
{
    public UIElement position;
    public UITextbox positionSpeed;
    public UITextbox positionX;
    public UITextbox positionY;
    public UITextbox positionZ;
    public UITextbox positionDrag;
    public UIElement positionDragRow;

    public UIElement rotation;
    public UITextbox rotationAngle;
    public UITextbox rotationRate;
    public UITextbox rotationAcceleration;
    public UITextbox rotationDrag;
    public UIElement rotationDragRow;

    private ParticleComponentInitialSpeed speed;
    private ParticleComponentInitialSpin spin;

    public UIParticleSchemeMotionSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.positionSpeed = new UITextbox(10000, (str) ->
        {
            this.speed.speed = this.parse(str, this.speed.speed);
            this.editor.markUndoBoundary();
        });
        this.positionSpeed.placeholder(UIKeys.SNOWSTORM_MOTION_POSITION_SPEED);

        this.positionX = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentMotionDynamic)
            {
                ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
                comp.motionAcceleration[0] = this.parse(str, comp.motionAcceleration[0]);
            }
            else
            {
                ParticleComponentMotionParametric comp = (ParticleComponentMotionParametric) this.component;
                comp.position[0] = this.parse(str, comp.position[0]);
            }
            this.editor.markUndoBoundary();
        });
        this.positionX.placeholder(UIKeys.GENERAL_X);

        this.positionY = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentMotionDynamic)
            {
                ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
                comp.motionAcceleration[1] = this.parse(str, comp.motionAcceleration[1]);
            }
            else
            {
                ParticleComponentMotionParametric comp = (ParticleComponentMotionParametric) this.component;
                comp.position[1] = this.parse(str, comp.position[1]);
            }
            this.editor.markUndoBoundary();
        });
        this.positionY.placeholder(UIKeys.GENERAL_Y);

        this.positionZ = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentMotionDynamic)
            {
                ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
                comp.motionAcceleration[2] = this.parse(str, comp.motionAcceleration[2]);
            }
            else
            {
                ParticleComponentMotionParametric comp = (ParticleComponentMotionParametric) this.component;
                comp.position[2] = this.parse(str, comp.position[2]);
            }
            this.editor.markUndoBoundary();
        });
        this.positionZ.placeholder(UIKeys.GENERAL_Z);

        this.positionDrag = new UITextbox(10000, (str) ->
        {
            ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
            comp.motionDrag = this.parse(str, comp.motionDrag);
            this.editor.markUndoBoundary();
        });
        this.positionDrag.placeholder(UIKeys.SNOWSTORM_MOTION_POSITION_DRAG);

        this.rotationAngle = new UITextbox(10000, (str) ->
        {
            this.spin.rotation = this.parse(str, this.spin.rotation);
            this.editor.markUndoBoundary();
        });
        this.rotationAngle.placeholder(UIKeys.SNOWSTORM_MOTION_ROTATION_ANGLE);

        this.rotationRate = new UITextbox(10000, (str) ->
        {
            this.spin.rate = this.parse(str, this.spin.rate);
            this.editor.markUndoBoundary();
        });
        this.rotationRate.placeholder(UIKeys.SNOWSTORM_MOTION_ROTATION_SPEED);

        this.rotationAcceleration = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentMotionDynamic)
            {
                ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
                comp.rotationAcceleration = this.parse(str, comp.rotationAcceleration);
            }
            else
            {
                ParticleComponentMotionParametric comp = (ParticleComponentMotionParametric) this.component;
                comp.rotation = this.parse(str, comp.rotation);
            }
            this.editor.markUndoBoundary();
        });
        this.rotationAcceleration.placeholder(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION);

        this.rotationDrag = new UITextbox(10000, (str) ->
        {
            ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
            comp.rotationDrag = this.parse(str, comp.rotationDrag);
            this.editor.markUndoBoundary();
        });
        this.rotationDrag.placeholder(UIKeys.SNOWSTORM_MOTION_ROTATION_DRAG);

        this.position = new UIElement();
        this.position.column(UIConstants.MARGIN).vertical().stretch();
        this.positionDragRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_POSITION_DRAG, this.positionDrag);
        this.position.add(UI.label(UIKeys.SNOWSTORM_MOTION_POSITION, 20).labelAnchor(0, 1F));
        this.position.add(this.labeledField(UIKeys.SNOWSTORM_MOTION_POSITION_SPEED, this.positionSpeed));
        this.position.add(this.labeledField(UIKeys.GENERAL_X, this.positionX));
        this.position.add(this.labeledField(UIKeys.GENERAL_Y, this.positionY));
        this.position.add(this.labeledField(UIKeys.GENERAL_Z, this.positionZ));

        this.rotation = new UIElement();
        this.rotation.column(UIConstants.MARGIN).vertical().stretch();
        this.rotationDragRow = this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_DRAG, this.rotationDrag);
        this.rotation.add(UI.label(UIKeys.SNOWSTORM_MOTION_ROTATION, 20).labelAnchor(0, 1F));
        this.rotation.add(this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_ANGLE, this.rotationAngle));
        this.rotation.add(this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_SPEED, this.rotationRate));
        this.rotation.add(this.labeledField(UIKeys.SNOWSTORM_MOTION_ROTATION_ACCELERATION, this.rotationAcceleration));

        this.fields.add(this.position, this.rotation);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_MOTION_TITLE;
    }

    @Override
    protected void fillModes(UICirculate button)
    {
        button.addLabel(UIKeys.SNOWSTORM_MOTION_DYNAMIC);
        button.addLabel(UIKeys.SNOWSTORM_MOTION_PARAMETRIC);
    }

    @Override
    protected Class<ParticleComponentMotion> getBaseClass()
    {
        return ParticleComponentMotion.class;
    }

    @Override
    protected Class getDefaultClass()
    {
        return ParticleComponentMotionDynamic.class;
    }

    @Override
    protected Class getModeClass(int value)
    {
        if (value == 1)
        {
            return ParticleComponentMotionParametric.class;
        }

        return ParticleComponentMotionDynamic.class;
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        this.speed = this.scheme.getOrCreate(ParticleComponentInitialSpeed.class);
        this.spin = this.scheme.getOrCreate(ParticleComponentInitialSpin.class);

        this.positionSpeed.setText(this.speed.speed == null ? "" : this.speed.speed.toString());
        this.rotationAngle.setText(this.spin.rotation == null ? "" : this.spin.rotation.toString());
        this.rotationRate.setText(this.spin.rate == null ? "" : this.spin.rate.toString());

        if (this.component instanceof ParticleComponentMotionDynamic)
        {
            ParticleComponentMotionDynamic comp = (ParticleComponentMotionDynamic) this.component;
            this.positionX.setText(comp.motionAcceleration[0] == null ? "" : comp.motionAcceleration[0].toString());
            this.positionY.setText(comp.motionAcceleration[1] == null ? "" : comp.motionAcceleration[1].toString());
            this.positionZ.setText(comp.motionAcceleration[2] == null ? "" : comp.motionAcceleration[2].toString());
            this.positionDrag.setText(comp.motionDrag == null ? "" : comp.motionDrag.toString());
            this.rotationAcceleration.setText(comp.rotationAcceleration == null ? "" : comp.rotationAcceleration.toString());
            this.rotationDrag.setText(comp.rotationDrag == null ? "" : comp.rotationDrag.toString());
        }
        else
        {
            ParticleComponentMotionParametric comp = (ParticleComponentMotionParametric) this.component;
            this.positionX.setText(comp.position[0] == null ? "" : comp.position[0].toString());
            this.positionY.setText(comp.position[1] == null ? "" : comp.position[1].toString());
            this.positionZ.setText(comp.position[2] == null ? "" : comp.position[2].toString());
            this.rotationAcceleration.setText(comp.rotation == null ? "" : comp.rotation.toString());
        }

        this.positionDragRow.removeFromParent();
        this.rotationDragRow.removeFromParent();

        if (this.component instanceof ParticleComponentMotionDynamic)
        {
            this.position.add(this.positionDragRow);
            this.rotation.add(this.rotationDragRow);
        }

        this.resizeParent();
    }
}
