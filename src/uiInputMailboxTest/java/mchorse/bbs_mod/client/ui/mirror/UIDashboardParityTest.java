package mchorse.bbs_mod.client.ui.mirror;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.api.client.ui.BBSUiRemoteInputState;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.particles.vanilla.VanillaParticlePreview;
import mchorse.bbs_mod.settings.values.core.ValueRecentData;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanels;
import mchorse.bbs_mod.ui.dashboard.panels.UIEditorDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UICRUDOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.bar.UIPanelActionBar;
import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingScreen;
import mchorse.bbs_mod.ui.dashboard.panels.landing.UILandingRow;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabsHost;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UITabList;
import mchorse.bbs_mod.ui.framework.UIBaseMenu;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.IUIElement;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIPanelBase;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.context.UISimpleContextMenu;
import mchorse.bbs_mod.ui.framework.elements.input.color.UIColorPicker;
import mchorse.bbs_mod.ui.framework.elements.input.drag.AdditiveDrag;
import mchorse.bbs_mod.ui.framework.elements.input.drag.DragContext;
import mchorse.bbs_mod.ui.framework.elements.input.drag.DragStrategyFactory;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TranslateDrag;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIVanillaParticleList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCanvas;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelinePanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UITabStrip;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.ui.utils.GizmoDrag;
import mchorse.bbs_mod.ui.utils.context.ContextAction;
import mchorse.bbs_mod.ui.utils.context.MenuIcon;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.Axis;
import mchorse.bbs_mod.utils.colors.Color;
import org.lwjgl.glfw.GLFW;
import net.minecraft.resources.ResourceLocation;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Executing regressions for the shared FS dashboard, popup and tab contracts. */
public final class UIDashboardParityTest
{
    private UIDashboardParityTest()
    {}

    public static void runAll()
    {
        long inputLease = BBSUiRemoteHeldState.install(0x44415348424F4152L,
            new BBSUiRemoteInputState(0D, 0D, 0, Set.of(), 0));
        Map<Field, Object> settings = new HashMap<>();
        try
        {
            for (Field field : BBSSettings.class.getFields())
            {
                if (BaseValue.class.isAssignableFrom(field.getType())) settings.put(field, field.get(null));
            }
            BBSSettings.register(new SettingsBuilder(null, "dashboard-test", null));
            tabsKeepDocumentIdentityWhenClosing();
            dataPanelsUseTheSharedDocumentOwner();
            documentTabStripRoutesSelectionAndReservesActions();
            panelSwitchersUseOneTabOwnerOnEverySide();
            disabledGlowSkipsTheRealShadowOwner();
            rayFactoryIgnoresTheRetiredHotkeySwitch();
            lifecycleCallbacksRunParentsFirstWhenEnteringAndChildrenFirstWhenLeaving();
            timelineOwnersShareTimeAndRetireExternalInspectors();
            particlePickerUsesStaticSamplesAndClearsReloadCache();
            menusRestoreTheParkedInstanceAndDismissTheChain();
            overlayGripKeepsItsOwnerAndPersistsItsSize();
            enterConfirmsDialogsAfterChildrenHaveTheirChance();
            taskbarSidesMoveLayoutAndHitAreasTogether();
            actionSlotsMeasureTheTabsAndAutoHideLayers();
            colorPresetsPreserveTheCurrentAlpha();
            recentEntriesFilterMissingDocumentsWithoutDeletingHistory();
            landingArtworkKeepsDocumentActionsReachable();
        }
        catch (ReflectiveOperationException exception)
        {
            throw new AssertionError("Could not execute dashboard parity regressions", exception);
        }
        finally
        {
            BBSUiRemoteHeldState.clear(inputLease);
            for (Map.Entry<Field, Object> setting : settings.entrySet())
            {
                try
                {
                    setting.getKey().set(null, setting.getValue());
                }
                catch (IllegalAccessException exception)
                {
                    throw new AssertionError("Could not restore dashboard test settings", exception);
                }
            }
        }

        System.out.println("UIDashboardParityTest: all tests passed");
    }

