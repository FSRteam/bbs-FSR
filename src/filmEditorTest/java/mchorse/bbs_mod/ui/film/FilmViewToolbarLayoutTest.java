package mchorse.bbs_mod.ui.film;

import mchorse.bbs_mod.ui.film.view.FilmViewToolbarLayout;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Anchored preview tools must remain inside the preview after every dock resize. */
public final class FilmViewToolbarLayoutTest
{
    public static void main(String[] args)
    {
        runAll();
        System.out.println("FilmViewToolbarLayoutTest: all checks passed");
    }

    public static void runAll()
    {
        requireProductionContentSizing();
        reproduceUnfittedToolbarClipping();
        resizeKeepsEveryButtonInsideAndClickable();
        firstLayoutAndReopenKeepBothToolbarsInside();
        shortDockKeepsAllButtonsClickable();
    }

    private static void reproduceUnfittedToolbarClipping()
    {
        UIElement preview = new UIElement();
        UIElement[] buttons = new UIElement[5];

        for (int i = 0; i < buttons.length; i++)
        {
            buttons[i] = new UIElement().wh(20, 20);
        }

        UIElement tools = UI.row(0, 0, buttons);

        tools.relative(preview).x(1F, -4).y(4).anchorX(1F);
        preview.add(tools);
        preview.xy(31, 47).wh(320, 180);
        preview.resize();
        check(tools.area.w == 0 && buttons[4].area.x >= preview.area.ex(),
            "the regression setup must reproduce the zero-width row placing options outside the preview");

        UIElement[] legacyButtons = new UIElement[9];

        for (int i = 0; i < legacyButtons.length; i++)
        {
            legacyButtons[i] = new UIElement().wh(20, 20);
        }

        UIElement icons = UI.row(0, 0, legacyButtons);

        icons.row().resize();
        icons.relative(preview).x(0.5F).y(1F).anchor(0.5F, 1F);
        preview.add(icons);
        preview.wh(109, 120);
        preview.resize();
        check(legacyButtons[0].area.x < preview.area.x && legacyButtons[8].area.ex() > preview.area.ex(),
            "the regression setup must reproduce both clipped ends of the nine-button row at GUI scale two");
    }

    private static void resizeKeepsEveryButtonInsideAndClickable()
    {
        TestPreview preview = new TestPreview();
        UIContext context = new UIContext(null);
        int[][] layouts = {
            {320, 90, 180, 20, 100, 20},
            {109, 90, 100, 40, 100, 20},
            {108, 90, 100, 40, 100, 20},
            {107, 110, 100, 40, 80, 40},
            {80, 120, 80, 60, 60, 40},
            {64, 140, 60, 60, 40, 60},
            {48, 180, 40, 100, 40, 60},
            {28, 320, 20, 180, 20, 100},
            {218, 90, 180, 20, 100, 20},
            {179, 90, 160, 40, 100, 20},
            {180, 90, 180, 20, 100, 20},
            {1024, 180, 180, 20, 100, 20},
            {109, 90, 100, 40, 100, 20}
        };

        for (int[] layout : layouts)
        {
            preview.xy(31, 47).wh(layout[0], layout[1]);
            preview.resize();

            check(preview.icons.area.w == layout[2] && preview.icons.area.h == layout[3],
                "the complete bottom toolbar must wrap immediately at width " + layout[0]);
            check(preview.tools.area.w == layout[4] && preview.tools.area.h == layout[5],
                "the complete top toolbar must wrap immediately at width " + layout[0]);
            check(preview.icons.area.mx() == preview.area.mx() && preview.icons.area.ey() == preview.area.ey(),
                "wrapped bottom controls lost their centered bottom anchor");
            check(preview.tools.area.ex() == preview.area.ex() - 4 && preview.tools.area.y == preview.area.y + 4,
                "wrapped view tools lost their top-right inset");
            check(preview.tools.area.ey() <= preview.icons.area.y,
                "top and bottom toolbars overlap");
            assertButtons(preview, preview, context);
        }
    }

    private static void firstLayoutAndReopenKeepBothToolbarsInside()
    {
        TestPreview preview = new TestPreview();
        TestPreview neighbor = new TestPreview();
        UIElement dock = new UIElement();
        UIContext context = new UIContext(null);

        dock.wh(400, 220);
        preview.xy(11, 17).wh(48, 180);
        neighbor.xy(66, 17).wh(109, 180);
        dock.add(preview, neighbor);
        dock.resize();
        check(preview.icons.area.h == 100 && preview.tools.area.h == 60,
            "a newly opened narrow dock must not need a second resize to discover its toolbar heights");
        assertButtons(preview, dock, context);
        assertButtons(neighbor, dock, context);

        preview.removeFromParent();
        preview.xy(186, 17).wh(109, 180);
        dock.add(preview);
        dock.resize();
        check(preview.icons.area.h == 40 && preview.tools.area.h == 20,
            "reopening a preview must discard the previous narrower toolbar layout");
        assertButtons(preview, dock, context);
        assertButtons(neighbor, dock, context);
    }

