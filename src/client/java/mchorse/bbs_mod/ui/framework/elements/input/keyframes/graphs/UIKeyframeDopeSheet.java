package mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs;

import com.mojang.blaze3d.systems.RenderSystem;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UISection;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeGroup;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.IKeyframeShapeRenderer;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.shapes.KeyframeShapeRenderers;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.Scroll;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.renderers.TimelineRulerRenderer;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeShape;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

public class UIKeyframeDopeSheet implements IUIKeyframeGraph
{
    private static final int POSE_TAB_BASE_INDENT = 4;
    private static final int POSE_TAB_DEPTH_STEP = 4;
    private static final float TRACK_BAR_ALPHA = 0.3F;

    private static final int LABEL_TEXT_LEFT = 5;
    private static final int LABEL_RIGHT_PAD = 2;
    private static final int LABEL_ICON_SIZE = 16;
    private static final int LABEL_TEXT_ICON_GAP = 3;
    private static final int LABEL_ARROW_SIZE = 10;

    /** Below this width the label column is reduced to its icon controls; titles hide altogether. */
    private static final int LABEL_COMPACT_WIDTH = 60;

    private UIKeyframes keyframes;

    @Override
    public UIKeyframes getKeyframes()
    {
        return this.keyframes;
    }

    private List<UIKeyframeSheet> sheets = new ArrayList<>();
    private Map<UIKeyframeSheet, Integer> sheetYCache = new HashMap<>();
    private UIKeyframeSheet lastSheet;
    private final Map<UIKeyframeSheet, Integer> sheetRowCache = new HashMap<>();
    private FoldState<String> folds = new FoldState<>(true);
    private int legacyGroupSequence;

    /* Legacy groups are normalized once at insertion; rendering only sees sections and sheets. */
    private final Map<UIKeyframeSheet.Section, Integer> legacySectionOffsets = new LinkedHashMap<>();
    private final Map<UIKeyframeSheet.Section, UIKeyframeSheet.Section> sectionParents = new HashMap<>();
    private final Map<UIKeyframeSheet.Section, UIKeyframeGroup> legacyGroups = new HashMap<>();
    private final Map<UIKeyframeSheet, UIKeyframeSheet.Section> originalSections = new HashMap<>();

    /** Section header rows in first-appearance order, rebuilt by every {@link #updateScrollSize()}. */
    private final Map<UIKeyframeSheet.Section, Integer> sectionYCache = new LinkedHashMap<>();

    /** Owner-provided fold state per section id, so folds survive the timeline being rebuilt. */
    private Map<String, Boolean> sectionFolds = new HashMap<>();

    /** What to draw when there are no tracks at all - see {@link #setEmptyState(IKey, IKey)}. */
    private IKey emptyLabel;
    private IKey emptyHint;

    private Scroll dopeSheet;
    private double trackHeight;

    public static IKeyframeShapeRenderer renderShape(Keyframe frame, UIContext context, BufferBuilder builder, Matrix4f matrix, int x, int y, int offset, int c)
    {
        KeyframeShape keyframeShape = frame.getShape();
        IKeyframeShapeRenderer shape = KeyframeShapeRenderers.SHAPES.get(keyframeShape);

        shape.renderKeyframe(context, builder, matrix, x, y, offset, c);

        return shape;
    }

    public UIKeyframeDopeSheet(UIKeyframes keyframes)
    {
        this.keyframes = keyframes;
        this.dopeSheet = new Scroll(this.keyframes.area);
        this.dopeSheet.smoothScrolling(() -> !BBSSettings.scrollingDisableSmoothnessInEditors.get());
        this.dopeSheet.wheelScrollStep(() -> (int) this.trackHeight);

        this.setTrackHeight(16);
    }

    public double getTrackHeight()
    {
        return this.trackHeight;
    }

    public void setTrackHeight(double height)
    {
        this.trackHeight = MathUtils.clamp(height, 8D, 100D);
        this.updateScrollSize();
    }

    private void updateScrollSize()
    {
        this.sheetYCache.clear();
        this.sheetRowCache.clear();
        this.sectionYCache.clear();

        int y = 0;
        int row = 0;

        for (int i = 0; i <= this.sheets.size(); i++)
        {
            for (Map.Entry<UIKeyframeSheet.Section, Integer> entry : this.legacySectionOffsets.entrySet())
            {
                if (entry.getValue() == i && this.isSectionVisible(entry.getKey()))
                {
                    this.sectionYCache.put(entry.getKey(), y);
                    y += (int) this.trackHeight;
                    row++;
                }
            }

            if (i == this.sheets.size())
            {
                break;
            }

            UIKeyframeSheet sheet = this.sheets.get(i);
            sheet.folded = !this.folds.isExpanded(sheet.id);

            if (sheet.section != null && !this.sectionYCache.containsKey(sheet.section) && this.isSectionVisible(sheet.section))
            {
                this.sectionYCache.put(sheet.section, y);
                y += (int) this.trackHeight;
                row++;
            }

            if (this.isVisible(sheet))
            {
                this.sheetYCache.put(sheet, y);
                this.sheetRowCache.put(sheet, row);
                y += this.getTrackHeight(sheet);
                row++;
            }
        }

        this.dopeSheet.scrollSize = y + TOP_MARGIN;
        this.dopeSheet.clamp();
    }

    private boolean isSectionExpanded(UIKeyframeSheet.Section section)
    {
        return this.sectionFolds.getOrDefault(section.id(), Boolean.TRUE) && this.isSectionVisible(section);
    }

    private boolean isSectionVisible(UIKeyframeSheet.Section section)
    {
        for (UIKeyframeSheet.Section parent = this.sectionParents.get(section); parent != null; parent = this.sectionParents.get(parent))
        {
            if (!this.sectionFolds.getOrDefault(parent.id(), Boolean.TRUE))
            {
                return false;
            }
        }

        return true;
    }

    private boolean isWithinSection(UIKeyframeSheet.Section section, UIKeyframeSheet.Section ancestor)
    {
        for (UIKeyframeSheet.Section current = section; current != null; current = this.sectionParents.get(current))
        {
            if (ancestor.equals(current))
            {
                return true;
            }
        }

        return false;
    }

    private int getSectionDepth(UIKeyframeSheet.Section section)
    {
        int depth = 0;

        for (UIKeyframeSheet.Section current = section; current != null; current = this.sectionParents.get(current))
        {
            depth++;
        }

        return depth;
    }