    private static void timelineOwnersShareTimeAndRetireExternalInspectors()
    {
        UITimelineCanvas canvas = new UITimelineCanvas() {};
        canvas.area.set(12, 0, 200, 80);
        canvas.getXAxis().setShift(4D);
        canvas.getXAxis().setZoom(2D);
        double before = canvas.fromGraphX(52);
        canvas.dragTimeBy(10);
        check(Math.abs(canvas.fromGraphX(52) - (before - 5D)) < 0.00001D,
            "middle-drag did not pan the shared axis in zoom-compensated time");
        int pixel = canvas.toGraphX(16D);
        check(Math.abs(canvas.fromGraphX(pixel) - 16D) < 0.00001D,
            "the shared timeline conversions disagree");

        TimelinePanel owner = new TimelinePanel();
        UIElement host = new UIElement();
        host.w(220).h(160);
        owner.setTarget(host);
        owner.setEmptyState(() -> IKey.raw("hint"));
        owner.mount();
        host.resize();
        check(owner.inspector.getParent() == host && owner.inspector.area.w == 220,
            "inspector was mounted on its hidden timeline instead of its dock target");
        owner.setPropertiesVisible(false);
        check(!owner.inspector.isVisible(), "hiding properties did not reach the docked inspector");
        owner.removeFromParent();
        check(owner.inspector.getParent() == null && host.getChildren().isEmpty(),
            "removing a timeline left its inspector or empty hint in the external dock");
    }

    private static final class TimelinePanel extends UITimelinePanel
    {
        private final UIElement inspector = new UIElement();
        private final UIElement timeline = new UIElement();
        protected UIElement getPropertiesPanel() { return this.inspector; }
        protected UIElement getTimeline() { return this.timeline; }
        private void mount() { this.attachPropertiesPanel(this.inspector, 100); }
    }

    @SuppressWarnings("unchecked")
    private static void particlePickerUsesStaticSamplesAndClearsReloadCache() throws ReflectiveOperationException
    {
        UIListOverlayPanel picker = new UIListOverlayPanel(IKey.EMPTY, null, UIVanillaParticleList::new);
        check(picker.list.list instanceof UIVanillaParticleList,
            "particle picker discarded its specialized static-thumbnail list");

        Method load = VanillaParticlePreview.class.getDeclaredMethod("loadPreview", ResourceLocation.class);
        load.setAccessible(true);
        ResourceLocation particle = ResourceLocation.parse("minecraft:block");
        Optional<?> preview = (Optional<?>) load.invoke(null, particle);
        check(preview.isPresent(), "argument-dependent particles lost their representative sample");
        Method sprite = preview.get().getClass().getDeclaredMethod("sprite");
        sprite.setAccessible(true);
        check(ResourceLocation.parse("minecraft:block/stone").equals(sprite.invoke(preview.get())),
            "block particles no longer use the block atlas sample");

        Map<ResourceLocation, Optional<?>> cache = (Map<ResourceLocation, Optional<?>>) field(VanillaParticlePreview.class, "PREVIEWS").get(null);
        cache.put(particle, preview);
        VanillaParticlePreview.clearCache();
        check(cache.isEmpty(), "resource reload kept particle samples from the previous resource pack");
    }

    private static void tabsKeepDocumentIdentityWhenClosing()
    {
        TabHost host = new TabHost();
        UITabList tabs = new UITabList(host);

        tabs.pick("first");
        tabs.addTab();
        tabs.pick("second");
        tabs.addTab();
        tabs.pick("third");
        tabs.switchTab(1);
        tabs.closeTab(1);

        check(tabs.size() == 2 && "first".equals(tabs.get(0).dataId) && "third".equals(tabs.get(1).dataId),
            "closing the active tab overwrote a surviving document id");
        check("first".equals(host.open) && "first".equals(tabs.getCurrentId()),
            "closing the active tab did not show its previous neighbor");
        tabs.closeTab(0);
        check("third".equals(host.open), "closing the first tab did not open the surviving neighbor");
        tabs.closeTab(0);
        check(tabs.size() == 1 && host.open == null && tabs.getCurrentId() == null,
            "closing the last tab did not return to a reusable empty tab");
        check(host.saved.contains("second") && host.saved.contains("third"),
            "tab transitions did not flush the documents they left");
    }

