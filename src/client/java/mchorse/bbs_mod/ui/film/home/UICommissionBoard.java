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
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Left column of the film home: the commission board (e.g. Blockbench art
 * commissions). Shows a header with the board's display name and an open-slots
 * summary, then a vertical stack of commission cards: cover, status badge,
 * optional progress bar, title and a slots/price meta line. Clicking a card
 * opens a detail overlay with the note and a copy-contact button. The host
 * hides the whole column when there is no content.
 */
public class UICommissionBoard extends UIElement
{
    private static final int GAP = 8;
    private static final int COVER_H = 96;
    private static final int TEXT_H = 36;
    private static final int CARD_H = COVER_H + 3 + TEXT_H + GAP;

    private final Scroll scroll;
    private final List<CardView> cards = new ArrayList<>();
    private String title = "";
    private String summary = "";

    public UICommissionBoard()
    {
        this.scroll = new Scroll(this.area, CARD_H);

        this.add(new UIRenderable((ctx) -> this.renderBoard(ctx)));
    }

    public void fill(FilmHomeContent.CommissionBoard board)
    {
        this.cards.clear();

        for (FilmHomeContent.CommissionItem item : board.items)
        {
            this.cards.add(new CardView(item));

            if (item.coverLink() != null)
            {
                BBSModClient.getTextures().getTexture(item.coverLink(), GL11.GL_NEAREST, true);
            }
        }

        this.title = board.displayName.isEmpty()
            ? L10n.lang("bbs.ui.film.home.commissions").get()
            : board.displayName;

        int open = board.countByStatus("open");
        int left = 0;

        for (FilmHomeContent.CommissionItem item : board.items)
        {
            if (item.status.equals("open") && item.slots != null && item.slots[0] > 0)
            {
                left += Math.max(0, item.slots[0] - item.slots[1]);
            }
        }

        this.summary = L10n.lang("bbs.ui.film.home.commissions_summary").format(open, left).get();
        this.scroll.setSize(this.cards.size());
        this.scroll.clamp();
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
            CardView card = this.pick(context.mouseY);

            if (card != null)
            {
                this.openDetails(card.item);

                return true;
            }
        }

