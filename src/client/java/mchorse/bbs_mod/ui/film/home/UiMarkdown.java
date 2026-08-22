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
 * lists, {@code > } quotes (accent bar), fenced {@code ```} code blocks,
 * {@code ![alt](url)} images (async loaded through {@link WebImages}) and
 * blank-line paragraph spacing. Everything unknown renders as a plain
 * paragraph, so malformed input never crashes — it just looks plain.
 *
 * <p>Rendering is split into {@link #layout} (measured, position-stable lines)
 * and {@link #render}, which enables hit testing and partial text selection
 * ({@link #hitTest} + {@link #extract}).
 */
public class UiMarkdown
{
    public enum Style
    {
        PLAIN, HEADING, BOLD, ITALIC, CODE, LINK, MUTED
    }

    public record Segment(String text, Style style)
    {}

    /** One styled word placed at an x offset within its line. */
    public static class Run
    {
        public final Style style;
        public final String text;
        public final int x;
        public final int width;

        public Run(Style style, String text, int x, int width)
        {
            this.style = style;
            this.text = text;
            this.x = x;
            this.width = width;
        }
    }

    /** A single visual line of laid out text. */
    public static class Line
    {
        public final int y;
        public final int height;
        public final List<Run> runs = new ArrayList<>();
        public final StringBuilder plain = new StringBuilder();

        public Line(int y, int height)
        {
            this.y = y;
            this.height = height;
        }

        public void add(Run run)
        {
            this.runs.add(run);
            this.plain.append(run.text);
        }
    }

    /** Measured document: visual lines plus overall drawn height. */
    public static class Layout
    {
        public final List<Line> lines = new ArrayList<>();
        public int height;
        /** Image slots reserved by ![alt](url) blocks, in document order. */
        public final List<ImageSlot> images = new ArrayList<>();
    }

    public static class ImageSlot
    {
        public final String url;
        public final int y;
        public final int width;
        public final int height;

        public ImageSlot(String url, int y, int width, int height)
        {
            this.url = url;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** Character-range selection across two laid out lines. */
    public static class Selection
    {
        public int anchorLine;
        public int anchorPos;
        public int focusLine;
        public int focusPos;

        public boolean isEmpty()
        {
            return this.anchorLine == this.focusLine && this.anchorPos == this.focusPos;
        }
    }

    private UiMarkdown()
    {}

    /**
     * Measures the document into position-stable lines against the given
     * wrap width. Pure measurement — safe to call again whenever the width
     * or the content changes.
     */
    public static Layout layout(Batcher2D batcher, String markdown, int w)
    {
        Layout layout = new Layout();
        int fontH = batcher.getFont().getHeight();

        /* Airier leading, matching list rows elsewhere in the UI (~16px for 9px text) */
        int lineH = fontH + 6;
        int cy = 0;
        boolean inFence = false;

        if (markdown == null || markdown.isEmpty())
        {
            return layout;
        }

        for (String rawLine : markdown.split("\n", -1))
        {
            String line = rawLine.trim();

            if (line.startsWith("```"))
            {
                inFence = !inFence;
                cy += lineH / 2;

                continue;
            }

            if (inFence)
            {
                cy = appendText(batcher, layout, line, Style.CODE, x0(layout, w, 10), cy, w - 10, lineH);

                continue;
            }

            if (line.startsWith("![") )
            {
                int close = line.indexOf("](");
                int end = close > 0 ? line.indexOf(')', close) : -1;

                if (close > 0 && end > close)
                {
                    String url = line.substring(close + 2, end);
                    int imgW = Math.min(w, 320);
                    int imgH = Math.min(180, Math.max(80, imgW * 9 / 16));

                    layout.images.add(new ImageSlot(url, cy, imgW, imgH));
                    cy += imgH + 6;

                    continue;
                }
            }

            if (line.isEmpty())
            {
                cy += lineH;

                continue;
            }

            if (line.startsWith("### ") || line.startsWith("## ") || line.startsWith("# "))
            {
                List<Segment> segments = new ArrayList<>();

                segments.add(new Segment(line.substring(line.indexOf(' ') + 1), Style.HEADING));
                cy = appendSegments(batcher, layout, segments, 0, cy, w, lineH);

                continue;
            }

            if (line.startsWith("- "))
            {
                List<Segment> segments = new ArrayList<>();

                segments.add(new Segment("• ", Style.MUTED));
                segments.addAll(inline(line.substring(2)));
                cy = appendSegments(batcher, layout, segments, 10, cy, w - 10, lineH);

                continue;
            }

            if (line.startsWith("> "))
            {
                layout.lines.add(new QuoteMarkLine(cy, lineH));
                cy = appendSegments(batcher, layout, inline(line.substring(2)), 12, cy, w - 12, lineH);

                continue;
            }

            cy = appendSegments(batcher, layout, inline(line), 0, cy, w, lineH);
            cy += 3;
        }

        layout.height = cy;

        return layout;
    }

    /** Quote marker line: renders as a small accent bar instead of text. */
    public static class QuoteMarkLine extends Line
    {
        public QuoteMarkLine(int y, int height)
        {
            super(y, height);
        }
    }

    private static int x0(Layout layout, int w, int offset)
    {
        return offset;
    }

    private static int appendText(Batcher2D batcher, Layout layout, String text, Style style, int indent, int cy, int w, int lineH)
    {
        return appendSegments(batcher, layout, List.of(new Segment(text, style)), indent, cy, w, lineH);
    }

    private static int appendSegments(Batcher2D batcher, Layout layout, List<Segment> segments, int indent, int cy, int w, int lineH)
    {
        List<Run> words = flatten(segments);
        int ly = cy;
        Line line = new Line(ly, lineH);

        layout.lines.add(line);

        int cx = indent;

        for (Run word : words)
        {
            int width = batcher.getFont().getWidth(word.text);

            if (width > w)
            {
                /* Unbreakable oversized word (CJK text has no spaces):
                 * place it character by character, wrapping mid-word. */
                for (int i = 0; i < word.text.length(); i++)
                {
                    String c = String.valueOf(word.text.charAt(i));
                    int cw = batcher.getFont().getWidth(c);

                    if (cx + cw > indent + w && cx > indent)
                    {
                        ly += lineH;
                        line = new Line(ly, lineH);
                        layout.lines.add(line);
                        cx = indent;
                    }

                    line.add(new Run(word.style, c, cx, cw));
                    cx += cw;
                }

                continue;
            }

            if (cx + width > indent + w && cx > indent)
            {
                ly += lineH;
                line = new Line(ly, lineH);
                layout.lines.add(line);
                cx = indent;
            }

            line.add(new Run(word.style, word.text, cx, width));
            cx += width;
        }

        return ly + lineH;
    }

    private static List<Run> flatten(List<Segment> segments)
    {
        List<Run> words = new ArrayList<>();

        for (Segment segment : segments)
        {
            String[] parts = segment.text().split(" ", -1);

            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                {
                    words.add(new Run(segment.style(), " ", 0, 0));
                }

                if (!parts[i].isEmpty())
                {
                    words.add(new Run(segment.style(), parts[i], 0, 0));
                }
            }
        }

        return words;
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
                    flush(segments, plain);
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
                    flush(segments, plain);
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
                    flush(segments, plain);
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

                flush(segments, plain);
                segments.add(new Segment(text.substring(i, end), Style.LINK));
                i = end;

                continue;
            }

            plain.append(text.charAt(i));
            i += 1;
        }

        flush(segments, plain);

        return segments;
    }

    private static void flush(List<Segment> segments, StringBuilder plain)
    {
        if (plain.length() > 0)
        {
            segments.add(new Segment(plain.toString(), Style.PLAIN));
            plain.setLength(0);
        }
    }

    /* Rendering */

    /**
     * Draws a laid out document at the given origin. When a selection is
     * passed, highlighted background boxes are drawn behind the covered
     * characters before the text.
     */
    public static void render(UIContext context, Layout layout, int x, int y, Selection selection)
    {
        Batcher2D batcher = context.batcher;

        if (selection != null && !selection.isEmpty())
        {
            int from = Math.min(selection.anchorLine, selection.focusLine);
            int to = Math.max(selection.anchorLine, selection.focusLine);

            for (int i = from; i <= to && i < layout.lines.size(); i++)
            {
                Line line = layout.lines.get(i);

                if (line instanceof QuoteMarkLine)
                {
                    continue;
                }

                int start = charX(batcher, line, charPos(selection, i, true, line.plain.length()));
                int end = charX(batcher, line, charPos(selection, i, false, line.plain.length()));

                if (end > start)
                {
                    batcher.box(x + start, y + line.y, x + end, y + line.y + line.height - 1, BBSSettings.primaryColor(Colors.A25));
                }
            }
        }

        for (Line line : layout.lines)
        {
            if (line instanceof QuoteMarkLine mark)
            {
                batcher.box(x + 2, y + mark.y + 1, x + 4, y + mark.y + mark.height - 1, BBSSettings.primaryColor());

                continue;
            }

            for (Run run : line.runs)
            {
                if (!run.text.isEmpty())
                {
                    batcher.textShadow(run.text, x + run.x, y + line.y, color(run.style));
                }
            }
        }
    }

    /** X offset of a character position within a line (clamped to line length). */
    public static int charX(Batcher2D batcher, Line line, int pos)
    {
        String plain = line.plain.toString();

        pos = Math.max(0, Math.min(pos, plain.length()));

        return batcher.getFont().getWidth(plain.substring(0, pos));
    }

    /** Maps a screen point onto {line index, char position}; clamps to the document. */
    public static int[] hitTest(Batcher2D batcher, Layout layout, int mx, int my, int x, int y)
    {
        if (layout.lines.isEmpty())
        {
            return new int[] {0, 0};
        }

        int lineIndex = 0;

        for (int i = 0; i < layout.lines.size(); i++)
        {
            Line line = layout.lines.get(i);

            if (my < y + line.y + line.height)
            {
                lineIndex = i;

                break;
            }

            lineIndex = i;
        }

        Line line = layout.lines.get(lineIndex);
        String plain = line.plain.toString();
        int pos = 0;

        for (int k = 0; k <= plain.length(); k++)
        {
            if (mx <= x + batcher.getFont().getWidth(plain.substring(0, k)))
            {
                pos = k;

                break;
            }

            pos = k;
        }

        return new int[] {lineIndex, pos};
    }

    /** Plain text covered by the selection (lines joined with newlines). */
    public static String extract(Layout layout, Selection selection)
    {
        int from = Math.min(selection.anchorLine, selection.focusLine);
        int to = Math.max(selection.anchorLine, selection.focusLine);
        StringBuilder builder = new StringBuilder();

        for (int i = from; i <= to && i < layout.lines.size(); i++)
        {
            Line line = layout.lines.get(i);
            String plain = line.plain.toString();
            int a = Math.max(0, Math.min(charPos(selection, i, true, plain.length()), plain.length()));
            int b = Math.max(a, Math.min(charPos(selection, i, false, plain.length()), plain.length()));

            if (b > a || builder.length() > 0 && i > from)
            {
                if (builder.length() > 0)
                {
                    builder.append('\n');
                }

                builder.append(plain, a, b);
            }
        }

        return builder.toString();
    }

    /**
     * Boundary character position of the selection on one line.
     *
     * @param start true for the leading boundary of the selection, false for
     *              the trailing one
     */
    private static int charPos(Selection selection, int line, boolean start, int lineLength)
    {
        int anchorPos = Math.max(0, Math.min(selection.anchorPos, lineLength));
        int focusPos = Math.max(0, Math.min(selection.focusPos, lineLength));

        if (selection.anchorLine == selection.focusLine)
        {
            return start ? Math.min(anchorPos, focusPos) : Math.max(anchorPos, focusPos);
        }

        boolean anchorFirst = selection.anchorLine < selection.focusLine;
        boolean isFirst = line == (anchorFirst ? selection.anchorLine : selection.focusLine);
        boolean isLast = line == (anchorFirst ? selection.focusLine : selection.anchorLine);

        if (isFirst)
        {
            return start ? (anchorFirst ? anchorPos : focusPos) : lineLength;
        }

        if (isLast)
        {
            return start ? 0 : (anchorFirst ? focusPos : anchorPos);
        }

        /* Middle lines are fully covered */
        return start ? 0 : lineLength;
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
