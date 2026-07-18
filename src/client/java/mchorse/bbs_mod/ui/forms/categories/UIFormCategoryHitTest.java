package mchorse.bbs_mod.ui.forms.categories;

/** Coordinate-space contract for the collapsible form-category header. */
public final class UIFormCategoryHitTest
{
    private static final int HEADER_LEADING_WIDTH = 30;

    private UIFormCategoryHitTest()
    {}

    public static boolean isHeaderToggle(int localMouseX, int titleWidth)
    {
        return localMouseX >= 0 && localMouseX < HEADER_LEADING_WIDTH + Math.max(titleWidth, 0);
    }
}