        return super.subMouseClicked(context);
    }

    private CardView pick(int mouseY)
    {
        int y = this.area.y + 20 - (int) this.scroll.getScroll();

        for (CardView card : this.cards)
        {
            if (mouseY >= y && mouseY < y + CARD_H - GAP)
            {
                return card;
            }

            y += CARD_H;
        }

        return null;
    }

    private void openDetails(FilmHomeContent.CommissionItem item)
    {
        UIOverlayPanel panel = new UIOverlayPanel(IKey.raw(item.title));

        UIRenderable body = new UIRenderable((ctx) ->
        {
            int x = panel.content.area.x + 6;
            int y = panel.content.area.y + 6;

            ctx.batcher.textShadow(statusLabel(item.status), x, y, statusColor(item.status));
            y += 14;

            if (item.slots != null)
            {
                ctx.batcher.textShadow(L10n.lang("bbs.ui.film.home.slots").format(item.slots[1], item.slots[0]).get(), x, y, BBSSettings.textColor());
                y += 14;
            }

            if (!item.price.isEmpty())
            {
                ctx.batcher.textShadow(item.price, x, y, BBSSettings.textColor());
                y += 14;
            }

            if (!item.note.isEmpty())
            {
                ctx.batcher.wallText(item.note, x, y + 4, BBSSettings.mutedTextColor(), panel.content.area.w - 12);
            }
        });

        panel.content.add(body);

        if (!FilmHomeContent.INSTANCE.commissions.contact.isEmpty())
        {
            UIButton copy = new UIButton(L10n.lang("bbs.ui.film.home.copy_contact"), (b) ->
            {
                Window.setClipboard(FilmHomeContent.INSTANCE.commissions.contact);
                this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.contact_copied"));
            });

            copy.relative(panel.content).x(1F, -6).y(1F, -26).anchor(1F, 0F).wh(150, 20);
            panel.content.add(copy);
        }

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void renderBoard(UIContext context)
    {
        int x = this.area.x + GAP;
        int top = this.area.y;

        context.batcher.textShadow(this.title, x, top + 2, BBSSettings.textColor());
        context.batcher.textShadow(this.summary, x, top + 14, BBSSettings.mutedTextColor());

        int listY = top + 30;

        Area clip = new Area();

        clip.set(this.area.x, listY, this.area.w, this.area.h - 30);

        context.batcher.clip(clip, context);

        int y = listY - (int) this.scroll.getScroll();

        for (CardView card : this.cards)
        {
            if (y + CARD_H >= listY && y <= this.area.ey())
            {
                boolean hovered = context.mouseX >= this.area.x && context.mouseX < this.area.ex()
                    && context.mouseY >= y && context.mouseY < y + CARD_H - GAP;

                this.renderCard(context, card, x, y, hovered);
            }

            y += CARD_H;
        }

        context.batcher.unclip(context);
        this.scroll.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
    }

    private void renderCard(UIContext context, CardView card, int x, int y, boolean hovered)
    {
        FilmHomeContent.CommissionItem item = card.item;
        int w = this.area.w - GAP * 2 - 4;
        int radius = BBSSettings.cornerWidget();

        if (radius > 0)
        {
            context.batcher.roundedFrame(x, y, w, CARD_H - GAP, radius, 1, BBSSettings.dividerColor(), BBSSettings.raisedSurface());
        }
        else
        {
            context.batcher.box(x, y, x + w, y + CARD_H - GAP, BBSSettings.raisedSurface());
            context.batcher.outline(x, y, x + w, y + CARD_H - GAP, BBSSettings.dividerColor());
        }

        if (hovered)
        {
            int tint = BBSSettings.primaryColor(Colors.A25);

            if (radius > 0)
            {
                context.batcher.roundedBox(x, y, w, CARD_H - GAP, radius, tint);
            }
            else
            {
                context.batcher.box(x, y, x + w, y + CARD_H - GAP, tint);
            }
        }

        /* Cover */
        int coverH = COVER_H;

        if (item.coverLink() != null)
        {
            Texture texture = BBSModClient.getTextures().getTexture(item.coverLink(), GL11.GL_NEAREST, true);

            if (texture != null && texture != BBSModClient.getTextures().getError())
            {
                UINewsStrip.drawCover(context.batcher, texture, x + 2, y + 2, w - 4, coverH - 4);
            }
            else
            {
                this.drawFallbackCover(context, item, x + 2, y + 2, w - 4, coverH - 4);
            }
        }
        else
        {
            this.drawFallbackCover(context, item, x + 2, y + 2, w - 4, coverH - 4);
        }

        int statusColor = statusColor(item.status);

        String status = statusLabel(item.status);
        int badgeW = context.batcher.getFont().getWidth(status) + 8;

        context.batcher.box(x + 6, y + coverH - 15, x + 6 + badgeW, y + coverH - 4, Colors.A75);
        context.batcher.textShadow(status, x + 10, y + coverH - 13, statusColor);

        int textY = y + coverH + 4;

        if (item.progress >= 0F)
        {
            int barY = y + coverH + 1;
            int barW = w - 4;

            context.batcher.box(x + 2, barY, x + 2 + barW, barY + 2, BBSSettings.chromeSurface());
            context.batcher.box(x + 2, barY, x + 2 + (int) (barW * Math.min(1F, item.progress)), barY + 2, BBSSettings.primaryColor());

            textY += 3;
        }

        String title = context.batcher.getFont().limitToWidth(item.title, "...", w - GAP * 2);

        context.batcher.textShadow(title, x + GAP, textY, BBSSettings.textColor());

        String meta = card.meta;

        if (!meta.isEmpty())
        {
            context.batcher.textShadow(meta, x + GAP, textY + 12, BBSSettings.mutedTextColor());
        }
    }

    private void drawFallbackCover(UIContext context, FilmHomeContent.CommissionItem item, int x, int y, int w, int h)
    {
        int index = Math.abs(item.id.isEmpty() ? item.title.hashCode() : item.id.hashCode()) % UIFilmHomePanel.THUMB_COLORS.length;
        int[] colors = UIFilmHomePanel.THUMB_COLORS[index];

        context.batcher.gradientVBox(x, y, x + w, y + h, colors[0], colors[1]);
        context.batcher.iconArea(Icons.BRUSH, Colors.WHITE, x + w / 2 - 6, y + h / 2 - 6, 12, 12);
    }

    private static int statusColor(String status)
    {
        switch (status)
        {
            case "queued": return BBSSettings.warningColor();
            case "working": return BBSSettings.activeColor();
            case "done":
            case "closed": return BBSSettings.mutedTextColor();
            default: return BBSSettings.positiveColor();
        }
    }

    private static String statusLabel(String status)
    {
        switch (status)
        {
            case "queued": return L10n.lang("bbs.ui.film.home.status_queued").get();
            case "working": return L10n.lang("bbs.ui.film.home.status_working").get();
            case "done": return L10n.lang("bbs.ui.film.home.status_done").get();
            case "closed": return L10n.lang("bbs.ui.film.home.status_closed").get();
            default: return L10n.lang("bbs.ui.film.home.status_open").get();
        }
    }

    /** Render view over one commission item with cached derived strings. */
    private static class CardView
    {
        public final FilmHomeContent.CommissionItem item;
        public final String meta;

        public CardView(FilmHomeContent.CommissionItem item)
        {
            this.item = item;

            String meta = "";

            if (item.slots != null && item.slots[0] > 0)
            {
                meta += L10n.lang("bbs.ui.film.home.slots").format(item.slots[1], item.slots[0]).get();
            }

            if (!item.price.isEmpty())
            {
                if (!meta.isEmpty())
                {
                    meta += " · ";
                }

                meta += item.price;
            }

            this.meta = meta;
        }
    }
}
