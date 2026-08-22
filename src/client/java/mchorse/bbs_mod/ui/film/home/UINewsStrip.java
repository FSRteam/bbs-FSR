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
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal strip of news cards under the film home banner. Cards show an
 * optional thumbnail, a colored tag chip, the title and the date; clicking a
 * card opens an in-game detail overlay. Hidden entirely by the host when
 * there is no content.
 */
public class UINewsStrip extends UIElement
{
    private static final int CARD_W = 260;
    private static final int CARD_H = 64;
    private static final int GAP = 8;
    private static final int HEADER_H = 14;
    private static final int THUMB = 48;

    private final Scroll scroll;
    private final List<CardView> cards = new ArrayList<>();
    private String header = "";

    public UINewsStrip()
    {
        this.scroll = new Scroll(this.area, CARD_W + GAP, ScrollDirection.HORIZONTAL);
        this.scroll.scrollSpeed = 24;

        this.add(new UIRenderable((ctx) -> this.renderStrip(ctx)));
    }

    public int getPreferredHeight()
    {
        return HEADER_H + GAP / 2 + CARD_H + GAP / 2;
    }

    public void fill(List<FilmHomeContent.NewsItem> items)
    {
        this.cards.clear();

        for (FilmHomeContent.NewsItem item : items)
        {
            this.cards.add(new CardView(item));

            /* Pre-warm local thumbnails once per refill so the render loop
             * never hits disk; remote ones stream through WebImages. */
            if (item.imageLink() != null && !WebImages.isRemote(item.image))
            {
                BBSModClient.getTextures().getTexture(item.imageLink(), GL11.GL_NEAREST, true);
            }
        }

        this.header = L10n.lang("bbs.ui.film.home.news").get();
        this.scroll.setSize(this.cards.size());
        this.scroll.clamp();
    }

