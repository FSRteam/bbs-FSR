package mchorse.bbs_mod.ui.particles;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.renderers.ParticleFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleScheme;
import mchorse.bbs_mod.particles.emitter.ParticleEmitter;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.ui.EditorLayoutNode;
import mchorse.bbs_mod.settings.values.ui.ValueEditorLayout;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_mod.ui.particles.utils.ParticleUndoManager;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.layout.ILayoutSource;
import mchorse.bbs_mod.ui.framework.elements.layout.UIDockLayout;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeAppearanceSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeCollisionAppearanceSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeCollisionSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeCurvesSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeExpirationSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeExpireInBlocksSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeExpireNotInBlocksSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeEventsSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeGeneralSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeInitializationSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeLifetimeSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeMotionSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeParticleInitializationSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeRateSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeShapeSection;
import mchorse.bbs_mod.ui.particles.sections.UIParticleSchemeSpaceSection;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.presets.UICopyPasteController;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.IOUtils;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.presets.PresetManager;

import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class UIParticleSchemePanel extends UIDataDashboardPanel<ParticleScheme>
{
    public static final Link PARTICLE_PLACEHOLDER = Link.assets("particles/default_placeholder.json");

    private static final String PANEL_PREVIEW_ID = "preview";
    private static final String PANEL_FILE_ID = "file";
    private static final String PANEL_EMITTER_ID = "emitter";
    private static final String PANEL_MOTION_ID = "motion";
    private static final String PANEL_APPEARANCE_ID = "appearance";
    private static final String PANEL_TIME_ID = "time";
    private static final String PANEL_EVENTS_ID = "events";
    private static final String PANEL_CURVES_ID = "curves";

    private static final float DRAG_HANDLE_HEIGHT_NORM = 0.02F;
    private static final float DRAG_HANDLE_TOP_OFFSET_NORM = 0.01F;
    private static final int SPLITTER_HANDLE_PX = 14;
    private static final int SPLITTER_HANDLE_LINE_PX = 1;
    private static final int DROP_ZONE_CENTER = -1;
    private static final float DROP_EDGE_MARGIN = 0.2F;
    private static final int DOCK_STACK_TABS_HEIGHT_PX = 20;
    private static final int EDITOR_MIN_SIZE_FOR_PX_HANDLES = 10;

    public UIParticleSchemeRenderer renderer;
    public UIParticleSelectionPanel selectionPanel;
    public UIDockLayout dock;

    public List<UIParticleSchemeSection> sections = new ArrayList<>();

    public UIIcon lockLayoutButton;
    public UIIcon layoutPresetsButton;
    public UIIcon playPauseBtn;
    private boolean layoutLocked = true;
    private UICopyPasteController layoutPresetsController;

    private ParticleUndoManager particleUndo;
    private boolean applyingParticleUndo;

    /* Layout system */
    private final Map<String, UIElement> panelById = new LinkedHashMap<>();
    private final List<UIDraggable> splitterHandles = new ArrayList<>();
    private final Map<String, UIDraggable> dragHandlesById = new LinkedHashMap<>();
    private final List<EditorLayoutNode.SplitterHandleInfo> splitterHandleInfos = new ArrayList<>();
    private final List<UIDockStackTabs> dockStackTabs = new ArrayList<>();
    private final Map<String, DockStackInfo> dockStackByPanelId = new HashMap<>();

    private String draggingPanelId;
    private String dropTargetPanelId;
    private int dropTargetZone = DROP_ZONE_CENTER;
    private boolean pendingLayoutUpdate;

    public UIParticleSchemePanel(UIDashboard dashboard)
    {
        super(dashboard);
        this.enableTabs();

        /* Renderer (monitor preview) */
        this.renderer = new UIParticleSchemeRenderer();

        /* Selection panel */
        this.selectionPanel = new UIParticleSelectionPanel(this);
        this.selectionPanel.relative(this).y(UIDataTabs.TABS_HEIGHT_PX).wTo(this.iconBar.area).h(1F, -UIDataTabs.TABS_HEIGHT_PX);
        this.add(this.selectionPanel);

        /* Build 7 panel containers (no quickSetup) */
        UIElement previewPanel = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                this.area.render(context.batcher, Colors.A100);
                super.render(context);
            }
        };

        /* Play/Pause button in the preview panel */
        this.playPauseBtn = new UIIcon(() ->
        {
            ParticleEmitter e = UIParticleSchemePanel.this.renderer.emitter;
            return (e != null && e.paused) ? Icons.PLAY : Icons.PAUSE;
        }, (b) -> UIParticleSchemePanel.this.togglePlause());
        this.playPauseBtn.tooltip(UIKeys.SNOWSTORM_PLAUSE, Direction.TOP);

        previewPanel.add(this.renderer, this.playPauseBtn);
        this.renderer.full(previewPanel);
        this.playPauseBtn.relative(previewPanel).x(4).y(1F, -24);

        UIParticleTabPage filePage = new UIParticleTabPage();
        UIParticleTabPage emitterPage = new UIParticleTabPage();
        UIParticleTabPage motionPage = new UIParticleTabPage();
        UIParticleTabPage appearancePage = new UIParticleTabPage();
        UIParticleTabPage timePage = new UIParticleTabPage();
        UIParticleTabPage eventsPage = new UIParticleTabPage();
        UIParticleTabPage curvesPage = new UIParticleTabPage();

        /* Add sections to tab pages */
        filePage.addSection(new UIParticleSchemeGeneralSection(this));
        filePage.addSection(new UIParticleSchemeSpaceSection(this));
        filePage.addSection(new UIParticleSchemeInitializationSection(this));
        filePage.addSection(new UIParticleSchemeParticleInitializationSection(this));
        emitterPage.addSection(new UIParticleSchemeRateSection(this));
        emitterPage.addSection(new UIParticleSchemeLifetimeSection(this));
        emitterPage.addSection(new UIParticleSchemeShapeSection(this));
        motionPage.addSection(new UIParticleSchemeMotionSection(this));
        motionPage.addSection(new UIParticleSchemeCollisionSection(this));
        appearancePage.addSection(new UIParticleSchemeAppearanceSection(this));
        appearancePage.addSection(new UIParticleSchemeCollisionAppearanceSection(this));
        timePage.addSection(new UIParticleSchemeExpirationSection(this));
        timePage.addSection(new UIParticleSchemeExpireInBlocksSection(this));
        timePage.addSection(new UIParticleSchemeExpireNotInBlocksSection(this));
        eventsPage.addSection(new UIParticleSchemeEventsSection(this));
        curvesPage.addSection(new UIParticleSchemeCurvesSection(this));

        /* Collect all sections for iteration */
        this.collectSections(filePage);
        this.collectSections(emitterPage);
        this.collectSections(motionPage);
        this.collectSections(appearancePage);
        this.collectSections(timePage);
        this.collectSections(eventsPage);
        this.collectSections(curvesPage);

        /* Register panels by ID */
        this.panelById.put(PANEL_PREVIEW_ID, previewPanel);
        this.panelById.put(PANEL_FILE_ID, filePage);
        this.panelById.put(PANEL_EMITTER_ID, emitterPage);
        this.panelById.put(PANEL_MOTION_ID, motionPage);
        this.panelById.put(PANEL_APPEARANCE_ID, appearancePage);
        this.panelById.put(PANEL_TIME_ID, timePage);
        this.panelById.put(PANEL_EVENTS_ID, eventsPage);
        this.panelById.put(PANEL_CURVES_ID, curvesPage);

        this.dock = new UIDockLayout();
        this.dock.relative(this.editor).w(1F).h(1F);
        this.dock.source(this.createLayoutSource())
            .frameless(PANEL_PREVIEW_ID)
            .gate(() -> this.data != null)
            .ensure(this::ensureParticleLayoutPanels)
            .icons(this::getPanelIcon);

        for (Map.Entry<String, UIElement> entry : this.panelById.entrySet())
        {
            this.dock.addPanel(entry.getKey(), entry.getValue(), this.getPanelIcon(entry.getKey()));
        }

        this.dock.mount();
        this.editor.add(this.dock);
        this.prepend(new UIRenderable(this::drawOverlay));

        /* Icon bar buttons */
        UIIcon restart = new UIIcon(Icons.REFRESH, (b) ->
        {
            this.renderer.setScheme(this.data);
        });
        restart.tooltip(UIKeys.SNOWSTORM_RESTART_EMITTER, Direction.LEFT);
        this.iconBar.add(restart);

        this.lockLayoutButton = new UIIcon(() -> this.dock.isLocked() ? Icons.LOCKED : Icons.UNLOCKED, (b) -> this.toggleLayoutLock());
        this.updateLayoutLockTooltip();
        this.iconBar.add(this.lockLayoutButton);

        this.layoutPresetsController = new UICopyPasteController(PresetManager.PARTICLE_LAYOUTS, "_CopyParticleEditorLayout")
            .supplier(this::getLayoutPresetData)
            .consumer(this::applyLayoutFromPreset);
        this.layoutPresetsButton = new UIIcon(Icons.LAYOUT, (b) ->
        {
            UIContext ctx = this.getContext();
            this.layoutPresetsController.openPresets(ctx, ctx.mouseX, ctx.mouseY);
        });
        this.layoutPresetsButton.context((menu) -> menu.action(Icons.REFRESH, UIKeys.PARTICLE_EDITOR_LAYOUT_RESET, this::resetLayout));
        this.layoutPresetsButton.tooltip(UIKeys.PARTICLE_EDITOR_LAYOUT_PRESETS, Direction.LEFT);
        this.iconBar.add(this.layoutPresetsButton);

        this.keys().register(Keys.FILM_CONTROLLER_NEXT_DOCK_TAB, () ->
        {
            if (this.dock.cycleDockStackTab(1))
            {
                UIUtils.playClick();
            }
        }).category(UIKeys.PARTICLE_EDITOR_TITLE);
        this.keys().register(Keys.FILM_CONTROLLER_PREV_DOCK_TAB, () ->
        {
            if (this.dock.cycleDockStackTab(-1))
            {
                UIUtils.playClick();
            }
        }).category(UIKeys.PARTICLE_EDITOR_TITLE);

        this.overlay.namesList.setFileIcon(Icons.PARTICLE);

        this.fill(null);

        /* Undo/Redo keybinds */
        this.setUndoId("particle_panel");
        this.add(new UIParticleSchemePanelKeys(this).full(this));
    }

    private void collectSections(UIParticleTabPage page)
    {
        this.sections.addAll(page.sections);
    }

    private ILayoutSource createLayoutSource()
    {
        ValueEditorLayout layout = BBSSettings.editorLayoutSettings;

        return new ILayoutSource()
        {
            @Override
            public BaseValue value()
            {
                return layout;
            }

            @Override
            public EditorLayoutNode getRoot()
            {
                return layout.getParticleLayoutRoot();
            }

            @Override
            public void setRoot(EditorLayoutNode root)
            {
                layout.setParticleLayoutRoot(root);
            }

            @Override
            public List<EditorLayoutNode.SplitterNode> getSplitters()
            {
                return layout.getParticleSplitters();
            }

            @Override
            public List<EditorLayoutNode.SplitterNode> getSplittersForWrite()
            {
                return layout.getParticleSplittersForWrite();
            }

            @Override
            public EditorLayoutNode getDefault()
            {
                return EditorLayoutNode.defaultParticleLayout();
            }
        };
    }

    private void updateLayoutLockTooltip()
    {
        if (this.lockLayoutButton != null)
        {
            this.lockLayoutButton.tooltip(this.dock.isLocked() ? UIKeys.PARTICLE_EDITOR_LAYOUT_UNLOCK : UIKeys.PARTICLE_EDITOR_LAYOUT_LOCK, Direction.LEFT);
        }
    }

    /* ===== Layout system (adapted from UIFilmPanel) ===== */

    private void setupParticleEditorFlex(boolean resize)
    {
        ValueEditorLayout layout = BBSSettings.editorLayoutSettings;
        EditorLayoutNode originalRoot = layout.getParticleLayoutRoot();
        EditorLayoutNode root = this.ensureParticleLayoutPanels(originalRoot);

        if (root != originalRoot)
        {
            layout.setParticleLayoutRoot(root);
        }

        List<EditorLayoutNode.SplitterNode> splitters = layout.getParticleSplitters();

        if (!this.layoutLocked && resize && splitters.size() == this.splitterHandles.size())
        {
            this.updateEditorFlexBoundsOnly(root);
            this.resize();
            this.resize();
            return;
        }

        List<DockStackInfo> stackInfos = new ArrayList<>();
        this.collectDockStacks(root, 0F, 0F, 1F, 1F, stackInfos);

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

        if (this.layoutLocked)
        {
            for (UIDraggable h : this.dragHandlesById.values())
            {
                h.setVisible(false);
            }
        }
        else
        {
            this.splitterHandleInfos.clear();
            EditorLayoutNode.computeSplitterHandles(root, 0F, 0F, 1F, 1F, this.splitterHandleInfos);

            for (int i = 0; i < splitters.size(); i++)
            {
                final int index = i;
                UIDraggable handle = new UIDraggable((context) ->
                {
                    float ratio = this.getSplitterRatioFromMouse(index, context.mouseX, context.mouseY);
                    if (ratio >= 0F)
                    {
                        layout.setParticleSplitterRatio(index, ratio);
                        this.pendingLayoutUpdate = true;
                    }
                });
                int cursor = this.splitterHandleInfos.get(index).horizontal ? GLFW.GLFW_VRESIZE_CURSOR : GLFW.GLFW_HRESIZE_CURSOR;

                handle.hoverOnly().cursors(cursor, cursor);
                handle.reference(() -> this.getSplitterHandleReferencePosition(index, splitters));
                handle.rendering((context) -> this.renderSplitter(context, index));
                this.applySplitterHandleBounds(handle, this.splitterHandleInfos.get(index));
                this.splitterHandles.add(handle);
                IUIElement insertAfter = index == 0 ? null : this.splitterHandles.get(index - 1);
                if (insertAfter == null)
                {
                    this.editor.add(handle);
                }
                else
                {
                    this.editor.addAfter(insertAfter, handle);
                }
            }

            this.applyDragHandleBoundsFromStacks(stackInfos);
        }

        this.updateTabVisibility();

        if (resize)
        {
            this.resize();
            this.resize();
        }
    }

    private EditorLayoutNode ensureParticleLayoutPanels(EditorLayoutNode root)
    {
        if (root == null)
        {
            return EditorLayoutNode.defaultParticleLayout();
        }

        HashSet<String> ids = new HashSet<>();
        this.collectPanelIds(root, ids);

        boolean hasExpectedPanels = ids.containsAll(this.panelById.keySet()) && this.panelById.keySet().containsAll(ids);

        if (hasExpectedPanels)
        {
            return root;
        }

        return EditorLayoutNode.defaultParticleLayout();
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

    private void applyPanelBoundsFromStacks(List<DockStackInfo> stackInfos)
    {
        this.dockStackByPanelId.clear();

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

                panel.relative(this.editor).x(info.x).y(info.y, topOffset).w(info.w).h(info.h, -topOffset);
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

        for (DockStackInfo info : stackInfos)
        {
            if (!info.isStacked())
            {
                continue;
            }

            UIDockStackTabs tabs = new UIDockStackTabs();
            tabs.configure(info);
            tabs.relative(this.editor).x(info.x).y(info.y).w(info.w).h(DOCK_STACK_TABS_HEIGHT_PX);
            this.dockStackTabs.add(tabs);
            this.editor.add(tabs);
        }
    }

    private void applyDragHandleBoundsFromStacks(List<DockStackInfo> stackInfos)
    {
        for (UIDraggable handle : this.dragHandlesById.values())
        {
            handle.setVisible(false);
        }

        int editorHeight = Math.max(1, this.editor.area.h);

        for (DockStackInfo info : stackInfos)
        {
            UIDraggable handle = this.dragHandlesById.get(info.activePanelId);

            if (handle == null)
            {
                continue;
            }

            float tabsOffset = info.isStacked() ? (float) DOCK_STACK_TABS_HEIGHT_PX / editorHeight : 0F;
            handle.relative(this.editor)
                .x(info.x)
                .y(info.y + tabsOffset + DRAG_HANDLE_TOP_OFFSET_NORM)
                .w(info.w)
                .h(DRAG_HANDLE_HEIGHT_NORM);
            handle.setVisible(!this.layoutLocked);
        }
    }

    private void updateEditorFlexBoundsOnly(EditorLayoutNode root)
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
            UIDockStackTabs tabs = this.dockStackTabs.get(i);
            DockStackInfo info = stackedInfos.get(i);

            if (!tabs.matches(info))
            {
                return false;
            }
        }

        for (int i = 0; i < stackedInfos.size(); i++)
        {
            UIDockStackTabs tabs = this.dockStackTabs.get(i);
            DockStackInfo info = stackedInfos.get(i);

            tabs.configure(info);
            tabs.relative(this.editor).x(info.x).y(info.y).w(info.w).h(DOCK_STACK_TABS_HEIGHT_PX);
        }

        return true;
    }

    private void updateTabVisibility()
    {
        boolean hasData = this.data != null;

        if (!hasData)
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
                boolean active = PANEL_PREVIEW_ID.equals(entry.getKey()) || this.isDockPanelActive(entry.getKey());
                entry.getValue().setVisible(active);
            }
        }

        for (Map.Entry<String, UIDraggable> entry : this.dragHandlesById.entrySet())
        {
            DockStackInfo stack = this.dockStackByPanelId.get(entry.getKey());
            boolean active = stack != null && entry.getKey().equals(stack.activePanelId);
            entry.getValue().setVisible(hasData && !this.layoutLocked && active);
        }

        for (UIDockStackTabs tabs : this.dockStackTabs)
        {
            tabs.setVisible(hasData);
        }

        this.selectionPanel.setVisible(!hasData);
    }

    private boolean isDockPanelActive(String panelId)
    {
        DockStackInfo stack = this.dockStackByPanelId.get(panelId);
        return stack != null && panelId.equals(stack.activePanelId);
    }

    /* ===== Splitter handles ===== */

    private void applySplitterHandleBounds(UIDraggable handle, EditorLayoutNode.SplitterHandleInfo info)
    {
        int ew = this.editor.area.w;
        int eh = this.editor.area.h;

        if (ew < EDITOR_MIN_SIZE_FOR_PX_HANDLES || eh < EDITOR_MIN_SIZE_FOR_PX_HANDLES)
        {
            handle.relative(this.editor).x(info.hx).y(info.hy).w(info.hw).h(info.hh);
            return;
        }

        if (info.horizontal)
        {
            float centerY = info.hy + info.hh * 0.5F;
            float hyNew = centerY - (SPLITTER_HANDLE_PX / (2F * eh));
            handle.relative(this.editor).x(info.hx).y(hyNew).w(info.hw).h(SPLITTER_HANDLE_PX);
        }
        else
        {
            float centerX = info.hx + info.hw * 0.5F;
            float hxNew = centerX - (SPLITTER_HANDLE_PX / (2F * ew));
            handle.relative(this.editor).x(hxNew).y(info.hy).w(SPLITTER_HANDLE_PX).h(info.hh);
        }
    }

    private void syncSplitterHandleBounds()
    {
        for (int i = 0; i < this.splitterHandles.size() && i < this.splitterHandleInfos.size(); i++)
        {
            this.applySplitterHandleBounds(this.splitterHandles.get(i), this.splitterHandleInfos.get(i));
        }
    }

    private float getSplitterRatioFromMouse(int index, int mouseX, int mouseY)
    {
        if (index < 0 || index >= this.splitterHandleInfos.size())
        {
            return -1F;
        }
        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);
        int ex = this.editor.area.x;
        int ey = this.editor.area.y;
        int ew = Math.max(1, this.editor.area.w);
        int eh = Math.max(1, this.editor.area.h);
        float ratio = info.horizontal
            ? (mouseY - (ey + info.py * eh)) / (info.ph * eh)
            : (mouseX - (ex + info.px * ew)) / (info.pw * ew);
        return MathUtils.clamp(ratio, 0.05F, 0.95F);
    }

    private Vector2i getSplitterHandleReferencePosition(int index, List<EditorLayoutNode.SplitterNode> splitters)
    {
        if (index < 0 || index >= this.splitterHandleInfos.size() || index >= splitters.size())
        {
            return new Vector2i(this.editor.area.x, this.editor.area.y);
        }
        EditorLayoutNode.SplitterHandleInfo info = this.splitterHandleInfos.get(index);
        float r = splitters.get(index).getRatio();
        int ex = this.editor.area.x;
        int ey = this.editor.area.y;
        int ew = Math.max(1, this.editor.area.w);
        int eh = Math.max(1, this.editor.area.h);
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
        int lineColor = BBSSettings.primaryColor(Colors.A100);

        if (!splitter.isDragging())
        {
            return;
        }

        if (info.horizontal)
        {
            int cy = splitter.area.y + splitter.area.h / 2;
            int half = SPLITTER_HANDLE_LINE_PX / 2;
            context.batcher.box(splitter.area.x, cy - half, splitter.area.ex(), cy - half + SPLITTER_HANDLE_LINE_PX, lineColor);
        }
        else
        {
            int cx = splitter.area.x + splitter.area.w / 2;
            int half = SPLITTER_HANDLE_LINE_PX / 2;
            context.batcher.box(cx - half, splitter.area.y, cx - half + SPLITTER_HANDLE_LINE_PX, splitter.area.ey(), lineColor);
        }
    }

    /* ===== Panel drag handles ===== */

    private UIDraggable createPanelDragHandle(String panelId)
    {
        UIDraggable handle = new UIDraggable((context) ->
        {
            if (this.draggingPanelId == null)
            {
                this.draggingPanelId = panelId;
            }
            this.dropTargetPanelId = null;
            this.dropTargetZone = DROP_ZONE_CENTER;

            for (UIDockStackTabs tabs : this.dockStackTabs)
            {
                if (tabs.isVisible() && tabs.area.isInside(context.mouseX, context.mouseY))
                {
                    String targetPanelId = tabs.getPanelIdAt(context.mouseX);

                    if (targetPanelId != null)
                    {
                        this.dropTargetPanelId = targetPanelId;
                        this.dropTargetZone = DROP_ZONE_CENTER;
                        return;
                    }
                    break;
                }
            }

            for (Map.Entry<String, UIElement> e : this.panelById.entrySet())
            {
                if (!e.getValue().isVisible())
                {
                    continue;
                }
                if (e.getValue().area.isInside(context.mouseX, context.mouseY))
                {
                    this.dropTargetPanelId = e.getKey();
                    this.dropTargetZone = this.computeDropZone(e.getValue().area, context.mouseX, context.mouseY);
                    break;
                }
            }
        });
        handle.dragEnd(() ->
        {
            String dragId = this.draggingPanelId;
            String targetId = this.dropTargetPanelId;
            int targetZone = this.dropTargetZone;

            try
            {
                if (dragId == null || targetId == null || dragId.equals(targetId))
                {
                    return;
                }

                this.applyPanelDropResult(dragId, targetId, targetZone);
            }
            finally
            {
                this.clearPanelDragState();
            }
        });
        handle.hoverOnly().rendering((context) -> this.renderPanelDragHandle(context, handle));
        return handle;
    }

    private void renderPanelDragHandle(UIContext context, UIDraggable handle)
    {
        boolean active = handle.area.isInside(context) || handle.isDragging();
        int color = active ? Colors.WHITE : Colors.setA(Colors.WHITE, 0.6F);
        int cx = handle.area.mx();
        int cy = handle.area.y + handle.area.h / 2 + 4;
        context.batcher.icon(Icons.ALL_DIRECTIONS, color, cx, cy, 0.5F, 0.5F);
    }

    private int computeDropZone(Area area, int mouseX, int mouseY)
    {
        float nx = area.w <= 0 ? 0.5F : (mouseX - area.x) / (float) area.w;
        float ny = area.h <= 0 ? 0.5F : (mouseY - area.y) / (float) area.h;
        if (nx < DROP_EDGE_MARGIN) return EditorLayoutNode.EDGE_LEFT;
        if (nx > 1F - DROP_EDGE_MARGIN) return EditorLayoutNode.EDGE_RIGHT;
        if (ny < DROP_EDGE_MARGIN) return EditorLayoutNode.EDGE_TOP;
        if (ny > 1F - DROP_EDGE_MARGIN) return EditorLayoutNode.EDGE_BOTTOM;
        return DROP_ZONE_CENTER;
    }

    private void renderDropZoneHighlight(UIContext context)
    {
        if (this.layoutLocked || this.draggingPanelId == null || this.dropTargetPanelId == null)
        {
            return;
        }
        UIElement target = this.panelById.get(this.dropTargetPanelId);
        if (target == null)
        {
            return;
        }
        Area a = target.area;
        int border = BBSSettings.primaryColor(Colors.A50);
        int fill = BBSSettings.primaryColor(Colors.A25);

        if (this.dropTargetZone == DROP_ZONE_CENTER)
        {
            context.batcher.box(a.x, a.y, a.ex(), a.ey(), fill);
            int t = 2;
            context.batcher.box(a.x, a.y, a.ex(), a.y + t, border);
            context.batcher.box(a.x, a.ey() - t, a.ex(), a.ey(), border);
            context.batcher.box(a.x, a.y, a.x + t, a.ey(), border);
            context.batcher.box(a.ex() - t, a.y, a.ex(), a.ey(), border);
            return;
        }

        float m = DROP_EDGE_MARGIN;
        switch (this.dropTargetZone)
        {
            case EditorLayoutNode.EDGE_LEFT:
                context.batcher.box(a.x, a.y, a.x + (int) (a.w * m), a.ey(), fill);
                break;
            case EditorLayoutNode.EDGE_RIGHT:
                context.batcher.box(a.ex() - (int) (a.w * m), a.y, a.ex(), a.ey(), fill);
                break;
            case EditorLayoutNode.EDGE_TOP:
                context.batcher.box(a.x, a.y, a.ex(), a.y + (int) (a.h * m), fill);
                break;
            case EditorLayoutNode.EDGE_BOTTOM:
                context.batcher.box(a.x, a.ey() - (int) (a.h * m), a.ex(), a.ey(), fill);
                break;
        }
    }

    private void applyPanelDropResult(String dragId, String targetId, int zone)
    {
        ValueEditorLayout layout = BBSSettings.editorLayoutSettings;
        EditorLayoutNode root = layout.getParticleLayoutRoot();
        EditorLayoutNode newRoot = zone == DROP_ZONE_CENTER
            ? EditorLayoutNode.copyWithInsertStackAt(root, targetId, dragId)
            : EditorLayoutNode.copyWithInsertSplitAt(root, targetId, dragId, zone);

        if (newRoot != null && newRoot != root)
        {
            layout.setParticleLayoutRoot(newRoot);
            this.pendingLayoutUpdate = true;
        }
    }

    private void clearPanelDragState()
    {
        this.draggingPanelId = null;
        this.dropTargetPanelId = null;
        this.dropTargetZone = DROP_ZONE_CENTER;
    }

    /* ===== DockStack Tabs (inner class) ===== */

    private Icon getPanelIcon(String panelId)
    {
        switch (panelId)
        {
            case PANEL_PREVIEW_ID: return Icons.VIDEO_CAMERA;
            case PANEL_FILE_ID: return Icons.PARTICLE_TAB_FILE;
            case PANEL_EMITTER_ID: return Icons.PARTICLE_TAB_EMITTER;
            case PANEL_MOTION_ID: return Icons.PARTICLE_TAB_MOTION;
            case PANEL_APPEARANCE_ID: return Icons.PARTICLE_TAB_APPEARANCE;
            case PANEL_TIME_ID: return Icons.PARTICLE_TAB_TIME;
            case PANEL_EVENTS_ID: return Icons.PARTICLE_TAB_EVENTS;
            case PANEL_CURVES_ID: return Icons.PARTICLE_TAB_CURVES;
            default: return Icons.FILE;
        }
    }

    private IKey getPanelTooltip(String panelId)
    {
        switch (panelId)
        {
            case PANEL_PREVIEW_ID: return UIKeys.PARTICLE_TAB_PREVIEW;
            case PANEL_FILE_ID: return UIKeys.PARTICLE_TAB_FILE;
            case PANEL_EMITTER_ID: return UIKeys.PARTICLE_TAB_EMITTER;
            case PANEL_MOTION_ID: return UIKeys.PARTICLE_TAB_MOTION;
            case PANEL_APPEARANCE_ID: return UIKeys.PARTICLE_TAB_APPEARANCE;
            case PANEL_TIME_ID: return UIKeys.PARTICLE_TAB_TIME;
            case PANEL_EVENTS_ID: return UIKeys.PARTICLE_TAB_EVENTS;
            case PANEL_CURVES_ID: return UIKeys.PARTICLE_TAB_CURVES;
            default: return null;
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

    private class UIDockStackTabs extends UIElement
    {
        private String anchorPanelId;
        private final List<String> panelIds = new ArrayList<>();
        private String activePanelId;

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

        public String getPanelIdAt(int mouseX)
        {
            if (this.panelIds.isEmpty() || this.area.w <= 0)
            {
                return null;
            }
            int tabWidth = this.getTabSize();
            int index = (mouseX - this.area.x) / tabWidth;
            if (index < 0 || index >= this.panelIds.size())
            {
                return null;
            }
            return this.panelIds.get(index);
        }

        @Override
        public boolean subMouseClicked(UIContext context)
        {
            if (!this.isVisible() || context.mouseButton != 0 || !this.area.isInside(context) || this.panelIds.isEmpty())
            {
                return super.subMouseClicked(context);
            }

            String clickedId = this.getPanelIdAt(context.mouseX);
            if (clickedId != null && !clickedId.equals(this.activePanelId))
            {
                this.activateStackTab(clickedId);
            }
            return true;
        }

        private void activateStackTab(String panelId)
        {
            ValueEditorLayout layout = BBSSettings.editorLayoutSettings;
            EditorLayoutNode root = layout.getParticleLayoutRoot();
            EditorLayoutNode next = EditorLayoutNode.copyWithStackActivePanel(root, this.anchorPanelId, panelId);

            if (next != root)
            {
                layout.setParticleLayoutRoot(next);
                UIParticleSchemePanel.this.pendingLayoutUpdate = true;
            }
        }

        @Override
        public void render(UIContext context)
        {
            if (!this.isVisible() || this.panelIds.isEmpty())
            {
                return;
            }

            int tabSize = this.getTabSize();
            int hovered = this.area.isInside(context.mouseX, context.mouseY) ? this.getTabIndex(context.mouseX) : -1;
            int y = this.area.y;
            int ey = this.area.ey();
            IKey hoveredTooltip = null;
            int hoveredX = this.area.x;
            int hoveredEx = this.area.ex();

            this.removeTooltip();
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), Colors.A100);

            for (int i = 0; i < this.panelIds.size(); i++)
            {
                int x = this.area.x + i * tabSize;

                if (x >= this.area.ex())
                {
                    break;
                }

                int ex = Math.min(this.area.ex(), x + tabSize);
                String id = this.panelIds.get(i);
                boolean active = id.equals(this.activePanelId);
                boolean hover = i == hovered;
                Icon icon = UIParticleSchemePanel.this.getPanelIcon(id);
                int iconColor = active ? Colors.WHITE : (hover ? Colors.LIGHTEST_GRAY : Colors.mulRGB(Colors.WHITE, 0.75F));

                if (active)
                {
                    Area.SHARED.set(x, y, ex - x, ey - y);
                    UIDashboardPanels.renderHighlight(context.batcher, Area.SHARED, Direction.BOTTOM);
                }

                context.batcher.icon(icon, iconColor, (x + ex) / 2, (y + ey) / 2, 0.5F, 0.5F);

                if (hover)
                {
                    hoveredTooltip = UIParticleSchemePanel.this.getPanelTooltip(id);
                    hoveredX = x;
                    hoveredEx = ex;
                }
            }

            super.render(context);

            if (hoveredTooltip != null)
            {
                this.tooltip(hoveredTooltip, Direction.BOTTOM);
                context.tooltip.set(context, this);
                context.tooltip.area.set(context.globalX(hoveredX), context.globalY(y), hoveredEx - hoveredX, ey - y);
            }
        }

        private int getTabSize()
        {
            return Math.max(20, this.area.w / Math.max(1, this.panelIds.size()));
        }

        private int getTabIndex(int mouseX)
        {
            if (this.panelIds.isEmpty() || this.area.w <= 0)
            {
                return -1;
            }

            int tabSize = this.getTabSize();
            int index = (mouseX - this.area.x) / tabSize;

            return index >= 0 && index < this.panelIds.size() ? index : -1;
        }
    }

    /* ===== Data management ===== */

    @Override
    protected IKey getTitle()
    {
        return UIKeys.PARTICLE_EDITOR_TITLE;
    }

    @Override
    public ContentType getType()
    {
        return ContentType.PARTICLES;
    }

    @Override
    public Icon getTabIcon(DataTab tab)
    {
        return tab != null && tab.dataId == null ? Icons.SEARCH : Icons.PARTICLE;
    }

    public void dirty()
    {
        if (!this.applyingParticleUndo)
        {
            this.markUndoBoundary();
        }

        ParticleEmitter emitter = this.renderer.emitter;

        if (emitter != null && emitter.scheme != null)
        {
            emitter.scheme.setup();
            emitter.setupVariables();
        }
    }

    @Override
    protected void fillData(ParticleScheme data)
    {
        if (data != null)
        {
            this.particleUndo = new ParticleUndoManager(data);
        }
        else
        {
            this.particleUndo = null;
        }

        this.renderer.setVisible(data != null);
        this.selectionPanel.setVisible(data == null);

        if (this.data != null)
        {
            this.renderer.setScheme(this.data);

            for (UIParticleSchemeSection section : this.sections)
            {
                section.setScheme(this.data);
            }

            for (UIElement panel : this.panelById.values())
            {
                if (panel instanceof UIParticleTabPage)
                {
                    ((UIParticleTabPage) panel).scrollView.resize();
                }
            }
        }
        else
        {
            this.renderer.setScheme(null);
        }

        if (this.dock != null)
        {
            this.dock.setupFlex(true);
        }
    }

    @Override
    public void fillNames(Collection<String> names)
    {
        super.fillNames(names);

        if (this.selectionPanel != null)
        {
            this.selectionPanel.fillNames(names);
        }
    }

    @Override
    public void forceSave()
    {
        super.forceSave();

        ParticleFormRenderer.lastUpdate = System.currentTimeMillis();
    }

    @Override
    public void fillDefaultData(ParticleScheme data)
    {
        super.fillDefaultData(data);

        try (InputStream asset = BBSMod.getProvider().getAsset(PARTICLE_PLACEHOLDER))
        {
            MapType map = DataToString.mapFromString(IOUtils.readText(asset));
            ParticleScheme.PARSER.fromData(data, map);
        }
        catch (Exception e)
        {}
    }

    @Override
    public void appear()
    {
        super.appear();
    }

    @Override
    protected boolean shouldAutoOpenListOnFirstResize()
    {
        return false;
    }

    @Override
    public void close()
    {
        super.close();

        if (this.renderer.emitter != null)
        {
            this.renderer.emitter.particles.clear();
        }
    }

    @Override
    public void resize()
    {
        super.resize();

        if (this.dock != null)
        {
            this.dock.setupFlex(true);
        }

        this.renderer.resize();
    }

    @Override
    protected void renderBackground(UIContext context)
    {
        if (this.iconBar.isVisible())
        {
            this.iconBar.area.render(context.batcher, BBSSettings.chromeSurface());
            context.batcher.gradientHBox(this.iconBar.area.x - 6, this.iconBar.area.y, this.iconBar.area.x, this.iconBar.area.ey(), 0, 0x29000000);
        }
    }

    private void drawOverlay(UIContext context)
    {
        if (this.editor.isVisible() && this.data != null)
        {
            ParticleEmitter emitter = this.renderer.emitter;
            String label = emitter.particles.size() + "P - " + emitter.age + "A";

            UIElement preview = this.panelById.get(PANEL_PREVIEW_ID);
            int y = (preview != null ? preview.area.ey() : this.area.ey()) - 12;

            context.batcher.textShadow(label, this.area.ex() - 4 - context.batcher.getFont().getWidth(label), y);

            /* Draw play/pause button in preview bottom-left */
            if (preview != null)
            {
                Icon icon = emitter.paused ? Icons.PLAY : Icons.PAUSE;
                int bx = preview.area.x + 4;
                int by = preview.area.ey() - 20;
                context.batcher.icon(icon, Colors.WHITE, bx, by, 1F, 1F);
            }
        }
    }

    @Override
    public void render(UIContext context)
    {
        if (this.pendingLayoutUpdate)
        {
            this.pendingLayoutUpdate = false;
            this.dock.refresh();
        }

        int color = BBSSettings.accentColorRGB();
        this.area.render(context.batcher, Colors.mulRGB(color | Colors.A100, 0.2F));

        if (this.editor.isVisible() && this.data != null)
        {
            UIElement preview = this.panelById.get(PANEL_PREVIEW_ID);
            if (preview != null && preview.isVisible()) preview.area.render(context.batcher, Colors.A75);
        }

        super.render(context);

        if (this.particleUndo != null)
        {
            this.particleUndo.trySubmit();
        }
    }

    /* ===== Layout management ===== */

    private void toggleLayoutLock()
    {
        this.dock.toggleLock();
        this.updateLayoutLockTooltip();
    }

    public boolean isLayoutLocked()
    {
        return this.dock == null || this.dock.isLocked();
    }

    private MapType getLayoutPresetData()
    {
        MapType data = new MapType();
        data.put("particle_layout", this.dock.getLayoutRoot().toData());

        for (UIParticleSchemeSection section : this.sections)
        {
            data.putBool(section.getClassId(), UISectionStateManager.isCollapsed(section.getClassId()));
        }

        data.putBool("layoutLocked", this.dock.isLocked());
        return data;
    }

    private void applyLayoutFromPreset(MapType data, int mouseX, int mouseY)
    {
        if (data.has("particle_layout"))
        {
            EditorLayoutNode root = EditorLayoutNode.fromData(data.get("particle_layout"));
            if (root != null)
            {
                this.dock.applyLayoutRoot(root);
            }
        }

        for (UIParticleSchemeSection section : this.sections)
        {
            String id = section.getClassId();
            if (data.has(id))
            {
                boolean collapsed = data.getBool(id);
                UISectionStateManager.setCollapsed(id, collapsed);
                section.applyCollapsedState(collapsed);
            }
        }

        if (data.has("layoutLocked"))
        {
            boolean locked = data.getBool("layoutLocked");

            if (this.dock.isLocked() != locked)
            {
                this.dock.toggleLock();
            }

            this.updateLayoutLockTooltip();
        }

        this.dock.refresh();
    }

    private void resetLayout()
    {
        UISectionStateManager.clearAll();

        for (UIParticleSchemeSection section : this.sections)
        {
            section.applyCollapsedState(false);
        }

        if (!this.dock.isLocked())
        {
            this.dock.toggleLock();
        }

        this.updateLayoutLockTooltip();
        this.dock.resetLayout();
    }

    /* ===== Undo/Redo ===== */

    public void pushUndoSnapshot()
    {
        if (this.data != null && this.particleUndo != null)
        {
            this.particleUndo.pushSnapshot(this.data);
        }
    }

    public void markUndoBoundary()
    {
        if (this.particleUndo != null)
        {
            this.particleUndo.markBoundary();
        }
    }

    public void undo()
    {
        if (this.data != null && this.particleUndo != null)
        {
            MapType previous = this.particleUndo.undo();

            if (previous != null)
            {
                try
                {
                    this.applyingParticleUndo = true;
                    ParticleScheme.PARSER.fromData(this.data, previous);
                    this.data.setup();
                    this.dirty();
                    this.refreshSections();
                    UIUtils.playClick();
                }
                catch (Exception e) {}
                finally
                {
                    this.applyingParticleUndo = false;
                }
            }
        }
    }

    public void redo()
    {
        if (this.data != null && this.particleUndo != null)
        {
            MapType next = this.particleUndo.redo();

            if (next != null)
            {
                try
                {
                    this.applyingParticleUndo = true;
                    ParticleScheme.PARSER.fromData(this.data, next);
                    this.data.setup();
                    this.dirty();
                    this.refreshSections();
                    UIUtils.playClick();
                }
                catch (Exception e) {}
                finally
                {
                    this.applyingParticleUndo = false;
                }
            }
        }
    }

    private void refreshSections()
    {
        for (UIParticleSchemeSection section : this.sections)
        {
            section.setScheme(this.data);
        }
    }

    /* ===== Undo keys overlay ===== */

    private static class UIParticleSchemePanelKeys extends UIElement
    {
        public UIParticleSchemePanelKeys(UIParticleSchemePanel panel)
        {
            this.keys().register(Keys.UNDO, panel::undo).category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
            this.keys().register(Keys.REDO, panel::redo).category(UIKeys.CAMERA_EDITOR_KEYS_EDITOR_TITLE);
            this.keys().register(Keys.PARTICLE_PLAUSE, panel::togglePlause).category(UIKeys.PARTICLE_EDITOR_TITLE);
            this.noCulling();
        }
    }

    /* ===== Plause (play/pause toggle) ===== */

    public void togglePlause()
    {
        ParticleEmitter emitter = this.renderer.emitter;

        if (emitter == null)
        {
            return;
        }

        emitter.paused = !emitter.paused;
    }
}