    private static void assertButtons(TestPreview preview, UIElement root, UIContext context)
    {
        for (UIElement toolbar : new UIElement[] {preview.icons, preview.tools})
        {
            check(contains(preview.area, toolbar.area) && toolbar.isVisible() && toolbar.canBeRendered(preview.area),
                "a complete toolbar escaped the preview clip or was culled");

            for (int i = 0; i < toolbar.getChildren().size(); i++)
            {
                TestIcon button = (TestIcon) toolbar.getChildren().get(i);

                check(button.area.w == 20 && button.area.h == 20,
                    "wrapping must retain each real UIIcon's default button and hit size");
                check(contains(toolbar.area, button.area) && contains(preview.area, button.area),
                    "a button or its hit area is clipped outside the toolbar");
                check(button.isVisible() && button.canBeRendered(preview.area), "a button was hidden or culled");

                for (UIElement otherToolbar : new UIElement[] {preview.icons, preview.tools})
                {
                    for (int j = 0; j < otherToolbar.getChildren().size(); j++)
                    {
                        UIElement otherButton = (UIElement) otherToolbar.getChildren().get(j);

                        if (otherButton == button)
                        {
                            continue;
                        }

                        Area other = otherButton.area;

                        check(button.area.ex() <= other.x || other.ex() <= button.area.x
                            || button.area.ey() <= other.y || other.ey() <= button.area.y,
                            "two buttons overlap within or across the toolbars");
                    }
                }

                int clicks = button.clicks;

                context.setMouse(button.area.mx(), button.area.my(), 0);
                check(root.mouseClicked(context) == button && button.clicks == clicks + 1,
                    "a visible button center did not route through the UI hierarchy to its callback");
                root.mouseReleased(context);
            }
        }
    }

    private static boolean contains(Area outer, Area inner)
    {
        return inner.x >= outer.x && inner.ex() <= outer.ex()
            && inner.y >= outer.y && inner.ey() <= outer.ey();
    }

    private static void shortDockKeepsAllButtonsClickable()
    {
        TestPreview preview = new TestPreview();
        UIElement dock = new UIElement();
        UIContext context = new UIContext(null);
        int[][] sizes = {
            {80, 80}, {81, 80}, {99, 80}, {100, 80}, {107, 80}, {108, 80}, {109, 80},
            {80, 103}, {80, 104}, {80, 120}, {109, 90}, {80, 80}
        };

        dock.wh(400, 220);
        dock.add(preview);

        for (int[] size : sizes)
        {
            preview.xy(11, 17).wh(size[0], size[1]);
            dock.resize();
            check(preview.icons.getChildren().size() == 9 && preview.tools.getChildren().size() == 5,
                "short docks must retain every button in its original toolbar");
            assertButtons(preview, dock, context);
        }

        check(preview.tools.area.w == 80 && preview.tools.area.h == 40
            && preview.icons.area.w == 60 && preview.icons.area.h == 60,
            "the minimum dock must fit all fourteen full-size buttons into four shared rows");
        check(preview.tools.area.x == preview.area.x && preview.tools.area.y == preview.area.y
            && preview.icons.area.x == preview.area.x + 20 && preview.icons.area.y == preview.area.y + 20,
            "the bottom toolbar must use the free columns beside the tools' last row");
    }

    private static void requireProductionContentSizing()
    {
        Path path = Path.of("src/client/java/mchorse/bbs_mod/ui/film/UIFilmPreview.java");

        try
        {
            String source = Files.readString(path);
            int start = source.indexOf("private void setupViewTools()");
            int end = source.indexOf("private void viewOptions(", start);

            check(start >= 0 && end > start, "the preview toolbar setup could not be located");
            String setup = source.substring(start, end);
            int create = setup.indexOf("this.viewTools = UI.row(");
            int size = setup.indexOf("this.viewTools.row().resize();", create);
            int attach = setup.indexOf("this.add(this.viewTools);", size);

            check(create >= 0 && size > create && attach > size,
                "the production toolbar must enable row content sizing before mounting");
            check(source.contains("protected void afterResizeApplied()")
                && source.contains("FilmViewToolbarLayout.fit(this, this.icons, this.viewTools);"),
                "production must fit both toolbars after receiving the current dock bounds");
        }
        catch (IOException exception)
        {
            throw new AssertionError("Could not read the preview toolbar source", exception);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private FilmViewToolbarLayoutTest()
    {}

    private static final class TestPreview extends UIElement
    {
        private final UIElement icons = toolbar(9);
        private final UIElement tools = toolbar(5);

        private static UIElement toolbar(int count)
        {
            UIElement toolbar = UI.row(0, 0);

            toolbar.row().resize();

            for (int i = 0; i < count; i++)
            {
                toolbar.add(new TestIcon());
            }

            return toolbar;
        }

        private TestPreview()
        {
            this.icons.relative(this).x(0.5F).y(1F).anchor(0.5F, 1F);
            this.tools.relative(this).x(1F, -4).y(4).anchorX(1F);
            this.add(this.icons, this.tools);
        }

        @Override
        protected void afterResizeApplied()
        {
            FilmViewToolbarLayout.fit(this, this.icons, this.tools);
        }
    }

    private static final class TestIcon extends UIIcon
    {
        private int clicks;

        private TestIcon()
        {
            super((Icon) null, null);
            this.callback = button -> this.clicks++;
        }

        @Override
        protected void playClickSound()
        {}
    }
}
