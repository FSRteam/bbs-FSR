package mchorse.bbs_mod.ui.framework.elements.layout;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.motion.UIMotions;
import mchorse.bbs_mod.ui.utils.motion.UITween;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.interps.Lerps;

import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Reusable dockable-panel layout. Owns a set of registered panels and arranges them per an
 * {@link EditorLayoutNode} tree provided by an {@link ILayoutSource}: resizable splitters,
 * drag-to-dock with edge/center drop zones, tab/stack grouping, lock toggle and reset.
 *
 * <p>Panels are registered with {@link #addPanel} and become direct children of this element.
 * Film- or particle-specific behavior (which panels exist, default tree, frameless preview,
 * data gating, follow-up visibility) is supplied as configuration so a single implementation
 * serves both editors.
 */
public class UIDockLayout extends UIElement
{
    private static final float DRAG_HANDLE_HEIGHT_NORM = 0.02F;
    private static final float DRAG_HANDLE_TOP_OFFSET_NORM = 0.01F;
    /** Space a panel's content leaves at the top while unlocked; the drag strip fills all of it. */
    private static final int DRAG_STRIP_HEIGHT_PX = 20;
    private static final int SPLITTER_HANDLE_PX = 14;
    private static final int SPLITTER_LINK_HITBOX_PADDING_PX = 8;
    private static final int DROP_ZONE_CENTER = -1;
    private static final int DROP_EDITOR_EDGE_PX = 16;
    private static final int DROP_PANEL_EDGE_PX = 48;
    private static final float DROP_PANEL_EDGE_MAX = 0.25F;
    private static final float DROP_ROOT_RATIO = 0.25F;
    private static final int EDITOR_MIN_SIZE_FOR_PX_HANDLES = 10;
    /** Solid outline on the highlighted edge; a wash alone would sink into the 3D viewport. */
    private static final int DROP_OUTLINE_PX = 2;
    /** Share of the target the highlight covers. It marks the zone, not the size the panel ends up. */
    private static final float DROP_HIGHLIGHT_RATIO = 0.2F;
    private static final int DOCK_STACK_TABS_HEIGHT_PX = 20;
    /** Splitter drags stop where a side would become too small to use, measured in pixels. */
    private static final int MIN_PANEL_SIZE_PX = 80;
    /** Movement (px, manhattan) before a pressed tab turns into a panel drag instead of a click. */
    private static final int DRAG_START_THRESHOLD_PX = 4;
    /** Dwell time over another stack's tab, mid-drag, before that tab is flipped open. */
    private static final long SPRING_LOAD_DELAY_MS = 500;
    private static final int LAYOUT_UNDO_CAP = 32;
    /** Two clicks on a seam within this window even the split back out. */
    private static final long SPLITTER_DOUBLE_CLICK_MS = 300;
    private static final int PANEL_GAP_PX = 4;
    private static final float PANEL_EDGE_EPS = 0.001F;

    private final Map<String, UIElement> panelById = new LinkedHashMap<>();
    private final Map<String, Icon> iconById = new HashMap<>();
    private final Map<String, UIDraggable> dragHandlesById = new LinkedHashMap<>();
    private final Map<String, IKey> labelById = new HashMap<>();
    private final List<UIDraggable> splitterHandles = new ArrayList<>();
    private final List<EditorLayoutNode.SplitterHandleInfo> splitterHandleInfos = new ArrayList<>();
    private final List<UIDockStackTabs> dockStackTabs = new ArrayList<>();
    private final Map<String, DockStackInfo> dockStackByPanelId = new HashMap<>();
    private final List<Integer> draggedSplitterIndices = new ArrayList<>();
    private final Map<String, float[]> transitionFromPanels = new HashMap<>();
    private final Map<String, float[]> transitionToPanels = new HashMap<>();
    private final Map<String, float[]> transitionFromHandles = new HashMap<>();
    private final Map<String, float[]> transitionToHandles = new HashMap<>();
    private final UITween layoutTransition = new UITween();
    /** Undo history of structural layout changes; snapshots are cheap because the tree is immutable. */
    private final List<LayoutSnapshot> layoutUndo = new ArrayList<>();
    /** The tree as it stood when a splitter drag began, pushed as one undo entry at drag end. */
    private EditorLayoutNode splitterDragUndoRoot;
    private int lastSplitterClickIndex = -1;
    private long lastSplitterClickTime;
    private boolean splitterDragChanged;

    private final UIRenderable surfaces = new UIRenderable(this::renderPanelSurfaces);
    private final UIRenderable borders = new UIRenderable(this::renderPanelBorders);
    private final UIRenderable dropHighlight = new UIRenderable(this::renderDropZoneHighlight);

    private boolean layoutLocked = true;
    private String draggingPanelId;
    private String dropTargetPanelId;
    private boolean dropTargetIsRoot;
    private int dropTargetZone = DROP_ZONE_CENTER;
    private boolean layoutTransitioning;
    private boolean transitionCapturePending;
    /** Tab strip and tab the drag is over, for the insertion caret between tabs. */
    private UIDockStackTabs dropTargetTabStrip;
    private int dropTargetTabIndex = -1;
    /** Session-only: which panel is blown up to the whole dock; the stored tree is untouched. */
    private String maximizedPanelId;
    /* A pressed tab is a click until it moves DRAG_START_THRESHOLD_PX, then it is a panel drag. */
    private String tabPressPanelId;
    private int tabPressX;
    private int tabPressY;
    private boolean dragFromTab;
    /** Set by Esc: the in-flight drag is dead, ignore it until the mouse is released. */
    private boolean panelDragCancelled;
    private String springTabPanelId;
    private long springTabSince;
    /** Spring-load fires from inside a render pass, so the actual flip waits for the next one. */
    private String pendingSpringPanelId;

    /* Configuration */
    private ILayoutSource source;
    private String framelessPanelId;
    private Supplier<Boolean> gate = () -> true;
    private Runnable onChanged = () -> {};
    private Runnable onLayoutSettled = () -> {};
    private Runnable beforeLayoutChange = () -> {};
    private Runnable afterLayoutChange = () -> {};
    private UnaryOperator<EditorLayoutNode> ensureFn = UnaryOperator.identity();
    private Function<String, Icon> iconFn;
    private Supplier<Boolean> animateLayoutChanges = () -> false;

    public UIDockLayout()
    {
        this.ensureFn = this::ensureRegisteredPanels;
    }

    /* Configuration setters */

    public UIDockLayout source(ILayoutSource source)
    {
        this.source = source;

        return this;
    }

    /** Initial lock state, e.g. restored from settings; the default is locked. */
    public UIDockLayout locked(boolean locked)
    {
        this.layoutLocked = locked;

        return this;
    }

    /** Panel id whose surface/borders/gutter are skipped (e.g. a frameless 3D preview viewport). */
    public UIDockLayout frameless(String panelId)
    {
        this.framelessPanelId = panelId;

        return this;
    }

    public UIDockLayout gate(Supplier<Boolean> gate)
    {
        this.gate = gate;

        return this;
    }

    /** Run after every layout rebuild so the host can re-sync its own visibility. */
    public UIDockLayout onChanged(Runnable onChanged)
    {
        this.onChanged = onChanged;

        return this;
    }

    /** Run after the layout has received settled bounds, including drops, reset, maximize and undo. */
    public UIDockLayout onLayoutSettled(Runnable onLayoutSettled)
    {
        this.onLayoutSettled = onLayoutSettled == null ? () -> {} : onLayoutSettled;

        return this;
    }

    public UIDockLayout layoutChangeCallbacks(Runnable before, Runnable after)
    {
        this.beforeLayoutChange = before == null ? () -> {} : before;
        this.afterLayoutChange = after == null ? () -> {} : after;

        return this;
    }

    public UIDockLayout animateLayoutChanges(Supplier<Boolean> enabled)
    {
        this.animateLayoutChanges = enabled == null ? () -> false : enabled;

        return this;
    }

    /** Override how missing required panels are inserted into a loaded tree (default: append-split). */
    public UIDockLayout ensure(UnaryOperator<EditorLayoutNode> ensureFn)
    {
        this.ensureFn = ensureFn;

        return this;
    }

    public UIDockLayout icons(Function<String, Icon> iconFn)
    {
        this.iconFn = iconFn;

        return this;
    }

    /**
     * Register a panel. The panel becomes a direct child of this element and is arranged by the
     * layout. Call {@link #mount()} once after registering all panels.
     */
    public UIDockLayout addPanel(String id, UIElement panel, Icon icon)
    {
        return this.addPanel(id, panel, icon, IKey.EMPTY);
    }

    public UIDockLayout addPanel(String id, UIElement panel, Icon icon, IKey label)
    {
        this.panelById.put(id, panel);
        this.iconById.put(id, icon == null ? Icons.FILE : icon);
        this.labelById.put(id, label == null ? IKey.EMPTY : label);
        UIDraggable handle = this.createPanelDragHandle(id);

        handle.context((menu) -> this.fillSlotContextMenu(menu, id));
        this.dragHandlesById.put(id, handle);

        return this;
    }

    /** Add all children in z-order and run the first layout pass. Call after {@link #addPanel}s. */
    public void mount()
    {
        this.add(this.surfaces);

        for (UIElement panel : this.panelById.values())
        {
            this.add(panel);
        }

        this.add(this.borders, this.dropHighlight);

        for (UIDraggable handle : this.dragHandlesById.values())
        {
            this.add(handle);
        }

        this.setupFlex(false);
    }

    /**
     * Space the drag strip occupies at the top of an unlocked panel. Panel content that would sit
     * underneath it has to be pushed down by this much.
     */
    public static int dragStripHeightPx()
    {
        return DRAG_STRIP_HEIGHT_PX;
    }

    public UIElement getPanel(String id)
    {
        return this.panelById.get(id);
    }

    public boolean isLocked()
    {
        return this.layoutLocked;
    }

    public boolean isSplitterDragging()
    {
        return this.splitterHandles.stream().anyMatch(UIDraggable::isDragging);
    }

    public boolean isPanelActive(String panelId)
    {
        DockStackInfo stack = this.dockStackByPanelId.get(panelId);

        return stack != null && panelId.equals(stack.activePanelId);
    }

    private Icon getDockPanelIcon(String panelId)
    {
        if (this.iconFn != null)
        {
            return this.iconFn.apply(panelId);
        }

        return this.iconById.getOrDefault(panelId, Icons.FILE);
    }

    public IKey getPanelLabel(String panelId)
    {
        return this.labelById.getOrDefault(panelId, IKey.EMPTY);
    }

    /* Layout settings access */

    private EditorLayoutNode layoutRoot()
    {
        return this.source.getRoot();
    }

    private void setLayoutRoot(EditorLayoutNode root)
    {
        this.source.setRoot(root);
    }

    private List<EditorLayoutNode.SplitterNode> layoutSplitters()
    {
        return this.source.getSplitters();
    }

    private List<EditorLayoutNode.SplitterNode> layoutSplittersForWrite()
    {
        return this.source.getSplittersForWrite();
    }

    /* Public actions */

    /** Full re-read for a source switch: drag state, undo history and maximize don't carry over. */
    public void refresh()
    {
        this.layoutUndo.clear();
        this.maximizedPanelId = null;
        this.clearPanelDragState();
        this.clearSplitterDragState();
        this.setupFlex(true);
    }

    public void refreshVisibility()
    {
        this.updateTabVisibility();
    }

    public void toggleLock()
    {
        this.beginLayoutChange();

        try
        {
            this.layoutLocked = !this.layoutLocked;
            this.clearPanelDragState();
            this.clearSplitterDragState();
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }
    }

    public void resetLayout()
    {
        this.beginLayoutChange();

        try
        {
            this.pushLayoutUndo(this.layoutRoot());
            this.maximizedPanelId = null;
            this.source.setHiddenPanels(new HashSet<>());
            this.setLayoutRoot(this.source.getDefault());
            this.clearPanelDragState();
            this.clearSplitterDragState();
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }
    }

    /** Current layout tree (with all required panels ensured), e.g. for serializing into a preset. */
    public EditorLayoutNode getLayoutRoot()
    {
        return this.ensureLayoutPanels(this.layoutRoot());
    }

    public void applyLayoutRoot(EditorLayoutNode root)
    {
        if (root != null)
        {
            this.beginLayoutChange();

            try
            {
                this.pushLayoutUndo(this.layoutRoot());
                this.maximizedPanelId = null;
                this.setLayoutRoot(root);
                this.clearPanelDragState();
                this.clearSplitterDragState();
                this.setupFlex(true);
            }
            finally
            {
                this.endLayoutChange();
            }
        }
    }

    private void beginLayoutChange()
    {
        this.transitionCapturePending = false;

        if (this.animateLayoutChanges.get() && this.area.w > 0 && this.area.h > 0)
        {
            this.advanceLayoutTransition();
            this.captureBounds(this.panelById, this.transitionFromPanels);
            this.captureBounds(this.dragHandlesById, this.transitionFromHandles);
            this.transitionCapturePending = true;
        }
        else
        {
            this.clearLayoutTransition();
        }

        this.beforeLayoutChange.run();
    }

    private void endLayoutChange()
    {
        this.afterLayoutChange.run();

        if (!this.transitionCapturePending)
        {
            return;
        }

        this.transitionCapturePending = false;
        this.captureBounds(this.panelById, this.transitionToPanels);
        this.captureBounds(this.dragHandlesById, this.transitionToHandles);

        if (!hasChangedBounds(this.transitionFromPanels, this.transitionToPanels)
            && !hasChangedBounds(this.transitionFromHandles, this.transitionToHandles))
        {
            this.clearLayoutTransition();

            return;
        }

        this.layoutTransition.snap(0F);
        this.layoutTransition.to(1F, UIMotions.layout());
        this.layoutTransitioning = true;
        this.applyInterpolatedBounds(0F);
    }

    private void captureBounds(Map<String, ? extends UIElement> elements, Map<String, float[]> out)
    {
        out.clear();

        for (Map.Entry<String, ? extends UIElement> entry : elements.entrySet())
        {
            Area area = entry.getValue().area;

            out.put(entry.getKey(), new float[] {
                (area.x - this.area.x) / (float) this.area.w,
                (area.y - this.area.y) / (float) this.area.h,
                area.w / (float) this.area.w,
                area.h / (float) this.area.h
            });
        }
    }

    private static boolean hasChangedBounds(Map<String, float[]> from, Map<String, float[]> to)
    {
        for (Map.Entry<String, float[]> entry : from.entrySet())
        {
            float[] target = to.get(entry.getKey());

            if (target == null)
            {
                continue;
            }

            float[] source = entry.getValue();

            for (int i = 0; i < source.length; i++)
            {
                if (Math.abs(source[i] - target[i]) > 5E-4F)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Steps the layout back to how it stood before the last structural change. */
    public boolean undoLayout()
    {
        if (this.layoutUndo.isEmpty())
        {
            return false;
        }

        LayoutSnapshot snapshot = this.layoutUndo.remove(this.layoutUndo.size() - 1);

        this.beginLayoutChange();

        try
        {
            this.maximizedPanelId = null;
            this.clearPanelDragState();
            this.clearSplitterDragState();
            this.source.setHiddenPanels(snapshot.hidden);
            this.setLayoutRoot(snapshot.root);
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }

        return true;
    }

    /** Blow the hovered panel up to the whole dock, or restore if one already is. */
    public boolean toggleMaximizeUnderCursor()
    {
        UIContext context = this.getContext();

        if (context == null || !this.gate.get())
        {
            return false;
        }

        if (this.maximizedPanelId != null)
        {
            this.toggleMaximizePanel(this.maximizedPanelId);

            return true;
        }

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            UIElement panel = entry.getValue();

            if (panel.isVisible() && panel.area.isInside(context.mouseX, context.mouseY))
            {
                this.toggleMaximizePanel(entry.getKey());

                return true;
            }
        }

        return false;
    }

    private void advanceLayoutTransition()
    {
        if (!this.layoutTransitioning)
        {
            return;
        }

        float progress = MathUtils.clamp(this.layoutTransition.update(System.currentTimeMillis()), 0F, 1F);

        this.applyInterpolatedBounds(progress);

        if (this.layoutTransition.isSettled())
        {
            this.applyInterpolatedBounds(1F);
            this.clearLayoutTransition();
        }
    }

    private void applyInterpolatedBounds(float progress)
    {
        this.applyInterpolatedBounds(this.panelById, this.transitionFromPanels, this.transitionToPanels, progress);
        this.applyInterpolatedBounds(this.dragHandlesById, this.transitionFromHandles, this.transitionToHandles, progress);
        this.resize();
    }

    private void applyInterpolatedBounds(Map<String, ? extends UIElement> elements, Map<String, float[]> from, Map<String, float[]> to, float progress)
    {
        for (Map.Entry<String, ? extends UIElement> entry : elements.entrySet())
        {
            float[] source = from.get(entry.getKey());
            float[] target = to.get(entry.getKey());

            if (source == null || target == null)
            {
                continue;
            }

            entry.getValue().relative(this)
                .x(Lerps.lerp(source[0], target[0], progress))
                .y(Lerps.lerp(source[1], target[1], progress))
                .w(Lerps.lerp(source[2], target[2], progress))
                .h(Lerps.lerp(source[3], target[3], progress));
        }
    }

    private void clearLayoutTransition()
    {
        this.layoutTransitioning = false;
        this.transitionCapturePending = false;
        this.transitionFromPanels.clear();
        this.transitionToPanels.clear();
        this.transitionFromHandles.clear();
        this.transitionToHandles.clear();
    }

    private void toggleMaximizePanel(String panelId)
    {
        this.beginLayoutChange();

        try
        {
            this.maximizedPanelId = panelId.equals(this.maximizedPanelId) ? null : panelId;
            this.clearPanelDragState();
            this.clearSplitterDragState();
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }
    }

    private void pushLayoutUndo(EditorLayoutNode root)
    {
        this.layoutUndo.add(new LayoutSnapshot(copyLayoutTree(root), this.source.getHiddenPanels()));

        while (this.layoutUndo.size() > LAYOUT_UNDO_CAP)
        {
            this.layoutUndo.remove(0);
        }
    }

    private static EditorLayoutNode copyLayoutTree(EditorLayoutNode root)
    {
        return root == null ? null : EditorLayoutNode.fromData(root.toData());
    }

    public boolean cycleDockStackTab(int offset)
    {
        if (offset == 0)
        {
            return false;
        }

        DockStackInfo stack = this.resolveDockStackForKeyboardCycle();

        if (stack == null || !stack.isStacked() || stack.panelIds.isEmpty())
        {
            return false;
        }

        int currentIndex = stack.panelIds.indexOf(stack.activePanelId);

        if (currentIndex < 0)
        {
            currentIndex = 0;
        }

        int size = stack.panelIds.size();
        int nextIndex = (currentIndex + offset) % size;

        if (nextIndex < 0)
        {
            nextIndex += size;
        }

        this.activateDockStackTab(stack.getAnchorPanelId(), stack.panelIds.get(nextIndex));

        return true;
    }

    private DockStackInfo resolveDockStackForKeyboardCycle()
    {
        UIContext context = this.getContext();

        if (context == null)
        {
            return null;
        }

        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            if (!tabs.isVisible() || !tabs.area.isInside(context.mouseX, context.mouseY))
            {
                continue;
            }

            DockStackInfo hoveredStack = this.dockStackByPanelId.get(tabs.anchorPanelId);

            if (hoveredStack != null && hoveredStack.isStacked())
            {
                return hoveredStack;
            }
        }

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            UIElement panel = entry.getValue();

            if (!panel.isVisible() || !panel.area.isInside(context.mouseX, context.mouseY))
            {
                continue;
            }

            DockStackInfo stack = this.dockStackByPanelId.get(entry.getKey());

            if (stack != null && stack.isStacked())
            {
                return stack;
            }
        }

        return null;
    }

    private void activateDockStackTab(String stackPanelId, String panelId)
    {
        if (stackPanelId == null || panelId == null)
        {
            return;
        }

        EditorLayoutNode root = this.layoutRoot();
        EditorLayoutNode next = EditorLayoutNode.copyWithStackActivePanel(root, stackPanelId, panelId);

        if (next != root)
        {
            this.applyLayoutRoot(next);
        }
    }

    /* Layout build */

    /** Drop what this dock cannot show, apply the host's placement hints, then backstop the rest. */
    private EditorLayoutNode ensureLayoutPanels(EditorLayoutNode root)
    {
        EditorLayoutNode out = this.ensureRegisteredPanels(this.ensureFn.apply(this.pruneUnknownPanels(root)));

        this.reconcileHiddenPanels(out);

        return out;
    }

    /**
     * A hidden panel that made it back into the tree anyway — through a preset or an older save —
     * is visibly there, so the hidden flag has to yield, or the panels menu would lie about it.
     */
    private void reconcileHiddenPanels(EditorLayoutNode root)
    {
        Set<String> hidden = this.source.getHiddenPanels();

        if (hidden.isEmpty())
        {
            return;
        }

        HashSet<String> present = new HashSet<>();

        EditorLayoutNode.collectPanelIds(root, present);

        if (hidden.removeAll(present))
        {
            this.source.setHiddenPanels(hidden);
        }
    }

    /**
     * Panel ids with no registered panel — a layout from another editor, or one renamed since it was
     * saved — would otherwise keep their share of the space as a hole that nothing can be dropped
     * into and only a reset can clear.
     */
    private EditorLayoutNode pruneUnknownPanels(EditorLayoutNode root)
    {
        HashSet<String> ids = new HashSet<>();

        EditorLayoutNode.collectPanelIds(root, ids);

        EditorLayoutNode out = root;

        for (String id : ids)
        {
            if (!this.panelById.containsKey(id))
            {
                out = EditorLayoutNode.copyWithRemovedPanel(out, id);
            }
        }

        return out;
    }

    private EditorLayoutNode ensureRegisteredPanels(EditorLayoutNode root)
    {
        HashSet<String> ids = new HashSet<>();
        this.collectPanelIds(root, ids);

        Set<String> hidden = this.source.getHiddenPanels();
        EditorLayoutNode out = root;

        for (String id : this.panelById.keySet())
        {
            if (!ids.contains(id) && !hidden.contains(id))
            {
                /* Root-level append: a missing panel comes back as a side column, not as half of
                 * whatever leaf happened to be first in the tree. */
                out = EditorLayoutNode.copyWithInsertSplitAtRoot(out, id, EditorLayoutNode.EDGE_RIGHT, DROP_ROOT_RATIO);
            }
        }

        return out;
    }

    private void collectPanelIds(EditorLayoutNode node, HashSet<String> out)
    {
        if (node instanceof EditorLayoutNode.PanelNode)
        {
            out.add(((EditorLayoutNode.PanelNode) node).getPanelId());
        }
        else if (node instanceof EditorLayoutNode.StackNode)
        {
            out.addAll(((EditorLayoutNode.StackNode) node).getPanelIds());
        }
        else if (node instanceof EditorLayoutNode.SplitterNode)
        {
            EditorLayoutNode.SplitterNode s = (EditorLayoutNode.SplitterNode) node;
            this.collectPanelIds(s.getFirst(), out);
            this.collectPanelIds(s.getSecond(), out);
        }
    }

    public void setupFlex(boolean resize)
    {
        EditorLayoutNode originalRoot = this.layoutRoot();
        EditorLayoutNode root = this.ensureLayoutPanels(originalRoot);

        if (root != originalRoot)
        {
            this.setLayoutRoot(root);
        }

        EditorLayoutNode effectiveRoot = this.effectiveLayoutTree(root);
        List<EditorLayoutNode.SplitterNode> splitters = new ArrayList<>();

        EditorLayoutNode.collectSplitters(effectiveRoot, splitters);

        if (resize && splitters.size() == this.splitterHandles.size())
        {
            this.updateFlexBoundsOnly(effectiveRoot);
            this.resize();
            this.resize();

            if (this.draggedSplitterIndices.isEmpty())
            {
                this.onLayoutSettled.run();
            }

            return;
        }

        this.clearSplitterDragState();

        List<DockStackInfo> stackInfos = new ArrayList<>();
        this.collectDockStacks(effectiveRoot, 0F, 0F, 1F, 1F, stackInfos);

        for (UIElement el : this.panelById.values())
        {
            el.resetFlex();
        }

        for (UIDraggable h : this.splitterHandles)
        {
            h.removeFromParent();
        }

        this.splitterHandles.clear();

        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            tabs.removeFromParent();
        }

        this.dockStackTabs.clear();
        this.dockStackByPanelId.clear();

        for (UIDraggable h : this.dragHandlesById.values())
        {
            h.resetFlex();
        }

        this.applyPanelBoundsFromStacks(stackInfos);
        this.rebuildDockStackTabs(stackInfos);

        this.splitterHandleInfos.clear();
        EditorLayoutNode.computeSplitterHandles(effectiveRoot, 0F, 0F, 1F, 1F, this.splitterHandleInfos);

        for (int i = 0; i < splitters.size(); i++)
        {
            UIDraggable handle = this.createSplitterHandle(i);
            this.splitterHandles.add(handle);
            this.addBefore(this.borders, handle);
        }

        if (this.layoutLocked)
        {
            for (UIDraggable h : this.dragHandlesById.values())
            {
                h.setVisible(false);
            }
        }
        else
        {
            this.applyDragHandleBoundsFromStacks(stackInfos);
        }

        this.updateTabVisibility();

        if (resize)
        {
            this.resize();
            this.resize();

            if (this.draggedSplitterIndices.isEmpty())
            {
                this.onLayoutSettled.run();
            }
        }
    }

    private void updateFlexBoundsOnly(EditorLayoutNode root)
    {
        List<DockStackInfo> stackInfos = new ArrayList<>();

        this.collectDockStacks(root, 0F, 0F, 1F, 1F, stackInfos);
        this.applyPanelBoundsFromStacks(stackInfos);

        if (!this.updateDockStackTabsBoundsOnly(stackInfos))
        {
            this.rebuildDockStackTabs(stackInfos);
        }

        this.splitterHandleInfos.clear();
        EditorLayoutNode.computeSplitterHandles(root, 0F, 0F, 1F, 1F, this.splitterHandleInfos);
        this.syncSplitterHandleBounds();
        this.applyDragHandleBoundsFromStacks(stackInfos);
        this.updateTabVisibility();
    }

    private void updateTabVisibility()
    {
        boolean show = this.gate.get();

        if (!show)
        {
            for (UIElement panel : this.panelById.values())
            {
                panel.setVisible(false);
            }
        }
        else
        {
            for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
            {
                entry.getValue().setVisible(this.isPanelActive(entry.getKey()));
            }
        }

        for (Map.Entry<String, UIDraggable> entry : this.dragHandlesById.entrySet())
        {
            DockStackInfo stack = this.dockStackByPanelId.get(entry.getKey());
            boolean active = stack != null && entry.getKey().equals(stack.activePanelId);

            entry.getValue().setVisible(show && !this.layoutLocked && active);
        }

        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            tabs.setVisible(show);
        }

        for (UIDraggable handle : this.splitterHandles)
        {
            handle.setVisible(show && !this.layoutLocked);
        }

        this.onChanged.run();
    }

    /* Splitter handles */

    private void applySplitterHandleBounds(UIDraggable handle, EditorLayoutNode.SplitterHandleInfo info)
    {
        int ew = this.area.w;
        int eh = this.area.h;

        if (ew < EDITOR_MIN_SIZE_FOR_PX_HANDLES || eh < EDITOR_MIN_SIZE_FOR_PX_HANDLES)
        {
            handle.relative(this).x(info.hx).y(info.hy).w(info.hw).h(info.hh);
            return;
        }

        if (info.horizontal)
        {
            float centerY = info.hy + info.hh * 0.5F;
            float hyNew = centerY - (SPLITTER_HANDLE_PX / (2F * eh));
            handle.relative(this).x(info.hx).y(hyNew).w(info.hw).h(SPLITTER_HANDLE_PX);
        }
        else
        {
            float centerX = info.hx + info.hw * 0.5F;
            float hxNew = centerX - (SPLITTER_HANDLE_PX / (2F * ew));
            handle.relative(this).x(hxNew).y(info.hy).w(SPLITTER_HANDLE_PX).h(info.hh);
        }
    }

    private void syncSplitterHandleBounds()
    {
        for (int i = 0; i < this.splitterHandles.size() && i < this.splitterHandleInfos.size(); i++)
        {
            this.applySplitterHandleBounds(this.splitterHandles.get(i), this.splitterHandleInfos.get(i));
        }
    }

    private UIDraggable createSplitterHandle(int index)
    {
        UIDraggable handle = new UIDraggable((context) -> this.applySplitterDrag(context.mouseX, context.mouseY))
        {
            @Override
            protected boolean subMouseClicked(UIContext context)
            {
                if (context.mouseButton == 0 && this.area.isInside(context)
                    && BBSSettings.editorResizablePanels.get()
                    && UIDockLayout.this.consumeSplitterDoubleClick(index))
                {
                    return true;
                }

                UIDockLayout.this.beginSplitterDrag(index, context.mouseX, context.mouseY);
                boolean handled = super.subMouseClicked(context);

                if (!handled)
                {
                    UIDockLayout.this.clearSplitterDragState();
                }

                return handled;
            }
        };

        /* Disable the handle entirely (no click, no resize cursor) when panel resizing is turned off. */
        handle.enabled(() -> BBSSettings.editorResizablePanels.get());

        handle.dragEnd(() ->
        {
            if (this.splitterDragUndoRoot != null && this.splitterDragChanged)
            {
                this.pushLayoutUndo(this.splitterDragUndoRoot);
            }

            this.clearSplitterDragState();
            this.onLayoutSettled.run();
        });
        handle.reference(() -> this.getSplitterHandleReferencePosition(index))
            .referenceAxis(!this.splitterHandleInfos.get(index).horizontal, this.splitterHandleInfos.get(index).horizontal);
        handle.rendering((context) -> this.renderSplitter(context, index));
        this.applySplitterHandleBounds(handle, this.splitterHandleInfos.get(index));

        return handle;
    }

    /**
     * Double-clicking a seam evens its two sides out. Returns true when this click completed a pair,
     * in which case it must not also start a drag.
     */
    private boolean consumeSplitterDoubleClick(int index)
    {
        long now = System.currentTimeMillis();
        boolean paired = index == this.lastSplitterClickIndex && now - this.lastSplitterClickTime <= SPLITTER_DOUBLE_CLICK_MS;

        /* Reset rather than keep the index, so a third click starts a fresh pair. */
        this.lastSplitterClickIndex = paired ? -1 : index;
        this.lastSplitterClickTime = now;

        if (!paired || index < 0 || index >= this.splitterHandleInfos.size())
        {
            return false;
        }

        EditorLayoutNode root = this.layoutRoot();
        List<EditorLayoutNode.SplitterNode> splitters = new ArrayList<>();
        EditorLayoutNode.collectSplitters(root, splitters);

        if (index >= splitters.size())
        {
            return false;
        }

        Map<EditorLayoutNode.SplitterNode, Float> ratios = new HashMap<>();

        ratios.put(splitters.get(index), EditorLayoutNode.SPLIT_RATIO);

        EditorLayoutNode next = EditorLayoutNode.copyWithSplitterRatios(root, ratios);

        if (next != root)
        {
            this.applyLayoutRoot(next);
        }

        return true;
    }

    private void beginSplitterDrag(int index, int mouseX, int mouseY)
    {
        if (!BBSSettings.editorResizablePanels.get() || index < 0 || index >= this.splitterHandleInfos.size())
        {
            this.clearSplitterDragState();
            return;
        }

        this.splitterDragUndoRoot = copyLayoutTree(this.layoutRoot());
        this.splitterDragChanged = false;
        this.draggedSplitterIndices.clear();
        this.draggedSplitterIndices.add(index);
        boolean horizontal = this.splitterHandleInfos.get(index).horizontal;

        for (int i = 0; i < this.splitterHandles.size() && i < this.splitterHandleInfos.size(); i++)
        {
            if (i == index || this.splitterHandleInfos.get(i).horizontal == horizontal)
            {
                continue;
            }

            UIDraggable handle = this.splitterHandles.get(i);

            if (this.isInsideSplitterIntersectionHitbox(handle, mouseX, mouseY))
            {
                this.draggedSplitterIndices.add(i);
            }
        }
    }

    private boolean isInsideSplitterIntersectionHitbox(UIDraggable handle, int mouseX, int mouseY)
    {
        int padding = SPLITTER_LINK_HITBOX_PADDING_PX;

        return mouseX >= handle.area.x - padding
            && mouseX < handle.area.ex() + padding
            && mouseY >= handle.area.y - padding
            && mouseY < handle.area.ey() + padding;
    }

    private void clearSplitterDragState()
    {
        this.draggedSplitterIndices.clear();
        this.splitterDragUndoRoot = null;
        this.splitterDragChanged = false;
    }

    private void applySplitterDrag(int mouseX, int mouseY)
    {
        if (this.draggedSplitterIndices.isEmpty())
        {
            return;
        }

        BaseValue.edit(this.source.value(), (__) ->
        {
            List<EditorLayoutNode.SplitterNode> splitters = this.layoutSplittersForWrite();

            for (int draggedIndex : this.draggedSplitterIndices)
            {
                this.applySplitterRatioFromMouse(splitters, draggedIndex, mouseX, mouseY);
            }
        });

        this.setupFlex(true);
    }

    private void applySplitterRatioFromMouse(List<EditorLayoutNode.SplitterNode> splitters, int index, int mouseX, int mouseY)
    {
        if (index < 0 || index >= splitters.size())
        {
            return;
        }

        float ratio = this.getSplitterRatioFromMouse(index, mouseX, mouseY);

        if (ratio >= 0F)
        {
            EditorLayoutNode.SplitterNode splitter = splitters.get(index);

            if (splitter.getRatio() != ratio)
            {
                splitter.setRatio(ratio);
                this.splitterDragChanged = true;
            }
        }
    }

    private float getSplitterRatioFromMouse(int index, int mouseX, int mouseY)
    {
        if (index < 0 || index >= this.splitterHandleInfos.size())
        {
            return -1F;
        }

        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);
        int ex = this.area.x;
        int ey = this.area.y;
        int ew = Math.max(1, this.area.w);
        int eh = Math.max(1, this.area.h);
        float ratio = info.horizontal
            ? (mouseY - (ey + info.py * eh)) / (info.ph * eh)
            : (mouseX - (ex + info.px * ew)) / (info.pw * ew);
        float lo = EditorLayoutNode.MIN_RATIO;
        float hi = EditorLayoutNode.MAX_RATIO;
        float lengthPx = info.horizontal ? info.ph * eh : info.pw * ew;
        float need = lengthPx > 0 ? MIN_PANEL_SIZE_PX / lengthPx : 1F;

        /* Keep both sides usable in pixels, not in shares; deep in the tree a share of a share can
         * shrink a panel to nothing. When the pair is too small even for that, the model's own
         * clamp is all that is left. */
        if (need <= 0.5F)
        {
            lo = Math.max(lo, need);
            hi = Math.min(hi, 1F - need);
        }

        return MathUtils.clamp(ratio, lo, hi);
    }

    private Vector2i getSplitterHandleReferencePosition(int index)
    {
        List<EditorLayoutNode.SplitterNode> splitters = this.layoutSplitters();

        if (index < 0 || index >= this.splitterHandleInfos.size() || index >= splitters.size())
        {
            return new Vector2i(this.area.x, this.area.y);
        }

        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);
        float r = splitters.get(index).getRatio();
        int ex = this.area.x;
        int ey = this.area.y;
        int ew = Math.max(1, this.area.w);
        int eh = Math.max(1, this.area.h);
        int hx = ex + (int) ((info.px + (info.horizontal ? info.pw * 0.5F : r * info.pw)) * ew);
        int hy = ey + (int) ((info.py + (info.horizontal ? r * info.ph : info.ph * 0.5F)) * eh);

        return new Vector2i(hx, hy);
    }

    private void renderSplitter(UIContext context, int index)
    {
        if (index < 0 || index >= this.splitterHandles.size() || index >= this.splitterHandleInfos.size())
        {
            return;
        }

        UIDraggable splitter = this.splitterHandles.get(index);
        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);
        boolean legacyVisible = splitter.isDragging() || this.draggedSplitterIndices.contains(index);
        boolean active = legacyVisible || splitter.area.isInside(context);

        if ((splitter.isDragging() || splitter.area.isInside(context)) && BBSSettings.editorResizablePanels.get())
        {
            context.requestCursor(this.getSplitterCursor(index, context.mouseX, context.mouseY));
        }

        UIDockStyleRenderer.renderSplitter(context, splitter.area, info.horizontal, active, legacyVisible);
    }

    private int getSplitterCursor(int index, int mouseX, int mouseY)
    {
        if (index < 0 || index >= this.splitterHandleInfos.size())
        {
            return GLFW.GLFW_ARROW_CURSOR;
        }

        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);

        return this.isInsideSplitterIntersection(index, mouseX, mouseY)
            ? GLFW.GLFW_CROSSHAIR_CURSOR
            : info.horizontal
            ? GLFW.GLFW_VRESIZE_CURSOR
            : GLFW.GLFW_HRESIZE_CURSOR;
    }

    private boolean isInsideSplitterIntersection(int index, int mouseX, int mouseY)
    {
        if (index < 0 || index >= this.splitterHandleInfos.size())
        {
            return false;
        }

        boolean horizontal = this.splitterHandleInfos.get(index).horizontal;

        for (int i = 0; i < this.splitterHandles.size() && i < this.splitterHandleInfos.size(); i++)
        {
            if (i == index || this.splitterHandleInfos.get(i).horizontal == horizontal)
            {
                continue;
            }

            if (this.isInsideSplitterIntersectionHitbox(this.splitterHandles.get(i), mouseX, mouseY))
            {
                return true;
            }
        }

        return false;
    }

    /* Dock stacks */

    private void collectDockStacks(EditorLayoutNode node, float x, float y, float w, float h, List<DockStackInfo> out)
    {
        if (node instanceof EditorLayoutNode.PanelNode)
        {
            String panelId = ((EditorLayoutNode.PanelNode) node).getPanelId();
            List<String> ids = new ArrayList<>();
            ids.add(panelId);
            out.add(new DockStackInfo(ids, panelId, x, y, w, h));

            return;
        }

        if (node instanceof EditorLayoutNode.StackNode)
        {
            EditorLayoutNode.StackNode stack = (EditorLayoutNode.StackNode) node;
            out.add(new DockStackInfo(new ArrayList<>(stack.getPanelIds()), stack.getActivePanelId(), x, y, w, h));

            return;
        }

        if (!(node instanceof EditorLayoutNode.SplitterNode))
        {
            return;
        }

        EditorLayoutNode.SplitterNode splitter = (EditorLayoutNode.SplitterNode) node;

        if (splitter.isHorizontal())
        {
            float h1 = h * splitter.getRatio();

            this.collectDockStacks(splitter.getFirst(), x, y, w, h1, out);
            this.collectDockStacks(splitter.getSecond(), x, y + h1, w, h - h1, out);
        }
        else
        {
            float w1 = w * splitter.getRatio();

            this.collectDockStacks(splitter.getFirst(), x, y, w1, h, out);
            this.collectDockStacks(splitter.getSecond(), x + w1, y, w - w1, h, out);
        }
    }

    private float[] framelessStackRect(List<DockStackInfo> stackInfos)
    {
        if (this.framelessPanelId == null)
        {
            return null;
        }

        for (DockStackInfo info : stackInfos)
        {
            if (info.panelIds.contains(this.framelessPanelId))
            {
                return new float[] {info.x, info.y, info.w, info.h};
            }
        }

        return null;
    }

    /**
     * Per-edge gaps so seams between panels don't double up: a full gap where a side does not get
     * a matching half from the other side (the outer edge or the frameless panel), and a half gap
     * where a regular neighbour meets it. Returns left, top, right, bottom offsets in pixels.
     */
    private int[] panelGutter(DockStackInfo info, float[] frameless)
    {
        int half = PANEL_GAP_PX / 2;
        float x = info.x, y = info.y, w = info.w, h = info.h;

        boolean left = x <= PANEL_EDGE_EPS;
        boolean top = y <= PANEL_EDGE_EPS;
        boolean right = x + w >= 1F - PANEL_EDGE_EPS;
        boolean bottom = y + h >= 1F - PANEL_EDGE_EPS;

        if (frameless != null)
        {
            float vx = frameless[0], vy = frameless[1], vw = frameless[2], vh = frameless[3];
            boolean spanY = y < vy + vh - PANEL_EDGE_EPS && y + h > vy + PANEL_EDGE_EPS;
            boolean spanX = x < vx + vw - PANEL_EDGE_EPS && x + w > vx + PANEL_EDGE_EPS;

            left |= spanY && Math.abs(x - (vx + vw)) <= PANEL_EDGE_EPS;
            right |= spanY && Math.abs((x + w) - vx) <= PANEL_EDGE_EPS;
            top |= spanX && Math.abs(y - (vy + vh)) <= PANEL_EDGE_EPS;
            bottom |= spanX && Math.abs((y + h) - vy) <= PANEL_EDGE_EPS;
        }

        return new int[] {
            left ? PANEL_GAP_PX : half,
            top ? PANEL_GAP_PX : half,
            right ? PANEL_GAP_PX : half,
            bottom ? PANEL_GAP_PX : half
        };
    }

    private void applyPanelBoundsFromStacks(List<DockStackInfo> stackInfos)
    {
        this.dockStackByPanelId.clear();

        float[] frameless = this.framelessStackRect(stackInfos);

        for (DockStackInfo info : stackInfos)
        {
            int topOffset = info.isStacked() ? DOCK_STACK_TABS_HEIGHT_PX : 0;

            for (String panelId : info.panelIds)
            {
                UIElement panel = this.panelById.get(panelId);

                if (panel == null)
                {
                    continue;
                }

                int[] g = this.isFrameless(panelId) ? new int[4] : this.panelGutter(info, frameless);

                panel.relative(this)
                    .x(info.x, g[0])
                    .y(info.y, topOffset + g[1])
                    .w(info.w, -g[0] - g[2])
                    .h(info.h, -topOffset - g[1] - g[3]);
                this.dockStackByPanelId.put(panelId, info);
            }
        }
    }

    private void rebuildDockStackTabs(List<DockStackInfo> stackInfos)
    {
        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            tabs.removeFromParent();
        }

        this.dockStackTabs.clear();

        float[] frameless = this.framelessStackRect(stackInfos);

        for (DockStackInfo info : stackInfos)
        {
            if (!info.isStacked())
            {
                continue;
            }

            UIDockStackTabs tabs = new UIDockStackTabs(this);
            tabs.configure(info);
            int[] g = this.panelGutter(info, frameless);

            tabs.relative(this).x(info.x, g[0]).y(info.y, g[1]).w(info.w, -g[0] - g[2]).h(DOCK_STACK_TABS_HEIGHT_PX);
            this.dockStackTabs.add(tabs);
            this.add(tabs);
        }
    }

    private boolean updateDockStackTabsBoundsOnly(List<DockStackInfo> stackInfos)
    {
        List<DockStackInfo> stackedInfos = new ArrayList<>();

        for (DockStackInfo info : stackInfos)
        {
            if (info.isStacked())
            {
                stackedInfos.add(info);
            }
        }

        if (stackedInfos.size() != this.dockStackTabs.size())
        {
            return false;
        }

        for (int i = 0; i < stackedInfos.size(); i++)
        {
            if (!this.dockStackTabs.get(i).matches(stackedInfos.get(i)))
            {
                return false;
            }
        }

        float[] frameless = this.framelessStackRect(stackInfos);

        for (int i = 0; i < stackedInfos.size(); i++)
        {
            UIDockStackTabs tabs = this.dockStackTabs.get(i);
            DockStackInfo info = stackedInfos.get(i);

            tabs.configure(info);
            int[] g = this.panelGutter(info, frameless);

            tabs.relative(this).x(info.x, g[0]).y(info.y, g[1]).w(info.w, -g[0] - g[2]).h(DOCK_STACK_TABS_HEIGHT_PX);
        }

        return true;
    }

    private void applyDragHandleBoundsFromStacks(List<DockStackInfo> stackInfos)
    {
        for (UIDraggable handle : this.dragHandlesById.values())
        {
            handle.setVisible(false);
        }

        int editorHeight = Math.max(1, this.area.h);
        float[] frameless = this.framelessStackRect(stackInfos);

        for (DockStackInfo info : stackInfos)
        {
            UIDraggable handle = this.dragHandlesById.get(info.activePanelId);

            if (handle == null)
            {
                continue;
            }

            float tabsOffset = info.isStacked() ? (float) DOCK_STACK_TABS_HEIGHT_PX / editorHeight : 0F;
            int[] g = this.isFrameless(info.activePanelId) ? new int[4] : this.panelGutter(info, frameless);

            handle.relative(this)
                .x(info.x, g[0])
                .y(info.y + tabsOffset + DRAG_HANDLE_TOP_OFFSET_NORM, g[1])
                .w(info.w, -g[0] - g[2])
                .h(DRAG_HANDLE_HEIGHT_NORM);
            handle.setVisible(!this.layoutLocked);
        }
    }

    /* Panel drag-to-dock */

    private void clearPanelDragState()
    {
        this.draggingPanelId = null;
        this.tabPressPanelId = null;
        this.dragFromTab = false;
        this.panelDragCancelled = false;
        this.clearSpringLoad();
        this.pendingSpringPanelId = null;
        this.clearDropTarget();
    }

    private void clearDropTarget()
    {
        this.dropTargetPanelId = null;
        this.dropTargetIsRoot = false;
        this.dropTargetZone = DROP_ZONE_CENTER;
        this.dropTargetTabStrip = null;
        this.dropTargetTabIndex = -1;
    }

    private boolean hasDropTarget()
    {
        return this.dropTargetIsRoot || this.dropTargetPanelId != null;
    }

    private void applyPanelDropResult(String dragId, String targetId, int zone)
    {
        EditorLayoutNode root = this.layoutRoot();
        EditorLayoutNode newRoot;

        if (targetId == null)
        {
            newRoot = EditorLayoutNode.copyWithInsertSplitAtRoot(root, dragId, zone, DROP_ROOT_RATIO);
        }
        else if (zone == DROP_ZONE_CENTER && Window.isShiftPressed())
        {
            /* Shift turns the stack drop into an exchange: both panels keep their stacks/splits. */
            newRoot = EditorLayoutNode.copyWithSwappedPanels(root, dragId, targetId);
        }
        else if (zone == DROP_ZONE_CENTER)
        {
            newRoot = EditorLayoutNode.copyWithInsertStackAt(root, targetId, dragId);
        }
        else
        {
            newRoot = EditorLayoutNode.copyWithInsertSplitAt(root, targetId, dragId, zone);
        }

        if (newRoot != null && newRoot != root)
        {
            this.applyLayoutRoot(newRoot);
        }
    }

    private UIDraggable createPanelDragHandle(String panelId)
    {
        UIDraggable handle = new UIDraggable((context) ->
        {
            if (this.panelDragCancelled)
            {
                return;
            }

            if (this.draggingPanelId == null)
            {
                this.draggingPanelId = panelId;
            }

            this.updateDropTarget(context.mouseX, context.mouseY);
        });

        handle.dragEnd(this::finishPanelDrag);
        handle.hoverOnly().cursors(GLFW.GLFW_HAND_CURSOR, GLFW.GLFW_HAND_CURSOR).rendering((context) -> this.renderPanelDragHandle(context, handle));

        return handle;
    }

    /** Recomputes what the dragged panel would land on. Runs every frame while a drag is live. */
    private void updateDropTarget(int mouseX, int mouseY)
    {
        this.clearDropTarget();

        /* While maximized the tree on screen is not the tree being edited, so drops are disabled. */
        if (this.maximizedPanelId != null)
        {
            this.clearSpringLoad();

            return;
        }

        /* Tabs are the smallest target, so they win over the bands around them. */
        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            if (!tabs.isVisible() || !tabs.area.isInside(mouseX, mouseY))
            {
                continue;
            }

            int index = tabs.getTabIndex(mouseX);

            if (index >= 0)
            {
                String targetPanelId = tabs.panelIds.get(index);

                this.dropTargetPanelId = targetPanelId;
                this.dropTargetTabStrip = tabs;
                this.dropTargetTabIndex = index;
                this.updateSpringLoad(targetPanelId);

                return;
            }

            break;
        }

        this.clearSpringLoad();

        /* The dock's own rim comes next: right at the screen edge you dock against everything,
         * a little further in against the panel you are over. */
        int editorEdge = nearestEdge(this.area, mouseX, mouseY, DROP_EDITOR_EDGE_PX, DROP_EDITOR_EDGE_PX);

        if (editorEdge != DROP_ZONE_CENTER)
        {
            this.dropTargetIsRoot = true;
            this.dropTargetZone = editorEdge;

            return;
        }

        /* Panels never overlap outside stacks, so the first visible hit is the only hit. */
        for (Map.Entry<String, UIElement> e : this.panelById.entrySet())
        {
            UIElement panel = e.getValue();

            if (panel.isVisible() && panel.area.isInside(mouseX, mouseY))
            {
                int zone = this.computeDropZone(panel.area, mouseX, mouseY);

                this.dropTargetPanelId = this.resolveEdgeDropTarget(e.getKey(), zone);
                this.dropTargetZone = zone;

                break;
            }
        }
    }

    /**
     * Dropping a stack's own tab onto an edge of the slot it already lives in means "pull it out and
     * put it on that side". The split has to be built against a panel that stays behind, otherwise
     * the target and the dragged panel are the same one and the drop is discarded as a no-op.
     */
    private String resolveEdgeDropTarget(String panelId, int zone)
    {
        if (zone == DROP_ZONE_CENTER || !panelId.equals(this.draggingPanelId))
        {
            return panelId;
        }

        DockStackInfo stack = this.dockStackByPanelId.get(panelId);

        if (stack == null || !stack.isStacked())
        {
            return panelId;
        }

        for (String id : stack.panelIds)
        {
            if (!id.equals(this.draggingPanelId))
            {
                return id;
            }
        }

        return panelId;
    }

    /** Dwelling on another stack's tab mid-drag flips to it, so covered panels can be aimed into. */
    private void updateSpringLoad(String tabPanelId)
    {
        if (tabPanelId.equals(this.draggingPanelId))
        {
            this.clearSpringLoad();

            return;
        }

        long now = System.currentTimeMillis();

        if (!tabPanelId.equals(this.springTabPanelId))
        {
            this.springTabPanelId = tabPanelId;
            this.springTabSince = now;

            return;
        }

        if (now - this.springTabSince >= SPRING_LOAD_DELAY_MS)
        {
            /* Deferred: this runs while the dock's children are being iterated, and flipping a tab
             * rebuilds the tab strips. */
            this.pendingSpringPanelId = tabPanelId;
            this.clearSpringLoad();
        }
    }

    private void clearSpringLoad()
    {
        this.springTabPanelId = null;
        this.springTabSince = 0L;
    }

    private void finishPanelDrag()
    {
        boolean ontoItself = this.draggingPanelId != null && this.draggingPanelId.equals(this.dropTargetPanelId);

        if (!this.panelDragCancelled && this.draggingPanelId != null && this.hasDropTarget() && !ontoItself)
        {
            this.applyPanelDropResult(this.draggingPanelId, this.dropTargetPanelId, this.dropTargetZone);
        }

        this.clearPanelDragState();
    }

    /** Kills the in-flight drag; the cancelled flag mutes the drag until the mouse is released. */
    private void cancelPanelDrag()
    {
        this.draggingPanelId = null;
        this.tabPressPanelId = null;
        this.dragFromTab = false;
        this.panelDragCancelled = true;
        this.clearSpringLoad();
        this.clearDropTarget();
    }

    /* Dragging by a stack tab: press arms it, movement past the threshold starts the drag,
     * release either drops the panel or, if it never moved, activates the tab as a click. */

    private void onTabPressed(String panelId, int mouseX, int mouseY)
    {
        this.tabPressPanelId = panelId;
        this.tabPressX = mouseX;
        this.tabPressY = mouseY;
        this.panelDragCancelled = false;
    }

    private void onTabsReleased()
    {
        String pressed = this.tabPressPanelId;

        if (pressed == null)
        {
            /* A cancelled press keeps its flag until release; this is the release. */
            if (this.draggingPanelId == null)
            {
                this.panelDragCancelled = false;
            }

            return;
        }

        if (this.dragFromTab && this.draggingPanelId != null)
        {
            this.finishPanelDrag();

            return;
        }

        boolean cancelled = this.panelDragCancelled;

        this.tabPressPanelId = null;
        this.panelDragCancelled = false;

        if (!cancelled)
        {
            this.activateDockStackTab(pressed, pressed);
        }
    }

    /**
     * Runs at the top of {@link #render}, before the children are walked, so the layout rebuilds it
     * can trigger are safe here.
     */
    private void updateTabDrag(UIContext context)
    {
        if (this.tabPressPanelId == null || this.panelDragCancelled)
        {
            return;
        }

        /* The release event can miss us when the strip it started on was rebuilt mid-drag, which
         * would leave the press armed and turn the next mouse move into a phantom drag. */
        if (!Window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT))
        {
            this.onTabsReleased();

            return;
        }

        if (this.draggingPanelId == null)
        {
            if (this.layoutLocked)
            {
                return;
            }

            int moved = Math.abs(context.mouseX - this.tabPressX) + Math.abs(context.mouseY - this.tabPressY);

            if (moved < DRAG_START_THRESHOLD_PX)
            {
                return;
            }

            this.draggingPanelId = this.tabPressPanelId;
            this.dragFromTab = true;
        }

        if (this.dragFromTab)
        {
            context.requestCursor(GLFW.GLFW_HAND_CURSOR);
            this.updateDropTarget(context.mouseX, context.mouseY);
        }
    }

    /* Hiding and showing panels */

    private boolean canHidePanel(String panelId)
    {
        HashSet<String> present = new HashSet<>();

        EditorLayoutNode.collectPanelIds(this.layoutRoot(), present);

        return present.contains(panelId) && present.size() > 1;
    }

    private void hidePanel(String panelId)
    {
        if (!this.canHidePanel(panelId))
        {
            return;
        }

        EditorLayoutNode root = this.layoutRoot();

        this.beginLayoutChange();

        try
        {
            this.pushLayoutUndo(root);

            Set<String> hidden = this.source.getHiddenPanels();

            hidden.add(panelId);
            this.source.setHiddenPanels(hidden);

            if (panelId.equals(this.maximizedPanelId))
            {
                this.maximizedPanelId = null;
            }

            this.setLayoutRoot(EditorLayoutNode.copyWithRemovedPanel(root, panelId));
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }
    }

    private void showPanel(String panelId)
    {
        Set<String> hidden = this.source.getHiddenPanels();

        if (!hidden.remove(panelId))
        {
            return;
        }

        this.beginLayoutChange();

        try
        {
            this.pushLayoutUndo(this.layoutRoot());
            this.source.setHiddenPanels(hidden);
            this.setupFlex(true);
        }
        finally
        {
            this.endLayoutChange();
        }
    }

    /**
     * Menu entries that bring hidden panels back. Hosts surface these in their own menus too, so a
     * panel hidden while unlocked can still be recovered after the layout is locked again.
     */
    public void fillHiddenPanelsMenu(ContextMenuManager menu)
    {
        Set<String> hidden = this.source.getHiddenPanels();

        for (String id : this.panelById.keySet())
        {
            if (hidden.contains(id))
            {
                menu.action(Icons.VISIBLE, UIKeys.DOCK_SHOW.format(this.getPanelLabel(id).get()), () -> this.showPanel(id));
            }
        }
    }

    /** Right-click menu of a panel's drag strip: maximize, hide, bring hidden panels back. */
    private void fillSlotContextMenu(ContextMenuManager menu, String panelId)
    {
        if (this.layoutLocked || !this.gate.get())
        {
            return;
        }

        boolean maximized = panelId.equals(this.maximizedPanelId);

        menu.action(maximized ? Icons.MINIMIZE : Icons.MAXIMIZE, maximized ? UIKeys.DOCK_RESTORE : UIKeys.DOCK_MAXIMIZE, () -> this.toggleMaximizePanel(panelId));

        if (this.canHidePanel(panelId))
        {
            menu.action(Icons.INVISIBLE, UIKeys.DOCK_HIDE.format(this.getPanelLabel(panelId).get()), () -> this.hidePanel(panelId));
        }

        this.fillHiddenPanelsMenu(menu);
    }

    /** The tree the layout pass actually renders: the stored one, or a single maximized panel. */
    private EditorLayoutNode effectiveLayoutTree(EditorLayoutNode root)
    {
        if (this.maximizedPanelId != null
            && (!this.panelById.containsKey(this.maximizedPanelId) || this.source.getHiddenPanels().contains(this.maximizedPanelId)))
        {
            this.maximizedPanelId = null;
        }

        return this.maximizedPanelId == null ? root : new EditorLayoutNode.PanelNode(this.maximizedPanelId);
    }

    private boolean isSwapDrop()
    {
        return !this.dropTargetIsRoot
            && this.dropTargetPanelId != null
            && this.draggingPanelId != null
            && !this.draggingPanelId.equals(this.dropTargetPanelId)
            && Window.isShiftPressed();
    }

    private void renderPanelDragHandle(UIContext context, UIDraggable handle)
    {
        boolean active = handle.area.isInside(context) || handle.isDragging();

        UIDockStyleRenderer.renderPanelDragHandle(context, handle.area, active);
    }

    private int computeDropZone(Area area, int mouseX, int mouseY)
    {
        return nearestEdge(area, mouseX, mouseY, panelEdgeBand(area.w), panelEdgeBand(area.h));
    }

    private static int panelEdgeBand(int size)
    {
        return Math.min(DROP_PANEL_EDGE_PX, (int) (size * DROP_PANEL_EDGE_MAX));
    }

    private static int nearestEdge(Area area, int mouseX, int mouseY, int bandX, int bandY)
    {
        int left = mouseX - area.x;
        int right = area.ex() - 1 - mouseX;
        int top = mouseY - area.y;
        int bottom = area.ey() - 1 - mouseY;

        if (left < 0 || right < 0 || top < 0 || bottom < 0)
        {
            return DROP_ZONE_CENTER;
        }

        int zone = DROP_ZONE_CENTER;
        int best = Integer.MAX_VALUE;

        if (left < bandX && left < best)
        {
            best = left;
            zone = EditorLayoutNode.EDGE_LEFT;
        }

        if (right < bandX && right < best)
        {
            best = right;
            zone = EditorLayoutNode.EDGE_RIGHT;
        }

        if (top < bandY && top < best)
        {
            best = top;
            zone = EditorLayoutNode.EDGE_TOP;
        }

        if (bottom < bandY && bottom < best)
        {
            zone = EditorLayoutNode.EDGE_BOTTOM;
        }

        return zone;
    }

    /* Rendering */

    private boolean isFrameless(String panelId)
    {
        return this.framelessPanelId != null && this.framelessPanelId.equals(panelId);
    }

    private void renderPanelSurfaces(UIContext context)
    {
        this.area.render(context.batcher, BBSSettings.baseSurface());

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            UIElement panel = entry.getValue();

            if (panel.isVisible() && !this.isFrameless(entry.getKey()))
            {
                panel.area.render(context.batcher, BBSSettings.deepSurface());
            }
        }
    }

    private void renderPanelBorders(UIContext context)
    {
        if (!BBSSettings.interfaceShadows.get())
        {
            return;
        }

        int fade = Colors.setA(Colors.A100, 0F);

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            UIElement panel = entry.getValue();

            if (!panel.isVisible() || this.isFrameless(entry.getKey()))
            {
                continue;
            }

            Area a = panel.area;

            context.batcher.gradientVBox(a.x, a.y, a.ex(), a.y + 4, Colors.A25, fade);
            context.batcher.gradientVBox(a.x, a.ey() - 4, a.ex(), a.ey(), fade, Colors.A25);
            context.batcher.gradientHBox(a.x, a.y, a.x + 4, a.ey(), Colors.A25, fade);
            context.batcher.gradientHBox(a.ex() - 4, a.y, a.ex(), a.ey(), fade, Colors.A25);
        }
    }

    @Override
    public void render(UIContext context)
    {
        if (this.pendingSpringPanelId != null)
        {
            String panelId = this.pendingSpringPanelId;

            this.pendingSpringPanelId = null;
            this.activateDockStackTab(panelId, panelId);
        }

        this.updateTabDrag(context);
        this.advanceLayoutTransition();

        super.render(context);

        this.renderDragOverlay(context);
    }

    @Override
    protected boolean subKeyPressed(UIContext context)
    {
        if (context.getKeyCode() == GLFW.GLFW_KEY_ESCAPE && (this.draggingPanelId != null || this.tabPressPanelId != null))
        {
            this.cancelPanelDrag();

            return true;
        }

        return super.subKeyPressed(context);
    }

    /** Insertion caret between tabs plus the ghost of the dragged panel, on top of everything. */
    private void renderDragOverlay(UIContext context)
    {
        if (this.draggingPanelId == null || this.panelDragCancelled || this.maximizedPanelId != null)
        {
            return;
        }

        if (this.dropTargetTabStrip != null && this.dropTargetTabStrip.isVisible() && this.dropTargetTabIndex >= 0)
        {
            UIDockStackTabs strip = this.dropTargetTabStrip;
            int x = Math.min(strip.area.x + (this.dropTargetTabIndex + 1) * strip.getTabSize(), strip.area.ex() - 1);

            context.batcher.box(x - 1, strip.area.y, x + 1, strip.area.ey(), BBSSettings.primaryColor(Colors.A100));
        }

        String label = this.getPanelLabel(this.draggingPanelId).get();

        context.batcher.icon(this.getDockPanelIcon(this.draggingPanelId), Colors.WHITE, context.mouseX + 16, context.mouseY + 16, 0.5F, 0.5F);

        if (!label.isEmpty())
        {
            context.batcher.textCard(label, context.mouseX + 26, context.mouseY + 12);
        }
    }

    /**
     * Marks where the panel would land: a band along the edge it will dock to, densest at that edge
     * and thinning inwards, so it reads as the panel being pulled to that side while leaving the
     * content it passes over legible. Centre drops have no direction, so they get a feathered rim.
     */
    private void renderDropZoneHighlight(UIContext context)
    {
        if (this.layoutLocked || this.draggingPanelId == null || !this.hasDropTarget())
        {
            return;
        }

        Area area = this.area;
        float ratio = DROP_ROOT_RATIO;

        if (!this.dropTargetIsRoot)
        {
            UIElement target = this.panelById.get(this.dropTargetPanelId);

            if (target == null)
            {
                return;
            }

            area = target.area;
            ratio = EditorLayoutNode.SPLIT_RATIO;
        }

        UIDockStyleRenderer.renderDropZone(context, area, this.dropTargetZone, ratio);

        if (this.isSwapDrop())
        {
            UIElement dragged = this.panelById.get(this.draggingPanelId);

            if (dragged != null && dragged.isVisible())
            {
                UIDockStyleRenderer.renderDropZone(context, dragged.area, DROP_ZONE_CENTER, EditorLayoutNode.SPLIT_RATIO);
            }

            context.batcher.icon(Icons.EXCHANGE, Colors.WHITE, area.mx(), area.my(), 0.5F, 0.5F);
        }
    }

    /* Helper types */

    /** One undo step: the tree plus the hidden set that went with it. */
    private static class LayoutSnapshot
    {
        public final EditorLayoutNode root;
        public final Set<String> hidden;

        public LayoutSnapshot(EditorLayoutNode root, Set<String> hidden)
        {
            this.root = root;
            this.hidden = hidden;
        }
    }
    private static class DockStackInfo
    {
        public final List<String> panelIds;
        public final String activePanelId;
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public DockStackInfo(List<String> panelIds, String activePanelId, float x, float y, float w, float h)
        {
            this.panelIds = panelIds;
            this.activePanelId = activePanelId;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean isStacked()
        {
            return this.panelIds.size() > 1;
        }

        public String getAnchorPanelId()
        {
            return this.panelIds.isEmpty() ? "" : this.panelIds.get(0);
        }
    }

    private static class UIDockStackTabs extends UIElement
    {
        private final UIDockLayout layout;
        private String anchorPanelId = "";
        private final List<String> panelIds = new ArrayList<>();
        private String activePanelId;

        public UIDockStackTabs(UIDockLayout layout)
        {
            this.layout = layout;
        }

        public void configure(DockStackInfo info)
        {
            this.anchorPanelId = info.getAnchorPanelId();
            this.panelIds.clear();
            this.panelIds.addAll(info.panelIds);
            this.activePanelId = info.activePanelId;
            this.setVisible(info.isStacked());
        }

        public boolean matches(DockStackInfo info)
        {
            return this.anchorPanelId.equals(info.getAnchorPanelId()) && this.panelIds.equals(info.panelIds);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (!this.isVisible() || context.mouseButton != 0 || !this.area.isInside(context) || this.panelIds.isEmpty())
            {
                return super.subMouseClicked(context);
            }

            int index = this.getTabIndex(context.mouseX);

            if (index >= 0 && index < this.panelIds.size())
            {
                /* Activation waits for the release: the same press may grow into a drag. */
                this.layout.onTabPressed(this.panelIds.get(index), context.mouseX, context.mouseY);

                return true;
            }

            return super.subMouseClicked(context);
        }

        @Override
        protected boolean subMouseReleased(UIContext context)
        {
            this.layout.onTabsReleased();

            return super.subMouseReleased(context);
        }

        @Override
        public void render(UIContext context)
        {
            if (!this.isVisible() || this.panelIds.isEmpty())
            {
                return;
            }

            if (this.area.isInside(context))
            {
                context.requestCursor(GLFW.GLFW_HAND_CURSOR);
            }

            int tabSize = this.getTabSize();
            int y = this.area.y;
            int ey = this.area.ey();

            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.chromeSurface());

            for (int i = 0; i < this.panelIds.size(); i++)
            {
                int x = this.area.x + i * tabSize;

                if (x >= this.area.ex())
                {
                    break;
                }

                int ex = Math.min(this.area.ex(), x + tabSize);
                String panelId = this.panelIds.get(i);
                boolean active = panelId.equals(this.activePanelId);
                Icon icon = this.layout.getDockPanelIcon(panelId);

                if (active)
                {
                    Area.SHARED.set(x, y, ex - x, ey - y);
                    UIDashboardPanels.renderHighlight(context.batcher, Area.SHARED, Direction.BOTTOM);
                }

                context.batcher.icon(icon, Colors.WHITE, (x + ex) / 2, (y + ey) / 2, 0.5F, 0.5F);
            }

            int hovered = this.area.isInside(context) ? this.getTabIndex(context.mouseX) : -1;

            if (hovered >= 0 && this.layout.draggingPanelId == null && this.layout.tabPressPanelId == null)
            {
                String label = this.layout.getPanelLabel(this.panelIds.get(hovered)).get();

                if (!label.isEmpty())
                {
                    int ty = this.area.y - 14;

                    context.batcher.textCard(label, context.mouseX + 6, ty < 2 ? this.area.ey() + 4 : ty);
                }
            }

            super.render(context);
        }

        private int getTabSize()
        {
            return Math.max(1, this.area.h);
        }

        private int getTabIndex(int mouseX)
        {
            int index = (mouseX - this.area.x) / this.getTabSize();

            if (index < 0 || index >= this.panelIds.size())
            {
                return -1;
            }

            return index;
        }

    }
}
