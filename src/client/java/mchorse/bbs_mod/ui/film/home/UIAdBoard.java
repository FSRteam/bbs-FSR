package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.List;

/**
 * Bottom-left ad board of the film home: a paged carousel of 4:3 ad images.
 *
 * <p>The carousel auto-flips to the left every 10 seconds; during a flip both
 * the outgoing and incoming slides travel offset from each other. Slides can
 * also be dragged horizontally with the mouse (short tap opens, longer drags
 * flip to the previous/next ad) and pagination dots underneath jump straight
 * to a given ad. The "ad slot for rent" tile sits statically below and never
 * participates in paging. Detail overlays show the full image plus a
 * selectable Markdown body with copy/open link actions. Content comes from
 * the remote film-home publisher and is cached on disk by the content loader.
 */
public class UIAdBoard extends UIElement
{
    private static final int GAP = 8;
    private static final int RENT_MIN_H = 48;
    private static final int DOTS_H = 12;
    private static final long FLIP_INTERVAL_MS = 10_000L;
    private static final long FLIP_DURATION_MS = 400L;

    private List<FilmHomeContent.AdItem> ads = List.of();
    private int page;
    private long pageShownAt = System.currentTimeMillis();
    private long flipStart = -1L;

    /* Swipe gesture */
    private boolean swiping;
    private int swipeStartX;
    private int swipeDX;

    /* Pagination dot geometry, refreshed during rendering */
    private int dotsY;
    private int dotsX;
    private int dotStep = 12;

    public UIAdBoard()
    {
        this.add(new UIRenderable((ctx) -> this.renderBoard(ctx)));
    }

    public void fill(List<FilmHomeContent.AdItem> ads)
    {
        this.ads = ads;
        this.page = 0;
        this.pageShownAt = System.currentTimeMillis();
        this.flipStart = -1L;

    }

    /** Slide width fills the column interior; height keeps the 4:3 ratio so images show in full. */
    private int slideWidth()
    {
        return Math.max(1, this.area.w - GAP * 2 - 4);
    }