    private static void menusRestoreTheParkedInstanceAndDismissTheChain() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        UISimpleContextMenu first = simpleMenu(180);
        UISimpleContextMenu child = simpleMenu(2);

        menu.context.setMouse(50, 40, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        menu.context.setContextMenu(first);
        first.filter.setText("entry 1");
        first.actions.filter("entry 1");
        first.actions.scroll.setScroll(20);
        int x = first.area.x;
        int y = first.area.y;

        menu.context.replaceContextMenu(child);
        first.dismiss();
        check(menu.context.contextMenu == child && !first.isVisible() && first.hasParent(),
            "dismissing the action that opened a submenu removed its parked parent");
        check(menu.context.canGoBack() && child.bar.getContentWidth() == 20,
            "a nested menu has no automatic Back icon");
        menu.context.backContextMenu();
        check(menu.context.contextMenu == first && first.isVisible() && !child.hasParent(),
            "Back did not restore the same parent menu instance");
        check(first.area.x == x && first.area.y == y && "entry 1".equals(first.filter.getText())
                && first.actions.isFiltering() && first.actions.scroll.getScroll() == 20,
            "Back lost the parent's position, search or scroll");

        AtomicInteger actions = new AtomicInteger();
        first.actions.getList().get(1).runnable = actions::incrementAndGet;
        menu.context.setMouse(first.area.x + 30, first.area.y + 25, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        first.actions.pick(1);
        menu.context.setMouse(first.area.x + 30, first.area.y + 25, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        first.subMouseReleased(menu.context);
        check(actions.get() == 0, "a right release activated a left-owned menu action");
        menu.context.setMouse(first.area.x + 30, first.area.y + 25, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        first.subMouseReleased(menu.context);
        check(actions.get() == 1 && !menu.context.hasContextMenu(),
            "the owning release did not run and close its menu action");

        UISimpleContextMenu root = simpleMenu(1);
        UISimpleContextMenu middle = simpleMenu(1);
        UISimpleContextMenu leaf = simpleMenu(1);

        menu.context.setContextMenu(root);
        menu.context.replaceContextMenu(middle);
        menu.context.replaceContextMenu(leaf);
        root.removeFromParent();
        check(!menu.context.canGoBack() && !middle.hasParent() && menu.context.contextMenu == leaf,
            "removing a parked ancestor kept an orphaned Back chain");
        menu.context.closeContextMenu();
        menu.context.invalidateContextMenus();
        check(!leaf.hasParent(), "screen teardown kept an exiting menu attached");
    }

    private static void dataPanelsUseTheSharedDocumentOwner()
    {
        DataPanel panel = new DataPanel();
        panel.pickData("folder/first");
        panel.addTab();
        panel.pickData("folder/second");
        panel.addTab();
        panel.pickData("folder/third");
        panel.documentTabs.switchTab(1);
        panel.closeTab(1);

        check(panel.tabs == panel.documentTabs.getEntries()
                && panel.currentTab == panel.documentTabs.getCurrentTab(),
            "the data panel kept a second tab state beside its shared owner");
        check(panel.tabs.size() == 2 && "folder/first".equals(panel.getOpenId())
                && "folder/third".equals(panel.tabs.get(1).dataId),
            "the data adapter lost a surviving document when closing the active tab");
        panel.documentTabs.closeTab(0);
        check(panel.currentTab == 0 && "folder/third".equals(panel.getData().getId()),
            "actions through the shared tab owner left the data adapter on another tab");
        panel.documentTabs.closeTab(0);
        check(panel.tabs.size() == 1 && panel.getData() == null && panel.saved.contains("folder/second"),
            "the data adapter failed to save before closing or clear the final tab");

        panel.pickData("old/folder/item");
        panel.documentTabs.renameFolder("old/folder/", "new/folder/");
        check("new/folder/item".equals(panel.documentTabs.getCurrentId()),
            "a full destination path was appended to the source parent");

        panel.documentTabs.setOpenId("pending");
        panel.editor.setEnabled(false);
        int requests = panel.requests;
        panel.pickData("old/folder/item");
        check(panel.requests == requests + 1 && "old/folder/item".equals(panel.documentTabs.getCurrentId()),
            "returning to the loaded document left another tab's pending request active");
        panel.close();
        check(panel.saved.getLast().equals("old/folder/item-closed"),
            "the data panel saved before the child flushed its final edits");
    }

    private static void lifecycleCallbacksRunParentsFirstWhenEnteringAndChildrenFirstWhenLeaving()
    {
        List<String> calls = new ArrayList<>();
        UIDashboardPanel panel = new LifecycleChild(calls);
        panel.open();
        panel.appear();
        panel.disappear();
        panel.close();
        check(calls.equals(List.of("base.open", "child.open", "base.appear", "child.appear",
                "child.disappear", "base.disappear", "child.close", "base.close")),
            "dashboard lifecycle callbacks lost their parent/child ownership order");
    }

    private static void documentTabStripRoutesSelectionAndReservesActions() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        DataPanel panel = new DataPanel();

        panel.full(menu.main);
        menu.main.add(panel);
        panel.pickData("first");
        panel.addTab();
        panel.pickData("second");
        menu.resize(640, 480);

        check(panel.tabBar.getTabCount() == 2 && panel.tabBar.scroll == panel.tabBar,
            "document tabs do not expose the shared strip through the legacy scroll adapter");
        check(panel.tabBar.area.ex() == panel.actions().area.x,
            "the document strip overlaps the action buttons reserved by its top bar");
        panel.tabBar.select(0);
        check("first".equals(panel.getOpenId()), "the shared tab strip did not select its owner's document");
        panel.tabBar.close(0);
        check("second".equals(panel.getOpenId()) && panel.tabBar.getTabCount() == 1,
            "closing through the shared strip did not rebuild or select the remaining document");
    }

    private static void panelSwitchersUseOneTabOwnerOnEverySide() throws ReflectiveOperationException
    {
        UIPanelBase<UIElement> owner = new UIPanelBase<>();
        UIElement first = new UIElement();
        UIElement second = new UIElement();
        UIIcon firstButton = owner.registerPanel(first, IKey.raw("first"), Icons.ADD);
        UIIcon secondButton = owner.registerPanel(second, IKey.raw("second"), Icons.REMOVE);

        owner.w(240).h(160);
        owner.buttonTabs.select(firstButton);
        check(owner.view == first && first.getParent() == owner,
            "the panel tab did not activate and mount its registered panel");
        owner.buttonTabs.select(1);
        check(owner.view == second && !first.hasParent() && second.getParent() == owner,
            "switching through the shared strip left the previous panel mounted");
        check(owner.buttons == owner.buttonTabs && owner.buttonTabs.getTabCount() == 2
                && owner.getButton(second) == secondButton
                && UIPanelBase.class.getField("buttons").getType() == UIScrollView.class,
            "the panel switcher lost its legacy field descriptor or kept duplicate tab owners");

        Field activeEdge = field(UITabStrip.class, "activeEdge");

        for (Direction direction : Direction.values())
        {
            owner.changeDirection(direction);
            boolean side = direction.isHorizontal();
            boolean far = direction == Direction.RIGHT || direction == Direction.BOTTOM;
            int barX = side && far ? 220 : 0;
            int barY = !side && far ? 140 : 0;
            int panelX = side && !far ? 20 : 0;
            int panelY = !side && !far ? 20 : 0;

            check(owner.buttonTabs.area.x == barX && owner.buttonTabs.area.y == barY
                    && owner.buttonTabs.area.w == (side ? 20 : 240)
                    && owner.buttonTabs.area.h == (side ? 160 : 20),
                "the panel tab strip did not follow its " + direction + " edge");
            check(second.area.x == panelX && second.area.y == panelY
                    && second.area.w == (side ? 220 : 240)
                    && second.area.h == (side ? 160 : 140),
                "the active panel overlaps its " + direction + " tab strip");
            check(owner.buttonTabs.scroll.direction == (side ? ScrollDirection.VERTICAL : ScrollDirection.HORIZONTAL)
                    && activeEdge.get(owner.buttonTabs) == direction.opposite(),
                "the shared panel strip lost its scroll axis or content-facing highlight");
        }

        owner.setPanel(null);
        check(!second.hasParent() && owner.buttonTabs.getTabCount() == 2,
            "clearing the panel destroyed its reusable registered tabs");
    }

    private static void disabledGlowSkipsTheRealShadowOwner()
    {
        boolean previous = BBSSettings.interfaceGlow.get();

        try
        {
            BBSSettings.interfaceGlow.set(false);
            check(!BBSSettings.hasInterfaceGlow() && !BBSSettings.hasOverlayGradientBorder(),
                "canonical glow and the legacy panel-glow API disagree");
            new Batcher2D(null).dropShadow(0, 0, 20, 20, 4, -1, 0);
        }
        finally
        {
            BBSSettings.interfaceGlow.set(previous);
        }
    }

    private static void rayFactoryIgnoresTheRetiredHotkeySwitch()
    {
        boolean previous = BBSSettings.transformHotkeys3dRay.get();
        GizmoDrag snapshot = new GizmoDrag();
        DragContext withRay = dragContext(snapshot);

        try
        {
            BBSSettings.transformHotkeys3dRay.set(false);
            check(DragStrategyFactory.create(withRay, TransformOp.TRANSLATE, Axis.X, null,
                    DragStrategyFactory.Variant.AXIS) instanceof TranslateDrag
                    && DragStrategyFactory.create(withRay, TransformOp.TRANSLATE, Axis.X, null,
                    DragStrategyFactory.Variant.AXIS, true) instanceof TranslateDrag,
                "the retired hidden hotkey setting still disables ray editing");
            check(DragStrategyFactory.create(dragContext(null), TransformOp.TRANSLATE, Axis.X, null,
                    DragStrategyFactory.Variant.AXIS) instanceof AdditiveDrag
                    && DragStrategyFactory.create(withRay, TransformOp.SCALE, Axis.X, null,
                    DragStrategyFactory.Variant.UNIFORM_SCALE) instanceof AdditiveDrag,
                "shared factory lost the no-ray or uniform-scale fallback");
        }
        finally
        {
            BBSSettings.transformHotkeys3dRay.set(previous);
        }
    }

    private static DragContext dragContext(GizmoDrag snapshot)
    {
        return (DragContext) Proxy.newProxyInstance(DragContext.class.getClassLoader(),
            new Class<?>[] {DragContext.class}, (proxy, method, arguments) ->
            {
                if (method.getName().equals("drag")) return snapshot;
                throw new AssertionError("Strategy selection unexpectedly read " + method.getName());
            });
    }

    private static void enterConfirmsDialogsAfterChildrenHaveTheirChance() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        AtomicInteger confirms = new AtomicInteger();
        AtomicInteger fieldCommits = new AtomicInteger();
        UIOverlayPanel panel = new UIOverlayPanel(IKey.EMPTY)
        {
            @Override
            public void confirm()
            {
                confirms.incrementAndGet();
            }
        };
        UITextbox field = new UITextbox((text) -> fieldCommits.incrementAndGet());

        panel.content.add(field);
        UIOverlay.addOverlay(menu.context, panel, 200, 100);
        menu.context.focus(field);
        menu.context.setKeyEvent(GLFW.GLFW_KEY_ENTER, 0, GLFW.GLFW_PRESS);
        panel.keyPressed(menu.context);
        check(confirms.get() == 1, "a focused field prevented an unused Enter from confirming its dialog");

        field.delayedInput();
        panel.keyPressed(menu.context);
        check(confirms.get() == 1 && fieldCommits.get() == 1,
            "the dialog confirmed after its delayed field had already consumed Enter");

        menu.context.setKeyEvent(GLFW.GLFW_KEY_KP_ENTER, 0, GLFW.GLFW_PRESS);
        panel.keyPressed(menu.context);
        check(confirms.get() == 2, "keypad Enter did not confirm the dialog");
        menu.context.setKeyEvent(GLFW.GLFW_KEY_KP_ENTER, 0, GLFW.GLFW_RELEASE);
        panel.keyPressed(menu.context);
        check(confirms.get() == 2, "releasing Enter confirmed the dialog again");
        menu.context.unfocus();
    }

    private static void overlayGripKeepsItsOwnerAndPersistsItsSize() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        SizedPanel panel = new SizedPanel();
        UIOverlay overlay = UIOverlay.addOverlay(menu.context, panel, 200, 100);
        UIDraggable grip = (UIDraggable) field(UIOverlayPanel.class, "grip").get(panel);

        check(grip != null, "resizable overlay did not install its grip");
        menu.context.setMouse(grip.area.mx(), grip.area.my(), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        grip.mouseClicked(menu.context);
        menu.context.setMouse(grip.area.mx(), grip.area.my(), GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        grip.mouseReleased(menu.context);
        check(grip.isDragging(), "a secondary release canceled the overlay's left-owned grip");

        menu.context.setMouse(500, 360, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Method drag = UIOverlayPanel.class.getDeclaredMethod("dragSize", UIContext.class);

        drag.setAccessible(true);
        drag.invoke(panel, menu.context);
        check(panel.wasResized() && panel.area.w == 360 && panel.area.h == 240
                && panel.area.ex() == 500 && panel.area.ey() == 360,
            "center-anchored resizing did not keep the corner under the pointer");
        grip.mouseReleased(menu.context);
        check(!grip.isDragging(), "the owning grip release did not finish resizing");

        Method save = UIOverlay.class.getDeclaredMethod("saveLayout", UIOverlayPanel.class);

        save.setAccessible(true);
        save.invoke(null, panel);
        panel.removeFromParent();
        overlay.removeFromParent();
        SizedPanel reopened = new SizedPanel();

        UIOverlay.addOverlay(menu.context, reopened, 200, 100);
        check(reopened.area.w == 360 && reopened.area.h == 240,
            "reopening a resized overlay lost its remembered dimensions");
        UIMessageOverlayPanel message = new UIMessageOverlayPanel(IKey.EMPTY, IKey.EMPTY);

        check(!message.isResizable() && message.getPreferredWidth() == 280,
            "a self-measuring message dialog incorrectly offers a resize grip");
    }

    private static void taskbarSidesMoveLayoutAndHitAreasTogether() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        UIDashboardPanels panels = new UIDashboardPanels();
        UIDashboardPanel content = new UIDashboardPanel(null);

        panels.full(menu.main);
        menu.main.add(panels);
        panels.registerPanel(content, IKey.EMPTY, Icons.FOLDER);
        panels.setPanel(content);
        panels.open();
        menu.resize(640, 480);

        Method slide = UIDashboardPanels.class.getDeclaredMethod("applyTaskbarSlide", int.class);

        slide.setAccessible(true);

        for (Direction side : UIDashboardPanels.SIDES)
        {
            panels.setSide(side);
            slide.invoke(panels, 0);
            check(panels.area.x == 0 && panels.area.y == 0 && panels.area.w == 640 && panels.area.h == 480,
                "changing the taskbar side moved the dashboard root");
            check(side.isVertical() ? panels.taskBar.area.w == 640 && panels.taskBar.area.h == 20
                    : panels.taskBar.area.w == 20 && panels.taskBar.area.h == 480,
                "taskbar orientation did not update its actual hit area");
            check(panels.panelButtons.scroll.direction == (side.isVertical() ? ScrollDirection.HORIZONTAL : ScrollDirection.VERTICAL),
                "the taskbar scroll axis disagrees with its side");
            check(content.area.w == (side.isVertical() ? 640 : 620)
                    && content.area.h == (side.isVertical() ? 460 : 480),
                "the visible taskbar did not reserve content space on its own edge");
            slide.invoke(panels, 20);
            check(content.area.x == 0 && content.area.y == 0 && content.area.w == 640 && content.area.h == 480,
                "a hidden taskbar retained a content inset or moved the editor outside the screen");
            check(!panels.taskBar.area.isInside(320, 240), "a hidden taskbar still hits the content center");
        }
    }

    private static void actionSlotsMeasureTheTabsAndAutoHideLayers() throws ReflectiveOperationException
    {
        TestMenu menu = menu();
        EditorPanel editor = new EditorPanel();

        editor.full(menu.main);
        menu.main.add(editor);
        editor.actions().common(new UIIcon(Icons.SAVED, null));
        editor.actions().editor(new UIIcon(Icons.POSE, null), () -> true);
        editor.actions().menu(new UIIcon(Icons.GEAR, null));
        menu.resize(640, 480);
        int width = 3 * 18 + UIPanelActionBar.SEPARATOR_WIDTH;

        check(editor.actions().area.w == width && editor.actions().area.ex() == editor.area.ex(),
            "shared action slots do not measure or anchor themselves at the top-right edge");
        editor.setDashboardChromeHiddenAmount(1F);
        editor.resize();
        check(editor.topBar.area.ey() == editor.area.y && editor.editor.area.y == editor.area.y
                && editor.layer.area.y == editor.area.y && editor.layer.area.h == editor.area.h,
            "auto-hide did not update both editor and empty-tab content layers");
    }

    private static void colorPresetsPreserveTheCurrentAlpha()
    {
        AtomicInteger result = new AtomicInteger();
        UIColorPicker picker = new UIColorPicker(result::set)
        {
            @Override
            public void updateField()
            {}
        };

        picker.editAlpha();
        picker.setValue(0x66335577, true);
        picker.applyOpaqueColor(Color.rgb(0xe74c3c));
        check(result.get() == 0x66e74c3c && picker.color.getARGBColor() == 0x66e74c3c,
            "an opaque quick preset erased the alpha of an alpha-editing property");
    }

    private static void recentEntriesFilterMissingDocumentsWithoutDeletingHistory()
    {
        ValueRecentData previous = BBSSettings.recentData;

        try
        {
            BBSSettings.recentData = new ValueRecentData("recent");
            BBSSettings.recentData.touch("film", "missing");
            BBSSettings.recentData.touch("film", "exists");
            check(UILandingScreen.recentEntries("film", Set.of("exists")).size() == 1,
                "the landing page showed a recent document absent from repository names");
            check(BBSSettings.recentData.get("film").size() == 2,
                "filtering stale recents destructively changed the persistent history");
            BBSSettings.recentData.touch("film", "old/folder/item");
            BBSSettings.recentData.renameFolder("film", "old/folder/", "new/folder/");
            check(BBSSettings.recentData.get("film").stream().anyMatch(entry -> entry.id.equals("new/folder/item")),
                "recent history did not follow a folder moved to another parent");
        }
        finally
        {
            BBSSettings.recentData = previous;
        }
    }

    private static void landingArtworkKeepsDocumentActionsReachable() throws ReflectiveOperationException
    {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        UILandingScreen landing = new UILandingScreen("film", IKey.raw("Films"), (id) -> Icons.FILM,
            (id) -> opened.incrementAndGet(), () -> {}, () -> null);
        UIElement card = (UIElement) field(UILandingScreen.class, "card").get(landing);
        UIElement banner = (UIElement) field(UILandingScreen.class, "banner").get(landing);
        UIElement heading = (UIElement) field(UILandingScreen.class, "heading").get(landing);
        UIElement directory = (UIElement) field(UILandingScreen.class, "directory").get(landing);

        for (int height : new int[] {480, 360})
        {
            landing.wh(640, height).resize();
            check(banner.area.w == card.area.w && banner.area.h > 0 && heading.area.y >= banner.area.ey(),
                "landing artwork overlaps the document heading or loses its card width");
            check(landing.recent.area.y >= banner.area.ey() && landing.recent.area.ey() <= card.area.ey(),
                "landing artwork pushes recent documents outside the card");
        }

        landing.creation(IKey.raw("Create"), created::incrementAndGet);
        landing.wh(320, 360).resize();
        UILandingRow create = (UILandingRow) field(UILandingScreen.class, "create").get(landing);
        create.callback.accept(create);
        check(created.get() == 1 && create.area.y >= banner.area.ey()
                && directory.area.ey() <= card.area.ey() && landing.recent.area.w > 0,
            "the compact landing loses its creation action, directory or recent-document column");
        check(opened.get() == 0 && !directory.isEnabled(),
            "constructing or resizing a landing unexpectedly opens a document or enables a missing directory");
    }

    private static UISimpleContextMenu simpleMenu(int count)
    {
        UISimpleContextMenu menu = new UISimpleContextMenu();

        for (int i = 0; i < count; i++)
        {
            menu.actions.add(new ContextAction(Icons.NONE, IKey.raw("entry " + i), () -> {}));
        }

        return menu;
    }

    private static TestMenu menu() throws ReflectiveOperationException
    {
        TestMenu menu = new TestMenu();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");

        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Batcher2D batcher = (Batcher2D) unsafe.allocateInstance(Batcher2D.class);

        batcher.setFont(new FontRenderer()
        {
            @Override
            public int getWidth(String text)
            {
                return text.length() * 6;
            }

            @Override
            public int getHeight()
            {
                return 9;
            }
        });
        menu.context.batcher = batcher;
        menu.resize(640, 480);

        return menu;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException
    {
        Field field = type.getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionError(message);
        }
    }

    private static final class TestMenu extends UIBaseMenu
    {}

    private static final class SizedPanel extends UIOverlayPanel
    {
        private SizedPanel()
        {
            super(IKey.EMPTY);
        }
    }

    private static final class EditorPanel extends UIEditorDashboardPanel
    {
        private final UIElement layer;

        private EditorPanel()
        {
            super(null);
            this.layer = this.layoutUnderTopBar(new UIElement());
            this.add(this.layer);
        }

        @Override
        public IKey getTitle() { return IKey.EMPTY; }
        @Override
        public String getRecentType() { return "test"; }
        @Override
        public void pickData(String id) {}
        @Override
        protected void showTab(String id) {}
        @Override
        public String getOpenId() { return null; }
        @Override
        public void openDataManager() {}
        @Override
        protected String getLandingId() { return null; }

        @Override
        public void requestNames()
        {}
    }

    private static final class TabHost implements IUITabsHost
    {
        private String open;
        private final List<String> saved = new ArrayList<>();

        @Override
        public void openTab(String id)
        {
            this.open = id;
        }

        @Override
        public String getOpenId()
        {
            return this.open;
        }

        @Override
        public void saveOpen()
        {
            this.saved.add(this.open);
        }

        @Override
        public IKey getNewTabLabel()
        {
            return IKey.EMPTY;
        }

        @Override
        public Icon getTabIcon(String id)
        {
            return Icons.NONE;
        }
    }

    private static final class DataPanel extends UIDataDashboardPanel<ValueGroup>
    {
        private final List<String> saved = new ArrayList<>();
        private int requests;

        private DataPanel()
        {
            super(null);
            this.enableTabs();
            this.onClose(() ->
            {
                if (this.data != null) this.data.setId(this.data.getId() + "-closed");
            });
        }

        @Override
        protected UICRUDOverlayPanel createOverlayPanel()
        {
            return new UICRUDOverlayPanel(IKey.EMPTY, null)
            {
                @Override public boolean showActionButtons() { return false; }
                @Override protected void addNewData(String name, MapType data) {}
                @Override protected void addNewFolder(String path) {}
                @Override protected void dupeData(String name) {}
                @Override protected void renameData(String name) {}
                @Override protected void renameFolder(String name) {}
                @Override protected void removeData() {}
                @Override protected void removeFolder() {}
            };
        }

        @Override
        public ContentType getType() { return null; }
        @Override
        public IKey getTitle() { return IKey.EMPTY; }
        @Override
        public void requestNames() {}
        @Override
        public void openDataManager() {}
        @Override
        public void requestData(String id)
        {
            this.requests += 1;
            this.fill(new ValueGroup(id));
        }
        @Override
        public void fill(ValueGroup data)
        {
            this.data = data;
            this.editor.setEnabled(true);
            this.documentTabs.setOpenId(data == null ? null : data.getId());
        }
        @Override
        protected void fillData(ValueGroup data) {}
        @Override
        public void save()
        {
            if (this.data != null) this.saved.add(this.data.getId());
        }
    }

    private static class LifecycleBase extends UIDashboardPanel
    {
        private LifecycleBase(List<String> calls)
        {
            super(null);
            this.onOpen(() -> calls.add("base.open"));
            this.onAppear(() -> calls.add("base.appear"));
            this.onDisappear(() -> calls.add("base.disappear"));
            this.onClose(() -> calls.add("base.close"));
        }
    }

    private static final class LifecycleChild extends LifecycleBase
    {
        private LifecycleChild(List<String> calls)
        {
            super(calls);
            this.onOpen(() -> calls.add("child.open"));
            this.onAppear(() -> calls.add("child.appear"));
            this.onDisappear(() -> calls.add("child.disappear"));
            this.onClose(() -> calls.add("child.close"));
        }
    }
}
