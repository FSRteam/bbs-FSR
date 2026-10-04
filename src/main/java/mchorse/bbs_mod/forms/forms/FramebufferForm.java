package mchorse.bbs_mod.forms.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

public class FramebufferForm extends Form
{
    public final ValueInt width = new ValueInt("width", 512);
    public final ValueInt height = new ValueInt("height", 512);
    public final ValueFloat scale = new ValueFloat("scale", 0.5F);

    public FramebufferForm()
    {
        this.width.invisible();
        this.height.invisible();

        this.add(this.width);
        this.add(this.height);
        this.add(this.scale);
    }

    /**
     * Names the form "Framebuffer" instead of the raw type id "bbs:framebuffer". The form-list
     * header ({@code UIFormList#render}) and the palette search ({@code UIFormCategory#search})
     * both read this, and without it an unedited framebuffer form reads as a registry path.
     *
     * <p>It is <b>not</b> what a palette cell draws: the cell draws {@code Form.name}, the
     * user-set name, which is empty on a fresh form. A fresh cell is therefore blank — the
     * renderer paints no UI preview (see {@code FramebufferFormRenderer#renderInUI}) — and only
     * the selection outline marks it.</p>
     */
    @Override
    protected String getDefaultDisplayName()
    {
        return "Framebuffer";
    }
}
