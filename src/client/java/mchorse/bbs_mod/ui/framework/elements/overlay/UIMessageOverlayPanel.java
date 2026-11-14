package mchorse.bbs_mod.ui.framework.elements.overlay;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;

public class UIMessageOverlayPanel extends UIOverlayPanel
{
    public static final int WIDTH = 280;
    protected static final int TOP = 12;
    protected static final int GAP = 10;

    public UIText message;
    protected UIElement bottom;
    private boolean fits = true;

    public UIMessageOverlayPanel(IKey title, IKey message)
    {
        super(title);

        this.message = new UIText().text(message).textAnchorX(0.5F);
        this.message.relative(this.content).x(0.5F).y(TOP).w(0.7F).anchorX(0.5F);

        this.content.add(this.message);
    }

    public void setMessage(IKey message)
    {
        this.message.text(message);
    }

    protected void fillsOverlay()
    {
        this.fits = false;
    }

    @Override
    public boolean isResizable()
    {
        return !this.fits;
    }

    @Override
    public int getPreferredWidth()
    {
        return this.fits ? WIDTH : super.getPreferredWidth();
    }

    @Override
    public int getContentHeight()
    {
        if (!this.fits || this.message.area.h <= 0)
        {
            return -1;
        }

        int height = this.content.getFlex().y.offset + TOP + this.message.area.h + GAP;

        if (this.bottom != null)
        {
            height += this.bottom.area.h - this.bottom.getFlex().y.offset;
        }

        return height;
    }
}
