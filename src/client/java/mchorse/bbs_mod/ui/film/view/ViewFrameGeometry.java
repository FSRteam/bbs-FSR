package mchorse.bbs_mod.ui.film.view;

/** Maps an output camera frame into a dock without changing its projection. */
public record ViewFrameGeometry(int x, int y, int width, int height)
{
    public static ViewFrameGeometry fit(int x, int y, int width, int height, int outputWidth, int outputHeight,
        float zoom, float panX, float panY)
    {
        int availableWidth = Math.max(1, width);
        int availableHeight = Math.max(1, height);
        double aspect = Math.max(1, outputWidth) / (double) Math.max(1, outputHeight);
        double fittedWidth = Math.min(availableWidth, availableHeight * aspect);
        double fittedHeight = fittedWidth / aspect;
        float scale = Float.isFinite(zoom) ? Math.max(0.25F, Math.min(8F, zoom)) : 1F;
        int frameWidth = Math.max(1, (int) Math.round(fittedWidth * scale));
        int frameHeight = Math.max(1, (int) Math.round(fittedHeight * scale));
        int frameX = x + (availableWidth - frameWidth) / 2 + Math.round(availableWidth * finitePan(panX));
        int frameY = y + (availableHeight - frameHeight) / 2 + Math.round(availableHeight * finitePan(panY));

        return new ViewFrameGeometry(frameX, frameY, frameWidth, frameHeight);
    }

    public boolean contains(int mouseX, int mouseY, int clipX, int clipY, int clipWidth, int clipHeight)
    {
        return mouseX >= Math.max(this.x, clipX) && mouseX < Math.min(this.x + this.width, clipX + clipWidth)
            && mouseY >= Math.max(this.y, clipY) && mouseY < Math.min(this.y + this.height, clipY + clipHeight);
    }

    private static float finitePan(float value)
    {
        return Float.isFinite(value) ? Math.max(-1F, Math.min(1F, value)) : 0F;
    }
}
