package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetime;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeExpression;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeLooping;
import mchorse.bbs_mod.particles.components.lifetime.ParticleComponentLifetimeOnce;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

public class UIParticleSchemeLifetimeSection extends UIParticleSchemeModeSection<ParticleComponentLifetime>
{
    public UITextbox active;
    public UITextbox expiration;

    public UIParticleSchemeLifetimeSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.active = new UITextbox(10000, (str) ->
        {
            this.component.activeTime = this.parse(str, this.component.activeTime);
            this.editor.markUndoBoundary();
        });
        this.active.placeholder(UIKeys.SNOWSTORM_LIFETIME_TIME);
        this.active.tooltip(IKey.EMPTY);

        this.expiration = new UITextbox(10000, (str) ->
        {
            if (this.component instanceof ParticleComponentLifetimeLooping)
            {
                ParticleComponentLifetimeLooping component = (ParticleComponentLifetimeLooping) this.component;
                component.sleepTime = this.parse(str, component.sleepTime);
            }
            else
            {
                ParticleComponentLifetimeExpression component = (ParticleComponentLifetimeExpression) this.component;
                component.expiration = this.parse(str, component.expiration);
            }

            this.editor.markUndoBoundary();
        });
        this.expiration.placeholder(UIKeys.SNOWSTORM_EXPRESSION);
        this.expiration.tooltip(IKey.EMPTY);

        this.fields.add(this.active);
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.SNOWSTORM_LIFETIME_TITLE;
    }

    @Override
    protected void fillModes(UICirculate button)
    {
        button.addLabel(UIKeys.SNOWSTORM_LIFETIME_EXPRESSION);
        button.addLabel(UIKeys.SNOWSTORM_LIFETIME_LOOPING);
        button.addLabel(UIKeys.SNOWSTORM_LIFETIME_ONCE);
    }

    @Override
    protected void restoreInfo(ParticleComponentLifetime component, ParticleComponentLifetime old)
    {
        component.activeTime = old.activeTime;
    }

    @Override
    protected Class<ParticleComponentLifetime> getBaseClass()
    {
        return ParticleComponentLifetime.class;
    }

    @Override
    protected Class getDefaultClass()
    {
        return ParticleComponentLifetimeLooping.class;
    }

    @Override
    protected Class getModeClass(int value)
    {
        if (value == 0)
        {
            return ParticleComponentLifetimeExpression.class;
        }
        else if (value == 1)
        {
            return ParticleComponentLifetimeLooping.class;
        }

        return ParticleComponentLifetimeOnce.class;
    }

    @Override
    protected void fillData()
    {
        super.fillData();

        boolean once = this.component instanceof ParticleComponentLifetimeOnce;

        this.expiration.setVisible(!once);

        if (this.component instanceof ParticleComponentLifetimeExpression)
        {
            this.expiration.tooltip(UIKeys.SNOWSTORM_LIFETIME_EXPIRATION_EXPRESSION);
            this.active.tooltip(UIKeys.SNOWSTORM_LIFETIME_ACTIVE_EXPRESSION);
        }
        else if (this.component instanceof ParticleComponentLifetimeLooping)
        {
            this.expiration.tooltip(UIKeys.SNOWSTORM_LIFETIME_SLEEP_TIME);
            this.active.tooltip(UIKeys.SNOWSTORM_LIFETIME_ACTIVE_LOOPING);
        }
        else
        {
            this.active.tooltip(UIKeys.SNOWSTORM_LIFETIME_ACTIVE_ONCE);
        }

        this.active.setText(this.component.activeTime == null ? "" : this.component.activeTime.toString());

        if (this.component instanceof ParticleComponentLifetimeLooping)
        {
            ParticleComponentLifetimeLooping comp = (ParticleComponentLifetimeLooping) this.component;
            this.expiration.setText(comp.sleepTime == null ? "" : comp.sleepTime.toString());
        }
        else if (this.component instanceof ParticleComponentLifetimeExpression)
        {
            ParticleComponentLifetimeExpression comp = (ParticleComponentLifetimeExpression) this.component;
            this.expiration.setText(comp.expiration == null ? "" : comp.expiration.toString());
        }

        this.expiration.removeFromParent();

        if (!once)
        {
            this.fields.add(this.expiration);
        }

        this.resizeParent();
    }
}
