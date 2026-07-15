package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.particles.ParticleMaterial;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.BillboardDirection;
import mchorse.bbs_mod.particles.components.appearance.CameraFacing;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionAppearance;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionTinting;
import mchorse.bbs_mod.particles.components.appearance.colors.Gradient;
import mchorse.bbs_mod.particles.components.appearance.colors.Solid;
import mchorse.bbs_mod.particles.components.appearance.colors.Tint;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.utils.UIGradientEditor;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.Arrays;

public class UIParticleSchemeCollisionAppearanceSection extends UIParticleSchemeComponentSection<ParticleComponentCollisionAppearance>
{
    public UIToggle enabled;
    public UIButton pick;
    public UICirculate material;
    public UICirculate textureMode;
    public UIToggle lit;
    public UICirculate facingMode;

    /* Direction sub-controls */
    public UICirculate directionMode;
    public UITrackpad speedThreshold;
    public UITextbox customDirX;
    public UITextbox customDirY;
    public UITextbox customDirZ;
    public UIElement directionFields;

    public UITextbox sizeW;
    public UITextbox sizeH;
    public UITextbox uvX;
    public UITextbox uvY;
    public UITextbox uvW;
    public UITextbox uvH;

    public UIElement flipbook;
    public UITrackpad stepX;
    public UITrackpad stepY;
    public UITrackpad fps;
    public UITextbox max;
    public UIToggle stretch;
    public UIToggle loop;

    /* Collision tinting */
    public UICirculate tintMode;
    public UIColor tintColor;
    public UITextbox tR;
    public UITextbox tG;
    public UITextbox tB;
    public UITextbox tA;
    public UIElement tChannels;
    public UIGradientEditor tGradientEditor;
    public UIColor tGradientColor;
    public UITextbox tGradientInterpolant;
    public UIElement tGradient;
    public UIToggle tintingEnabled;

    private ParticleComponentCollisionTinting tinting;
    private Tint[] tintCache = new Tint[3];
    private int previousTintMode;

    public UIParticleSchemeCollisionAppearanceSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.enabled = new UIToggle(UIKeys.SNOWSTORM_COLLISION_APPEARANCE_ENABLED, (b) -> this.editor.dirty());

        this.pick = new UIButton(UIKeys.SNOWSTORM_GENERAL_PICK, (b) ->
        {
            UITexturePicker.open(this.getContext(), this.component.texture, (link) ->
            {
                if (link == null)
                {
                    link = ParticleScheme.DEFAULT_TEXTURE;
                }

                this.setCollisionTextureSize(link);
                this.component.texture = link;
                this.editor.dirty();
            });
        });

        this.material = new UICirculate((b) ->
        {
            this.component.material = ParticleMaterial.values()[this.material.getValue()];
            this.editor.dirty();
        });
        this.material.addLabel(UIKeys.SNOWSTORM_GENERAL_PARTICLES_OPAQUE);
        this.material.addLabel(UIKeys.SNOWSTORM_GENERAL_PARTICLES_ALPHA);
        this.material.addLabel(UIKeys.SNOWSTORM_GENERAL_PARTICLES_BLEND);
        this.material.addLabel(UIKeys.SNOWSTORM_GENERAL_PARTICLES_ADD);

