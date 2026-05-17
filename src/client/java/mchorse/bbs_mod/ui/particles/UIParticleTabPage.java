package mchorse.bbs_mod.ui.particles;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSection;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

public class UIParticleTabPage extends UIElement
{
    public final UIScrollView scrollView;
    public final List<UIParticleSchemeSection> sections = new ArrayList<>();

    public UIParticleTabPage()
    {
        super();

        this.scrollView = UI.scrollView(20, 10);
        this.scrollView.scroll.cancelScrolling().opposite().scrollSpeed *= 3;
        this.scrollView.column().stretch().vertical();

        this.add(this.scrollView);
    }

    public void addSection(UIParticleSchemeSection section)
    {
        this.sections.add(section);
        this.scrollView.add(section);
    }

    public void setScheme(ParticleScheme scheme)
    {
        for (UIParticleSchemeSection section : this.sections)
        {
            section.setScheme(scheme);
        }
    }

    @Override
    public void render(UIContext context)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.CONTROL_BAR);
        super.render(context);
    }
}
