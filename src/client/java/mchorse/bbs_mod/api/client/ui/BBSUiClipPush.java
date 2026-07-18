package mchorse.bbs_mod.api.client.ui;

public final class BBSUiClipPush implements BBSUiDrawCommand
{
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    public BBSUiClipPush(int x, int y, int width, int height)
    {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    @Override
    public BBSUiDrawCommandType type()
    {
        return BBSUiDrawCommandType.CLIP_PUSH;
    }

    public int x()
    {
        return this.x;
    }

    public int y()
    {
        return this.y;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }
}
