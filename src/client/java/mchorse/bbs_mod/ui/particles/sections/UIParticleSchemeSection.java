package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.UISectionStateManager;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.function.Consumer;

public abstract class UIParticleSchemeSection extends UIElement
{
    protected static final int FIELD_LABEL_WIDTH = 76;

    public UILabel title;
    public UIElement fields;

    protected ParticleScheme scheme;
    protected UIParticleSchemePanel editor;

    protected boolean collapsed;

    public UIParticleSchemeSection(UIParticleSchemePanel editor)
    {
        super();

        this.editor = editor;
        this.title = UI.label(this.getTitle()).background(() -> BBSSettings.primaryColor.get() | Colors.A100);
        this.fields = new UIElement();
        this.fields.column().stretch().vertical().height(20);

        this.column().stretch().vertical();

        UISectionStateManager.setDefaultState(this.getClassId(), false);
        this.collapseState();
    }

    public String getClassId()
    {
        return this.getClass().getSimpleName();
    }

    protected void collapseState()
    {
        this.collapsed = UISectionStateManager.isCollapsed(this.getClassId());

        if (this.collapsed)
        {
            this.add(this.title);
        }
        else
        {
            this.add(this.title, this.fields);
        }
    }

    /**
     * Apply a collapsed state programmatically (used by layout presets).
     */
    public void applyCollapsedState(boolean collapsed)
    {
        this.collapsed = collapsed;
        UISectionStateManager.setCollapsed(this.getClassId(), collapsed);

        if (collapsed)
        {
            this.fields.removeFromParent();
        }
        else
        {
            if (!this.fields.hasParent())
            {
                this.add(this.fields);
            }
        }

        this.resizeParent();
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        Icon icon = this.collapsed ? Icons.ARROW_RIGHT : Icons.ARROW_DOWN;
        context.batcher.icon(icon, this.title.area.ex() - 18, this.title.area.y + (this.title.area.h - 16) / 2);
    }

    protected void resizeParent()
    {
        this.getParent().resize();
    }

    public UIParticleSchemePanel getEditor()
    {
        return this.editor;
    }

    protected UILabel fieldLabel(IKey label)
    {
        UILabel element = UI.label(label, 20).labelAnchor(0, 0.5F);

        element.w(FIELD_LABEL_WIDTH);

        return element;
    }

    protected UIElement labeledField(IKey label, UIElement field)
    {
        return UI.row(5, 0, 20, this.fieldLabel(label), field);
    }

    public void dirty()
    {
        this.editor.dirty();
    }

    public abstract IKey getTitle();

    protected UITextbox molangField(IKey placeholder, IKey tooltip, Consumer<String> callback, MolangExpression expression)
    {
        UITextbox textbox = new UITextbox(10000, (str) ->
        {
            if (callback != null)
            {
                callback.accept(str);
            }

            this.editor.markUndoBoundary();
        });
        textbox.placeholder(placeholder);
        if (tooltip != null) textbox.tooltip(tooltip);
        textbox.setText(expression == null ? "" : expression.toString());
        return textbox;
    }

    public MolangExpression parse(String string, MolangExpression old)
    {
        if (string.isEmpty())
        {
            return MolangParser.ZERO;
        }

        try
        {
            MolangExpression expression = this.scheme.parser.parseExpression(string);

            this.editor.dirty();

            return expression;
        }
        catch (Exception e)
        {}

        return old;
    }

    public ParticleScheme getScheme()
    {
        return this.scheme;
    }

    public void setScheme(ParticleScheme scheme)
    {
        this.scheme = scheme;
    }

    public void beforeSave(ParticleScheme scheme)
    {}

    /**
     * Toggle visibility of the field section
     */
    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.title.area.isInside(context))
        {
            if (this.fields.hasParent())
            {
                this.fields.removeFromParent();
                this.collapsed = true;
                UISectionStateManager.setCollapsed(this.getClassId(), true);
            }
            else
            {
                this.add(this.fields);
                this.collapsed = false;
                UISectionStateManager.setCollapsed(this.getClassId(), false);
            }

            this.resizeParent();

            return true;
        }

        return super.subMouseClicked(context);
    }
}
