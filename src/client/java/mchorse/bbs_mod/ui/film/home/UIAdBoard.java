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
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Bottom-left ad board of the film home: a vertical stack of 4:3 ad images
 * (no captions), each with a hover highlight, opening a detail overlay with
 * the full image and a copy-link action. A trailing "ad slot for rent" tile
 * with a plus sign closes the stack. The whole column is driven by
 * {@code film_home/ads.json} (config override, jar fallback).
 */
public class UIAdBoard extends UIElement
{
    private static final int GAP = 8;
    private static final int RENT_H = 90;

    private final Scroll scroll;
    private List<FilmHomeContent.AdItem> ads = List.of();

    public UIAdBoard()
    {
        this.scroll = new Scroll(this.area, 100);

        this.add(new UIRenderable((ctx) -> this.renderBoard(ctx)));
    }

    public void fill(List<FilmHomeContent.AdItem> ads)
    {
        this.ads = ads;

        for (FilmHomeContent.AdItem ad : ads)
        {
            if (ad.imageLink() != null)
            {
                BBSModClient.getTextures().getTexture(ad.imageLink(), GL11.GL_NEAREST, true);
            }
        }

        this.recalculateScroll();
    }

    @Override
    public void resize()
    {
        super.resize();

        this.recalculateScroll();
    }

    private void recalculateScroll()
    {
        int tileH = this.tileHeight();

        this.scroll.scrollItemSize = tileH + GAP;
        this.scroll.setSize(this.ads.size() + 1);
        this.scroll.clamp();
    }

    private int tileWidth()
    {
        return Math.max(1, this.area.w - GAP * 2 - 4);
    }

    private int tileHeight()
    {
        return this.tileWidth() * 3 / 4;
    }

    @Override
    public boolean subMouseScrolled(UIContext context)
    {
        return this.scroll.mouseScroll(context);
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (this.area.isInside(context) && context.mouseButton == 0)
        {
            int index = this.pick(context.mouseY);

            if (index >= 0 && index < this.ads.size())
            {
                this.openDetails(this.ads.get(index));

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    /** @return the ad index under the cursor, or -1 for the rent slot/empty space. */
    private int pick(int mouseY)
    {
        int tileH = this.tileHeight();
        int y = this.area.y + GAP / 2 - (int) this.scroll.getScroll();

        for (int i = 0; i < this.ads.size(); i++)
        {
            if (mouseY >= y && mouseY < y + tileH)
            {
                return i;
            }

            y += tileH + GAP;
        }

        return -1;
    }

    private void openDetails(FilmHomeContent.AdItem ad)
    {
        UIOverlayPanel panel = new UIOverlayPanel(IKey.EMPTY);

        final int imageW = 288;
        final int imageH = imageW * 3 / 4;

        UIElement imageBox = new UIElement();

        imageBox.wh(imageW, imageH);
        imageBox.add(new UIRenderable((ctx) ->
        {
            Texture texture = ad.imageLink() == null ? null : BBSModClient.getTextures().getTexture(ad.imageLink(), GL11.GL_NEAREST, true);

            if (texture != null && texture != BBSModClient.getTextures().getError())
            {
                UINewsStrip.drawCover(ctx.batcher, texture, imageBox.area.x, imageBox.area.y, imageBox.area.w, imageBox.area.h);
            }
        }));

        UIRenderable body = new UIRenderable((ctx) ->
        {
            UiMarkdown.render(ctx, ad.markdown, panel.content.area.x + 6, panel.content.area.y + imageH + 12, panel.content.area.w - 12, 110);
        });

        panel.content.add(imageBox, body);

        boolean web = ad.link.startsWith("http://") || ad.link.startsWith("https://");

        if (web)
        {
            UIButton open = new UIButton(L10n.lang("bbs.ui.film.home.open_url"), (b) -> mchorse.bbs_mod.ui.utils.UIUtils.openWebLink(ad.link));

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

    private void renderBoard(UIContext context)
    {
        int x = this.area.x + GAP;
        int w = this.tileWidth();
        int tileH = this.tileHeight();
        int y = this.area.y + GAP / 2 - (int) this.scroll.getScroll();

        Area clip = new Area();

        clip.set(this.area.x, this.area.y, this.area.w, this.area.h);
        context.batcher.clip(clip, context);

        for (FilmHomeContent.AdItem ad : this.ads)
        {
            if (y + tileH >= this.area.y && y <= this.area.ey())
            {
                boolean hovered = context.mouseX >= x && context.mouseX < x + w && context.mouseY >= y && context.mouseY < y + tileH;

                this.renderAd(context, ad, x, y, w, tileH, hovered);
            }

            y += tileH + GAP;
        }

        if (y + RENT_H >= this.area.y && y <= this.area.ey())
        {
            this.renderRentSlot(context, x, y, w);
        }

        context.batcher.unclip(context);
        this.scroll.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
    }

    private void renderAd(UIContext context, FilmHomeContent.AdItem ad, int x, int y, int w, int h, boolean hovered)
    {
        Texture texture = ad.imageLink() == null ? null : BBSModClient.getTextures().getTexture(ad.imageLink(), GL11.GL_NEAREST, true);

        if (texture != null && texture != BBSModClient.getTextures().getError())
        {
            UINewsStrip.drawCover(context.batcher, texture, x, y, w, h);
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
        context.batcher.box(x, y, x + w, y + RENT_H, BBSSettings.chromeSurface());
        context.batcher.outline(x, y, x + w, y + RENT_H, BBSSettings.dividerColor());

        String caption = L10n.lang("bbs.ui.film.home.ad_rent").get();

        context.batcher.textShadow(caption, x + w / 2 - context.batcher.getFont().getWidth(caption) / 2, y + RENT_H / 2 - 18, BBSSettings.mutedTextColor());
        context.batcher.iconArea(Icons.ADD, BBSSettings.mutedTextColor(), x + w / 2 - 8, y + RENT_H / 2 + 2, 16, 16);
    }
}
