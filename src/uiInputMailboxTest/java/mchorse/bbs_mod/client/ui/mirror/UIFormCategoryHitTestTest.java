package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.ui.forms.categories.UIFormCategoryHitTest;

/** Regression for local-vs-screen coordinates in form-category headers. */
public final class UIFormCategoryHitTestTest
{
    private UIFormCategoryHitTestTest()
    {}

    public static void main(String[] args)
    {
        runAll();
    }

    public static void runAll()
    {
        assertHeaderBoundariesAtOffset(0);
        assertHeaderBoundariesAtOffset(275);
    }

    private static void assertHeaderBoundariesAtOffset(int areaX)
    {
        int titleWidth = 41;
        int firstOutside = 30 + titleWidth;

        check(UIFormCategoryHitTest.isHeaderToggle(areaX - areaX, titleWidth),
            "form-category header rejected its leading boundary at area.x=" + areaX);
        check(UIFormCategoryHitTest.isHeaderToggle(areaX + firstOutside - 1 - areaX, titleWidth),
            "form-category header rejected the last title pixel at area.x=" + areaX);
        check(!UIFormCategoryHitTest.isHeaderToggle(areaX + firstOutside - areaX, titleWidth),
            "form-category header included the first pixel after the title at area.x=" + areaX);
        check(!UIFormCategoryHitTest.isHeaderToggle(areaX - 1 - areaX, titleWidth),
            "form-category header accepted a pixel left of the category at area.x=" + areaX);
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }
}
