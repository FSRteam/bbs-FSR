package mchorse.bbs_mod.ui.framework.elements.input.grid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level regression for the card grid used by the film home: theme
 * tokens must drive plate rendering (rounded fallback included), selection
 * and hover overlays must come from the primary color, arrow key navigation
 * must exist, and no semantic colors may be hardcoded.
 */
public final class UICardGridSourceTest
{
    private static final Path GRID = Path.of(
        "src/client/java/mchorse/bbs_mod/ui/framework/elements/input/grid/UICardGrid.java"
    );

    public static void runAll() throws IOException
    {
        String source = Files.readString(GRID);

        check(source.contains("class UICardGrid<T> extends UIElement"),
            "card grid must extend the UI element base");
        check(source.contains("BBSSettings.cornerWidget()"),
            "corner radius must come from the corner widget token");
        check(source.contains("roundedFrame") && source.contains("batcher.box(") && source.contains("batcher.outline("),
            "plate rendering must keep the square fallback alongside rounded frames");
        check(source.contains("BBSSettings.primaryColor(Colors.A12)")
                && source.contains("BBSSettings.primaryColor(Colors.A25)"),
            "selection and hover overlays must use primary color tints");
        check(source.contains("GLFW.GLFW_KEY_LEFT") && source.contains("GLFW.GLFW_KEY_DOWN"),
            "arrow key navigation must be implemented");
        check(source.contains("scroll.scrollIntoView("),
            "keyboard navigation must scroll the selection into view");
        check(source.contains("scroll.renderScrollbar("),
            "grid must render its scrollbar through Scroll");
        check(!containsHardcodedColor(source),
            "semantic colors must not be hardcoded");
    }

    /**
     * Flags raw hex literals that look like ARGB colors. Colors.* constants
     * are pure alpha masks/whites and are allowed by the theming spec.
     */
    private static boolean containsHardcodedColor(String source)
    {
        Matcher matcher = Pattern.compile("0x[0-9a-fA-F]{6,8}").matcher(source);

        while (matcher.find())
        {
            int start = matcher.start();
            String prefix = source.substring(Math.max(0, start - 8), start);

            if (!prefix.contains("GLFW_KEY"))
            {
                return true;
            }
        }

        return false;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private UICardGridSourceTest()
    {}
}
