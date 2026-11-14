package mchorse.bbs_mod.ui.particles.utils;

import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.particles.UIParticleSchemePanel;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Inline particle expression with an optional docked editor for longer code. */
public class UIMolangExpression extends UITextbox
{
    private final Supplier<MolangExpression> expression;

    public UIMolangExpression(Supplier<MolangExpression> expression, Consumer<String> callback)
    {
        super(10000, callback);

        this.expression = expression;
        this.icon(Icons.CODE);
    }

    @Override
    public UIMolangExpression icon(Icon icon)
    {
        super.icon(icon);

        return this;
    }

    @Override
    public UIMolangExpression barColor(int color)
    {
        super.barColor(color);

        return this;
    }

    public void editor(UIParticleSchemePanel panel, String id)
    {
        this.context((menu) -> menu.action(Icons.CODE, UIKeys.SNOWSTORM_PANELS_MOLANG, () ->
            panel.editMoLang(id, (text) ->
            {
                this.callback.accept(text);
                this.refresh();
            }, this.expression.get())));
    }

    public void refresh()
    {
        MolangExpression value = this.expression.get();

        this.setText(value == null ? "" : value.toString());
    }
}