    private int slideHeight()
    {
        return this.slideWidth() * 3 / 4;
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.area.isInside(context))
        {
            /* Pagination dots */
            if (this.ads.size() > 1 && this.dotsHit(context.mouseX, context.mouseY) >= 0)
            {
                this.page = this.dotsHit(context.mouseX, context.mouseY);
                this.pageShownAt = System.currentTimeMillis();
                this.flipStart = -1L;

                return true;
            }

            /* Slide area starts a swipe/tap gesture */
            int viewY = this.area.y + GAP / 2;

            if (!this.ads.isEmpty() && this.flipStart < 0
                && context.mouseY >= viewY && context.mouseY < viewY + this.slideHeight())
            {
                this.swiping = true;
                this.swipeStartX = context.mouseX;
                this.swipeDX = 0;

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        if (this.swiping && context.mouseButton == 0)
        {
            this.swiping = false;

            int count = this.ads.size();
            int dx = this.swipeDX;

            this.swipeDX = 0;

            if (count == 0 || this.flipStart >= 0)
            {
                return true;
            }

            if (Math.abs(dx) < 6)
            {
                /* Tap: open the details */
                this.openDetails(this.ads.get(this.page));
            }
            else if (dx <= -w6())
            {
                /* Dragged left: next ad through the animated flip */
                this.flipStart = System.currentTimeMillis();
            }
            else if (dx >= w6())
            {
                /* Dragged right: previous ad, snaps without animation */
                this.page = (this.page + count - 1) % count;
                this.pageShownAt = System.currentTimeMillis();
            }

            return true;
        }

        return super.subMouseReleased(context);
    }

    private int w6()
    {
        return this.slideWidth() / 6;
    }

    private int dotsHit(int mouseX, int mouseY)
    {
        if (mouseY < this.dotsY || mouseY >= this.dotsY + DOTS_H)
        {
            return -1;
        }

        int index = (mouseX - this.dotsX) / this.dotStep;

        return index >= 0 && index < this.ads.size() ? index : -1;
    }

    private void renderBoard(UIContext context)
    {
        int x = this.area.x + GAP;
        int w = this.slideWidth();
        int h = this.slideHeight();
        int viewY = this.area.y + GAP / 2;

        Area clip = new Area();

        clip.set(this.area.x, this.area.y, this.area.w, this.area.h);
        context.batcher.clip(clip, context);

        if (this.swiping && Window.isMouseButtonPressed(0))
        {
            this.swipeDX = context.mouseX - this.swipeStartX;

            /* Holding a drag pauses the automatic flipping */
            this.pageShownAt = System.currentTimeMillis();
        }

        if (!this.ads.isEmpty())
        {
            long now = System.currentTimeMillis();
            boolean flipping = false;
            float progress = 1F;

            if (this.ads.size() > 1 && !this.swiping)
            {
                if (this.flipStart < 0 && now - this.pageShownAt >= FLIP_INTERVAL_MS)
                {
                    this.flipStart = now;
                }

                if (this.flipStart >= 0)
                {
                    flipping = true;
                    progress = Math.min(1F, (now - this.flipStart) / (float) FLIP_DURATION_MS);

                    if (progress >= 1F)
                    {
                        this.page = (this.page + 1) % this.ads.size();
                        this.pageShownAt = now;
                        this.flipStart = -1L;
                        flipping = false;
                    }
                }
            }

            boolean hovered = !flipping && !this.swiping
                && context.mouseX >= x && context.mouseX < x + w
                && context.mouseY >= viewY && context.mouseY < viewY + h;

            if (this.swiping && Math.abs(this.swipeDX) > 0)
            {
                /* Manual drag: current slide follows the cursor, neighbors wait adjacent */
                FilmHomeContent.AdItem current = this.ads.get(this.page);

                if (this.swipeDX < 0)
                {
                    FilmHomeContent.AdItem next = this.ads.get((this.page + 1) % this.ads.size());

                    this.drawSlide(context, next, x + w + this.swipeDX, viewY, w, h, false);
                    this.drawSlide(context, current, x + this.swipeDX, viewY, w, h, false);
                }
                else
                {
                    FilmHomeContent.AdItem prev = this.ads.get((this.page + this.ads.size() - 1) % this.ads.size());

                    this.drawSlide(context, current, x + this.swipeDX, viewY, w, h, false);
                    this.drawSlide(context, prev, x - w + this.swipeDX, viewY, w, h, false);
                }
            }
            else if (!flipping)
            {
                /* Idle: the current slide rests in place */
                this.drawSlide(context, this.ads.get(this.page), x, viewY, w, h, hovered);
            }
            else
            {
                /* Flip: outgoing travels left, incoming follows from the right,
                 * staying adjacent so both are visible while they move */
                FilmHomeContent.AdItem outgoing = this.ads.get(this.page);
                FilmHomeContent.AdItem incoming = this.ads.get((this.page + 1) % this.ads.size());

                this.drawSlide(context, incoming, x + (int) (w * (1F - progress)), viewY, w, h, false);
                this.drawSlide(context, outgoing, x - (int) (w * progress), viewY, w, h, false);
            }
        }
        else if (FilmHomeContent.INSTANCE.fetchingAds)
        {
            /* Remote ads still streaming in: spinner in the slide area */
            context.batcher.box(x, viewY, x + w, viewY + h, BBSSettings.chromeSurface());
            WebImages.drawSpinner(context, x + w / 2F, viewY + h / 2F, BBSSettings.accentColorRGB());
        }

        this.renderDots(context, x, w, viewY + h + 4);

        if (this.ads.size() > 1)
        {
            this.renderRentSlot(context, x, this.dotsY + DOTS_H + 2, w);
        }
        else
        {
            this.renderRentSlot(context, x, viewY + h + GAP, w);
        }

        context.batcher.unclip(context);
    }

    private void renderDots(UIContext context, int x, int w, int y)
    {
        this.dotsY = y;
        this.dotStep = 12;

        if (this.ads.size() <= 1)
        {
            return;
        }

        int total = this.ads.size() * this.dotStep - (this.dotStep - 4);

        this.dotsX = x + w / 2 - total / 2;

        for (int i = 0; i < this.ads.size(); i++)
        {
            boolean active = i == this.page;
            float cx = this.dotsX + i * this.dotStep + 2;
            float cy = y + DOTS_H / 2F;

            if (active)
            {
                context.batcher.filledCircle(cx, cy, 3.2F, BBSSettings.primaryColor(), 8);
            }
            else
            {
                context.batcher.filledCircle(cx, cy, 2.2F, Colors.setA(BBSSettings.mutedTextColor(), 0.6F), 8);
            }
        }
    }

    private void drawSlide(UIContext context, FilmHomeContent.AdItem ad, int x, int y, int w, int h, boolean hovered)
    {
        Texture texture = WebImages.resolve(ad.image);

        if (texture != null)
        {
            UINewsStrip.drawCover(context.batcher, texture, x, y, w, h);
        }
        else if (WebImages.isLoading(ad.image))
        {
            context.batcher.box(x, y, x + w, y + h, BBSSettings.chromeSurface());
            WebImages.drawSpinner(context, x + w / 2F, y + h / 2F, BBSSettings.accentColorRGB());
        }
        else
        {
            context.batcher.box(x, y, x + w, y + h, BBSSettings.chromeSurface());
            context.batcher.iconArea(Icons.IMAGE, Colors.WHITE, x + w / 2 - 6, y + h / 2 - 6, 12, 12);
        }

        if (hovered)
        {
            context.batcher.box(x, y, x + w, y + h, BBSSettings.primaryColor(Colors.A25));
            context.batcher.outline(x, y, x + w, y + h, BBSSettings.primaryColor());
        }
    }

    private void renderRentSlot(UIContext context, int x, int y, int w)
    {
        int h = Math.max(RENT_MIN_H, this.area.ey() - y - GAP);

        if (h <= 0 || y >= this.area.ey())
        {
            return;
        }

        context.batcher.box(x, y, x + w, y + h, BBSSettings.chromeSurface());
        context.batcher.outline(x, y, x + w, y + h, BBSSettings.dividerColor());

        String caption = L10n.lang("bbs.ui.film.home.ad_rent").get();

        context.batcher.textShadow(caption, x + w / 2 - context.batcher.getFont().getWidth(caption) / 2, y + h / 2 - 16, BBSSettings.mutedTextColor());
        context.batcher.iconArea(Icons.ADD, BBSSettings.mutedTextColor(), x + w / 2 - 8, y + h / 2 + 4, 16, 16);
    }

    private void openDetails(FilmHomeContent.AdItem ad)
    {
        UIOverlayPanel panel = new UIOverlayPanel(IKey.EMPTY);

        /* All geometry derives from the window size: the panel takes 90% of
         * the GUI area capped at a readable width, the image keeps its 4:3
         * ratio, and the Markdown body scrolls when it overflows. The
         * content is 20px narrower than the panel (right strip hosts the
         * close button), so every width stays clear of it. */
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        int panelW = Math.min(400, Math.max(240, (int) (minecraft.getWindow().getGuiScaledWidth() * 0.9F)));
        int contentW = panelW - 20;
        int imageH = contentW * 3 / 4;
        int bodyH = Math.min(200, Math.max(120, (int) (minecraft.getWindow().getGuiScaledHeight() * 0.35F)));
        final int buttonsY = 4 + imageH + 10 + bodyH + 8;

        UIElement imageBox = new UIElement();

        imageBox.relative(panel.content).xy(6, 4).wh(contentW, imageH);
        imageBox.add(new UIRenderable((ctx) ->
        {
            Texture texture = WebImages.resolve(ad.image);

            if (texture != null)
            {
                UINewsStrip.drawCover(ctx.batcher, texture, imageBox.area.x, imageBox.area.y, imageBox.area.w, imageBox.area.h);
            }
            else if (WebImages.isLoading(ad.image))
            {
                ctx.batcher.box(imageBox.area.x, imageBox.area.y, imageBox.area.ex(), imageBox.area.ey(), BBSSettings.chromeSurface());
                WebImages.drawSpinner(ctx, imageBox.area.mx(), imageBox.area.my(), BBSSettings.accentColorRGB());
            }
        }));

        MarkdownBody body = new MarkdownBody(ad.markdown);

        body.relative(panel.content).xy(6, 4 + imageH + 10).w(contentW).h(bodyH);

        panel.content.add(imageBox, body);

        boolean web = ad.link.startsWith("http://") || ad.link.startsWith("https://");

        UIButton copy = new UIButton(L10n.lang("bbs.ui.film.home.copy_link"), (b) ->
        {
            Window.setClipboard(ad.link);
            this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.link_copied"));
        });

        copy.relative(panel.content).xy(6, buttonsY).wh(120, 20);
        panel.content.add(copy);

        if (web)
        {
            UIIcon open = new UIIcon(Icons.HELP, (b) -> UIUtils.openWebLink(ad.link));

            open.relative(panel.content).xy(6 + 128, buttonsY).wh(20, 20);
            open.tooltip(L10n.lang("bbs.ui.film.home.open_url"));
            panel.content.add(open);
        }

        UIOverlay.addOverlay(this.getContext(), panel, panelW, buttonsY + 20 + 18);
    }
}
