package mchorse.bbs_mod.ui.framework.elements;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.function.Consumer;

/**
 * A collapsible section: a clickable header bar (a fold arrow and a title sitting
 * inside a tinted card) and a body of fields that toggles open and closed. Put any
 * parameters into {@link #fields} and stack sections inside a scroll view. State is
 * purely in-memory and resets when the panel is rebuilt.
 */
public class UISection extends UIElement
{
    private static final int HEADER_HEIGHT = 10;
    private static final int ARROW_SIZE = 8;

    public UILabel title;
    public UIElement fields;

    private boolean expanded = true;
    private Consumer<UISection> callback;

    public UISection()
    {
        this(IKey.EMPTY);
    }

    public UISection(IKey title)
    {
        super();

        this.title = new UILabel(title)
        {
            @Override
            public void render(UIContext context)
            {
                UISection.this.renderHeader(context, this);
            }
        };
        this.title.h(HEADER_HEIGHT);

        this.fields = new UIElement();
        this.fields.column().stretch().vertical().height(20);

        this.column(UIConstants.MARGIN).stretch().vertical().padding(4);
        this.add(this.title, this.fields);
    }

    public UISection title(IKey title)
    {
        this.title.label = title;

        return this;
    }

    /**
     * Notified whenever the fold state changes, so an owner can remember it —
     * panels are rebuilt from scratch on many editor actions, and a section
     * that always came back at its default would undo the user's fold.
     */
    public UISection onToggle(Consumer<UISection> callback)
    {
        this.callback = callback;

        return this;
    }

    public boolean isExpanded()
    {
        return this.expanded;
    }

    public void toggle()
    {
        this.setExpanded(!this.expanded);
    }

    public void setExpanded(boolean expanded)
    {
        if (this.expanded == expanded)
        {
            return;
        }

        this.expanded = expanded;

        if (expanded)
        {
            this.add(this.fields);
        }
        else
        {
            this.fields.removeFromParent();
        }

        if (this.callback != null)
        {
            this.callback.accept(this);
        }

        this.resizeParent();
    }

    public void resizeParent()
    {
        UIElement parent = this.getParent();

        if (parent == null)
        {
            return;
        }

        UIElement element = parent;

        while (element != null && !(element instanceof UIScrollView))
        {
            element = element.getParent();
        }

        (element == null ? parent : element).resize();
    }

    @Override
    public void render(UIContext context)
    {
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.raisedSurface());

        /* The block is the raised (light) surface, so inputs inside it drop to the deep surface to
         * stay readable - mirroring how the film editor scopes lightInputs for its dark panels. */
        boolean lightInputs = BBSSettings.lightInputs;

        BBSSettings.lightInputs = false;

        try
        {
            super.render(context);
        }
        finally
        {
            BBSSettings.lightInputs = lightInputs;
        }
    }

    private void renderHeader(UIContext context, UILabel title)
    {
        Area header = title.area;
        FontRenderer font = context.batcher.getFont();

        this.renderArrow(context, header.ex() - ARROW_SIZE / 2F, header.my());

        String label = font.limitToWidth(title.label.get(), header.w - ARROW_SIZE - 2);

        context.batcher.textShadow(label, header.x, header.my() - font.getHeight() / 2, title.color);
    }

    /**
     * Draw {@link Icons#ARROW_SMALL} centred at {@code cx}/{@code cy}, rotated for the open state.
     */
    private void renderArrow(UIContext context, float cx, float cy)
    {
        PoseStack matrices = context.batcher.getContext().pose();

        matrices.pushPose();
        matrices.translate(cx, cy, 0F);
        matrices.rotateAround(Axis.ZP.rotationDegrees(this.expanded ? 90F : 0F), 0F, 0F, 0F);
        context.batcher.icon(Icons.ARROW_SMALL, Colors.WHITE, 0, 0, 0.5F, 0.5F);
        matrices.popPose();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.title.area.isInside(context))
        {
            this.toggle();

            return true;
        }

        return super.subMouseClicked(context);
    }
}
