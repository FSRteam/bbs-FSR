package mchorse.bbs_mod.api.client.ui;

public final class BBSUiClipPop implements BBSUiDrawCommand
{
    public static final BBSUiClipPop INSTANCE = new BBSUiClipPop();

    private BBSUiClipPop()
    {}

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.CLIP_POP;
    }
}
