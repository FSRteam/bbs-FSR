package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.UI;

public class UIParticleSchemeQuickSetupSection extends UIParticleSchemeSection
{
    public UIParticleSchemeQuickSetupSection(UIParticleSchemePanel parent)
    {
        super(parent);

        // Shape & Motion presets
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_SHAPE, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SPHERE, "sphere");
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_RAIN, "rain");
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_RING, "ring");

        // Timing presets
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_TIMING, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_BURST, "burst");
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_STEADY, "steady");

        // Physics presets
        this.fields.add(UI.label(UIKeys.SNOWSTORM_QUICK_SETUP_PHYSICS, 20).labelAnchor(0, 0.5F));
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_NONE, "none_physics");
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SOLID, "solid");
        this.addPresetButton(UIKeys.SNOWSTORM_QUICK_SETUP_SMOKE, "smoke");
    }

    private void addPresetButton(IKey label, String presetId)
    {
        UIButton button = new UIButton(label, (b) -> this.applyPreset(presetId));
        this.fields.add(button);
    }

    private void applyPreset(String presetId)
    {
        if (this.scheme == null)
        {
            return;
        }

        // TODO: Implement preset data loading from assets/particles/presets/
        // For now, presets are placeholder buttons
        this.editor.dirty();
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_QUICK_SETUP_TITLE;
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);
    }
}
