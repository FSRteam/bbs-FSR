package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleMaterial;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionAppearance;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

/**
 * Collision appearance section — configures the alternate billboard
 * appearance shown when a particle has collided.
 */
public class UIParticleSchemeCollisionAppearanceSection extends UIParticleSchemeComponentSection<ParticleComponentCollisionAppearance>
{
    public UIToggle enabled;
    public UIToggle lit;
    public UICirculate material;
    public UITextbox sizeW;
    public UITextbox sizeH;
    public UITextbox uvX;
    public UITextbox uvY;
    public UITextbox uvW;
    public UITextbox uvH;

    public UIParticleSchemeCollisionAppearanceSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.enabled = new UIToggle(UIKeys.SNOWSTORM_COLLISION_APPEARANCE_ENABLED, (b) -> this.editor.dirty());
        this.lit = new UIToggle(UIKeys.SNOWSTORM_COLLISION_APPEARANCE_LIT, (b) ->
        {
            this.component.lit = b.getValue();
            this.editor.dirty();
        });

        this.material = new UICirculate((b) ->
        {
            this.component.material = ParticleMaterial.values()[this.material.getValue()];
            this.editor.dirty();
        });
        this.material.addLabel(IKey.constant("Opaque"));
        this.material.addLabel(IKey.constant("Alpha"));
        this.material.addLabel(IKey.constant("Blend"));
        this.material.addLabel(IKey.constant("Additive"));

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

        this.fields.add(this.enabled);
        this.fields.add(this.lit);
        this.fields.add(this.material);
        this.fields.add(this.labeledField(IKey.constant("Width"), this.sizeW));
        this.fields.add(this.labeledField(IKey.constant("Height"), this.sizeH));
        this.fields.add(this.labeledField(IKey.constant("UV X"), this.uvX));
        this.fields.add(this.labeledField(IKey.constant("UV Y"), this.uvY));
        this.fields.add(this.labeledField(IKey.constant("UV W"), this.uvW));
        this.fields.add(this.labeledField(IKey.constant("UV H"), this.uvH));
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_COLLISION_APPEARANCE_TITLE;
    }

    @Override
    public void beforeSave(ParticleScheme scheme)
    {
        this.component.enabled = this.enabled.getValue() ? MolangParser.ONE : MolangParser.ZERO;
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
        this.sizeW.setText(this.component.sizeW == null ? "" : this.component.sizeW.toString());
        this.sizeH.setText(this.component.sizeH == null ? "" : this.component.sizeH.toString());
        this.uvX.setText(this.component.uvX == null ? "" : this.component.uvX.toString());
        this.uvY.setText(this.component.uvY == null ? "" : this.component.uvY.toString());
        this.uvW.setText(this.component.uvW == null ? "" : this.component.uvW.toString());
        this.uvH.setText(this.component.uvH == null ? "" : this.component.uvH.toString());
    }
}