        this.textureMode = new UICirculate((b) ->
        {
            int val = this.textureMode.getValue();
            this.component.flipbook = val == 1;
            this.component.fullTexture = val == 2;
            this.updateFlipbookVisibility();
            this.editor.dirty();
        });
        this.textureMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_REGULAR);
        this.textureMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_ANIMATED);
        this.textureMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_FULL);

        this.lit = new UIToggle(UIKeys.SNOWSTORM_COLLISION_APPEARANCE_LIT, (b) ->
        {
            this.component.lit = b.getValue();
            this.editor.dirty();
        });

        this.facingMode = new UICirculate((b) ->
        {
            this.component.facing = CameraFacing.values()[this.facingMode.getValue()];
            this.updateDirectionVisibility();
            this.editor.dirty();
        });

        for (CameraFacing facing : CameraFacing.values())
        {
            this.facingMode.addLabel(IKey.raw(facing.id));
        }

        /* Direction sub-controls */
        this.directionMode = new UICirculate((b) ->
        {
            this.component.directionMode = this.directionMode.getValue() == 0
                ? BillboardDirection.DERIVE_FROM_VELOCITY
                : BillboardDirection.CUSTOM_DIRECTION;
            this.updateDirectionVisibility();
            this.editor.dirty();
        });
        this.directionMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_DERIVE);
        this.directionMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_CUSTOM);

        this.speedThreshold = new UITrackpad((v) ->
        {
            this.component.speedThreshold = v.floatValue();
            this.editor.dirty();
        });

        this.customDirX = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[0] = this.parse(str, this.component.customDirection[0]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirX.placeholder(IKey.raw("X"));

        this.customDirY = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[1] = this.parse(str, this.component.customDirection[1]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirY.placeholder(IKey.raw("Y"));

        this.customDirZ = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[2] = this.parse(str, this.component.customDirection[2]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirZ.placeholder(IKey.raw("Z"));

        this.directionFields = new UIElement();
        this.directionFields.column().vertical().stretch();
        this.directionFields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_MODE, 20).labelAnchor(0, 1F));
        this.directionFields.add(this.directionMode);
        this.directionFields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_SPEED_THRESHOLD, this.speedThreshold));
        this.directionFields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_CUSTOM_DIRECTION, 20).labelAnchor(0, 1F));
        this.directionFields.add(this.labeledField(IKey.raw("X"), this.customDirX));
        this.directionFields.add(this.labeledField(IKey.raw("Y"), this.customDirY));
        this.directionFields.add(this.labeledField(IKey.raw("Z"), this.customDirZ));

        this.sizeW = new UITextbox(10000, (str) ->
        {
            this.component.sizeW = this.parse(str, this.component.sizeW);
            this.editor.dirty();
        });
        this.sizeW.tooltip(IKey.constant("Width"));
        this.sizeH = new UITextbox(10000, (str) ->
        {
            this.component.sizeH = this.parse(str, this.component.sizeH);
            this.editor.dirty();
        });
        this.sizeH.tooltip(IKey.constant("Height"));

        this.uvX = new UITextbox(10000, (str) ->
        {
            this.component.uvX = this.parse(str, this.component.uvX);
            this.editor.dirty();
        });
        this.uvX.tooltip(IKey.constant("UV X"));
        this.uvY = new UITextbox(10000, (str) ->
        {
            this.component.uvY = this.parse(str, this.component.uvY);
            this.editor.dirty();
        });
        this.uvY.tooltip(IKey.constant("UV Y"));
        this.uvW = new UITextbox(10000, (str) ->
        {
            this.component.uvW = this.parse(str, this.component.uvW);
            this.editor.dirty();
        });
        this.uvW.tooltip(IKey.constant("UV W"));
        this.uvH = new UITextbox(10000, (str) ->
        {
            this.component.uvH = this.parse(str, this.component.uvH);
            this.editor.dirty();
        });
        this.uvH.tooltip(IKey.constant("UV H"));

        /* Flipbook controls */
        this.stepX = new UITrackpad((value) ->
        {
            this.component.stepX = value.floatValue();
            this.editor.dirty();
        });
        this.stepX.tooltip(UIKeys.SNOWSTORM_APPEARANCE_STEP_X);
        this.stepY = new UITrackpad((value) ->
        {
            this.component.stepY = value.floatValue();
            this.editor.dirty();
        });
        this.stepY.tooltip(UIKeys.SNOWSTORM_APPEARANCE_STEP_Y);
        this.fps = new UITrackpad((value) ->
        {
            this.component.fps = value.floatValue();
            this.editor.dirty();
        });
        this.fps.tooltip(UIKeys.SNOWSTORM_APPEARANCE_FPS);
        this.max = new UITextbox(10000, (str) ->
        {
            this.component.maxFrame = this.parse(str, this.component.maxFrame);
            this.editor.markUndoBoundary();
        });
        this.max.placeholder(UIKeys.SNOWSTORM_APPEARANCE_FRAMES);
        this.max.tooltip(UIKeys.SNOWSTORM_APPEARANCE_MAX);
        this.stretch = new UIToggle(UIKeys.SNOWSTORM_APPEARANCE_STRETCH, (b) ->
        {
            this.component.stretchFPS = b.getValue();
            this.editor.dirty();
        });
        this.loop = new UIToggle(UIKeys.SNOWSTORM_APPEARANCE_LOOP, (b) ->
        {
            this.component.loop = b.getValue();
            this.editor.dirty();
        });

        this.flipbook = new UIElement();
        this.flipbook.column().vertical().stretch().height(20);
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_STEP_X, this.stepX));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_STEP_Y, this.stepY));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_FPS, this.fps));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_FRAMES, this.max));
        this.flipbook.add(UI.row(5, 0, 20, this.stretch, this.loop));

        /* Collision tinting */
        this.tintingEnabled = new UIToggle(UIKeys.SNOWSTORM_COLLISION_TINTING_ENABLED, (b) -> this.editor.dirty());

        this.tintMode = new UICirculate((b) -> this.changeTintMode(b.getValue()));
        this.tintMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_SOLID);
        this.tintMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_EXPRESSION);
        this.tintMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_GRADIENT);

        this.tintColor = new UIColor((color) ->
        {
            Solid solid = this.getTintSolid();
            Color original = this.tintColor.picker.color;
            solid.r = this.setTint(solid.r, original.r);
            solid.g = this.setTint(solid.g, original.g);
            solid.b = this.setTint(solid.b, original.b);
            solid.a = this.setTint(solid.a, original.a);
            this.editor.dirty();
        });
        this.tintColor.withAlpha();

        this.tR = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.r = this.parse(str, solid.r);
            this.editor.markUndoBoundary();
        });
        this.tR.placeholder(IKey.constant("R"));
        this.tG = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.g = this.parse(str, solid.g);
            this.editor.markUndoBoundary();
        });
        this.tG.placeholder(IKey.constant("G"));
        this.tB = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.b = this.parse(str, solid.b);
            this.editor.markUndoBoundary();
        });
        this.tB.placeholder(IKey.constant("B"));
        this.tA = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.a = this.parse(str, solid.a);
            this.editor.markUndoBoundary();
        });
        this.tA.placeholder(IKey.constant("A"));

        this.tChannels = new UIElement();
        this.tChannels.column().vertical().stretch().height(20);
        this.tChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_RED, this.tR));
        this.tChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_GREEN, this.tG));
        this.tChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_BLUE, this.tB));
        this.tChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_ALPHA, this.tA));

        this.tGradientColor = new UIColor(this::setTintGradientColor).withAlpha();
        this.tGradientEditor = new UIGradientEditor(this, this.tGradientColor);
        this.tGradientInterpolant = new UITextbox(10000, (str) ->
        {
            Gradient gradient = (Gradient) this.tinting.color;
            gradient.interpolant = this.parse(str, gradient.interpolant);
            this.editor.markUndoBoundary();
        });
        this.tGradientInterpolant.placeholder(UIKeys.SNOWSTORM_LIGHTING_INTERPOLANT);
        this.tGradient = this.labeledField(UIKeys.SNOWSTORM_LIGHTING_INTERPOLANT, this.tGradientInterpolant);

        /* Layout */
        this.fields.add(this.enabled);
        this.fields.add(UI.row(5, 0, 20, this.pick, this.material));
        this.fields.add(UI.row(5, 0, 20, UI.label(UIKeys.SNOWSTORM_MODE, 20).labelAnchor(0, 0.5F), this.textureMode));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_FACING, 20).labelAnchor(0, 1F));
        this.fields.add(this.facingMode);
        this.fields.add(this.lit);
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_SIZE, 20).labelAnchor(0, 1F));
        this.fields.add(this.labeledField(IKey.constant("Width"), this.sizeW));
        this.fields.add(this.labeledField(IKey.constant("Height"), this.sizeH));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_COLLISION_UV_MAPPING, 20).labelAnchor(0, 1F).marginTop(8));
        this.fields.add(this.labeledField(IKey.constant("UV X"), this.uvX));
        this.fields.add(this.labeledField(IKey.constant("UV Y"), this.uvY));
        this.fields.add(this.labeledField(IKey.constant("UV W"), this.uvW));
        this.fields.add(this.labeledField(IKey.constant("UV H"), this.uvH));

        /* Collision tinting sub-section */
        this.fields.add(UI.label(UIKeys.SNOWSTORM_COLLISION_TINTING_TITLE, 20).labelAnchor(0, 1F).marginTop(8));
        this.fields.add(this.tintingEnabled);
        this.fields.add(UI.row(5, 0, 20, UI.label(UIKeys.SNOWSTORM_MODE, 20).labelAnchor(0, 0.5F), this.tintMode));
    }

    private void updateFlipbookVisibility()
    {
        this.flipbook.removeFromParent();

        if (this.component.flipbook)
        {
            this.fields.add(this.flipbook);
        }

        this.updateDirectionVisibility();
        this.resizeParent();
    }

    private void updateDirectionVisibility()
    {
        this.directionFields.removeFromParent();

        boolean showDirection = this.component.facing.isDirection;

        if (showDirection)
        {
            this.fields.add(this.directionFields);
        }

        this.speedThreshold.setVisible(this.component.directionMode == BillboardDirection.DERIVE_FROM_VELOCITY);
        this.customDirX.setVisible(this.component.directionMode == BillboardDirection.CUSTOM_DIRECTION);
        this.customDirY.setVisible(this.component.directionMode == BillboardDirection.CUSTOM_DIRECTION);
        this.customDirZ.setVisible(this.component.directionMode == BillboardDirection.CUSTOM_DIRECTION);

        this.resizeParent();
    }

    private void changeTintMode(int value)
    {
        if (this.tintCache[this.previousTintMode] == null)
        {
            this.tintCache[this.previousTintMode] = this.tinting.color;
        }

        Tint cached = this.tintCache[value];

        if (cached == null)
        {
            cached = value == 2 ? new Gradient() : new Solid();
            this.tintCache[value] = cached;
        }

        this.tinting.color = cached;
        this.fillTintData();
        this.editor.dirty();
        this.previousTintMode = value;
    }

    private Solid getTintSolid()
    {
        return (Solid) this.tinting.color;
    }

    private void setTintGradientColor(int color)
    {
        this.tGradientEditor.setColor(color);
    }

    private MolangExpression setTint(MolangExpression expression, float value)
    {
        if (expression == MolangParser.ZERO || expression == MolangParser.ONE)
        {
            return new MolangValue(null, new Constant(value));
        }

        if (!(expression instanceof MolangValue))
        {
            expression = new MolangValue(null, new Constant(0));
        }

        MolangValue v = (MolangValue) expression;

        if (!(v.expression instanceof Constant))
        {
            v.expression = new Constant(0);
        }

        v.expression.set(value);
        return expression;
    }

    private void fillTintData()
    {
        if (this.tinting == null)
        {
            return;
        }

        this.tGradientEditor.removeFromParent();
        this.tGradient.removeFromParent();
        this.tGradientColor.removeFromParent();
        this.tintColor.removeFromParent();
        this.tintColor.picker.removeFromParent();
        this.tChannels.removeFromParent();

        if (this.tintMode.getValue() == 0)
        {
            Solid solid = (Solid) this.tinting.color;
            this.tintColor.picker.color.set((float) solid.r.get(), (float) solid.g.get(), (float) solid.b.get(), (float) solid.a.get());
            this.fields.add(this.tintColor);
        }
        else if (this.tintMode.getValue() == 2)
        {
            this.tGradientEditor.setGradient((Gradient) this.tinting.color);
            this.fields.add(this.tGradientEditor);
            this.fields.add(this.tGradientColor);
            this.fields.add(this.tGradient);

            Gradient gradient = (Gradient) this.tinting.color;
            this.tGradientInterpolant.setText(gradient.interpolant == null ? "" : gradient.interpolant.toString());
        }
        else
        {
            Solid solid = (Solid) this.tinting.color;
            this.tR.setText(solid.r == null ? "" : solid.r.toString());
            this.tG.setText(solid.g == null ? "" : solid.g.toString());
            this.tB.setText(solid.b == null ? "" : solid.b.toString());
            this.tA.setText(solid.a == null ? "" : solid.a.toString());
            this.fields.add(this.tChannels);
        }

        this.resizeParent();
    }

    private void setCollisionTextureSize(Link link)
    {
        Texture texture = BBSModClient.getTextures().getTexture(link);
        this.component.textureWidth = texture.width;
        this.component.textureHeight = texture.height;
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_COLLISION_APPEARANCE_TITLE;
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);

        this.tinting = scheme.getOrCreate(ParticleComponentCollisionTinting.class);
        Arrays.fill(this.tintCache, null);

        if (this.tinting.color instanceof Solid)
        {
            Solid solid = (Solid) this.tinting.color;
            this.previousTintMode = solid.isConstant() ? 0 : 1;
            this.tintMode.setValue(this.previousTintMode);
        }
        else if (this.tinting.color instanceof Gradient)
        {
            this.previousTintMode = 2;
            this.tintMode.setValue(2);
        }

        this.fillTintData();
    }

    @Override
    public void beforeSave(ParticleScheme scheme)
    {
        this.component.enabled = this.enabled.getValue() ? MolangParser.ONE : MolangParser.ZERO;
        this.tinting.enabled = this.tintingEnabled.getValue() ? MolangParser.ONE : MolangParser.ZERO;
    }

    @Override
    protected ParticleComponentCollisionAppearance getComponent(ParticleScheme scheme)
    {
        return scheme.getOrCreate(ParticleComponentCollisionAppearance.class);
    }

    @Override
    protected void fillData()
    {
        this.enabled.setValue(MolangExpression.isOne(this.component.enabled));
        this.lit.setValue(this.component.lit);
        this.material.setValue(this.component.material.ordinal());
        this.facingMode.setValue(this.component.facing.ordinal());

        this.directionMode.setValue(this.component.directionMode == BillboardDirection.CUSTOM_DIRECTION ? 1 : 0);

        this.speedThreshold.setValue(this.component.speedThreshold);

        if (this.component.customDirection != null)
        {
            this.customDirX.setText(this.component.customDirection[0] == null ? "" : this.component.customDirection[0].toString());
            this.customDirY.setText(this.component.customDirection[1] == null ? "" : this.component.customDirection[1].toString());
            this.customDirZ.setText(this.component.customDirection[2] == null ? "" : this.component.customDirection[2].toString());
        }

        int modeVal = this.component.fullTexture ? 2 : (this.component.flipbook ? 1 : 0);
        this.textureMode.setValue(modeVal);

        this.sizeW.setText(this.component.sizeW == null ? "" : this.component.sizeW.toString());
        this.sizeH.setText(this.component.sizeH == null ? "" : this.component.sizeH.toString());
        this.uvX.setText(this.component.uvX == null ? "" : this.component.uvX.toString());
        this.uvY.setText(this.component.uvY == null ? "" : this.component.uvY.toString());
        this.uvW.setText(this.component.uvW == null ? "" : this.component.uvW.toString());
        this.uvH.setText(this.component.uvH == null ? "" : this.component.uvH.toString());

        this.stepX.setValue(this.component.stepX);
        this.stepY.setValue(this.component.stepY);
        this.fps.setValue(this.component.fps);
        this.max.setText(this.component.maxFrame == null ? "" : this.component.maxFrame.toString());
        this.stretch.setValue(this.component.stretchFPS);
        this.loop.setValue(this.component.loop);

        if (this.tinting != null)
        {
            this.tintingEnabled.setValue(MolangExpression.isOne(this.tinting.enabled));
        }

        this.updateFlipbookVisibility();
        this.fillTintData();
    }
}
