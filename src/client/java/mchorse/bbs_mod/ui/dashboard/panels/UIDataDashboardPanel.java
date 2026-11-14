package mchorse.bbs_mod.ui.dashboard.panels;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UICRUDOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UIDataOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabs;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Timer;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.repos.IRepository;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public abstract class UIDataDashboardPanel <T extends ValueGroup> extends UICRUDDashboardPanel implements IUITabs
{
    public UIIcon saveIcon;

    public final List<DataTab> tabs = this.documentTabs.getEntries();
    public int currentTab = -1;
    public UIDataTabs tabBar;

    protected T data;

    private boolean openedBefore;
    private boolean tabsEnabled;
    private float dashboardChromeHiddenAmount;

    private Timer savingTimer = new Timer(0);
    private long dataRequestVersion;
    private final RepositorySession<IRepository<T>> repositorySession = new RepositorySession<>(
        () -> (IRepository<T>) this.getType().getRepository()
    );

    public UIDataDashboardPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.onOpen(this::onPanelOpened);
        this.onClose(this::onPanelClosed);

        this.saveIcon = new UIIcon(Icons.SAVED, (b) -> this.save());

        this.actions().common(this.saveIcon);

        /* A separate element is needed to make save keybind a more priority than other keybinds, because
         * the keybinds are processed afterwards. */
        UIElement savePlease = new UIElement().noCulling();

        savePlease.keys().register(Keys.SAVE, this.saveIcon::clickItself).active(() -> this.data != null);
        savePlease.keys().register(Keys.OPEN_NEW_TAB, this::addTab).active(this::areTabsEnabled);
        this.add(savePlease);
    }

    protected final void enableTabs()
    {
        if (this.tabsEnabled)
        {
            return;
        }

        this.tabsEnabled = true;

        this.tabBar = this.topBar.enableTabs(this);
        this.setupTabsLayout();

        this.documentTabs.setBar(this.tabBar);
    }

    private void setupTabsLayout()
    {
        if (!this.tabsEnabled)
        {
            return;
        }

        int tabsHeight = UIDataTabs.TABS_HEIGHT_PX;
        int tabsSlide = Math.round(tabsHeight * this.dashboardChromeHiddenAmount);
        int contentTop = tabsHeight - tabsSlide;

        this.topBar.relative(this).y(-tabsSlide).w(1F).h(tabsHeight);
        this.topBar.setTabsRightInset(this.getTabsRightInsetPx());
        this.editor.relative(this).y(contentTop).w(1F).h(1F, -contentTop);
    }

    @Override
    public void setDashboardChromeHiddenAmount(float amount)
    {
        amount = MathUtils.clamp(amount, 0F, 1F);

        if (this.dashboardChromeHiddenAmount == amount)
        {
            return;
        }

        this.dashboardChromeHiddenAmount = amount;
        super.setDashboardChromeHiddenAmount(amount);
        this.setupTabsLayout();
    }

    @Override
    public boolean isDashboardChromeHovered(int mouseX, int mouseY)
    {
        return this.topBar.area.isInside(mouseX, mouseY);
    }

    protected int getSidebarWidthPx()
    {
        return 20;
    }

    protected int getTabsRightInsetPx()
    {
        return 0;
    }

    public boolean areTabsEnabled()
    {
        return this.tabsEnabled;
    }

    public IKey getNewTabLabel()
    {
        return UIKeys.PANELS_TABS_NEW_TAB;
    }

    public Icon getTabIcon(DataTab tab)
    {
        return tab != null && tab.dataId == null ? Icons.SEARCH : Icons.FOLDER;
    }

    @Override
    protected void onTabsChanged()
    {
        this.currentTab = this.documentTabs.getCurrentTab();
    }

    @Override
    protected void showTab(String id)
    {
        if (id == null)
        {
            this.fill(null);
        }
        else
        {
            this.requestData(id);
        }
    }

    @Override
    public String getOpenId()
    {
        return this.tabsEnabled ? this.documentTabs.getCurrentId() : this.data == null ? null : this.data.getId();
    }

    @Override
    public void saveOpen()
    {
        this.save();
    }

    public DataTab getCurrentDataTab()
    {
        return this.documentTabs.getCurrent();
    }

    public boolean isNewTab(DataTab tab)
    {
        return tab != null && tab.dataId == null;
    }

    /* IUITabs adapters preserve the data panel API while UITabList owns the state. */

    @Override
    public int getTabCount()
    {
        return this.documentTabs.getTabCount();
    }

    @Override
    public int getCurrentTab()
    {
        return this.documentTabs.getCurrentTab();
    }

    @Override
    public IKey getTabLabel(int index)
    {
        return this.documentTabs.getTabLabel(index);
    }

    @Override
    public IKey getTabTooltip(int index)
    {
        return this.documentTabs.getTabTooltip(index);
    }

    @Override
    public Icon getTabIcon(int index)
    {
        return this.getTabIcon(this.documentTabs.get(index));
    }

    @Override
    public boolean isNewTab(int index)
    {
        return this.isNewTab(this.documentTabs.get(index));
    }

    @Override
    public boolean canCloseTab(int index)
    {
        return this.documentTabs.canCloseTab(index);
    }

    @Override
    public void closeOtherTabs(int index)
    {
        if (this.tabsEnabled) this.documentTabs.closeOtherTabs(index);
    }

    @Override
    public void closeTabsLeft(int index)
    {
        if (this.tabsEnabled) this.documentTabs.closeTabsLeft(index);
    }

    @Override
    public void closeTabsRight(int index)
    {
        if (this.tabsEnabled) this.documentTabs.closeTabsRight(index);
    }

    public int findNewTabIndex()
    {
        return this.documentTabs.findEmptyTab();
    }

    public boolean canAddNewTab()
    {
        return this.findNewTabIndex() < 0;
    }

    @Override
    public void addTab()
    {
        if (this.tabsEnabled)
        {
            this.documentTabs.addTab();
        }
        else
        {
            this.openDataManager();
        }
    }

    public void closeTab(DataTab tab)
    {
        if (this.tabsEnabled) this.documentTabs.closeTab(tab);
    }

    @Override
    public void closeTab(int index)
    {
        if (this.tabsEnabled) this.documentTabs.closeTab(index);
    }

    public void closeOtherTabs(DataTab tab)
    {
        this.closeOtherTabs(this.tabs.indexOf(tab));
    }

    public void closeTabsLeft(DataTab tab)
    {
        this.closeTabsLeft(this.tabs.indexOf(tab));
    }

    public void closeTabsRight(DataTab tab)
    {
        this.closeTabsRight(this.tabs.indexOf(tab));
    }

    public void switchTab(DataTab tab)
    {
        if (this.tabsEnabled) this.documentTabs.switchTab(tab);
    }

    @Override
    public void switchTab(int index)
    {
        if (this.tabsEnabled) this.documentTabs.switchTab(index);
    }

    public void onDataRenamed(String from, String to)
    {
        if (!this.tabsEnabled || from == null || to == null || from.equals(to))
        {
            return;
        }

        this.documentTabs.renameId(from, to);

        if (BBSSettings.recentData != null)
        {
            BBSSettings.recentData.rename(this.getType().getId(), from, to);
        }
    }

    public void onDataFolderRenamed(String fromPath, String toPath)
    {
        if (!this.tabsEnabled || fromPath == null || toPath == null || toPath.trim().isEmpty())
        {
            return;
        }

        fromPath = normalizeFolderRenamePath(fromPath);
        toPath = resolveFolderRenamePath(fromPath, toPath);
        this.documentTabs.renameFolder(fromPath, toPath);

        if (BBSSettings.recentData != null)
        {
            BBSSettings.recentData.renameFolder(this.getType().getId(), fromPath, toPath);
        }
    }

    public static String remapIdAfterFolderRename(String id, String fromPath, String toPath)
    {
        if (id == null || fromPath == null || toPath == null || toPath.trim().isEmpty())
        {
            return id;
        }

        fromPath = normalizeFolderRenamePath(fromPath);
        toPath = resolveFolderRenamePath(fromPath, toPath);

        String oldPrefix = fromPath + "/";
        boolean exactFolder = id.equals(fromPath);

        if (!exactFolder && !id.startsWith(oldPrefix))
        {
            return id;
        }

        return exactFolder ? toPath : toPath + "/" + id.substring(oldPrefix.length());
    }

    private static String resolveFolderRenamePath(String fromPath, String toPath)
    {
        toPath = normalizeFolderRenamePath(toPath);

        if (!toPath.contains("/") && fromPath.contains("/"))
        {
            int slash = fromPath.lastIndexOf('/');

            return fromPath.substring(0, slash + 1) + toPath;
        }

        return toPath;
    }

    private static String normalizeFolderRenamePath(String path)
    {
        path = path.trim();

        while (path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }

        return path;
    }

    public void onDataRemoved(String id)
    {
        if (!this.tabsEnabled || id == null)
        {
            return;
        }

        this.documentTabs.forgetId(id);

        if (BBSSettings.recentData != null)
        {
            BBSSettings.recentData.forget(this.getType().getId(), id);
        }

        if (this.data != null && id.equals(this.data.getId()))
        {
            this.fill(null);
        }
    }

    public void onDataFolderRemoved(String path)
    {
        if (!this.tabsEnabled || path == null || path.isEmpty())
        {
            return;
        }

        this.documentTabs.forgetFolder(path);

        if (BBSSettings.recentData != null)
        {
            BBSSettings.recentData.forgetFolder(this.getType().getId(), path);
        }

        String prefix = path.endsWith("/") ? path : path + "/";

        if (this.data != null && this.data.getId() != null && this.data.getId().startsWith(prefix))
        {
            this.fill(null);
        }
    }

    public T getData()
    {
        return this.data;
    }

    /**
     * Get the content type of this panel
     */
    public abstract ContentType getType();

    /**
     * Current repository before a data session begins, or the pinned owner
     * afterward. A remote Film keeps its server repository after handshake
     * teardown, while an idle pre-handshake panel can still refresh to remote.
     */
    public final IRepository<T> getRepository()
    {
        return this.repositorySession.get();
    }

    private IRepository<T> pinRepository()
    {
        return this.repositorySession.pin();
    }

    private IRepository<T> pinRepository(IRepository<T> repository)
    {
        return this.repositorySession.pin(repository);
    }

    @Override
    protected UICRUDOverlayPanel createOverlayPanel()
    {
        return new UIDataOverlayPanel<>(this.getTitle(), this, this::pickData);
    }

    public void openDataManager()
    {
        super.openDataManager();
    }

    @Override
    public void pickData(String id)
    {
        if (this.isCurrentData(id))
        {
            return;
        }

        if (this.tabsEnabled)
        {
            this.documentTabs.pick(id);

            return;
        }

        this.save();
        this.requestData(id);
    }

    @Override
    protected String getLandingId()
    {
        DataTab tab = this.getCurrentDataTab();
        return tab == null ? (this.data == null ? null : this.data.getId()) : tab.dataId;
    }

    @Override
    public String getRecentType()
    {
        return this.getType().getId();
    }

    @Override
    public Icon getTabIcon(String id)
    {
        return this.getTabIcon(new DataTab(id));
    }

    @Override
    public IKey getCreateLabel()
    {
        return this.overlay.canCreate() ? UIKeys.GENERAL_ADD : null;
    }

    @Override
    public void addNewData(UIContext context)
    {
        this.overlay.addNewData(context);
    }

    @Override
    public File getDataFolder()
    {
        return this.getRepository().getFolder();
    }

    @Override
    public void showInList(String id)
    {
        this.overlay.namesList.setCurrentFile(id);
        this.openDataManager();
    }

    private boolean isCurrentData(String id)
    {
        return id != null && this.data != null && id.equals(this.data.getId())
            && this.editor.isEnabled()
            && (!this.tabsEnabled || id.equals(this.documentTabs.getCurrentId()));
    }

    public void requestData(String id)
    {
        DataTab requestTab = this.tabsEnabled ? this.getCurrentDataTab() : null;
        long requestVersion = ++this.dataRequestVersion;

        this.markDataLoading(requestTab, id);
        this.editor.setEnabled(false);

        /* Capture the request owner without pinning an idle/failed load. Once a
         * result is accepted, pin this exact repository before population so a
         * later disconnect cannot retarget its save. */
        IRepository<T> requestRepository = this.getRepository();

        requestRepository.load(id, (data) ->
        {
            if (requestVersion != this.dataRequestVersion)
            {
                return;
            }

            if (this.tabsEnabled && (requestTab == null || this.getCurrentDataTab() != requestTab || !Objects.equals(requestTab.dataId, id)))
            {
                return;
            }

            if (data != null)
            {
                this.pinRepository(requestRepository);
            }

            this.fill((T) data);
        });
    }

    private void markDataLoading(DataTab requestTab, String id)
    {
        if (this.tabsEnabled && requestTab != null)
        {
            requestTab.dataId = id;

            if (this.tabBar != null)
            {
                this.tabBar.sync();
            }
        }
    }

    /* Data population */

    public void fill(T data)
    {
        /* Any explicit population supersedes an in-flight repository response. */
        this.dataRequestVersion += 1;
        this.editor.setEnabled(true);

        if (data != null)
        {
            this.pinRepository();

            if (BBSSettings.recentData != null && data.getId() != null)
            {
                BBSSettings.recentData.touch(this.getType().getId(), data.getId());
            }
        }

        this.data = data;

        if (this.tabsEnabled && this.currentTab >= 0 && this.currentTab < this.tabs.size())
        {
            this.documentTabs.setOpenId(data == null ? null : data.getId());
        }

        this.saveIcon.setEnabled(data != null);
        this.editor.setVisible(data != null);
        this.overlay.dupe.setEnabled(data != null);
        this.overlay.rename.setEnabled(data != null);
        this.overlay.remove.setEnabled(data != null);

        this.fillData(data);
        this.syncLanding();

        if (data != null && data.getId() != null)
        {
            this.overlay.namesList.setCurrentFile(data.getId());
        }

        if (this.tabsEnabled && this.tabBar != null)
        {
            this.tabBar.sync();
        }

        this.savingTimer.mark(BBSSettings.editorPeriodicSave.get() * 1000L);
    }

    protected abstract void fillData(T data);

    public void fillDefaultData(T data)
    {}

    public void fillNames(Collection<String> names)
    {
        super.fillNames(names);
        String value;

        if (this.tabsEnabled)
        {
            DataTab tab = this.getCurrentDataTab();

            value = tab == null ? null : tab.dataId;
        }
        else
        {
            value = this.data == null ? null : this.data.getId();
        }

        if (value == null && this.data != null)
        {
            value = this.data.getId();

            if (this.tabsEnabled)
            {
                DataTab tab = this.getCurrentDataTab();

                if (tab != null && tab.dataId == null)
                {
                    tab.dataId = value;
                }
            }
        }

        this.overlay.namesList.fill(names);

        if (value != null)
        {
            this.overlay.namesList.setCurrentFile(value);
        }
    }

    @Override
    public void resize()
    {
        super.resize();
        this.setupTabsLayout();

        if (!this.openedBefore && this.getContext() != null && this.shouldAutoOpenListOnFirstResize())
        {
            this.openDataManager();

            this.openedBefore = true;
        }
    }

    /** If false, the list overlay is not auto-opened when the panel is first shown. Default true. */
    protected boolean shouldAutoOpenListOnFirstResize()
    {
        return true;
    }

    @Override
    public void requestNames()
    {
        this.getRepository().requestKeys(this::fillNames);
    }

    public void save()
    {
        if (!this.update && this.data != null && this.editor.isEnabled())
        {
            this.forceSave();
        }
    }

    public void forceSave()
    {
        IRepository<T> repository = this.getRepository();

        if (this.data == null || this.data.getId() == null || repository == null)
        {
            return;
        }

        repository.save(this.data.getId(), this.data.toData().asMap());
    }

    private void onPanelOpened()
    {
        int seconds = BBSSettings.editorPeriodicSave.get();

        if (seconds > 0)
        {
            this.savingTimer.mark(seconds * 1000L);
        }
    }

    private void onPanelClosed()
    {
        this.save();
    }

    @Override
    public void render(UIContext context)
    {
        if (this.data == null)
        {
            double ticks = context.getTickTransition() % 15D;
            double factor = Math.abs(ticks / 15D * 2 - 1F);

            int x = this.openOverlay.area.x - 10 + (int) Interpolations.SINE_INOUT.interpolate(-10, 0, factor);
            int y = this.openOverlay.area.my();

            context.batcher.icon(Icons.ARROW_RIGHT, x, y, 0.5F, 0.5F);
        }

        super.render(context);

        if (!this.editor.isEnabled() && this.data != null)
        {
            this.renderLockedArea(context);
        }

        this.checkPeriodicSave(context);
    }

    private void checkPeriodicSave(UIContext context)
    {
        if (this.data == null)
        {
            return;
        }

        int seconds = BBSSettings.editorPeriodicSave.get();

        if (seconds > 0)
        {
            if (this.savingTimer.check() && this.canSave(context))
            {
                this.savingTimer.mark(seconds * 1000L);

                this.save();
                context.notifySuccess(UIKeys.PANELS_SAVED_NOTIFICATION.format(this.data.getId()));
            }
        }
    }

    protected boolean canSave(UIContext context)
    {
        return true;
    }
}
