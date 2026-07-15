package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.components.motion.MotionComponents;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;

/**
 * Shared independent dynamic/parametric mode selector for one motion axis.
 */
public abstract class UIParticleSchemeMotionAxisSection extends UIParticleSchemeSection
{
    public UICirculate mode;

    protected UIParticleSchemeMotionAxisSection sibling;

    public UIParticleSchemeMotionAxisSection(UIParticleSchemePanel parent)
    {
        super(parent);

        this.mode = new UICirculate((b) -> this.changeMode(this.mode.getValue() == 1));
        this.mode.addLabel(UIKeys.SNOWSTORM_MOTION_DYNAMIC);
        this.mode.addLabel(UIKeys.SNOWSTORM_MOTION_PARAMETRIC);
        this.fields.add(this.labeledField(UIKeys.SNOWSTORM_MODE, this.mode));
    }

    public void link(UIParticleSchemeMotionAxisSection sibling)
    {
        this.sibling = sibling;
    }

    private void changeMode(boolean parametric)
    {
        this.applyMode(parametric);
        this.refresh();

        if (this.sibling != null)
        {
            this.sibling.refresh();
        }

        this.dirty();

        /* Existing particles retain their manual-axis flags, so structural changes need a restart. */
        this.editor.renderer.setScheme(this.scheme);
    }

    private void refresh()
    {
        if (this.scheme != null)
        {
            this.setScheme(this.scheme);
        }
    }

    @Override
    public void setScheme(ParticleScheme scheme)
    {
        super.setScheme(scheme);

        if (scheme == null)
        {
            return;
        }

        MotionComponents.setModes(scheme, MotionComponents.isPositionParametric(scheme), MotionComponents.isRotationParametric(scheme));
        this.mode.setValue(this.isParametric() ? 1 : 0);
        this.fillFields();
        this.resizeParent();
    }

    protected abstract boolean isParametric();

    protected abstract void applyMode(boolean parametric);

    protected abstract void fillFields();
}
