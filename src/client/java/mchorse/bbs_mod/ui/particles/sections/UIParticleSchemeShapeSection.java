package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeBase;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeBox;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeDisc;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeEntityAABB;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapePoint;
import mchorse.bbs_mod.particles.components.shape.ParticleComponentShapeSphere;
import mchorse.bbs_mod.particles.components.shape.directions.ShapeDirectionInwards;
import mchorse.bbs_mod.particles.components.shape.directions.ShapeDirectionVector;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UI;

public class UIParticleSchemeShapeSection extends UIParticleSchemeModeSection<ParticleComponentShapeBase>
{
    public UITextbox offsetX;
    public UITextbox offsetY;
    public UITextbox offsetZ;
    public UIDirectionSection direction;
    public UIToggle surface;

    public UILabel radiusLabel;
    public UITextbox radius;

    public UILabel label;
    public UIElement xyz;
    public UITextbox x;
    public UITextbox y;
    public UITextbox z;

    public UIParticleSchemeShapeSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.offsetX = new UITextbox(10000, (str) ->
        {
            this.component.offset[0] = this.parse(str, this.component.offset[0]);
            this.editor.markUndoBoundary();
        });
        this.offsetX.placeholder(UIKeys.GENERAL_X);

        this.offsetY = new UITextbox(10000, (str) ->
        {
            this.component.offset[1] = this.parse(str, this.component.offset[1]);
            this.editor.markUndoBoundary();
        });
        this.offsetY.placeholder(UIKeys.GENERAL_Y);

        this.offsetZ = new UITextbox(10000, (str) ->
        {
            this.component.offset[2] = this.parse(str, this.component.offset[2]);
            this.editor.markUndoBoundary();
        });
        this.offsetZ.placeholder(UIKeys.GENERAL_Z);

        this.direction = new UIDirectionSection(this);
        this.surface = new UIToggle(UIKeys.SNOWSTORM_SHAPE_SURFACE, (b) ->
        {
            this.component.surface = b.getValue();
            this.editor.dirty();
        });
        this.surface.tooltip(UIKeys.SNOWSTORM_SHAPE_SURFACE_TOOLTIP);

        this.radiusLabel = UI.label(UIKeys.SNOWSTORM_SHAPE_RADIUS, 20).labelAnchor(0, 1F);
        this.radius = new UITextbox(10000, (str) ->
        {
            ParticleComponentShapeSphere sphere = (ParticleComponentShapeSphere) this.component;
            sphere.radius = this.parse(str, sphere.radius);
            this.editor.markUndoBoundary();
        });
        this.radius.placeholder(UIKeys.SNOWSTORM_SHAPE_RADIUS);

        this.label = UI.label(IKey.EMPTY, 20).labelAnchor(0, 1F);

