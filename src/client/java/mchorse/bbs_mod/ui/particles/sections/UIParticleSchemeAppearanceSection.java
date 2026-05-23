package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentAppearanceBillboard;
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
            this.component.flipbook = this.mode.getValue() == 1;
            this.updateElements();
            this.editor.dirty();
        });
        this.mode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_REGULAR);
        this.mode.addLabel(UIKeys.SNOWSTORM_APPEARANCE_ANIMATED);
        this.modeLabel = UI.label(UIKeys.SNOWSTORM_MODE, 20).labelAnchor(0, 0.5F);

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
        this.flipbook.add(UI.row(5, 0, 20, this.stepX, this.stepY));
        this.flipbook.add(UI.row(5, 0, 20, this.fps, this.max));
        this.flipbook.add(UI.row(5, 0, 20, this.stretch, this.loop));

        this.fields.add(UI.row(5, 0, 20, this.modeLabel, this.mode));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_SIZE, 20).labelAnchor(0, 1F));
        this.fields.add(UI.row(this.sizeW, this.sizeH));
        this.fields.add(UI.label(UIKeys.SNOWSTORM_APPEARANCE_MAPPING, 20).labelAnchor(0, 1F));
        this.fields.add(UI.row(this.uvX, this.uvY), UI.row(this.uvW, this.uvH));
    }

    private void updateElements()
    {
        this.flipbook.removeFromParent();

        if (this.component.flipbook)
        {
            this.fields.add(this.flipbook);
        }

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

        this.mode.setValue(this.component.flipbook ? 1 : 0);

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
