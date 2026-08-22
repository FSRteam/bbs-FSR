package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Bottom-left ad board of the film home: a paged carousel of 4:3 ad images.
 *
 * <p>The carousel auto-flips to the left every 10 seconds; during a flip both
 * the outgoing and incoming slides are visible, offset while they travel. The
 * "ad slot for rent" tile sits statically below and never participates in
 * paging — with no ads (or a single ad) nothing flips. Clicking an ad opens a
 * detail overlay with the full image and a selectable Markdown body plus
 * copy/open link actions. Content comes from {@code film_home/ads.json}
 * (config override, jar fallback).
 */
public class UIAdBoard extends UIElement
{
    private static final int GAP = 8;
    private static final int RENT_MIN_H = 48;
    private static final long FLIP_INTERVAL_MS = 10_000L;
    private static final long FLIP_DURATION_MS = 400L;

    private List<FilmHomeContent.AdItem> ads = List.of();
    private int page;
    private long pageShownAt = System.currentTimeMillis();
    private long flipStart = -1L;

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

        for (FilmHomeContent.AdItem ad : ads)
        {
            /* Local covers pre-warm through the texture manager; remote ones
             * stream through WebImages on demand. */
            if (ad.imageLink() != null && !WebImages.isRemote(ad.image))
            {
                BBSModClient.getTextures().getTexture(ad.imageLink(), GL11.GL_NEAREST, true);
            }
        }
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
        if (this.area.isInside(context) && context.mouseButton == 0 && this.flipStart < 0 && !this.ads.isEmpty())
        {
            int viewY = this.area.y + GAP / 2;

            if (context.mouseY >= viewY && context.mouseY < viewY + this.slideHeight())
            {
                this.openDetails(this.ads.get(this.page % this.ads.size()));

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    /** Advances the auto-flip state machine; returns the eased flip progress (1 = idle). */
    private float advanceFlip(int count)
    {
        if (count <= 1)
        {
            return 1F;
        }

        long now = System.currentTimeMillis();

        if (this.flipStart < 0 && now - this.pageShownAt >= FLIP_INTERVAL_MS)
        {
            this.flipStart = now;
        }

        if (this.flipStart < 0)
        {
            return 1F;
        }

        float progress = Math.min(1F, (now - this.flipStart) / (float) FLIP_DURATION_MS);

        if (progress >= 1F)
        {
            this.page = (this.page + 1) % count;
            this.pageShownAt = now;
            this.flipStart = -1L;

            return 1F;
        }

        return progress;
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

        if (!this.ads.isEmpty())
        {
            long now = System.currentTimeMillis();
            boolean flipping = false;
            float progress = 1F;

            if (this.ads.size() > 1)
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

            boolean hovered = !flipping
                && context.mouseX >= x && context.mouseX < x + w
                && context.mouseY >= viewY && context.mouseY < viewY + h;

            if (!flipping)
            {
                /* Idle: the current slide rests in place. */
                this.drawSlide(context, this.ads.get(this.page), x, viewY, w, h, hovered);
            }
            else
            {
                /* Flip: outgoing travels left, incoming follows from the right,
                 * staying adjacent so both are visible while they move. */
                FilmHomeContent.AdItem outgoing = this.ads.get(this.page);
                FilmHomeContent.AdItem incoming = this.ads.get((this.page + 1) % this.ads.size());

                this.drawSlide(context, incoming, x + (int) (w * (1F - progress)), viewY, w, h, false);
                this.drawSlide(context, outgoing, x - (int) (w * progress), viewY, w, h, false);
            }
        }

        this.renderRentSlot(context, x, viewY + h + GAP, w);

        context.batcher.unclip(context);
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

        final int imageW = 288;
        final int imageH = imageW * 3 / 4;

        UIElement imageBox = new UIElement();

        imageBox.relative(panel.content).xy(6, 6).wh(imageW, imageH);
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

        body.relative(panel.content).xy(6, imageH + 14).w(1F, -12).h(110);

        panel.content.add(imageBox, body);

        boolean web = ad.link.startsWith("http://") || ad.link.startsWith("https://");

        if (web)
        {
            UIButton open = new UIButton(L10n.lang("bbs.ui.film.home.open_url"), (b) -> UIUtils.openWebLink(ad.link));

            open.relative(panel.content).x(1F, -116).y(1F, -26).wh(104, 20);
            panel.content.add(open);
        }

        UIButton copy = new UIButton(L10n.lang("bbs.ui.film.home.copy_link"), (b) ->
        {
            Window.setClipboard(ad.link);
            this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.link_copied"));
        });

        copy.relative(panel.content).x(1F, -6).y(1F, -26).anchor(1F, 0F).wh(104, 20);
        panel.content.add(copy);

        UIOverlay.addOverlay(this.getContext(), panel);
    }
}