    @Override
    public void resize()
    {
        super.resize();

        for (CardView card : this.cards)
        {
            card.titleWidth = -1;
        }

        this.scroll.clamp();
        this.scroll.updateTarget();
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
            CardView card = this.pick(context.mouseX, context.mouseY);

            if (card != null)
            {
                this.openDetails(card.item);

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    private CardView pick(int mouseX, int mouseY)
    {
        int y = this.area.y + HEADER_H + GAP / 2;

        if (mouseY < y || mouseY >= y + CARD_H)
        {
            return null;
        }

        int x = this.area.x + GAP - (int) this.scroll.getScroll();

        for (CardView card : this.cards)
        {
            if (mouseX >= x && mouseX < x + CARD_W)
            {
                return card;
            }

            x += CARD_W + GAP;
        }

        return null;
    }

    private void openDetails(FilmHomeContent.NewsItem item)
    {
        UIOverlayPanel panel = new UIOverlayPanel(IKey.raw(item.title));

        UIRenderable body = new UIRenderable((ctx) ->
        {
            ctx.batcher.textShadow(item.date, panel.content.area.x + 6, panel.content.area.y + 6, BBSSettings.mutedTextColor());

            String markdown = item.markdown.isEmpty() ? item.summary : item.markdown;

            UiMarkdown.render(ctx, markdown, panel.content.area.x + 6, panel.content.area.y + 22, panel.content.area.w - 12, panel.content.area.h - 60);
        });

        panel.content.add(body);

        if (!item.url.isEmpty())
        {
            boolean web = item.url.startsWith("http://") || item.url.startsWith("https://");

            if (web)
            {
                UIButton open = new UIButton(L10n.lang("bbs.ui.film.home.open_url"), (b) -> UIUtils.openWebLink(item.url));

                open.relative(panel.content).x(1F, -116).y(1F, -26).wh(104, 20);
                panel.content.add(open);
            }

            UIButton copy = new UIButton(L10n.lang("bbs.ui.film.home.copy_link"), (b) ->
            {
                Window.setClipboard(item.url);
                this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.link_copied"));
            });

            copy.relative(panel.content).x(1F, -6).y(1F, -26).anchor(1F, 0F).wh(104, 20);
            panel.content.add(copy);
        }

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void renderStrip(UIContext context)
    {
        context.batcher.textShadow(this.header, this.area.x + GAP, this.area.y + 2, BBSSettings.textColor());

        Area clip = new Area();

        clip.set(this.area.x, this.area.y + HEADER_H, this.area.w, this.area.h - HEADER_H);

        context.batcher.clip(clip, context);

        int x = this.area.x + GAP - (int) this.scroll.getScroll();
        int y = this.area.y + HEADER_H + GAP / 2;

        for (CardView card : this.cards)
        {
            if (x + CARD_W >= this.area.x && x <= this.area.ex())
            {
                boolean hovered = context.mouseX >= x && context.mouseX < x + CARD_W
                    && context.mouseY >= y && context.mouseY < y + CARD_H;

                this.renderCard(context, card, x, y, hovered);
            }

            x += CARD_W + GAP;
        }

        context.batcher.unclip(context);
        this.scroll.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
    }

    private void renderCard(UIContext context, CardView card, int x, int y, boolean hovered)
    {
        FilmHomeContent.NewsItem item = card.item;
        int radius = BBSSettings.cornerWidget();

        if (radius > 0)
        {
            context.batcher.roundedFrame(x, y, CARD_W, CARD_H, radius, 1, BBSSettings.dividerColor(), BBSSettings.raisedSurface());
        }
        else
        {
            context.batcher.box(x, y, x + CARD_W, y + CARD_H, BBSSettings.raisedSurface());
            context.batcher.outline(x, y, x + CARD_W, y + CARD_H, BBSSettings.dividerColor());
        }

        if (hovered)
        {
            int tint = BBSSettings.primaryColor(Colors.A25);

            if (radius > 0)
            {
                context.batcher.roundedBox(x, y, CARD_W, CARD_H, radius, tint);
            }
            else
            {
                context.batcher.box(x, y, x + CARD_W, y + CARD_H, tint);
            }
        }

        int textX = x + GAP;
        boolean loading = WebImages.isLoading(item.image);

        Texture texture;

        if (WebImages.isRemote(item.image))
        {
            texture = WebImages.get(item.image);
        }
        else if (item.imageLink() != null)
        {
            texture = BBSModClient.getTextures().getTexture(item.imageLink(), GL11.GL_NEAREST, true);

            if (texture == BBSModClient.getTextures().getError())
            {
                texture = null;
            }
        }
        else
        {
            texture = null;
        }

        if (texture != null)
        {
            drawCover(context.batcher, texture, textX, y + GAP, THUMB, THUMB);
            textX += THUMB + GAP;
        }

        if (textX == x + GAP)
        {
            if (loading)
            {
                WebImages.drawSpinner(context, textX + THUMB / 2F, y + GAP + THUMB / 2F, BBSSettings.accentColorRGB());
            }
            else
            {
                context.batcher.iconArea(Icons.IMAGE, Colors.A50 | BBSSettings.accentColorRGB(), x + GAP + (THUMB - 12) / 2, y + GAP + (THUMB - 12) / 2, 12, 12);
            }

            textX += THUMB + GAP;
        }

        String tagLabel = card.tagLabel;
        int tagW = context.batcher.getFont().getWidth(tagLabel) + 8;
        int tagColor = tagColor(item.tag);

        context.batcher.box(textX, y + GAP, textX + tagW, y + GAP + 11, Colors.A25 | tagColor);
        context.batcher.textShadow(tagLabel, textX + 4, y + GAP + 2, tagColor);

        if (card.titleWidth != CARD_W - (textX - x) - GAP)
        {
            card.titleWidth = CARD_W - (textX - x) - GAP;
            card.title = context.batcher.getFont().limitToWidth(item.title, "...", card.titleWidth);
        }

        context.batcher.textShadow(card.title, textX, y + GAP + 16, BBSSettings.textColor());

        if (!item.date.isEmpty())
        {
            context.batcher.textShadow(item.date, textX, y + CARD_H - GAP - 9, BBSSettings.mutedTextColor());
        }
    }

    /** Cover-cropped textured box (center crop, same math as the banner). */
    public static void drawCover(Batcher2D batcher, Texture texture, int x, int y, int w, int h)
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

    private static int tagColor(String tag)
    {
        switch (tag)
        {
            case "notice": return BBSSettings.warningColor();
            case "tutorial": return BBSSettings.activeColor();
            default: return BBSSettings.primaryColor();
        }
    }

    /** Render view over one news item with cached derived strings. */
    private static class CardView
    {
        public final FilmHomeContent.NewsItem item;
        public final String tagLabel;
        public String title = "";
        public int titleWidth = -1;

        public CardView(FilmHomeContent.NewsItem item)
        {
            this.item = item;
            this.tagLabel = tagLabel(item.tag);
        }
    }

    private static String tagLabel(String tag)
    {
        switch (tag)
        {
            case "notice": return L10n.lang("bbs.ui.film.home.tag_notice").get();
            case "tutorial": return L10n.lang("bbs.ui.film.home.tag_tutorial").get();
            default: return L10n.lang("bbs.ui.film.home.tag_update").get();
        }
    }
}