        this.x = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentShapeBox)
            {
                ParticleComponentShapeBox box = (ParticleComponentShapeBox) this.component;
                box.halfDimensions[0] = this.parse(str, box.halfDimensions[0]);
            }
            else if (this.component instanceof ParticleComponentShapeDisc)
            {
                ParticleComponentShapeDisc disc = (ParticleComponentShapeDisc) this.component;
                disc.normal[0] = this.parse(str, disc.normal[0]);
            }
            this.editor.markUndoBoundary();
        });
        this.x.placeholder(UIKeys.GENERAL_X);

        this.y = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentShapeBox)
            {
                ParticleComponentShapeBox box = (ParticleComponentShapeBox) this.component;
                box.halfDimensions[1] = this.parse(str, box.halfDimensions[1]);
            }
            else if (this.component instanceof ParticleComponentShapeDisc)
            {
                ParticleComponentShapeDisc disc = (ParticleComponentShapeDisc) this.component;
                disc.normal[1] = this.parse(str, disc.normal[1]);
            }
            this.editor.markUndoBoundary();
        });
        this.y.placeholder(UIKeys.GENERAL_Y);

        this.z = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentShapeBox)
            {
                ParticleComponentShapeBox box = (ParticleComponentShapeBox) this.component;
                box.halfDimensions[2] = this.parse(str, box.halfDimensions[2]);
            }
            else if (this.component instanceof ParticleComponentShapeDisc)
            {
                ParticleComponentShapeDisc disc = (ParticleComponentShapeDisc) this.component;
                disc.normal[2] = this.parse(str, disc.normal[2]);
            }
            this.editor.markUndoBoundary();
        });
        this.z.placeholder(UIKeys.GENERAL_Z);

        this.xyz = UI.row(this.x, this.y, this.z);

        this.modeLabel.label = UIKeys.SNOWSTORM_SHAPE_SHAPE;

        this.fields.add(UI.label(UIKeys.SNOWSTORM_SHAPE_OFFSET, 20).labelAnchor(0, 1F));
        this.fields.add(UI.row(this.offsetX, this.offsetY, this.offsetZ));
        this.fields.add(this.direction, this.surface);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_SHAPE_TITLE;
    }

    @Override
    protected void fillModes(UICirculate button)
    {
        button.addLabel(UIKeys.SNOWSTORM_SHAPE_POINT);
        button.addLabel(UIKeys.SNOWSTORM_SHAPE_BOX);
        button.addLabel(UIKeys.SNOWSTORM_SHAPE_SPHERE);
        button.addLabel(UIKeys.SNOWSTORM_SHAPE_DISC);
        button.addLabel(UIKeys.SNOWSTORM_SHAPE_AABB);
    }

    @Override
    protected void restoreInfo(ParticleComponentShapeBase component, ParticleComponentShapeBase old)
    {
        component.offset = old.offset;
        component.direction = old.direction;
        component.surface = old.surface;

        if (component instanceof ParticleComponentShapeSphere && old instanceof ParticleComponentShapeSphere)
        {
            ((ParticleComponentShapeSphere) component).radius = ((ParticleComponentShapeSphere) old).radius;
        }
    }

    @Override
    protected Class<ParticleComponentShapeBase> getBaseClass()
    {
        return ParticleComponentShapeBase.class;
    }

    @Override
    protected Class getDefaultClass()
    {
        return ParticleComponentShapePoint.class;
    }

    @Override
    protected Class getModeClass(int value)
    {
        if (value == 1)
        {
            return ParticleComponentShapeBox.class;
        }
        else if (value == 2)
        {
            return ParticleComponentShapeSphere.class;
        }
        else if (value == 3)
        {
            return ParticleComponentShapeDisc.class;
        }
        else if (value == 4)
        {
            return ParticleComponentShapeEntityAABB.class;
        }

        return ParticleComponentShapePoint.class;
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        this.direction.fillData();
        this.surface.setValue(this.component.surface);

        this.offsetX.setText(this.component.offset[0] == null ? "" : this.component.offset[0].toString());
        this.offsetY.setText(this.component.offset[1] == null ? "" : this.component.offset[1].toString());
        this.offsetZ.setText(this.component.offset[2] == null ? "" : this.component.offset[2].toString());

        this.radiusLabel.removeFromParent();
        this.radius.removeFromParent();
        this.label.removeFromParent();
        this.xyz.removeFromParent();
        this.surface.removeFromParent();

        if (this.component instanceof ParticleComponentShapeSphere)
        {
            ParticleComponentShapeSphere sphere = (ParticleComponentShapeSphere) this.component;
            this.radius.setText(sphere.radius == null ? "" : sphere.radius.toString());
            this.fields.add(this.radiusLabel, this.radius);
        }

        if (this.component instanceof ParticleComponentShapeBox || this.component instanceof ParticleComponentShapeDisc)
        {
            this.label.label = this.component instanceof ParticleComponentShapeBox ? UIKeys.SNOWSTORM_SHAPE_BOX_SIZE : UIKeys.SNOWSTORM_SHAPE_NORMAL;

            if (this.component instanceof ParticleComponentShapeBox)
            {
                ParticleComponentShapeBox box = (ParticleComponentShapeBox) this.component;
                this.x.setText(box.halfDimensions[0] == null ? "" : box.halfDimensions[0].toString());
                this.y.setText(box.halfDimensions[1] == null ? "" : box.halfDimensions[1].toString());
                this.z.setText(box.halfDimensions[2] == null ? "" : box.halfDimensions[2].toString());
            }
            else
            {
                ParticleComponentShapeDisc disc = (ParticleComponentShapeDisc) this.component;
                this.x.setText(disc.normal[0] == null ? "" : disc.normal[0].toString());
                this.y.setText(disc.normal[1] == null ? "" : disc.normal[1].toString());
                this.z.setText(disc.normal[2] == null ? "" : disc.normal[2].toString());
            }

            this.fields.add(this.label);
            this.fields.add(this.xyz);
        }

        this.fields.add(this.surface);

        this.resizeParent();
    }

    public static class UIDirectionSection extends UIElement
    {
        public UIParticleSchemeShapeSection parent;

        public UICirculate mode;
        public UIElement xyz;
        public UITextbox x;
        public UITextbox y;
        public UITextbox z;

        public UIDirectionSection(UIParticleSchemeShapeSection parent)
        {
            super();

            this.parent = parent;
            this.mode = new UICirculate((b) ->
            {
                int value = this.mode.getValue();

                if (value == 0)
                {
                    this.parent.component.direction = ShapeDirectionInwards.OUTWARDS;
                }
                else if (value == 1)
                {
                    this.parent.component.direction = ShapeDirectionInwards.INWARDS;
                }
                else
                {
                    this.parent.component.direction = new ShapeDirectionVector(MolangParser.ZERO, MolangParser.ZERO, MolangParser.ZERO);
                }

                this.parent.editor.dirty();
                this.fillData();
            });
            this.mode.addLabel(UIKeys.SNOWSTORM_SHAPE_DIRECTION_OUTWARDS);
            this.mode.addLabel(UIKeys.SNOWSTORM_SHAPE_DIRECTION_INWARDS);
            this.mode.addLabel(UIKeys.SNOWSTORM_SHAPE_DIRECTION_VECTOR);

            this.x = new UITextbox(10000, (str) ->
            {
                ShapeDirectionVector vector = this.getVector();
                vector.x = this.parent.parse(str, vector.x);
                this.parent.editor.markUndoBoundary();
            });
            this.x.placeholder(UIKeys.GENERAL_X);

            this.y = new UITextbox(10000, (str) ->
            {
                ShapeDirectionVector vector = this.getVector();
                vector.y = this.parent.parse(str, vector.y);
                this.parent.editor.markUndoBoundary();
            });
            this.y.placeholder(UIKeys.GENERAL_Y);

            this.z = new UITextbox(10000, (str) ->
            {
                ShapeDirectionVector vector = this.getVector();
                vector.z = this.parent.parse(str, vector.z);
                this.parent.editor.markUndoBoundary();
            });
            this.z.placeholder(UIKeys.GENERAL_Z);

            this.xyz = UI.row(this.x, this.y, this.z);

            this.column().vertical().stretch().height(20);
            this.add(UI.row(5, 0, 20, UI.label(UIKeys.SNOWSTORM_SHAPE_DIRECTION, 20).labelAnchor(0, 0.5F), this.mode));
        }

        private ShapeDirectionVector getVector()
        {
            return (ShapeDirectionVector) this.parent.component.direction;
        }

        public void fillData()
        {
            boolean isVector = this.parent.component.direction instanceof ShapeDirectionVector;
            int value = 0;

            if (this.parent.component.direction == ShapeDirectionInwards.INWARDS)
            {
                value = 1;
            }
            else if (isVector)
            {
                value = 2;
            }

            this.mode.setValue(value);

            this.xyz.removeFromParent();

            if (isVector)
            {
                ShapeDirectionVector vector = this.getVector();
                this.x.setText(vector.x == null ? "" : vector.x.toString());
                this.y.setText(vector.y == null ? "" : vector.y.toString());
                this.z.setText(vector.z == null ? "" : vector.z.toString());
                this.add(this.xyz);
            }

            this.parent.resizeParent();
        }
    }
}
