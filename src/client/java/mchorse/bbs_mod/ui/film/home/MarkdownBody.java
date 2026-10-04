package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * Selectable, scrollable Markdown body for detail overlays.
 *
 * <p>Lays out the document once per content/width change and renders it
 * clipped with vertical scrolling: the mouse wheel scrolls, a slim scrollbar
 * on the right edge can be grabbed, and when the document overflows a
 * left-button drag becomes a page-flip swipe (text drag-selection stays
 * available while the content fits). Markdown image slots stream through
 * {@link WebImages} with spinners.
 */
public class MarkdownBody extends UIElement
{
    private static final int SCROLL_SPEED = 44;

    private final String markdown;
    private UiMarkdown.Layout layout;
    private int layoutWidth = -1;
    private final UiMarkdown.Selection selection = new UiMarkdown.Selection();
    private boolean selecting;
    private boolean hasSelection;

    /* Scroll state (render thread only) */
    private int scrollY;
    private int scrollMax;

    /* Content swipe state */
    private boolean swiping;
    private int swipeStartY;
    private int swipeStartScroll;

    /* Scrollbar drag state, geometry cached during rendering */
    private boolean draggingBar;
    private int barX = -100;
    private int barY;
    private int barH;
    private int thumbY;
    private int thumbH;

    public MarkdownBody(String markdown)
    {
        this.markdown = markdown == null ? "" : markdown;

        this.add(new UIRenderable((ctx) -> this.draw(ctx)));
    }

    private int innerHeight()
    {
        return Math.max(0, this.area.h - 12);
    }

    private void clampScroll()
    {
        this.scrollY = Math.max(0, Math.min(this.scrollY, this.scrollMax));
    }

    private void draw(UIContext context)
    {
        /* 6px gutters plus a stable 8px right rail so the scrollbar never
         * covers text, whether or not this particular document overflows. */
        int w = this.area.w - 20;

        if (w <= 0)
        {
            return;
        }

        if (this.layout == null || this.layoutWidth != w)
        {
            this.layout = UiMarkdown.layout(context.batcher, this.markdown, w);
            this.layoutWidth = w;
            this.hasSelection = false;
            this.scrollY = 0;
        }

        int contentH = this.layout.height + 6;

        this.scrollMax = Math.max(0, contentH - this.innerHeight());
        this.clampScroll();

        boolean overflow = this.scrollMax > 0;

        if (this.selecting && Window.isMouseButtonPressed(0))
        {
            int[] hit = UiMarkdown.hitTest(context.batcher, this.layout, context.mouseX, context.mouseY, this.area.x + 6, this.area.y + 6 + this.scrollY);

            this.selection.focusLine = hit[0];
            this.selection.focusPos = hit[1];
            this.hasSelection = !this.selection.isEmpty();
        }

        if (this.swiping && Window.isMouseButtonPressed(0))
        {
            this.scrollY = this.swipeStartScroll - (context.mouseY - this.swipeStartY);
            this.clampScroll();
        }

        if (this.draggingBar && Window.isMouseButtonPressed(0) && this.scrollMax > 0 && this.barH > this.thumbH)
        {
            float fraction = (context.mouseY - this.barY - (this.thumbH / 2F)) / (float) (this.barH - this.thumbH);

            this.scrollY = Math.round(fraction * this.scrollMax);
            this.clampScroll();
        }

        for (UiMarkdown.ImageSlot slot : this.layout.images)
        {
            this.drawImageSlot(context, slot);
        }

        Area clip = new Area();

        clip.set(this.area.x, this.area.y, this.area.w, this.area.h);
        context.batcher.clip(clip, context);
        UiMarkdown.render(context, this.layout, this.area.x + 6, this.area.y + 6 - this.scrollY, this.hasSelection ? this.selection : null);
        context.batcher.unclip(context);

        if (overflow)
        {
            this.drawScrollbar(context);
        }
        else
        {
            this.barX = -100;
        }
    }

    private void drawImageSlot(UIContext context, UiMarkdown.ImageSlot slot)
    {
        int x = this.area.x + 6;
        int y = this.area.y + 6 + slot.y - this.scrollY;

        if (y + slot.height < this.area.y || y > this.area.ey())
        {
            return;
        }

        context.batcher.box(x, y, x + slot.width, y + slot.height, mchorse.bbs_mod.BBSSettings.chromeSurface());

        Texture texture = WebImages.get(slot.url);

        if (texture != null)
        {
            drawCover(context.batcher, texture, x, y, slot.width, slot.height);
        }
        else if (WebImages.isCoolingDown(slot.url))
        {
            String label = L10n.lang("bbs.ui.film.home.load_failed").get();

            context.batcher.textShadow(label, x + slot.width / 2F - context.batcher.getFont().getWidth(label) / 2F, y + slot.height / 2F - 4, mchorse.bbs_mod.BBSSettings.mutedTextColor());
        }
        else
        {
            WebImages.drawSpinner(context, x + slot.width / 2F, y + slot.height / 2F, mchorse.bbs_mod.BBSSettings.accentColorRGB());
        }
    }

