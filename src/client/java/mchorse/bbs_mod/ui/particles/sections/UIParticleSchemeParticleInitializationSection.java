package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentParticleInitialization;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

public class UIParticleSchemeParticleInitializationSection extends UIParticleSchemeComponentSection<ParticleComponentParticleInitialization>
{
    public UITextbox expression;

    public UIParticleSchemeParticleInitializationSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.expression = new UITextbox(10000, (str) ->
        {
            this.component.perUpdate = this.parse(str, this.component.perUpdate);
            this.editor.markUndoBoundary();
        });
        this.expression.placeholder(UIKeys.SNOWSTORM_EXPRESSION);
        this.expression.tooltip(UIKeys.SNOWSTORM_PARTICLE_EXPRESSION_TOOLTIP);

        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_PARTICLE_EXPRESSION_TITLE, this.expression));
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_PARTICLE_EXPRESSION_TITLE;
    }

    @Override
    protected ParticleComponentParticleInitialization getComponent(ParticleScheme scheme)
    {
        return scheme.getOrCreate(ParticleComponentParticleInitialization.class);
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        this.expression.setText(this.component.perUpdate == null ? "" : this.component.perUpdate.toString());
    }
}
