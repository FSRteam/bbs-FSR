package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.meta.ParticleComponentInitialization;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

public class UIParticleSchemeInitializationSection extends UIParticleSchemeComponentSection<ParticleComponentInitialization>
{
    public UITextbox create;
    public UITextbox update;

    public UIParticleSchemeInitializationSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.create = new UITextbox(10000, (str) ->
        {
            this.component.creation = this.parse(str, this.component.creation);
            this.editor.markUndoBoundary();
        });
        this.create.placeholder(UIKeys.SNOWSTORM_INITIALIZATION_CREATION);
        this.create.tooltip(UIKeys.SNOWSTORM_INITIALIZATION_CREATION_TOOLTIP);

        this.update = new UITextbox(10000, (str) ->
        {
            this.component.update = this.parse(str, this.component.update);
            this.editor.markUndoBoundary();
        });
        this.update.placeholder(UIKeys.SNOWSTORM_INITIALIZATION_UPDATE);
        this.update.tooltip(UIKeys.SNOWSTORM_INITIALIZATION_UPDATE_TOOLTIP);

        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_INITIALIZATION_CREATION, this.create));
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_INITIALIZATION_UPDATE, this.update));
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_INITIALIZATION_TITLE;
    }

    @Override
    protected ParticleComponentInitialization getComponent(ParticleScheme scheme)
    {
        return this.scheme.getOrCreate(ParticleComponentInitialization.class);
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        this.create.setText(this.component.creation == null ? "" : this.component.creation.toString());
        this.update.setText(this.component.update == null ? "" : this.component.update.toString());
    }
}
