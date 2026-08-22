package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

/**
 * Selectable Markdown body for detail overlays.
 *
 * <p>Lays out the document once per content/width change, renders it clipped,
 * and supports drag selection with the left button: press inside to anchor,
 * drag to extend (tracked during rendering while the button is held), and on
 * release the covered plain text is copied to the clipboard with a toast.
 * Markdown image slots stream through {@link WebImages} with spinners.
 */
public class MarkdownBody extends UIElement
{
    private final String markdown;
    private UiMarkdown.Layout layout;
    private int layoutWidth = -1;
    private final UiMarkdown.Selection selection = new UiMarkdown.Selection();
    private boolean selecting;
    private boolean hasSelection;

    public MarkdownBody(String markdown)
    {
        this.markdown = markdown == null ? "" : markdown;

        this.add(new UIRenderable((ctx) -> this.draw(ctx)));
    }

    private void draw(UIContext context)
    {
        int w = this.area.w - 12;

        if (w <= 0)
        {
            return;
        }

        if (this.layout == null || this.layoutWidth != w)
        {
            this.layout = UiMarkdown.layout(context.batcher, this.markdown, w);
            this.layoutWidth = w;
            this.hasSelection = false;
        }

        if (this.selecting && Window.isMouseButtonPressed(0))
        {
            int[] hit = UiMarkdown.hitTest(context.batcher, this.layout, context.mouseX, context.mouseY, this.area.x + 6, this.area.y + 6);

            this.selection.focusLine = hit[0];
            this.selection.focusPos = hit[1];
            this.hasSelection = !this.selection.isEmpty();
        }

        for (UiMarkdown.ImageSlot slot : this.layout.images)
        {
            this.drawImageSlot(context, slot);
        }

        Area clip = new Area();

        clip.set(this.area.x, this.area.y, this.area.w, this.area.h);
        context.batcher.clip(clip, context);
        UiMarkdown.render(context, this.layout, this.area.x + 6, this.area.y + 6, this.hasSelection ? this.selection : null);
        context.batcher.unclip(context);
    }

    private void drawImageSlot(UIContext context, UiMarkdown.ImageSlot slot)
    {
        int x = this.area.x + 6;
        int y = this.area.y + 6 + slot.y;

        context.batcher.box(x, y, x + slot.width, y + slot.height, mchorse.bbs_mod.BBSSettings.chromeSurface());

        Texture texture = WebImages.get(slot.url);

        if (texture != null)
        {
            UINewsStrip.drawCover(context.batcher, texture, x, y, slot.width, slot.height);
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

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (context.mouseButton == 0 && this.area.isInside(context))
        {
            this.selecting = true;
            this.hasSelection = false;

            UiMarkdown.Layout current = this.layout == null ? new UiMarkdown.Layout() : this.layout;
            int[] hit = UiMarkdown.hitTest(context.batcher, current, context.mouseX, context.mouseY, this.area.x + 6, this.area.y + 6);

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
}