    private void setSectionExpanded(UIKeyframeSheet.Section section, boolean expanded)
    {
        this.sectionFolds.put(section.id(), expanded);
        UIKeyframeGroup group = this.legacyGroups.get(section);

        if (group != null)
        {
            group.collapsed = !expanded;
        }
    }

    public int getTrackHeight(UIKeyframeSheet sheet)
    {
        return (int) this.trackHeight;
    }

    private boolean isVisible(UIKeyframeSheet sheet)
    {
        if (sheet.section != null && !this.isSectionExpanded(sheet.section))
        {
            return false;
        }

        for (UIKeyframeSheet parent = sheet.parent; parent != null; parent = parent.parent)
        {
            if (!this.folds.isExpanded(parent.id))
            {
                return false;
            }
        }

        return true;
    }

    private int getSheetIndent(UIKeyframeSheet sheet)
    {
        int depth = sheet.getDepth() + this.getSectionDepth(sheet.section);

        if (depth == 0)
        {
            return 0;
        }

        int labelWidth = Math.max(1, this.keyframes.getLabelWidth());
        float scale = MathUtils.clamp(labelWidth / 120F, 0.75F, 1.5F);
        int baseIndent = Math.max(1, Math.round(POSE_TAB_BASE_INDENT * scale));
        int depthStep = Math.max(1, Math.round(POSE_TAB_DEPTH_STEP * scale));

        return baseIndent + (depth - 1) * depthStep;
    }

