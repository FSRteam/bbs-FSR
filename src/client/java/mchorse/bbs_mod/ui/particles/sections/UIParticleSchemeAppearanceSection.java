package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.CameraFacing;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceLighting;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UI;

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
            this.facingMode.addLabel(IKey.literal(facing.id));
        }

        /* Direction sub-controls */
        this.directionMode = new UICirculate((b) ->
        {
            this.component.directionMode = this.directionMode.getValue() == 0 ? "derive_from_velocity" : "custom";
            this.updateDirectionVisibility();
            this.editor.dirty();
        });
        this.directionMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_DERIVE);
        this.directionMode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_CUSTOM);

        this.speedThreshold = new UITrackpad((v) ->
        {
            this.component.speedThreshold = v.floatValue();
            this.editor.dirty();
        }).decimalSingle();

        this.customDirX = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[0] = this.parse(str, this.component.customDirection[0]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirX.placeholder(IKey.str("X"));

        this.customDirY = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[1] = this.parse(str, this.component.customDirection[1]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirY.placeholder(IKey.str("Y"));

        this.customDirZ = new UITextbox(10000, (str) ->
        {
            if (this.component.customDirection != null)
            {
                this.component.customDirection[2] = this.parse(str, this.component.customDirection[2]);
            }
            this.editor.markUndoBoundary();
        });
        this.customDirZ.placeholder(IKey.str("Z"));

        this.directionFields = new UIElement();
        this.directionFields.column().vertical().stretch();
        this.directionFields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_DIRECTION_MODE, 20).labelAnchor(0, 1F));
        this.directionFields.add(this.directionMode);
        this.directionFields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_SPEED_THRESHOLD, this.speedThreshold));
        this.directionFields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_CUSTOM_DIRECTION, 20).labelAnchor(0, 1F));
        this.directionFields.add(this.labeledField(IKey.str("X"), this.customDirX));
        this.directionFields.add(this.labeledField(IKey.str("Y"), this.customDirY));
        this.directionFields.add(this.labeledField(IKey.str("Z"), this.customDirZ));

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

        this.fields.add(UI.row(5, 0, 20, this.modeLabel, this.mode));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_FACING, 20).labelAnchor(0, 1F));
        this.fields.add(this.facingMode);
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_SIZE, 20).labelAnchor(0, 1F));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_WIDTH, this.sizeW));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_HEIGHT, this.sizeH));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_MAPPING, 20).labelAnchor(0, 1F));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_X, this.uvX));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_Y, this.uvY));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_W, this.uvW));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_APPEARANCE_UV_H, this.uvH));
    }

    private void updateElements()
    {
        this.flipbook.removeFromParent();
        this.directionFields.removeFromParent();

        if (this.component.flipbook)
        {
            this.fields.add(this.flipbook);
        }

        if (!this.component.fullTexture)
        {
            /* UV fields already visible in fields, no extra action needed */
        }

        this.updateDirectionVisibility();
        this.resizeParent();
    }

    private void updateDirectionVisibility()
    {
        this.directionFields.removeFromParent();

        boolean showDirection = this.component.facing == CameraFacing.LOOKAT_DIRECTION
            || this.component.facing == CameraFacing.DIRECTION_X
            || this.component.facing == CameraFacing.DIRECTION_Y
            || this.component.facing == CameraFacing.DIRECTION_Z;

        if (showDirection)
        {
            this.fields.add(this.directionFields);
        }

        this.speedThreshold.setVisible("derive_from_velocity".equals(this.component.directionMode));
        this.customDirX.setVisible("custom".equals(this.component.directionMode));
        this.customDirY.setVisible("custom".equals(this.component.directionMode));
        this.customDirZ.setVisible("custom".equals(this.component.directionMode));

        this.resizeParent();
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_APPEARANCE_TITLE;
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

        if (this.component.directionMode != null)
        {
            this.directionMode.setValue("derive_from_velocity".equals(this.component.directionMode) ? 0 : 1);
        }
        else
        {
            this.directionMode.setValue(0);
        }

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

        this.updateElements();
    }
}