    /** Slim right-edge scrollbar; track jumps on click, thumb drags. */
    private void drawScrollbar(UIContext context)
    {
        int contentH = this.scrollMax + this.innerHeight();

        this.barX = this.area.ex() - 5;
        this.barY = this.area.y + 2;
        this.barH = this.area.h - 4;
        this.thumbH = Math.max(20, (int) ((long) this.barH * this.innerHeight() / Math.max(1, contentH)));
        this.thumbY = this.barY + (int) ((long) (this.barH - this.thumbH) * this.scrollY / Math.max(1, this.scrollMax));

        boolean hovered = context.mouseX >= this.barX && context.mouseX <= this.barX + 3
            && context.mouseY >= this.thumbY && context.mouseY < this.thumbY + this.thumbH;
        int color = this.draggingBar ? BBSSettings.primaryColor()
            : hovered ? BBSSettings.textColor()
            : BBSSettings.mutedTextColor();

        context.batcher.box(this.barX, this.barY, this.barX + 3, this.barY + this.barH, Colors.setA(BBSSettings.dividerColor(), 0.45F));
        context.batcher.box(this.barX, this.thumbY, this.barX + 3, this.thumbY + this.thumbH, Colors.setA(color, 0.85F));
    }

    private boolean insideBar(UIContext context)
    {
        return this.scrollMax > 0 && context.mouseX >= this.barX - 3 && context.mouseX <= this.barX + 6
            && context.mouseY >= this.barY && context.mouseY < this.barY + this.barH;
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        if (this.scrollMax > 0 && this.area.isInside(context) && context.mouseWheel != 0D)
        {
            this.scrollY -= (int) (context.mouseWheel * SCROLL_SPEED);
            this.clampScroll();

            return true;
        }

        return super.subMouseScrolled(context);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.area.isInside(context))
        {
            if (this.insideBar(context))
            {
                this.draggingBar = true;
                this.selecting = false;
                this.swiping = false;

                return true;
            }

            /* Overflowing documents flip by dragging; text selection is only
             * offered while everything fits on screen. */
            if (this.scrollMax > 0)
            {
                this.swiping = true;
                this.swipeStartY = context.mouseY;
                this.swipeStartScroll = this.scrollY;
                this.selecting = false;
                this.hasSelection = false;

                return true;
            }

            this.selecting = true;
            this.hasSelection = false;

            UiMarkdown.Layout current = this.layout == null ? new UiMarkdown.Layout() : this.layout;
            int[] hit = UiMarkdown.hitTest(context.batcher, current, context.mouseX, context.mouseY, this.area.x + 6, this.area.y + 6 + this.scrollY);

            this.selection.anchorLine = hit[0];
            this.selection.anchorPos = hit[1];
            this.selection.focusLine = hit[0];
            this.selection.focusPos = hit[1];

            return true;
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (this.draggingBar)
        {
            this.draggingBar = false;

            return true;
        }

        if (this.swiping && context.mouseButton == 0)
        {
            this.swiping = false;

            return true;
        }

        if (this.selecting)
        {
            this.selecting = false;

            if (this.hasSelection && this.layout != null)
            {
                String text = UiMarkdown.extract(this.layout, this.selection);

                if (!text.trim().isEmpty())
                {
                    Window.setClipboard(text);
                    this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.copied_selection"));
                }
            }

            return true;
        }

        return super.subMouseReleased(context);
    }

    /**
     * Cover-fit texture draw, inlined from UINewsStrip (which is not part of
     * this baseline yet) — reconcile with UINewsStrip.drawCover when the film
     * home panel itself gets merged.
     */
    private static void drawCover(Batcher2D batcher, Texture texture, int x, int y, int w, int h)
    {
        float texW = texture.width;
        float texH = texture.height;
        float texAspect = texW / texH;
        float areaAspect = w / (float) h;
        float u1;
        float u2;
        float v1;
        float v2;

        if (areaAspect > texAspect)
        {
            float cropH = texW / areaAspect;

            u1 = 0;
            u2 = texW;
            v1 = (texH - cropH) * 0.5F;
            v2 = v1 + cropH;
        }
        else
        {
            float cropW = texH * areaAspect;

            u1 = (texW - cropW) * 0.5F;
            u2 = u1 + cropW;
            v1 = 0;
            v2 = texH;
        }

        batcher.texturedBox(texture, Colors.WHITE, x, y, w, h, u1, v1, u2, v2, texture.width, texture.height);
    }
}
