package mchorse.bbs_mod.ui.dashboard.plugins;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.plugin.BBSPluginState;
import mchorse.bbs_mod.plugin.manager.BBSPluginManager;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.colors.Colors;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dashboard panel listing FSR hot plugins with live lifecycle state. Clicking
 * a plugin opens the detail overlay; the toolbar exposes rescan, auto-apply,
 * the plugin folder, and JAR installation.
 */
public class UIPluginsPanel extends UIDashboardPanel
{
    private static final int REFRESH_INTERVAL_TICKS = 40;

    public UIPluginList list;
    public UIIcon rescan;
    public UIIcon openFolder;
    public UIIcon install;
    public UIToggle autoApply;

    private int refreshTimer;

    public UIPluginsPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.rescan = new UIIcon(Icons.REFRESH, (b) ->
        {
            BBSMod.rescanPlugins();
            this.refreshTimer = 0;
        });
        this.rescan.tooltip(UIKeys.PLUGINS_RESCAN, Direction.BOTTOM);

        this.openFolder = new UIIcon(Icons.FOLDER, (b) ->
        {
            Path directory = BBSMod.getPluginDirectory();

            if (directory != null)
            {
                try
                {
                    Files.createDirectories(directory);
                }
                catch (Exception ignored)
                {}

                mchorse.bbs_mod.ui.utils.UIUtils.openFolder(directory.toFile());
            }
        });
        this.openFolder.tooltip(UIKeys.PLUGINS_OPEN_FOLDER, Direction.BOTTOM);

        this.install = new UIIcon(Icons.ADD, (b) -> this.browseForPlugin());
        this.install.tooltip(UIKeys.PLUGINS_INSTALL_BROWSE, Direction.BOTTOM);

        this.autoApply = new UIToggle(UIKeys.PLUGINS_AUTO_APPLY, BBSMod.isPluginAutoApply(), (t) -> BBSMod.setPluginAutoApply(t.getValue()));
        this.autoApply.tooltip(UIKeys.PLUGINS_AUTO_APPLY_TOOLTIP, Direction.BOTTOM);

        this.list = new UIPluginList((selected) ->
        {
            if (!selected.isEmpty())
            {
                this.openDetail(selected.get(0));
            }
        });
        this.list.background();

        this.rescan.relative(this).x(10).y(10).wh(20, 20);
        this.openFolder.relative(this).x(32).y(10).wh(20, 20);
        this.install.relative(this).x(54).y(10).wh(20, 20);
        this.autoApply.relative(this).x(84).y(12).w(120).h(16);
        this.list.relative(this).x(10).y(36).w(1F, -20).h(1F, -46);

        this.add(this.rescan, this.openFolder, this.install, this.autoApply, this.list);
        this.markContainer();
    }

    @Override
    public void appear()
    {
        super.appear();

        this.autoApply.setValue(BBSMod.isPluginAutoApply());
        this.refresh();
    }

    @Override
    public void update()
    {
        super.update();

        this.refreshTimer -= 1;

        if (this.refreshTimer <= 0)
        {
            this.refresh();
        }
    }

    public void refresh()
    {
        this.refreshTimer = REFRESH_INTERVAL_TICKS;

        List<BBSPluginManager.PluginStatus> statuses = BBSMod.getPluginDiagnostics();
        BBSPluginManager.PluginStatus current = this.list.getCurrentFirst();

        this.list.clear();
        this.list.add(statuses);

        if (current != null)
        {
            for (int i = 0; i < statuses.size(); i++)
            {
                if (statuses.get(i).pluginId().equals(current.pluginId()))
                {
                    this.list.setIndex(i);

                    break;
                }
            }
        }

        this.list.resize();
    }

    private void openDetail(BBSPluginManager.PluginStatus status)
    {
        if (status == null)
        {
            return;
        }

        UIOverlay.addOverlay(this.getContext(), UIPluginDetailOverlayPanel.installed(this::refresh, status), 300, 320);
    }

    private void browseForPlugin()
    {
        String result = TinyFileDialogs.tinyfd_openFileDialog(UIKeys.PLUGINS_INSTALL_BROWSE.get(), (CharSequence) null, null, null, false);

        if (result == null)
        {
            return;
        }

        PluginJarInfo candidate = PluginJarInfo.read(Paths.get(result));

        if (candidate == null)
        {
            UIOverlay.addOverlay(this.getContext(), new mchorse.bbs_mod.ui.framework.elements.overlay.UIMessageOverlayPanel(
                UIKeys.PLUGINS_INSTALL_BROWSE,
                UIKeys.PLUGINS_INSTALL_INVALID
            ));

            return;
        }

        BBSPluginManager.PluginStatus installed = null;

        for (BBSPluginManager.PluginStatus status : BBSMod.getPluginDiagnostics())
        {
            if (status.pluginId().equals(candidate.manifest.id()))
            {
                installed = status;

                break;
            }
        }

        UIOverlay.addOverlay(this.getContext(), UIPluginDetailOverlayPanel.candidate(this::refresh, candidate, installed), 300, 320);
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        if (this.list.getList().isEmpty())
        {
            Path directory = BBSMod.getPluginDirectory();
            String message = directory == null
                ? UIKeys.PLUGINS_RUNTIME_OFFLINE.get()
                : UIKeys.PLUGINS_EMPTY.format(directory.toString()).get();

            context.batcher.textCard(message,
                this.list.area.mx() - context.batcher.getFont().getWidth(message) / 2,
                this.list.area.my(), Colors.WHITE, Colors.A50);
        }
    }

    public static class UIPluginList extends UIList<BBSPluginManager.PluginStatus>
    {
        public UIPluginList(Consumer<List<BBSPluginManager.PluginStatus>> callback)
        {
            super(callback);

            this.scroll.scrollItemSize = 20;
        }

        @Override
        protected String elementToString(UIContext context, int i, BBSPluginManager.PluginStatus element)
        {
            String name = element.descriptor() != null ? element.descriptor().displayName() : element.pluginId();

            return name + " " + element.version();
        }

        @Override
        protected void renderElementPart(UIContext context, BBSPluginManager.PluginStatus element, int i, int x, int y, boolean hover, boolean selected)
        {
            int color = stateColor(element.state());
            int middle = y + this.scroll.scrollItemSize / 2;

            context.batcher.box(x + 6, middle - 3, x + 12, middle + 3, color);

            String name = element.descriptor() != null ? element.descriptor().displayName() : element.pluginId();
            String version = element.version();
            String state = UIKeys.C_PLUGIN_STATE.get(element.state()).get();
            int fontHeight = context.batcher.getFont().getHeight();

            context.batcher.textShadow(name + (version.isEmpty() ? "" : " §7" + version), x + 18, middle - fontHeight / 2, hover ? BBSSettings.highlightColor() : BBSSettings.textColor());
            context.batcher.textShadow(state, this.area.ex() - 8 - context.batcher.getFont().getWidth(state), middle - fontHeight / 2, color);
        }

        public static int stateColor(BBSPluginState state)
        {
            return switch (state)
            {
                case ACTIVE -> 0xff4caf50;
                case FAILED, INCOMPATIBLE -> 0xffe53935;
                case RESTART_REQUIRED, RELOAD_REJECTED_BUSY -> 0xffff9800;
                case DISABLED, LOGICALLY_UNLOADED -> 0xff9e9e9e;
                case RELOAD_PENDING, STAGED, PREPARING, DISCOVERED, VALIDATED -> 0xffffeb3b;
                case DRAINING, UNLOADING -> 0xff64b5f6;
            };
        }
    }
}
