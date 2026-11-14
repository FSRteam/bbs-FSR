package mchorse.bbs_mod.ui.film.utils.shader;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.resizers.layout.GridResizer;
import mchorse.bbs_mod.utils.iris.ShaderMenu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Refreshed shader-curve picker: a stock-BBS-styled mirror of the Iris shader-options menu. Renders
 * the pack's {@link ShaderMenu} as a grid of {@link UIShaderOptionCell cells} (one screen at a time),
 * with a navigation stack for sub-page links and a button that falls back to the legacy flat list.
 *
 * <p>Clicking a curvable option adds a curve channel (via {@code onAddOptionId}); already-animated
 * options show a highlighted outline. Track removal stays on the keyframe sheet's RMB menu.</p>
 *
 * <p>Sizing: {@link #preferredWidth()} /
 * {@link #preferredHeight()} are computed here and passed to {@code UIOverlay.addOverlay(ctx, panel,
 * w, h)} by the caller; height is re-fit per sub-screen on {@link #rebuild()}.</p>
 */
public class UIShaderOptionPicker extends UIOverlayPanel
{
    private static final int GRID_MARGIN = 4;
    private static final int GRID_PADDING = 6;
    private static final int COLUMN_WIDTH = 210;
    private static final int ICONS_WIDTH = 20;
    private static final int TITLE_HEIGHT = 20;
    private static final int MAX_WIDTH = 680;
    private static final int MAX_CONTENT_HEIGHT = 300;

    private final ShaderMenu menu;
    private final Map<String, String> lang;
    private final Set<String> addedOptionIds;
    private final Consumer<String> onAddOptionId;
    private final Runnable openLegacy;

    private final UIScrollView gridView;
    private final GridResizer gridResizer;
    private final UIIcon back;
    private final UIIcon legacy;

    private final Deque<String> stack = new ArrayDeque<>();

    public UIShaderOptionPicker(ShaderMenu menu, Map<String, String> lang, Set<String> addedOptionIds, Consumer<String> onAddOptionId, Runnable openLegacy)
    {
        super(UIKeys.CAMERA_PANELS_PICK_KEY);

        this.menu = menu;
        this.lang = lang;
        this.addedOptionIds = new HashSet<>(addedOptionIds);
        this.onAddOptionId = onAddOptionId;
        this.openLegacy = openLegacy;

        this.gridView = new UIScrollView();
        this.gridResizer = this.gridView.grid(GRID_MARGIN);
        this.gridResizer.padding(GRID_PADDING).height(UIShaderOptionCell.CELL_HEIGHT);
        this.gridView.scroll.scrollSpeed = 20;
        this.gridView.full(this.content);

        this.back = new UIIcon(Icons.MOVE_LEFT, (b) -> this.pop());
        this.back.tooltip(UIKeys.GENERAL_BACK);
        this.legacy = new UIIcon(Icons.LIST, (b) ->
        {
            if (this.openLegacy != null)
            {
                this.close();
                this.openLegacy.run();
            }
        });
        this.legacy.tooltip(UIKeys.CAMERA_PANELS_SHADER_LIST);

        this.content.add(this.gridView);
        this.icons.add(this.back, this.legacy);

        this.stack.push(ShaderMenu.MAIN_SCREEN);
        this.rebuild();
    }

    private void pushScreen(String screenId)
    {
        this.stack.push(screenId);
        this.rebuild();
    }

    private void pop()
    {
        if (this.stack.size() > 1)
        {
            this.stack.pop();
            this.rebuild();
        }
    }

    private void rebuild()
    {
        String screenId = this.stack.peek();
        ShaderMenu.Screen screen = this.menu.getScreen(screenId);

        this.gridView.removeAll();

        if (screen != null)
        {
            this.gridResizer.items(Math.max(1, screen.columnCount));

            for (ShaderMenu.Cell cell : screen.cells)
            {
                this.gridView.add(this.createCell(cell));
            }
        }

        this.title.label = this.screenTitle(screenId);
        this.back.setVisible(this.stack.size() > 1);
        this.gridView.scroll.scrollTo(0);

        if (this.hasParent())
        {
            this.h(this.preferredHeight(screen));
            this.getParent().resize();
        }
    }

    /* === Content-fit sizing (passed to UIOverlay.addOverlay by the caller) === */

    public int preferredWidth()
    {
        int columns = Math.max(1, this.menu.mainScreen.columnCount);
        int content = columns * COLUMN_WIDTH + (columns - 1) * GRID_MARGIN + GRID_PADDING * 2;

        return Math.min(MAX_WIDTH, content + ICONS_WIDTH);
    }

    public int preferredHeight()
    {
        return this.preferredHeight(this.menu.mainScreen);
    }

    private int preferredHeight(ShaderMenu.Screen screen)
    {
        int columns = screen == null ? 1 : Math.max(1, screen.columnCount);
        int count = screen == null ? 0 : screen.cells.size();
        int rows = Math.max(1, (count + columns - 1) / columns);
        int content = rows * UIShaderOptionCell.CELL_HEIGHT + (rows - 1) * GRID_MARGIN + GRID_PADDING * 2;

        return TITLE_HEIGHT + Math.min(MAX_CONTENT_HEIGHT, content);
    }

    private UIShaderOptionCell createCell(ShaderMenu.Cell cell)
    {
        UIShaderOptionCell element = new UIShaderOptionCell(cell, this::onCellActivate);

        element.label(this.cellLabel(cell));
        element.value(this.cellValue(cell));

        if (cell.type == ShaderMenu.CellType.OPTION && cell.optionId != null)
        {
            element.added(this.addedOptionIds.contains(cell.optionId));
            element.tooltip(IKey.constant(this.cellLabel(cell) + " (" + cell.optionId + ")"));
        }

        return element;
    }

    private void onCellActivate(UIShaderOptionCell element)
    {
        ShaderMenu.Cell cell = element.cell;

        if (cell.type == ShaderMenu.CellType.LINK)
        {
            if (cell.targetScreenId != null && this.menu.getScreen(cell.targetScreenId) != null)
            {
                this.pushScreen(cell.targetScreenId);
            }
        }
        else if (cell.type == ShaderMenu.CellType.OPTION && cell.curvable && cell.optionId != null)
        {
            if (this.addedOptionIds.add(cell.optionId))
            {
                if (this.onAddOptionId != null)
                {
                    this.onAddOptionId.accept(cell.optionId);
                }

                element.added(true);
            }
        }
    }

    private IKey screenTitle(String screenId)
    {
        if (screenId == null || screenId.isEmpty())
        {
            return UIKeys.CAMERA_PANELS_PICK_KEY;
        }

        return IKey.constant(this.lang.getOrDefault("screen." + screenId, screenId));
    }

    private String cellLabel(ShaderMenu.Cell cell)
    {
        switch (cell.type)
        {
            case OPTION:
                return this.lang.getOrDefault("option." + cell.optionId, cell.optionId);
            case LINK:
                return this.lang.getOrDefault("screen." + cell.targetScreenId, cell.targetScreenId);
            case PROFILE:
                return UIKeys.CAMERA_PANELS_SHADER_PROFILE.get();
            default:
                return "";
        }
    }

    private String cellValue(ShaderMenu.Cell cell)
    {
        if (cell.type == ShaderMenu.CellType.PROFILE)
        {
            return cell.profileName == null ? UIKeys.CAMERA_PANELS_SHADER_CUSTOM.get() : this.lang.getOrDefault("profile." + cell.profileName, cell.profileName);
        }

        if (cell.type != ShaderMenu.CellType.OPTION)
        {
            return "";
        }

        if (cell.booleanOption)
        {
            return "true".equals(cell.currentValue) ? UIKeys.CAMERA_PANELS_SHADER_ON.get() : UIKeys.CAMERA_PANELS_SHADER_OFF.get();
        }

        /* String option: prefix + localized value + suffix, mirroring StringElementWidget. */
        String value = cell.currentValue == null ? "" : cell.currentValue;
        String prefix = this.lang.getOrDefault("prefix." + cell.optionId, "");
        String suffix = this.lang.getOrDefault("suffix." + cell.optionId, "");
        String localized = this.lang.getOrDefault("value." + cell.optionId + "." + value, value);

        return prefix + localized + suffix;
    }
}