    private List<UIKeyframeSheet> getInteractiveSheets()
    {
        List<UIKeyframeSheet> sheets = new ArrayList<>();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isVisible(sheet))
            {
                sheets.add(sheet);
            }
        }

        return sheets;
    }

    /* Graphing */

    public Scroll getYAxis()
    {
        return this.dopeSheet;
    }

    public int getDopeSheetY()
    {
        return this.keyframes.area.y + TOP_MARGIN - (int) this.dopeSheet.getScroll();
    }

    public int getDopeSheetY(int sheet)
    {
        return this.getDopeSheetY(this.sheets.get(sheet));
    }

    public int getDopeSheetY(UIKeyframeSheet sheet)
    {
        Integer y = this.sheetYCache.get(sheet);

        return this.getDopeSheetY() + (y == null ? 0 : y);
    }

    /**
     * Whether given mouse coordinates are near the given point?
     */
    public static boolean isNear(double x, double y, int mouseX, int mouseY, boolean checkOnlyX)
    {
        if (checkOnlyX)
        {
            return Math.pow(mouseX - x, 2) < 25D;
        }

        return Math.pow(mouseX - x, 2) + Math.pow(mouseY - y, 2) < 25D;
    }

    /* Sheet management */

    @Override
    public void resetView()
    {
        this.keyframes.resetViewX();
    }

    @Override
    public UIKeyframeSheet getLastSheet()
    {
        List<UIKeyframeSheet> sheets = this.getInteractiveSheets();

        if (this.lastSheet != null && sheets.contains(this.lastSheet))
        {
            return this.lastSheet;
        }

        return CollectionUtils.getSafe(sheets, 0);
    }

    @Override
    public List<UIKeyframeSheet> getSheets()
    {
        return this.sheets;
    }

    public void configurePoseTabs(Map<UIKeyframeSheet, List<UIKeyframeSheet>> tabs, Map<UIKeyframeSheet, Integer> depths, Set<String> expandedPoseIds)
    {
        for (Map.Entry<UIKeyframeSheet, List<UIKeyframeSheet>> entry : tabs.entrySet())
        {
            UIKeyframeSheet parent = entry.getKey();
            this.folds.set(parent.id, expandedPoseIds.contains(parent.id));

            for (UIKeyframeSheet child : entry.getValue())
            {
                if (child.parent == null)
                {
                    child.setParent(parent);
                }
            }
        }

        this.updateScrollSize();
    }

    /** Shared hierarchy fold state; legacy pose-tab accessors remain the compatibility view. */
    public void configureTrackFolds(Set<String> expanded)
    {
        this.setExpanded(expanded);
    }

    public void setExpanded(Set<String> expanded)
    {
        FoldState<String> state = new FoldState<>();

        if (expanded != null)
        {
            state.expandAll(expanded);
        }

        this.setExpanded(state);
    }

    /** Adopt the owner's state so rebuilding rows preserves the same folds. */
    public void setExpanded(FoldState<String> folds)
    {
        this.folds = folds == null ? new FoldState<>() : folds;
        this.updateScrollSize();
    }

    public FoldState<String> getExpanded()
    {
        return this.folds;
    }

    public Set<String> getExpandedPoseTabIds()
    {
        Set<String> expandedIds = new HashSet<>();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isFoldable(sheet) && this.folds.isExpanded(sheet.id))
            {
                expandedIds.add(sheet.id);
            }
        }

        return expandedIds;
    }

    public Set<String> getPoseTabIds()
    {
        Set<String> ids = new HashSet<>();

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isFoldable(sheet))
            {
                ids.add(sheet.id);
            }
        }

        return ids;
    }

    public void removeAllSheets()
    {
        for (Map.Entry<UIKeyframeSheet, UIKeyframeSheet.Section> entry : this.originalSections.entrySet())
        {
            entry.getKey().section = entry.getValue();
        }

        for (UIKeyframeSheet.Section section : this.legacySectionOffsets.keySet())
        {
            this.sectionFolds.remove(section.id());
        }

        this.sheets.clear();
        this.legacySectionOffsets.clear();
        this.sectionParents.clear();
        this.legacyGroups.clear();
        this.originalSections.clear();
        this.legacyGroupSequence = 0;
        this.lastSheet = null;
        this.updateScrollSize();
    }

    /**
     * Take over the owner's fold map, so a section left folded stays folded when the timeline is rebuilt
     * (the same "map lives with the owner" contract as {@code UISection#remember}).
     */
    public void configureSectionFolds(Map<String, Boolean> folds)
    {
        if (folds != null)
        {
            this.sectionFolds = folds;
        }

        this.updateScrollSize();
    }

    public boolean hasSections()
    {
        return !this.sectionYCache.isEmpty();
    }

    public boolean hasExpandedSections()
    {
        for (UIKeyframeSheet.Section section : this.sectionYCache.keySet())
        {
            if (this.isSectionExpanded(section))
            {
                return true;
            }
        }

        return false;
    }

    public void setAllSectionsExpanded(boolean expanded)
    {
        Set<UIKeyframeSheet.Section> sections = new HashSet<>(this.sectionYCache.keySet());
        sections.addAll(this.legacySectionOffsets.keySet());

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.section != null)
            {
                sections.add(sheet.section);
            }
        }

        for (UIKeyframeSheet.Section section : sections)
        {
            this.setSectionExpanded(section, expanded);
        }

        if (!expanded)
        {
            for (UIKeyframeSheet sheet : this.sheets)
            {
                if (sheet.section != null)
                {
                    sheet.selection.clear();
                }
            }
        }

        this.updateScrollSize();
        this.pickSelected();
    }

    private void toggleSection(UIKeyframeSheet.Section section)
    {
        boolean expanded = !this.isSectionExpanded(section);

        this.setSectionExpanded(section, expanded);

        if (!expanded)
        {
            for (UIKeyframeSheet sheet : this.sheets)
            {
                if (this.isWithinSection(sheet.section, section))
                {
                    sheet.selection.clear();
                }
            }
        }

        this.updateScrollSize();
        this.pickSelected();
    }

    public void addSheet(UIKeyframeSheet sheet)
    {
        this.sheets.add(sheet);
        this.updateScrollSize();
    }

    /** Normalize the legacy metadata tree into the same section and sheet rows as catalog tracks. */
    public void addElement(UIKeyframeElement element)
    {
        if (element instanceof UIKeyframeSheet sheet)
        {
            this.addSheet(sheet);
        }
        else if (element instanceof UIKeyframeGroup group)
        {
            this.flatten(group, null);
            this.updateScrollSize();
        }
    }

    @Override
    public void clearSelection()
    {
        /* Every sheet, not only the interactive ones: folding a nested track only hides its rows,
         * and a selection made while it was open must stay reachable by clear/remove/navigation
         * instead of going stale behind the fold. */
        for (UIKeyframeSheet sheet : this.sheets)
        {
            sheet.selection.clear();
        }

        this.pickKeyframe(null);
    }

    @Override
    public void selectAll()
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            sheet.selection.all();
        }

        this.pickSelected();
    }

    @Override
    public void selectAfter(float tick, int direction)
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            sheet.selection.after(tick, direction);
        }

        this.pickSelected();
    }

    @Override
    public Keyframe getSelected()
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            Keyframe first = sheet.selection.getFirst();

            if (first != null)
            {
                return first;
            }
        }

        return null;
    }

    @Override
    public UIKeyframeSheet getSheet(String id)
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (sheet.id.equals(id))
            {
                return sheet;
            }
        }

        return null;
    }

    @Override
    public void removeSelected()
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            sheet.selection.removeSelected();
        }

        this.pickKeyframe(null);
    }

    /**
     * Unfold everything hiding given row and bring it into view: its section, every folded ancestor
     * track and a collapsed pose tab. A viewport pick can land on a track that is currently hidden
     * behind any of those, and the selection it just made would be invisible until the user found
     * and unfolded the row by hand. Navigates only — it neither selects nor moves the time viewport.
     */
    public void revealSheet(UIKeyframeSheet sheet)
    {
        if (sheet == null || !this.sheets.contains(sheet))
        {
            return;
        }

        for (UIKeyframeSheet.Section section = sheet.section; section != null; section = this.sectionParents.get(section))
        {
            this.setSectionExpanded(section, true);
        }

        for (UIKeyframeSheet parent = sheet.parent; parent != null; parent = parent.parent)
        {
            this.folds.set(parent.id, true);
        }

        this.updateScrollSize();
        this.lastSheet = sheet;

        int y = this.sheetYCache.getOrDefault(sheet, 0) + TOP_MARGIN;

        /* setScroll rather than scrollTo: the reveal is immediate, like the row jump it accompanies,
         * instead of a tween the next frame would animate away from. */
        this.dopeSheet.setScroll(y - (this.dopeSheet.area.h - this.trackHeight) / 2);
    }

    private void flatten(UIKeyframeGroup group, UIKeyframeSheet.Section parent)
    {
        UIKeyframeSheet.Section section = new UIKeyframeSheet.Section("legacy_group:" + this.legacyGroupSequence++, group.title, null, group.color);
        this.legacySectionOffsets.put(section, this.sheets.size());
        this.legacyGroups.put(section, group);
        this.sectionParents.put(section, parent);
        this.setSectionExpanded(section, !group.collapsed);

        for (UIKeyframeElement child : group.children)
        {
            if (child instanceof UIKeyframeSheet sheet)
            {
                if (sheet.section == null)
                {
                    this.originalSections.put(sheet, null);
                    sheet.section = section;
                }
                else
                {
                    this.sectionParents.put(sheet.section, section);
                }

                this.sheets.add(sheet);
            }
            else if (child instanceof UIKeyframeGroup nested)
            {
                this.flatten(nested, section);
            }
        }
    }

    /* Selection */

    @Override
    public void selectByX(int mouseX)
    {
        for (int i = 0; i < sheets.size(); i++)
        {
            UIKeyframeSheet sheet = sheets.get(i);
            List keyframes = sheet.channel.getKeyframes();

            for (int j = 0; j < keyframes.size(); j++)
            {
                Keyframe keyframe = (Keyframe) keyframes.get(j);
                int x = this.keyframes.toGraphX(keyframe.getTick());
                int y = this.getDopeSheetY(sheet) + (int) this.trackHeight / 2;

                if (this.isNear(x, y, mouseX, 0, true))
                {
                    sheet.selection.add(j);
                }
            }
        }

        this.pickSelected();
    }

    @Override
    public void selectInArea(Area area)
    {
        List<UIKeyframeSheet> sheets = this.getInteractiveSheets();

        for (int i = 0; i < sheets.size(); i++)
        {
            UIKeyframeSheet sheet = sheets.get(i);
            List keyframes = sheet.channel.getKeyframes();

            for (int j = 0; j < keyframes.size(); j++)
            {
                Keyframe keyframe = (Keyframe) keyframes.get(j);
                int x = this.keyframes.toGraphX(keyframe.getTick());
                int y = this.getDopeSheetY(sheet) + (int) this.trackHeight / 2;

                if (area.isInside(x, y))
                {
                    sheet.selection.add(j);
                }
            }
        }

        this.pickSelected();
    }

    @Override
    public UIKeyframeSheet getSheet(int mouseY)
    {
        int relY = mouseY - this.getDopeSheetY();

        for (Map.Entry<UIKeyframeSheet, Integer> entry : this.sheetYCache.entrySet())
        {
            int y = entry.getValue();

            if (relY >= y && relY < y + this.trackHeight)
            {
                return entry.getKey();
            }
        }

        return null;
    }

    @Override
    public boolean addKeyframe(int mouseX, int mouseY)
    {
        return this.addKeyframeAt(this.keyframes.fromGraphCursor(mouseX), mouseY);
    }

    @Override
    public boolean addKeyframeAt(float tick, int mouseY)
    {
        UIKeyframeSheet sheet = this.getSheet(mouseY);

        if (sheet != null)
        {
            this.addKeyframeManually(sheet, tick, null);
        }

        return sheet != null;
    }

    @Override
    public Pair<Keyframe, KeyframeType> findKeyframe(int mouseX, int mouseY)
    {
        UIKeyframeSheet sheet = this.getSheet(mouseY);

        if (sheet == null)
        {
            return null;
        }

        List keyframes = sheet.channel.getKeyframes();
        for (int j = 0; j < keyframes.size(); j++)
        {
            Keyframe keyframe = (Keyframe) keyframes.get(j);
            int x = this.keyframes.toGraphX(keyframe.getTick());
            int y = this.getDopeSheetY(sheet) + (int) this.trackHeight / 2;

            if (this.isNear(x, y, mouseX, mouseY, false))
            {
                return new Pair<>(keyframe, KeyframeType.REGULAR);
            }
        }

        return null;
    }

    @Override
    public void onCallback(Keyframe keyframe)
    {
        UIKeyframeSheet sheet = this.getSheet(keyframe);

        if (sheet != null)
        {
            this.lastSheet = sheet;
        }
    }

    @Override
    public void pickKeyframe(Keyframe keyframe)
    {
        this.keyframes.pickKeyframe(keyframe);
    }

    @Override
    public void selectKeyframe(Keyframe keyframe)
    {
        this.clearSelection();

        UIKeyframeSheet sheet = this.getSheet(keyframe);

        if (sheet != null)
        {
            sheet.selection.add(keyframe);
            this.pickKeyframe(keyframe);

            double x = keyframe.getTick();
            Integer sheetY = this.sheetYCache.get(sheet);
            int y = (sheetY == null ? 0 : sheetY) + TOP_MARGIN;

            this.keyframes.getXAxis().shiftIntoMiddle(x);
            this.dopeSheet.scrollTo((int) (y - (this.dopeSheet.area.h - this.trackHeight) / 2));
        }
    }

    @Override
    public void resize()
    {
        this.dopeSheet.clamp();
    }

    /* Input handling */

    @Override
    public boolean mouseClicked(UIContext context)
    {
        if (this.dopeSheet.mouseClicked(context))
        {
            return true;
        }

        if (context.mouseButton == 0 && this.keyframes.area.isInside(context))
        {
            if (context.mouseX > this.keyframes.area.x + this.keyframes.getLabelWidth())
            {
                return false;
            }

            return this.clickSheets(context);
        }

        return false;
    }

    private boolean clickSheets(UIContext context)
    {
        int labelWidth = this.keyframes.getLabelWidth();

        for (Map.Entry<UIKeyframeSheet.Section, Integer> entry : this.sectionYCache.entrySet())
        {
            int y = this.getDopeSheetY() + entry.getValue();

            if (context.mouseY >= y && context.mouseY < y + this.trackHeight)
            {
                this.toggleSection(entry.getKey());

                return true;
            }
        }

        UIKeyframeSheet sheet = this.getSheet(context.mouseY);

        if (sheet != null)
        {
            int y = this.getDopeSheetY(sheet);

            if (this.isFoldable(sheet) && this.isFoldToggleHit(context, sheet, y, labelWidth))
            {
                this.toggleFold(sheet, Window.isShiftPressed());
            }
            else
            {
                this.addKeyframeManually(sheet, this.keyframes.getTick(), null);
            }

            return true;
        }

        return false;
    }

    private void toggleFold(UIKeyframeSheet sheet, boolean branch)
    {
        this.setFolded(sheet, !this.folds.isExpanded(sheet.id), branch);
        this.updateScrollSize();
        this.pickSelected();
    }

    private void setFolded(UIKeyframeSheet sheet, boolean expanded, boolean branch)
    {
        this.folds.set(sheet.id, expanded);

        if (!expanded)
        {
            sheet.selection.clear();
        }

        for (UIKeyframeSheet child : sheet.children)
        {
            if (branch)
            {
                this.setFolded(child, expanded, true);
            }
            else if (!expanded)
            {
                clearSelectionBelow(child);
            }
        }
    }

    private static void clearSelectionBelow(UIKeyframeSheet sheet)
    {
        sheet.selection.clear();

        for (UIKeyframeSheet child : sheet.children)
        {
            clearSelectionBelow(child);
        }
    }

    @Override
    public void mouseReleased(UIContext context)
    {
        this.dopeSheet.mouseReleased(context);
    }

    @Override
    public void mouseScrolled(UIContext context)
    {
        if (context.mouseWheelHorizontal != 0)
        {
            double offsetX = (25F * BBSSettings.scrollingSensitivityHorizontal.get() * context.mouseWheelHorizontal) / this.keyframes.getXAxis().getZoom();

            this.keyframes.getXAxis().setShift(this.keyframes.getXAxis().getShift() - offsetX);
        }
        else if (Window.isShiftPressed() && !Window.isCtrlPressed())
        {
            this.dopeSheet.mouseScroll(context);
        }
        else if (Window.isAltPressed() && context.mouseWheel != 0D)
        {
            if (this.getSelected() != null)
            {
                float delta = (float) (context.mouseWheel * 1F);
                this.moveSelectedBy(delta, true);
            }
            else
            {
                this.setTrackHeight(this.trackHeight - context.mouseWheel);
            }
        }
        else if (context.mouseWheel != 0D)
        {
            this.keyframes.getXAxis().zoomAnchor(Scale.getAnchorX(context, this.keyframes.graphArea), Math.copySign(this.keyframes.getXAxis().getZoomFactor(), context.mouseWheel));
        }
    }

    @Override
    public void handleMouse(UIContext context, int lastX, int lastY)
    {
        this.dopeSheet.drag(context);

        if (this.keyframes.isNavigating())
        {
            int mouseX = context.mouseX;
            int mouseY = context.mouseY;
            double offset = (mouseX - lastX) / this.keyframes.getXAxis().getZoom();

            this.keyframes.getXAxis().setShift(this.keyframes.getXAxis().getShift() - offset);
            this.dopeSheet.scrollBy(-(mouseY - lastY));
        }
    }

    @Override
    public void dragKeyframes(UIContext context, Pair<Keyframe, KeyframeType> type, int originalX, int originalY, float originalT, Object originalV)
    {
        float offset = (float) (this.keyframes.fromGraphX(originalX) - originalT);
        float tick = (float) this.keyframes.fromGraphX(context.mouseX) - offset;

        if (this.keyframes.isSnappingToTicks())
        {
            tick = Math.round(this.keyframes.fromGraphX(context.mouseX) - offset);
        }

        this.setTick(tick, false);
        this.keyframes.triggerChange();
    }

    /* Rendering */

    @Override
    public void render(UIContext context)
    {
        this.renderGrid(context);
        this.renderGraph(context);
        this.renderTimelineGrid(context);
        this.renderPreviewHints(context);
        this.renderEmptyState(context);
    }

    /**
     * Say why the sheet is blank. Only the owner knows that - an empty curve clip is empty because it
     * has no channels, while the film timeline is empty because the track filter hid every last row.
     */
    public void setEmptyState(IKey label, IKey hint)
    {
        this.emptyLabel = label;
        this.emptyHint = hint;
    }

    private void renderEmptyState(UIContext context)
    {
        if (this.emptyLabel == null || !this.sheets.isEmpty() || !this.legacySectionOffsets.isEmpty())
        {
            return;
        }

        Area area = this.keyframes.graphArea;
        FontRenderer font = context.batcher.getFont();
        int w = (int) (area.w / 1.5F);
        int x = area.mx() - w / 2;
        int y = area.my() - font.getHeight();

        context.batcher.wallText(this.emptyLabel.get(), x, y, Colors.setA(Colors.WHITE, 0.5F), w, 12, 0.5F, 0F, true);

        if (this.emptyHint != null)
        {
            context.batcher.wallText(this.emptyHint.get(), x, y + font.getHeight() + 6, Colors.setA(Colors.WHITE, 0.25F), w, 12, 0.5F, 0F, true);
        }
    }

    /**
     * Render grid that allows easier to see where are specific ticks
     */
    protected void renderGrid(UIContext context)
    {
        Area area = this.keyframes.graphArea;
        int ht = (int) this.keyframes.fromGraphX(area.x);
        int duration = this.keyframes.getDuration();

        TimelineRulerRenderer.render(
            context,
            area,
            Math.max(ht, 0),
            duration,
            this.keyframes::toGraphX,
            TimeUtils::formatTime,
            this.keyframes::renderRuler
        );
    }

    protected void renderTimelineGrid(UIContext context)
    {
        if (!BBSSettings.verticalTimelineLinesEnabled())
        {
            return;
        }

        Area area = this.keyframes.graphArea;
        int ht = (int) this.keyframes.fromGraphX(area.x);

        TimelineRulerRenderer.renderGrid(
            context,
            area,
            TimelineRulerRenderer.getRulerBottom(area),
            Math.max(ht, 0),
            this.keyframes.getDuration(),
            this.keyframes::toGraphX,
            TimeUtils::formatTime
        );
    }

    private void renderPreviewHints(UIContext context)
    {
        Area area = this.keyframes.graphArea;

        if (!area.isInside(context))
        {
            return;
        }

        if (this.keyframes.isStacking())
        {
            List<UIKeyframeSheet> sheets = new ArrayList<>();
            float currentTick = (float) this.keyframes.fromGraphX(context.mouseX);

            for (UIKeyframeSheet sheet : this.getInteractiveSheets())
            {
                if (sheet.selection.hasAny())
                {
                    sheets.add(sheet);
                }
            }

            for (UIKeyframeSheet current : sheets)
            {
                List<Keyframe> selected = current.selection.getSelected();
                float mmin = Integer.MAX_VALUE;
                float mmax = Integer.MIN_VALUE;

                for (Keyframe keyframe : selected)
                {
                    mmin = Math.min(keyframe.getTick(), mmin);
                    mmax = Math.max(keyframe.getTick(), mmax);
                }

                float length = mmax - mmin + this.keyframes.getStackOffset();
                int times = (int) Math.max(1, Math.ceil((currentTick - mmax) / length));
                float x = 0;

                for (int i = 0; i < times; i++)
                {
                    for (Keyframe keyframe : selected)
                    {
                        float tick = mmax + this.keyframes.getStackOffset() + (keyframe.getTick() - mmin) + x;

                        this.renderPreviewKeyframe(context, current, tick, Colors.YELLOW);
                    }

                    x += length;
                }
            }
        }
        else if (Window.isCtrlPressed() && !this.keyframes.isDuplicatingAtPlayhead())
        {
            UIKeyframeSheet sheet = this.getSheet(context.mouseY);

            if (sheet != null)
            {
                float tick = this.keyframes.getCreationTick(context);

                this.renderPreviewKeyframe(context, sheet, tick, Colors.WHITE);
            }
        }
        else if (Window.isAltPressed() && this.keyframes.isDuplicatingKeyframes(context))
        {
            List<UIKeyframeSheet> sheets = new ArrayList<>();
            boolean atPlayhead = this.keyframes.isDuplicatingAtPlayhead();
            float tick = this.keyframes.getDuplicationTick(context);

            for (UIKeyframeSheet sheet : atPlayhead ? this.getSheets() : this.getInteractiveSheets())
            {
                if (sheet.selection.hasAny())
                {
                    sheets.add(sheet);
                }
            }

            if (sheets.size() == 1 && !atPlayhead)
            {
                UIKeyframeSheet current = sheets.get(0);
                UIKeyframeSheet hovered = this.getSheet(context.mouseY);

                if (hovered == null || current.channel.getFactory() != hovered.channel.getFactory())
                {
                    return;
                }

                List<Keyframe> selected = current.selection.getSelected();

                for (int i = 0; i < selected.size(); i++)
                {
                    Keyframe first = selected.get(0);
                    Keyframe keyframe = selected.get(i);

                    this.renderPreviewKeyframe(context, hovered, tick + (keyframe.getTick() - first.getTick()), Colors.YELLOW);
                }
            }
            else
            {
                float min = Float.MAX_VALUE;

                for (UIKeyframeSheet sheet : sheets)
                {
                    List<Keyframe> selected = sheet.selection.getSelected();

                    for (Keyframe keyframe : selected)
                    {
                        min = Math.min(min, keyframe.getTick());
                    }
                }

                for (UIKeyframeSheet sheet : sheets)
                {
                    if (!this.isVisible(sheet))
                    {
                        continue;
                    }

                    List<Keyframe> selected = sheet.selection.getSelected();

                    for (int i = 0; i < selected.size(); i++)
                    {
                        Keyframe keyframe = selected.get(i);

                        this.renderPreviewKeyframe(context, sheet, tick + (keyframe.getTick() - min), Colors.YELLOW);
                    }
                }
            }
        }
    }

    private void renderPreviewKeyframe(UIContext context, UIKeyframeSheet sheet, double tick, int color)
    {
        int x = this.keyframes.toGraphX(tick);
        int y = this.getDopeSheetY(sheet) + (int) this.trackHeight / 2;
        float a = (float) Math.sin(context.getTickTransition() / 2D) * 0.1F + 0.5F;
        int r = 4;

        context.batcher.box(x - r, y - r, x + r, y + r, Colors.setA(color, a));
    }

    /**
     * Render the graph
     */
    @SuppressWarnings({"rawtypes", "IntegerDivisionInFloatingPointContext"})
    protected void renderGraph(UIContext context)
    {
        if (this.sheets.isEmpty() && this.legacySectionOffsets.isEmpty())
        {
            return;
        }

        Area area = this.keyframes.graphArea;
        int rulerBottom = TimelineRulerRenderer.getRulerBottom(area);
        BufferBuilder builder = null;
        Matrix4f matrix = context.batcher.getContext().pose().last().pose();

        int contentTop = Math.min(rulerBottom, area.ey());

        context.batcher.clip(area.x, contentTop, area.w, area.ey() - contentTop, context);
        this.renderSheets(context, builder, matrix, area);
        this.renderOutOfRangeShading(context, builder, matrix, area);
        context.batcher.unclip(context);
    }

    private void renderOutOfRangeShading(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area)
    {
        int timelineBottom = TimelineRulerRenderer.getTimelineBottom(area);
        int contentY = Math.min(area.ey(), timelineBottom + 1);

        if (contentY >= area.ey())
        {
            return;
        }

        int startX = this.keyframes.toGraphX(0);

        if (startX > area.x)
        {
            int leftEx = Math.min(startX, area.ex());

            context.batcher.box(area.x, contentY, leftEx, area.ey(), BBSSettings.chromeSurface());
        }

        int endX = this.keyframes.toGraphX(this.keyframes.getDuration());

        if (endX < area.ex())
        {
            int rightX = Math.max(endX, area.x);

            context.batcher.box(rightX, contentY, area.ex(), area.ey(), BBSSettings.chromeSurface());
        }
    }

    private void renderLabels(UIContext context)
    {
        Area area = this.keyframes.area;
        int w = this.keyframes.getLabelWidth();

        context.batcher.box(area.x + w - 1, area.y, area.x + w, area.ey(), BBSSettings.dividerColor());
        context.batcher.clip(area.x, area.y, w, area.h, context);

        for (Map.Entry<UIKeyframeSheet.Section, Integer> entry : this.sectionYCache.entrySet())
        {
            this.renderSectionLabel(context, area, entry.getKey(), this.getDopeSheetY() + entry.getValue(), w);
        }

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isVisible(sheet))
            {
                this.renderSheetLabel(context, area, sheet, this.getDopeSheetY(sheet), w);
            }
        }

        context.batcher.unclip(context);
    }

    /** A section header row: the section's accent bar, title and fold arrow, in the label column. */
    private void renderSectionLabel(UIContext context, Area area, UIKeyframeSheet.Section section, int y, int w)
    {
        if (y + this.trackHeight < area.y || y > area.ey())
        {
            return;
        }

        boolean hover = area.isInside(context) && context.mouseY >= y && context.mouseY < y + this.trackHeight;
        int my = y + (int) this.trackHeight / 2;
        int lx = area.x;

        RowStyle.row(context.batcher, lx, y, w, (int) this.trackHeight, section.color(), true, hover, false);

        Icon icon = section.icon();
        boolean hasIcon = icon != null && this.trackHeight >= 12D;
        int iconX = lx + w - LABEL_RIGHT_PAD - LABEL_ICON_SIZE;

        UISection.renderArrow(context, this.getArrowX(lx, w, hasIcon) + LABEL_ARROW_SIZE / 2F, my, this.isSectionExpanded(section));

        if (w > LABEL_COMPACT_WIDTH)
        {
            FontRenderer font = context.batcher.getFont();
            int textRight = (hasIcon ? iconX - LABEL_TEXT_ICON_GAP : lx + w - LABEL_RIGHT_PAD) - LABEL_ARROW_SIZE;
            int textX = lx + LABEL_TEXT_LEFT + Math.max(0, this.getSectionDepth(section) - 1) * POSE_TAB_DEPTH_STEP;
            String title = font.limitToWidth(section.title().get(), Math.max(0, textRight - textX));

            context.batcher.textShadow(title, textX, my - font.getHeight() / 2, RowStyle.textColor(hover));
        }

        if (hasIcon)
        {
            context.batcher.icon(icon, iconX, my - icon.h / 2);
        }
    }

    private void renderSheetLabel(UIContext context, Area area, UIKeyframeSheet sheet, int y, int w)
    {
        if (y + this.trackHeight < area.y || y > area.ey())
        {
            return;
        }

        /* Hover: whole row (label + track area) */
        boolean hover = area.isInside(context) && context.mouseY >= y && context.mouseY < y + this.trackHeight;
        int my = y + (int) this.trackHeight / 2;
        int lx = area.x;

        RowStyle.row(context.batcher, lx, y, w, (int) this.trackHeight, sheet.color, false, hover, false);

        FontRenderer font = context.batcher.getFont();
        int textColor = RowStyle.textColor(hover);
        boolean foldable = this.isFoldable(sheet);
        Icon icon = sheet.getIcon();
        boolean hasIcon = icon != null && this.trackHeight >= 12D;
        int iconX = lx + w - LABEL_RIGHT_PAD - LABEL_ICON_SIZE;

        if (foldable)
        {
            UISection.renderArrow(context, this.getArrowX(lx, w, hasIcon) + LABEL_ARROW_SIZE / 2F, my, this.folds.isExpanded(sheet.id));
        }

        /* Hide every title together when the column is reduced to its icon controls. */
        if (w > LABEL_COMPACT_WIDTH)
        {
            int textX = lx + LABEL_TEXT_LEFT + this.getSheetIndent(sheet);
            int textRight = hasIcon ? iconX - LABEL_TEXT_ICON_GAP : lx + w - LABEL_RIGHT_PAD;

            if (foldable)
            {
                textRight -= LABEL_ARROW_SIZE;
            }

            String title = font.limitToWidth(sheet.title.get(), Math.max(0, textRight - textX));

            context.batcher.textShadow(title, textX, my - font.getHeight() / 2, textColor);
        }

        if (hasIcon)
        {
            context.batcher.icon(icon, iconX, my - icon.h / 2);
        }
    }

    private void renderSheets(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area)
    {
        for (Map.Entry<UIKeyframeSheet.Section, Integer> entry : this.sectionYCache.entrySet())
        {
            int y = this.getDopeSheetY() + entry.getValue();

            if (area.isInside(context) && context.mouseY >= y && context.mouseY < y + this.trackHeight)
            {
                context.batcher.box(area.x, y, area.ex(), y + (int) this.trackHeight, Colors.setA(entry.getKey().color(), 0.12F));
            }
        }

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isVisible(sheet))
            {
                this.renderSheet(context, builder, matrix, area, sheet, this.getDopeSheetY(sheet));
            }
        }
    }

    private int getTrackGap()
    {
        return 0;
    }

    private int getTrackBodyY(int y)
    {
        return y + this.getTrackGap();
    }

    private int getTrackBodyHeight()
    {
        int gap = this.getTrackGap();

        return Math.max(2, (int) this.trackHeight - gap * 2);
    }

    private void renderSheet(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area, UIKeyframeSheet sheet, int y)
    {
        if (!this.isVisible(sheet))
        {
            return;
        }

        if (y + this.trackHeight < area.y || y > area.ey())
        {
            return;
        }

        List keyframes = sheet.channel.getKeyframes();

        boolean hover = area.isInside(context) && context.mouseY >= y && context.mouseY < y + this.trackHeight;
        int my = y + (int) this.trackHeight / 2;
        int by = this.getTrackBodyY(y);
        int bh = this.getTrackBodyHeight();
        int row = this.sheetRowCache.getOrDefault(sheet, 0);

        int trackWidth = BBSSettings.editorTrackWidth.get();
        int surface = row % 2 == 0 ? BBSSettings.deepSurface() : BBSSettings.baseSurface();

        context.batcher.box(area.x, by, area.ex(), by + bh, surface);

        if (hover)
        {
            context.batcher.box(area.x, by, area.ex(), by + bh, BBSSettings.color(BBSSettings.raisedSurface(), Colors.A25));
        }

        if (keyframes.size() <= 1)
        {
            return;
        }

        /* Render track bars (horizontal lines) */
        builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        /* Render bars indicating same values */
        for (int j = 1; j < keyframes.size(); j++)
        {
            Keyframe previous = (Keyframe) keyframes.get(j - 1);
            Keyframe frame = (Keyframe) keyframes.get(j);
            int c = Colors.setA(sheet.color, TRACK_BAR_ALPHA);
            int xx = this.keyframes.toGraphX(previous.getTick());
            int xxx = this.keyframes.toGraphX(frame.getTick());

            if (previous.getFactory().compare(previous.getValue(), frame.getValue()))
            {
                int w = trackWidth + 2;

                context.batcher.fillRect(builder, matrix, xx, my - w / 2, this.keyframes.toGraphX(frame.getTick()) - xx, w, c, c, c, c);
            }

            if (Math.abs(xxx - xx) < 5)
            {
                c = Colors.setA(sheet.color, 0.5F);

                context.batcher.fillRect(builder, matrix, xx - 2, my + trackWidth / 2 + 4, xxx - xx + 4, 2, c, c, c, c);
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        MeshData mesh = builder.build();

        if (mesh != null)
        {
            BufferUploader.drawWithShader(mesh);
        }
    }

    /** Only tracks in this filtered timeline contribute to a parent's display-only overview. */
    public boolean isOverviewChild(UIKeyframeSheet parent, UIKeyframeSheet candidate)
    {
        if (parent == candidate || !this.sheets.contains(parent) || !this.sheets.contains(candidate))
        {
            return false;
        }

        for (UIKeyframeSheet current = candidate; current != null; current = current.parent)
        {
            if (current.parent == parent)
            {
                return true;
            }
        }

        return false;
    }

    private void renderSheetKeyframeShapes(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area, UIKeyframeSheet sheet, int y)
    {
        if (!this.isVisible(sheet))
        {
            return;
        }

        if (y + this.trackHeight < area.y || y > area.ey())
        {
            return;
        }

        List keyframes = sheet.channel.getKeyframes();
        int my = y + (int) this.trackHeight / 2;
        int forcedIndex = 0;

        /* Overview marks never enter the parent channel or its hit/selection paths. */
        for (UIKeyframeSheet child : this.sheets)
        {
            if (!this.isOverviewChild(sheet, child))
            {
                continue;
            }

            int color = Colors.setA(child.color, 0.5F);

            for (Object value : child.channel.getKeyframes())
            {
                int x = this.keyframes.toGraphX(((Keyframe) value).getTick());
                context.batcher.fillRect(builder, matrix, x - 2, my - 2, 4, 4, color, color, color, color);
            }
        }

        for (int j = 0; j < keyframes.size(); j++)
        {
            Keyframe frame = (Keyframe) keyframes.get(j);
            float tick = frame.getTick();
            int x1 = this.keyframes.toGraphX(tick);
            int x2 = this.keyframes.toGraphX(tick + frame.getDuration());

            /* Render custom duration markers */
            if (x1 != x2)
            {
                int y1 = my - 8 + (forcedIndex % 2 == 1 ? -4 : 0);
                int color = sheet.selection.has(j) ? Colors.WHITE :  Colors.setA(Colors.mulRGB(sheet.color, 0.9F), 0.75F);

                context.batcher.fillRect(builder, matrix, x1, y1 - 2, 1, 5, color, color, color, color);
                context.batcher.fillRect(builder, matrix, x2, y1 - 2, 1, 5, color, color, color, color);
                context.batcher.fillRect(builder, matrix, x1 + 1, y1, x2 - x1, 1, color, color, color, color);

                forcedIndex += 1;
            }

            boolean isPointHover = this.isNear(this.keyframes.toGraphX(frame.getTick()), my, context.mouseX, context.mouseY, Window.isAltPressed() && Window.isShiftPressed());
            boolean toRemove = this.keyframes.isRemovingKeyframe() && isPointHover;

            if (this.keyframes.isSelecting())
            {
                isPointHover = isPointHover || this.keyframes.getGrabbingArea(context).isInside(x1, my);
            }

            int kc = frame.getColor() != null ? frame.getColor().getRGBColor() | Colors.A100 : sheet.color;
            int c = (sheet.selection.has(j) || isPointHover ? Colors.WHITE : kc) | Colors.A100;

            if (toRemove)
            {
                c = Colors.RED | Colors.A100;
            }

            int pointOffset = toRemove ? 4 : 3;

            renderShape(frame, context, builder, matrix, x1, my, pointOffset, c);
        }

        /* Render keyframe handles (inner) */
        for (int j = 0; j < keyframes.size(); j++)
        {
            Keyframe frame = (Keyframe) keyframes.get(j);
            int c = sheet.selection.has(j) ? BBSSettings.activeColor() & Colors.RGB : 0;
            int mx = this.keyframes.toGraphX(frame.getTick());
            int mc = c | Colors.A100;
            IKeyframeShapeRenderer shapeResult = renderShape(frame, context, builder, matrix, mx, my, 2, mc);

            if (!frame.getStyle().isFilled()) shapeResult.renderKeyframeBackground(context, builder, matrix, mx, my, 2, mc);
        }
    }

    private void renderSheetsTopmostKeyframes(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area)
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (this.isVisible(sheet))
            {
                this.renderSheetKeyframeShapes(context, builder, matrix, area, sheet, this.getDopeSheetY(sheet));
            }
        }
    }

    /**
     * Aggregate overview of a section's keyframes, drawn on its header row so a folded section still
     * shows where its keys are - the reason the header is worth keeping visible when collapsed.
     */
    private void renderSectionKeyframes(UIContext context, BufferBuilder builder, Matrix4f matrix, Area area)
    {
        for (UIKeyframeSheet sheet : this.sheets)
        {
            for (UIKeyframeSheet.Section section = sheet.section; section != null; section = this.sectionParents.get(section))
            {
                Integer offset = this.sectionYCache.get(section);

                if (offset == null)
                {
                    continue;
                }

                int y = this.getDopeSheetY() + offset + (int) this.trackHeight / 2;

                if (y + 3 < area.y || y - 3 > area.ey())
                {
                    continue;
                }

                for (Object value : sheet.channel.getKeyframes())
                {
                    int x = this.keyframes.toGraphX(((Keyframe) value).getTick());
                    int color = section.color() | Colors.A100;

                    context.batcher.fillRect(builder, matrix, x - 3, y - 3, 6, 6, color, color, color, color);
                }
            }
        }
    }

    @Override
    public void renderTopmostKeyframes(UIContext context)
    {
        if (this.sheets.isEmpty() && this.legacySectionOffsets.isEmpty())
        {
            return;
        }

        /* Every sheet counts, not only the visible ones: a folded section still shows its overview. */
        boolean hasKeyframes = false;

        for (UIKeyframeSheet sheet : this.sheets)
        {
            if (!sheet.channel.getKeyframes().isEmpty())
            {
                hasKeyframes = true;

                break;
            }
        }

        if (!hasKeyframes)
        {
            return;
        }

        Area area = this.keyframes.graphArea;
        int rulerBottom = TimelineRulerRenderer.getRulerBottom(area);
        Matrix4f matrix = context.batcher.getContext().pose().last().pose();

        int contentTop = Math.min(rulerBottom, area.ey());

        context.batcher.clip(area.x, contentTop, area.w, area.ey() - contentTop, context);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        this.renderSectionKeyframes(context, builder, matrix, area);
        this.renderSheetsTopmostKeyframes(context, builder, matrix, area);
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        MeshData topmostMesh = builder.build();

        if (topmostMesh != null)
        {
            BufferUploader.drawWithShader(topmostMesh);
        }

        context.batcher.unclip(context);
    }

    private int getArrowX(int labelX, int labelWidth, boolean hasIcon)
    {
        int iconX = labelX + labelWidth - LABEL_RIGHT_PAD - LABEL_ICON_SIZE;

        return hasIcon ? iconX - LABEL_ARROW_SIZE : iconX + (LABEL_ICON_SIZE - LABEL_ARROW_SIZE) / 2;
    }

    private boolean isFoldToggleHit(UIContext context, UIKeyframeSheet sheet, int y, int labelWidth)
    {
        int height = this.getTrackHeight(sheet);
        boolean hasIcon = sheet.getIcon() != null && height >= 12;
        int x = this.getArrowX(this.keyframes.area.x, labelWidth, hasIcon);
        int right = this.keyframes.area.x + labelWidth - LABEL_RIGHT_PAD;

        return context.mouseX >= x && context.mouseX < right && context.mouseY >= y && context.mouseY < y + height;
    }

    /** Whether this row nests other rows (hotbar slots under their first slot). */
    private boolean isFoldable(UIKeyframeSheet sheet)
    {
        return !sheet.children.isEmpty();
    }

    @Override
    public void postRender(UIContext context)
    {
        if (!this.sheets.isEmpty() || !this.legacySectionOffsets.isEmpty())
        {
            this.renderLabels(context);
        }

        this.dopeSheet.renderScrollbar(context.batcher, context.mouseX, context.mouseY);
    }

    /* State recovery */

    @Override
    public void saveState(MapType extra)
    {
        extra.putDouble("track_height", this.trackHeight);
        extra.putDouble("scroll", this.dopeSheet.getScroll());
    }

    @Override
    public void restoreState(MapType extra)
    {
        this.setTrackHeight(extra.getDouble("track_height"));
        this.dopeSheet.setScroll(extra.getDouble("scroll"));
    }
}
