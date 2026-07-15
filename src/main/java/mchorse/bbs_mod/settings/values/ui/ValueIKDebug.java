package mchorse.bbs_mod.settings.values.ui;

public class ValueIKDebug extends ValueModelDebug
{
    public final ValueDebugElement tip = this.element("tip", false, 0x4da3ff, 0.1F, ValueDebugElement.SHAPE_CROSS);
    public final ValueDebugElement target = this.element("target", 0xffffff, 0.15F, ValueDebugElement.SHAPE_DIAMOND);
    public final ValueDebugElement pole = this.element("pole", 0xff8c26, 0.05F, ValueDebugElement.SHAPE_CUBE);

    public ValueIKDebug(String id)
    {
        super(id, false, 0xe6ebf2, 0.05F, false, 0xe6ebf2, 0.07F);
    }
}
