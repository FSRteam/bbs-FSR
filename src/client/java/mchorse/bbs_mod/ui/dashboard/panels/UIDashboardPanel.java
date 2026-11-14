package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.client.rendering.context.IBbsWorldRenderContext;

import java.util.ArrayList;
import java.util.List;

public class UIDashboardPanel extends UIElement
{
    public final UIDashboard dashboard;
    private final List<Runnable> opening = new ArrayList<>();
    private final List<Runnable> closing = new ArrayList<>();
    private final List<Runnable> appearing = new ArrayList<>();
    private final List<Runnable> disappearing = new ArrayList<>();

    public UIDashboardPanel(UIDashboard dashboard)
    {
        super();

        this.dashboard = dashboard;
        this.markContainer();
    }

    public boolean needsBackground()
    {
        return true;
    }

    public boolean canToggleVisibility()
    {
        return true;
    }

    public boolean canPause()
    {
        return true;
    }

    public boolean canRefresh()
    {
        return true;
    }

    protected void onOpen(Runnable step)
    {
        this.opening.add(step);
    }

    protected void onClose(Runnable step)
    {
        this.closing.add(step);
    }

    protected void onAppear(Runnable step)
    {
        this.appearing.add(step);
    }

    protected void onDisappear(Runnable step)
    {
        this.disappearing.add(step);
    }

    /* These remain virtual for addons compiled against the existing FSR panel API.
     * Core panels register constructor callbacks so saving does not depend on super calls. */
    public void appear()
    {
        this.run(this.appearing, false);
    }

    public void disappear()
    {
        this.run(this.disappearing, true);
    }

    public void open()
    {
        this.run(this.opening, false);
    }

    public void close()
    {
        this.run(this.closing, true);
    }

    private void run(List<Runnable> steps, boolean childFirst)
    {
        for (int i = 0; i < steps.size(); i++)
        {
            steps.get(childFirst ? steps.size() - 1 - i : i).run();
        }
    }

    public void update()
    {}

    public void setDashboardChromeHiddenAmount(float amount)
    {}

    public boolean isDashboardChromeHovered(int mouseX, int mouseY)
    {
        return false;
    }

    public void startRenderFrame(float tickDelta)
    {}

    public void renderInWorld(IBbsWorldRenderContext context)
    {}

    public void renderPanelBackground(UIContext context)
    {}
}
