package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.Constant;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.math.molang.expressions.MolangValue;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.BillboardDirection;
import mchorse.bbs_mod.particles.components.appearance.CameraFacing;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceLighting;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceTinting;
import mchorse.bbs_mod.particles.components.appearance.colors.Gradient;
import mchorse.bbs_mod.particles.components.appearance.colors.Solid;
import mchorse.bbs_mod.particles.components.appearance.colors.Tint;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UIColor;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.utils.UIGradientEditor;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Color;

import java.util.Arrays;

public class UIParticleSchemeAppearanceSection extends UIParticleSchemeComponentSection<ParticleComponentAppearanceBillboard>
{
    public UICirculate mode;
    public UILabel modeLabel;

    public UICirculate facingMode;

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

    /* Color & Lighting (integrated from LightingSection) */
    public UIToggle lightingToggle;
    public UICirculate colorMode;
    public UIColor colorPicker;
    public UITextbox colorR;
    public UITextbox colorG;
    public UITextbox colorB;
    public UITextbox colorA;
    public UIElement colorChannels;
    public UIElement colorSection;

    public UIGradientEditor gradientEditor;
    public UIColor gradientColor;
    public UITextbox gradientInterpolant;
    public UIElement gradient;

    private ParticleComponentAppearanceTinting tintingComponent;
    private Tint[] tintCache = new Tint[3];
    private int previousColorMode;

    public UIParticleSchemeAppearanceSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.mode = new UICirculate((b) ->
        {
            int val = this.mode.getValue();
            this.component.flipbook = val == 1;
            this.component.fullTexture = val == 2;
            this.updateElements();
            this.editor.dirty();
        });
        this.mode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_REGULAR);
        this.mode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_ANIMATED);
        this.mode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_FULL);
        this.modeLabel = UI.label(UIKeys.SNOWSTORM_MODE, 20).labelAnchor(0, 0.5F);

        /* Camera facing mode selector */
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
            this.editor.markUndoBoundary();
        });
        this.sizeW.placeholder(UIKeys.SNOWSTORM_APPEARANCE_WIDTH);

        this.sizeH = new UITextbox(10000, (str) ->
        {
            this.component.sizeH = this.parse(str, this.component.sizeH);
            this.editor.markUndoBoundary();
        });
        this.sizeH.placeholder(UIKeys.SNOWSTORM_APPEARANCE_HEIGHT);

        this.uvX = new UITextbox(10000, (str) ->
        {
            this.component.uvX = this.parse(str, this.component.uvX);
            this.editor.markUndoBoundary();
        });
        this.uvX.placeholder(UIKeys.GENERAL_X);
        this.uvX.tooltip(UIKeys.SNOWSTORM_APPEARANCE_UV_X);

        this.uvY = new UITextbox(10000, (str) ->
        {
            this.component.uvY = this.parse(str, this.component.uvY);
            this.editor.markUndoBoundary();
        });
        this.uvY.placeholder(UIKeys.GENERAL_Y);
        this.uvY.tooltip(UIKeys.SNOWSTORM_APPEARANCE_UV_Y);

        this.uvW = new UITextbox(10000, (str) ->
        {
            this.component.uvW = this.parse(str, this.component.uvW);
            this.editor.markUndoBoundary();
        });
        this.uvW.placeholder(UIKeys.SNOWSTORM_APPEARANCE_WIDTH);
        this.uvW.tooltip(UIKeys.SNOWSTORM_APPEARANCE_UV_W);

        this.uvH = new UITextbox(10000, (str) ->
        {
            this.component.uvH = this.parse(str, this.component.uvH);
            this.editor.markUndoBoundary();
        });
        this.uvH.placeholder(UIKeys.SNOWSTORM_APPEARANCE_HEIGHT);
        this.uvH.tooltip(UIKeys.SNOWSTORM_APPEARANCE_UV_H);

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
        this.stretch.tooltip(UIKeys.SNOWSTORM_APPEARANCE_STRETCH_TOOLTIP);
        this.loop = new UIToggle(UIKeys.SNOWSTORM_APPEARANCE_LOOP, (b) ->
        {
            this.component.loop = b.getValue();
            this.editor.dirty();
        });
        this.loop.tooltip(UIKeys.SNOWSTORM_APPEARANCE_LOOP_TOOLTIP);

        this.flipbook = new UIElement();
        this.flipbook.column().vertical().stretch();
        this.flipbook.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_ANIMATED, 20).labelAnchor(0, 1F));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_STEP_X, this.stepX));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_STEP_Y, this.stepY));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_FPS, this.fps));
        this.flipbook.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_FRAMES, this.max));
        this.flipbook.add(UI.row(5, 0, 20, this.stretch, this.loop));

        /* Color & Lighting controls */
        this.lightingToggle = new UIToggle(UIKeys.SNOWSTORM_LIGHTING_LIGHTING, (b) ->
        {
            if (b.getValue())
            {
                this.scheme.getOrCreate(ParticleComponentAppearanceLighting.class);
            }
            else
            {
                this.scheme.remove(ParticleComponentAppearanceLighting.class);
            }
            this.editor.dirty();
        });

        this.colorMode = new UICirculate((b) -> this.changeColorMode(b.getValue()));
        this.colorMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_SOLID);
        this.colorMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_EXPRESSION);
        this.colorMode.addLabel(UIKeys.SNOWSTORM_LIGHTING_GRADIENT);

        this.colorPicker = new UIColor((color) ->
        {
            Solid solid = this.getTintSolid();
            Color original = this.colorPicker.picker.color;
            solid.r = this.setTint(solid.r, original.r);
            solid.g = this.setTint(solid.g, original.g);
            solid.b = this.setTint(solid.b, original.b);
            solid.a = this.setTint(solid.a, original.a);
            this.editor.dirty();
        });
        this.colorPicker.withAlpha();

        this.colorR = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.r = this.parse(str, solid.r);
            this.editor.markUndoBoundary();
        });
        this.colorR.placeholder(IKey.constant("R"));
        this.colorR.tooltip(UIKeys.SNOWSTORM_LIGHTING_RED);

        this.colorG = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.g = this.parse(str, solid.g);
            this.editor.markUndoBoundary();
        });
        this.colorG.placeholder(IKey.constant("G"));
        this.colorG.tooltip(UIKeys.SNOWSTORM_LIGHTING_GREEN);

        this.colorB = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.b = this.parse(str, solid.b);
            this.editor.markUndoBoundary();
        });
        this.colorB.placeholder(IKey.constant("B"));
        this.colorB.tooltip(UIKeys.SNOWSTORM_LIGHTING_BLUE);

        this.colorA = new UITextbox(10000, (str) ->
        {
            Solid solid = this.getTintSolid();
            solid.a = this.parse(str, solid.a);
            this.editor.markUndoBoundary();
        });
        this.colorA.placeholder(IKey.constant("A"));
        this.colorA.tooltip(UIKeys.SNOWSTORM_LIGHTING_ALPHA);

        this.colorChannels = new UIElement();
        this.colorChannels.column().vertical().stretch().height(20);
        this.colorChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_RED, this.colorR));
        this.colorChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_GREEN, this.colorG));
        this.colorChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_BLUE, this.colorB));
        this.colorChannels.add(this.labeledField(UIKeys.SNOWSTORM_LIGHTING_ALPHA, this.colorA));

        this.gradientColor = new UIColor(this::setGradientColor).withAlpha();
        this.gradientEditor = new UIGradientEditor(this, this.gradientColor);
        this.gradientInterpolant = new UITextbox(10000, (str) ->
        {
            Gradient grad = (Gradient) this.tintingComponent.color;
            grad.interpolant = this.parse(str, grad.interpolant);
            this.editor.markUndoBoundary();
        });
        this.gradientInterpolant.placeholder(UIKeys.SNOWSTORM_LIGHTING_INTERPOLANT);
        this.gradient = this.labeledField(UIKeys.SNOWSTORM_LIGHTING_INTERPOLANT, this.gradientInterpolant);

        /* Layout: mode → color/lighting → size → UV → facing */
        this.fields.add(UI.row(5, 0, 20, this.modeLabel, this.mode));

        /* Color & Lighting right below mode */
        this.colorSection = new UIElement();
        this.colorSection.column().vertical().stretch();
        this.colorSection.add(this.lightingToggle);
        this.colorSection.add(UI.row(5, 0, 20, UI.label(UIKeys.SNOWSTORM_MODE, 20).labelAnchor(0, 0.5F), this.colorMode));
        this.fields.add(this.colorSection);

        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_SIZE, 20).labelAnchor(0, 1F));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_WIDTH, this.sizeW));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_HEIGHT, this.sizeH));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_MAPPING, 20).labelAnchor(0, 1F));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_X, this.uvX));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_Y, this.uvY));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_W, this.uvW));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_H, this.uvH));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_FACING, 20).labelAnchor(0, 1F));
        this.fields.add(this.facingMode);
    }

    private void changeColorMode(int value)
    {
        if (this.tintCache[this.previousColorMode] == null)
        {
            this.tintCache[this.previousColorMode] = this.tintingComponent.color;
        }

        Tint cached = this.tintCache[value];

        if (cached == null)
        {
            cached = value == 2 ? new Gradient() : new Solid();
            this.tintCache[value] = cached;
        }

        this.tintingComponent.color = cached;
        this.fillColorData();
        this.editor.dirty();
        this.previousColorMode = value;
    }

    private Solid getTintSolid()
    {
        return (Solid) this.tintingComponent.color;
    }

    private void setGradientColor(int color)
    {
        this.gradientEditor.setColor(color);
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

    private void fillColorData()
    {
        if (this.tintingComponent == null)
        {
            return;
        }

        this.gradientEditor.removeFromParent();
        this.gradient.removeFromParent();
        this.gradientColor.removeFromParent();
        this.colorPicker.removeFromParent();
        this.colorPicker.picker.removeFromParent();
        this.colorChannels.removeFromParent();

        if (this.colorMode.getValue() == 0)
        {
            Solid solid = (Solid) this.tintingComponent.color;
            this.colorPicker.picker.color.set((float) solid.r.get(), (float) solid.g.get(), (float) solid.b.get(), (float) solid.a.get());
            this.colorSection.add(this.colorPicker);
        }
        else if (this.colorMode.getValue() == 2)
        {
            this.gradientEditor.setGradient((Gradient) this.tintingComponent.color);
            this.colorSection.add(this.gradientEditor);
            this.colorSection.add(this.gradientColor);
            this.colorSection.add(this.gradient);

            Gradient grad = (Gradient) this.tintingComponent.color;
            this.gradientInterpolant.setText(grad.interpolant == null ? "" : grad.interpolant.toString());
        }
        else
        {
            Solid solid = (Solid) this.tintingComponent.color;
            this.colorR.setText(solid.r == null ? "" : solid.r.toString());
            this.colorG.setText(solid.g == null ? "" : solid.g.toString());
            this.colorB.setText(solid.b == null ? "" : solid.b.toString());
            this.colorA.setText(solid.a == null ? "" : solid.a.toString());
            this.colorSection.add(this.colorChannels);
        }

        this.resizeParent();
    }

    private void updateElements()
    {
        this.flipbook.removeFromParent();
        this.directionFields.removeFromParent();

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

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_APPEARANCE_TITLE;
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);

        this.tintingComponent = scheme.getOrCreate(ParticleComponentAppearanceTinting.class);
        Arrays.fill(this.tintCache, null);
        this.lightingToggle.setValue(scheme.get(ParticleComponentAppearanceLighting.class) != null);

        if (this.tintingComponent.color instanceof Solid)
        {
            Solid solid = (Solid) this.tintingComponent.color;
            this.previousColorMode = solid.isConstant() ? 0 : 1;
            this.colorMode.setValue(this.previousColorMode);
        }
        else if (this.tintingComponent.color instanceof Gradient)
        {
            this.previousColorMode = 2;
            this.colorMode.setValue(2);
        }

        this.fillColorData();
    }

    @Override
    public void beforeSave(ParticleScheme scheme)
    {
        if (this.lightingToggle.getValue())
        {
            scheme.getOrCreate(ParticleComponentAppearanceLighting.class);
        }
        else
        {
            scheme.remove(ParticleComponentAppearanceLighting.class);
        }
    }

    @Override
    protected ParticleComponentAppearanceBillboard getComponent(ParticleScheme scheme)
    {
        return scheme.getOrCreate(ParticleComponentAppearanceBillboard.class);
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        int modeVal = this.component.fullTexture ? 2 : (this.component.flipbook ? 1 : 0);
        this.mode.setValue(modeVal);
        this.facingMode.setValue(this.component.facing.ordinal());

        this.directionMode.setValue(this.component.directionMode == BillboardDirection.CUSTOM_DIRECTION ? 1 : 0);

        this.speedThreshold.setValue(this.component.speedThreshold);

        if (this.component.customDirection != null)
        {
            this.customDirX.setText(this.component.customDirection[0] == null ? "" : this.component.customDirection[0].toString());
            this.customDirY.setText(this.component.customDirection[1] == null ? "" : this.component.customDirection[1].toString());
            this.customDirZ.setText(this.component.customDirection[2] == null ? "" : this.component.customDirection[2].toString());
        }

        this.stepX.setValue(this.component.stepX);
        this.stepY.setValue(this.component.stepY);
        this.fps.setValue(this.component.fps);

        this.stretch.setValue(this.component.stretchFPS);
        this.loop.setValue(this.component.loop);

        this.sizeW.setText(this.component.sizeW == null ? "" : this.component.sizeW.toString());
        this.sizeH.setText(this.component.sizeH == null ? "" : this.component.sizeH.toString());
        this.uvX.setText(this.component.uvX == null ? "" : this.component.uvX.toString());
        this.uvY.setText(this.component.uvY == null ? "" : this.component.uvY.toString());
        this.uvW.setText(this.component.uvW == null ? "" : this.component.uvW.toString());
        this.uvH.setText(this.component.uvH == null ? "" : this.component.uvH.toString());
        this.max.setText(this.component.maxFrame == null ? "" : this.component.maxFrame.toString());

        if (this.tintingComponent != null)
        {
            this.lightingToggle.setValue(this.scheme.get(ParticleComponentAppearanceLighting.class) != null);
        }

        this.updateElements();
        this.fillColorData();
    }
}
