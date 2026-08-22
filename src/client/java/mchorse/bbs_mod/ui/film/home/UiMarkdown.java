package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.utils.colors.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Markdown renderer for film home overlays (news details, ad details).
 *
 * <p>Supported subset, chosen because Batcher2D only offers flat single-style
 * text runs: {@code #/##/###} headings (primary color), {@code **bold**},
 * {@code *italic*}, {@code `code`} and {@code [text](url)} plus bare
 * http(s) links (emphasis through color, links in primary), {@code - } bullet
 * lists, {@code > } quotes (accent bar), fenced {@code ```} code blocks
 * (muted) and blank-line paragraph spacing. Everything unknown renders as a
 * plain paragraph, so malformed input never crashes — it just looks plain.
 */
public class UiMarkdown
{
    public enum Style
    {
        PLAIN, HEADING, BOLD, ITALIC, CODE, LINK, MUTED
    }

    public record Segment(String text, Style style)
    {}

    private UiMarkdown()
    {}

    /**
     * Draws the markdown document into the given area (clipped) and returns
     * the total drawn height, so hosts can size scroll regions if needed.
     */
    public static int render(UIContext context, String markdown, int x, int y, int w, int maxHeight)
    {
        if (markdown == null || markdown.isEmpty())
        {
            return 0;
        }

        Batcher2D batcher = context.batcher;
        int fontH = batcher.getFont().getHeight();
        int lineH = fontH + 2;
        int cy = y;
        int bottom = y + maxHeight;

        Area clip = new Area();

        clip.set(x, y, w, maxHeight);
        batcher.clip(clip, context);

        boolean inFence = false;

        for (String rawLine : markdown.split("\n", -1))
        {
            if (cy >= bottom)
            {
                break;
            }

            String line = rawLine.trim();

            if (line.startsWith("```"))
            {
                inFence = !inFence;
                cy += lineH / 2;

                continue;
            }

            if (inFence)
            {
                cy = drawSegments(context, List.of(new Segment(line, Style.CODE)), x + 10, cy, w - 10, lineH, false);

                continue;
            }

            if (line.isEmpty())
            {
                cy += lineH;

                continue;
            }

            if (line.startsWith("!["))
            {
                int close = line.indexOf("](");
                int end = close > 0 ? line.indexOf(')', close) : -1;

                if (close > 0 && end > close)
                {
                    String url = line.substring(close + 2, end);
                    int imgW = Math.min(w, 320);
                    int imgH = Math.min(180, Math.max(80, bottom - cy - lineH));

                    cy = drawImageSlot(context, url, x, cy, imgW, imgH);

                    continue;
                }
            }

            if (line.startsWith("### ") || line.startsWith("## ") || line.startsWith("# "))
            {
                String text = line.substring(line.indexOf(' ') + 1);

                cy = drawSegments(context, List.of(new Segment(text, Style.HEADING)), x, cy, w, lineH, true);
                cy += 2;

                continue;
            }

            if (line.startsWith("- "))
            {
                List<Segment> segments = new ArrayList<>();

                segments.add(new Segment("• ", Style.MUTED));
                segments.addAll(inline(line.substring(2)));

                cy = drawSegments(context, segments, x + 10, cy, w - 10, lineH, false);

                continue;
            }

            if (line.startsWith("> "))
            {
                batcher.box(x + 2, cy + 1, x + 4, cy + lineH - 1, BBSSettings.primaryColor());
                cy = drawSegments(context, inline(line.substring(2)), x + 12, cy, w - 12, lineH, false);

                continue;
            }

            cy = drawSegments(context, inline(line), x, cy, w, lineH, false);
            cy += 3;
        }

        batcher.unclip(context);

        return cy - y;
    }

    /**
     * One markdown image block: a fixed-ratio slot showing a spinner while the
     * image downloads, the cover-cropped picture once it lands, or an error
     * note after repeated failures.
     */
    private static int drawImageSlot(UIContext context, String url, int x, int y, int w, int h)
    {
        Batcher2D batcher = context.batcher;

        batcher.box(x, y, x + w, y + h, BBSSettings.chromeSurface());

        Texture texture = WebImages.get(url);

        if (texture != null)
        {
            UINewsStrip.drawCover(batcher, texture, x, y, w, h);

            return y + h + 6;
        }

        float cx = x + w / 2F;
        float cy = y + h / 2F;
        boolean failed = WebImages.isCoolingDown(url);
        String label = failed ? L10n.lang("bbs.ui.film.home.load_failed").get() : L10n.lang("bbs.ui.film.home.loading").get();

        if (!failed)
        {
            WebImages.drawSpinner(context, cx, cy - 7, BBSSettings.accentColorRGB());
        }

        batcher.textShadow(label, cx - batcher.getFont().getWidth(label) / 2F, cy + 8, BBSSettings.mutedTextColor());

        return y + h + 6;
    }

    /** Word-wraps styled segments and draws them left to right. */
    private static int drawSegments(UIContext context, List<Segment> segments, int x, int y, int w, int lineH, boolean heading)
    {
        Batcher2D batcher = context.batcher;

        /* Flatten into words carrying their style. */
        List<Segment> words = new ArrayList<>();

        for (Segment segment : segments)
        {
            String[] parts = segment.text().split(" ", -1);

            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                {
                    words.add(new Segment(" ", segment.style()));
                }

                if (!parts[i].isEmpty())
                {
                    words.add(new Segment(parts[i], segment.style()));
                }
            }
        }

        int cx = x;
        int color = BBSSettings.textColor();

        for (Segment word : words)
        {
            int wordW = batcher.getFont().getWidth(word.text());

            if (cx + wordW > x + w && cx > x)
            {
                cx = x;
                y += lineH;
            }

            batcher.textShadow(word.text(), cx, y, color(word.style()));
            cx += wordW;
        }

        return y + lineH + (heading ? 0 : 0);
    }

    /** Parses inline markers into styled segments. */
    private static List<Segment> inline(String text)
    {
        List<Segment> segments = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;

        while (i < text.length())
        {
            if (text.startsWith("**", i))
            {
                int end = text.indexOf("**", i + 2);

                if (end > 0)
                {
                    flush(segments, plain, Style.PLAIN);
                    segments.add(new Segment(text.substring(i + 2, end), Style.BOLD));
                    i = end + 2;

                    continue;
                }
            }

            if (text.charAt(i) == '`')
            {
                int end = text.indexOf('`', i + 1);

                if (end > 0)
                {
                    flush(segments, plain, Style.PLAIN);
                    segments.add(new Segment(text.substring(i + 1, end), Style.CODE));
                    i = end + 1;

                    continue;
                }
            }

            if (text.charAt(i) == '[')
            {
                int close = text.indexOf("](", i + 1);
                int urlEnd = close > 0 ? text.indexOf(')', close) : -1;

                if (close > 0 && urlEnd > close)
                {
                    flush(segments, plain, Style.PLAIN);
                    segments.add(new Segment(text.substring(i + 1, close), Style.LINK));
                    i = urlEnd + 1;

                    continue;
                }
            }

            if (text.startsWith("http://", i) || text.startsWith("https://", i))
            {
                int end = i;

                while (end < text.length() && !Character.isWhitespace(text.charAt(end)))
                {
                    end += 1;
                }

                flush(segments, plain, Style.PLAIN);
                segments.add(new Segment(text.substring(i, end), Style.LINK));
                i = end;

                continue;
            }

            plain.append(text.charAt(i));
            i += 1;
        }

        flush(segments, plain, Style.PLAIN);

        return segments;
    }

    /** Italic shares the bold treatment: the atlas has no weight variants, so emphasis is color. */
    private static void flush(List<Segment> segments, StringBuilder plain, Style style)
    {
        if (plain.length() > 0)
        {
            segments.add(new Segment(plain.toString(), style));
            plain.setLength(0);
        }
    }

    private static int color(Style style)
    {
        switch (style)
        {
            case HEADING: return BBSSettings.primaryColor();
            case BOLD:
            case ITALIC: return BBSSettings.highlightColor();
            case CODE: return BBSSettings.activeColor();
            case LINK: return BBSSettings.primaryColor();
            case MUTED: return BBSSettings.mutedTextColor();
            default: return BBSSettings.textColor();
        }
    }
}
