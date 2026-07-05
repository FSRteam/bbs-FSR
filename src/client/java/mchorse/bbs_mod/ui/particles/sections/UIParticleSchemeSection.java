package mchorse.bbs_mod.ui.particles.sections;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.particles.UISectionStateManager;
import mchorse.bbs_mod.ui.utils.UI;

import java.util.function.Consumer;

public abstract class UIParticleSchemeSection extends UISection
{
    protected static final int FIELD_LABEL_WIDTH = 76;

    protected ParticleScheme scheme;
    protected UIParticleSchemePanel editor;

    /* Per-field expand: label click shows full text and moves field to next line */
    private String expandedField = null;

    private void setExpanded(IKey label, boolean expanded)
    {
        this.expandedField = expanded ? label.get() : null;
    }

    public UIParticleSchemeSection(UIParticleSchemePanel editor)
    {
        super();

        this.editor = editor;
        this.title(this.getTitle());

        UISectionStateManager.setDefaultState(this.getClassId(), false);
        this.setExpanded(!UISectionStateManager.isCollapsed(this.getClassId()));
    }

    public String getClassId()
    {
        return this.getClass().getSimpleName();
    }

    /**
     * Apply a collapsed state programmatically (used by layout presets).
     */
    public void applyCollapsedState(boolean collapsed)
    {
        this.setExpanded(!collapsed);
    }

    /**
     * Persist the collapsed state across section rebuilds within the session.
     */
    @Override
    public void setExpanded(boolean expanded)
    {
        super.setExpanded(expanded);

        UISectionStateManager.setCollapsed(this.getClassId(), !expanded);
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

    /**
     * Create a labeled field row. By default the label is truncated to FIELD_LABEL_WIDTH.
     * Clicking the label expands it to show the full text and moves the input field to the next line.
     */
    protected UIElement labeledField(IKey label, UIElement field)
    {
        return new ExpandableLabeledField(this.fieldLabel(label), field, label, this);
    }

    /**
     * Labeled field that toggles between inline (truncated label + field on same row)
     * and expanded (full-width label on top row, field on next row) on label click.
     */
    private static class ExpandableLabeledField extends UIElement
    {
        private final UILabel label;
        private final UIElement field;
        private final IKey labelKey;
        private final UIParticleSchemeSection section;
        private boolean expanded;

        ExpandableLabeledField(UILabel label, UIElement field, IKey labelKey, UIParticleSchemeSection section)
        {
            this.label = label;
            this.field = field;
            this.labelKey = labelKey;
            this.section = section;
            this.expanded = false;

            this.row(5).height(20);
            this.add(this.label, this.field);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (this.label.area.isInside(context) && context.mouseButton == 0)
            {
                this.expanded = !this.expanded;
                this.section.setExpanded(this.labelKey, this.expanded);
                this.rebuildLayout();
                this.section.resizeParent();

                return true;
            }

            return super.subMouseClicked(context);
        }

        private void rebuildLayout()
        {
            this.removeAll();

            if (this.expanded)
            {
                /* Expanded: label takes full width on its own row, field on next row */
                this.label.w(0);
                this.column().vertical().stretch();
                this.add(this.label);
                this.add(this.field);
            }
            else
            {
                /* Inline: label truncated, field on same row */
                this.label.w(FIELD_LABEL_WIDTH);
                this.row(5).height(20);
                this.add(this.label, this.field);
            }
        }
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
}
