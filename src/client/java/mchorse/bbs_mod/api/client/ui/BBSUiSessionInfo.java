package mchorse.bbs_mod.api.client.ui;

public final class BBSUiSessionInfo
{
    private final long sessionId;
    private final int width;
    private final int height;
    private final int framebufferWidth;
    private final int framebufferHeight;

    public BBSUiSessionInfo(long sessionId, int width, int height, int framebufferWidth, int framebufferHeight)
    {
        this.sessionId = sessionId;
        this.width = width;
        this.height = height;
        this.framebufferWidth = framebufferWidth;
        this.framebufferHeight = framebufferHeight;
    }

    public long sessionId()
    {
        return this.sessionId;
    }

    public int width()
    {
        return this.width;
    }

    public int height()
    {
        return this.height;
    }

    public int framebufferWidth()
    {
        return this.framebufferWidth;
    }

    public int framebufferHeight()
    {
        return this.framebufferHeight;
    }
}
