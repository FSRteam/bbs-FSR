package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRate;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateInstant;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateManual;
import mchorse.bbs_mod.particles.components.rate.ParticleComponentRateSteady;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcons;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;

public class UIParticleSchemeRateSection extends UIParticleSchemeModeSection<ParticleComponentRate>
{
    public UITextbox rate;
    public UITextbox particles;
    public UIElement rateRow;

    public UIParticleSchemeRateSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.rate = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentRateSteady)
            {
                ParticleComponentRateSteady comp = (ParticleComponentRateSteady) this.component;
                comp.spawnRate = this.parse(str, comp.spawnRate);
                this.editor.markUndoBoundary();
            }
        });
        this.rate.placeholder(UIKeys.SNOWSTORM_RATE_RATE);
        this.rate.icon(Icons.SPRAY);
        this.rate.tooltip(UIKeys.SNOWSTORM_RATE_SPAWN_RATE);

        this.particles = new UITextbox(10000, (str) ->
        {
            this.component.particles = this.parse(str, this.component.particles);
            this.editor.markUndoBoundary();
        });
        this.particles.placeholder(UIKeys.SNOWSTORM_RATE_AMOUNT);
        this.particles.icon(Icons.PARTICLE);

        this.rateRow = this.labeledField(UIKeys.SNOWSTORM_RATE_RATE, this.rate);

        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_RATE_AMOUNT, this.particles));
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_RATE_TITLE;
    }

    @Override
    protected void fillModes(UIIcons button)
    {
        button.add(Icons.BULLET, UIKeys.SNOWSTORM_RATE_INSTANT);
        button.add(Icons.TIME, UIKeys.SNOWSTORM_RATE_STEADY);
        button.add(Icons.EDIT, UIKeys.SNOWSTORM_RATE_MANUAL);
    }

    @Override
    protected void restoreInfo(ParticleComponentRate component, ParticleComponentRate old)
    {
        component.particles = old.particles;
    }

    @Override
    protected Class<ParticleComponentRate> getBaseClass()
    {
        return ParticleComponentRate.class;
    }

    @Override
    protected Class getDefaultClass()
    {
        return ParticleComponentRateInstant.class;
    }

    @Override
    protected Class getModeClass(int value)
    {
        if (value == 0)
        {
            return ParticleComponentRateInstant.class;
        }

        return value == 1 ? ParticleComponentRateSteady.class : ParticleComponentRateManual.class;
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        this.updateVisibility();

        this.particles.tooltip(this.isInstant()
            ? UIKeys.SNOWSTORM_RATE_PARTICLES
            : UIKeys.SNOWSTORM_RATE_MAX_PARTICLES);

        if (this.isSteady())
        {
            ParticleComponentRateSteady comp = (ParticleComponentRateSteady) this.component;
            this.rate.setText(comp.spawnRate == null ? "" : comp.spawnRate.toString());
        }

        this.particles.setText(this.component.particles == null ? "" : this.component.particles.toString());
    }

    private void updateVisibility()
    {
        if (!this.isSteady())
        {
            this.rateRow.removeFromParent();
        }
        else if (!this.rateRow.hasParent())
        {
            this.fields.add(this.rateRow);
        }

        this.resizeParent();
    }

    private boolean isInstant()
    {
        return this.component instanceof ParticleComponentRateInstant;
    }

    private boolean isSteady()
    {
        return this.component instanceof ParticleComponentRateSteady;
    }
}
