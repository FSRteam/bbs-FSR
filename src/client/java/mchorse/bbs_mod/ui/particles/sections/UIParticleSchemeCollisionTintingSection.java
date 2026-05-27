package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.appearance.ParticleComponentCollisionTinting;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

/**
 * Collision tinting section — configures the alternate color tint
 * applied when a particle has collided.
 */
public class UIParticleSchemeCollisionTintingSection extends UIParticleSchemeComponentSection<ParticleComponentCollisionTinting>
{
    public UIToggle enabled;

    public UIParticleSchemeCollisionTintingSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.enabled = new UIToggle(UIKeys.SNOWSTORM_COLLISION_TINTING_ENABLED, (b) -> this.editor.dirty());

        this.fields.add(this.enabled);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_COLLISION_TINTING_TITLE;
    }

    @Override
    public void beforeSave(ParticleScheme scheme)
    {
        this.component.enabled = this.enabled.getValue() ? MolangParser.ONE : MolangParser.ZERO;
    }

    @Override
    protected ParticleComponentCollisionTinting getComponent(ParticleScheme scheme)
    {
        return scheme.getOrCreate(ParticleComponentCollisionTinting.class);
    }

    @Override
    protected void fillData()
    {
        this.enabled.setValue(mchorse.bbs_mod.math.molang.expressions.MolangExpression.isOne(this.component.enabled));
    }
}
