package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

public class UIParticleSchemeEventsSection extends UIParticleSchemeSection
{
    public UIParticleSchemeEventsSection(UIParticleSchemePanel parent)
    {
        super(parent);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_EVENTS_TITLE;
    }
}
