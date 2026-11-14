package mchorse.bbs_mod.utils.iris;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Neutral, Iris-free snapshot of a shader pack's options menu.
 *
 * <p>Built by {@link IrisShaderMenu#buildShaderMenu()} from Iris' {@code OptionMenuContainer}, this
 * model lets the BBS UI mirror the layout/UX of the Iris shader-options menu (screens, columns,
 * sliders, toggles, sub-page links, profiles) without dragging Iris classes into the UI layer.</p>
 *
 * <p>Options are split into three sets relative to {@code ShaderCurves.variableMap} (the curvable
 * keys, <b>C</b>) and the set of option ids surfaced anywhere in the menu (<b>M</b>):</p>
 * <ul>
 *     <li>{@link #curvableInMenu} — <b>M &cap; C</b>: active cells (click = add curve channel).</li>
 *     <li>{@link #nonCurvableInMenu} — <b>M \ C</b>: shown for context, not animatable.</li>
 *     <li>{@link #curvableNotInMenu} — <b>C \ M</b>: only reachable through the legacy list.</li>
 * </ul>
 */
public class ShaderMenu
{
    /** Sentinel id of the root screen (Iris' main screen has no key). */
    public static final String MAIN_SCREEN = "";

    public final Screen mainScreen;
    public final Map<String, Screen> subScreens;

    public final Set<String> curvableInMenu;
    public final Set<String> nonCurvableInMenu;
    public final Set<String> curvableNotInMenu;

    public ShaderMenu(Screen mainScreen, Map<String, Screen> subScreens, Set<String> curvableInMenu, Set<String> nonCurvableInMenu, Set<String> curvableNotInMenu)
    {
        this.mainScreen = mainScreen;
        this.subScreens = subScreens;
        this.curvableInMenu = curvableInMenu;
        this.nonCurvableInMenu = nonCurvableInMenu;
        this.curvableNotInMenu = curvableNotInMenu;
    }

    /**
     * Resolve a screen by id. {@code null}/empty/{@link #MAIN_SCREEN} returns the root screen;
     * any other id is looked up among the sub-screens (may return {@code null}).
     */
    public Screen getScreen(String id)
    {
        if (id == null || id.isEmpty())
        {
            return this.mainScreen;
        }

        return this.subScreens.get(id);
    }

    public enum CellType
    {
        EMPTY, OPTION, LINK, PROFILE
    }

    /**
     * A single screen: an ordered list of {@link Cell cells} laid out across {@link #columnCount}
     * columns, exactly mirroring the order Iris uses (including {@link CellType#EMPTY} separators).
     */
    public static class Screen
    {
        public final String id;
        public final int columnCount;
        public final List<Cell> cells;

        public Screen(String id, int columnCount, List<Cell> cells)
        {
            this.id = id;
            this.columnCount = columnCount;
            this.cells = cells;
        }
    }

    /**
     * A single menu cell. Field relevance depends on {@link #type}; use the static factories.
     */
    public static class Cell
    {
        public final CellType type;

        /* OPTION */
        public final String optionId;
        public final boolean slider;
        public final boolean booleanOption;
        public final boolean curvable;
        public final String currentValue;
        public final List<String> allowedValues;
        public final int valueIndex;

        /* LINK */
        public final String targetScreenId;

        /* PROFILE */
        public final String profileName;
        public final int profileCount;

        private Cell(CellType type, String optionId, boolean slider, boolean booleanOption, boolean curvable, String currentValue, List<String> allowedValues, int valueIndex, String targetScreenId, String profileName, int profileCount)
        {
            this.type = type;
            this.optionId = optionId;
            this.slider = slider;
            this.booleanOption = booleanOption;
            this.curvable = curvable;
            this.currentValue = currentValue;
            this.allowedValues = allowedValues;
            this.valueIndex = valueIndex;
            this.targetScreenId = targetScreenId;
            this.profileName = profileName;
            this.profileCount = profileCount;
        }

        public static Cell empty()
        {
            return new Cell(CellType.EMPTY, null, false, false, false, null, Collections.emptyList(), -1, null, null, 0);
        }

        public static Cell option(String optionId, boolean slider, boolean booleanOption, boolean curvable, String currentValue, List<String> allowedValues, int valueIndex)
        {
            return new Cell(CellType.OPTION, optionId, slider, booleanOption, curvable, currentValue, allowedValues, valueIndex, null, null, 0);
        }

        public static Cell link(String targetScreenId)
        {
            return new Cell(CellType.LINK, null, false, false, false, null, Collections.emptyList(), -1, targetScreenId, null, 0);
        }

        public static Cell profile(String profileName, int profileCount)
        {
            return new Cell(CellType.PROFILE, null, false, false, false, null, Collections.emptyList(), -1, null, profileName, profileCount);
        }
    }
}
