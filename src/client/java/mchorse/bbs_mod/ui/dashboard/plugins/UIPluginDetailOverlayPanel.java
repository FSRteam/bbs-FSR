package mchorse.bbs_mod.ui.dashboard.plugins;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.plugin.BBSPluginState;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.plugin.manager.BBSPluginManager;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.utils.ScrollDirection;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.resources.Pixels;
import org.lwjgl.opengl.GL11;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * Detail view for a single hot plugin. Works in two modes: inspecting an
 * installed plugin (with lifecycle actions) and previewing a candidate JAR
 * before installing/updating it.
 */
public class UIPluginDetailOverlayPanel extends UIOverlayPanel
{
    private final Runnable refreshCallback;
    private final BBSPluginManager.PluginStatus status;
    private final PluginJarInfo jarInfo;
    private final boolean installMode;

    private UIScrollView view;
    private Texture iconTexture;
    private boolean iconAttempted;

    /** Detail view of an installed plugin. */
    public static UIPluginDetailOverlayPanel installed(Runnable refresh, BBSPluginManager.PluginStatus status)
    {
        PluginJarInfo info = PluginInstaller.findInstalled(status.pluginId());

        return new UIPluginDetailOverlayPanel(refresh, status, info, false);
    }

    /** Preview of a candidate JAR that can be installed or updated. */
    public static UIPluginDetailOverlayPanel candidate(Runnable refresh, PluginJarInfo candidate, BBSPluginManager.PluginStatus installedStatus)
    {
        return new UIPluginDetailOverlayPanel(refresh, installedStatus, candidate, true);
    }

    private UIPluginDetailOverlayPanel(Runnable refreshCallback, BBSPluginManager.PluginStatus status, PluginJarInfo jarInfo, boolean installMode)
    {
        super(IKey.raw(displayName(status, jarInfo)));

        this.refreshCallback = refreshCallback;
        this.status = status;
        this.jarInfo = jarInfo;
        this.installMode = installMode;

        this.view = new UIScrollView(ScrollDirection.VERTICAL);
        this.view.relative(this.content).w(1F).h(1F, -28);
        this.view.column(4).scroll().vertical().stretch().padding(8);

        this.buildDetails();
        this.buildActions();

        this.content.add(this.view);
        this.markContainer();
    }

    private static String displayName(BBSPluginManager.PluginStatus status, PluginJarInfo jarInfo)
    {
        if (jarInfo != null)
        {
            return jarInfo.manifest.displayName();
        }

        if (status != null && status.descriptor() != null)
        {
            return status.descriptor().displayName();
        }

        return status == null ? "" : status.pluginId();
    }

    private void buildDetails()
    {
        var descriptor = this.installMode && this.jarInfo != null
            ? this.jarInfo.manifest.descriptor()
            : (this.status == null ? null : this.status.descriptor());

        this.view.add(new UIIconHeader(this));

        if (this.installMode)
        {
            /* Version comparison between the candidate and what is installed. */
            String candidateVersion = this.jarInfo.manifest.version();

            if (this.status == null)
            {
                this.addRow(UIKeys.PLUGINS_DETAIL_INSTALLED_VERSION, UIKeys.PLUGINS_DETAIL_NOT_INSTALLED.get());
            }
            else
            {
                this.addRow(UIKeys.PLUGINS_DETAIL_INSTALLED_VERSION, this.status.version());
            }

            this.addRow(UIKeys.PLUGINS_DETAIL_VERSION, candidateVersion);
        }
        else if (this.status != null)
        {
            this.addRow(UIKeys.PLUGINS_DETAIL_VERSION, this.status.version());
            this.addRow(UIKeys.PLUGINS_DETAIL_STATE, UIKeys.C_PLUGIN_STATE.get(this.status.state()).get());
            this.addRow(UIKeys.PLUGINS_DETAIL_GENERATION, String.valueOf(this.status.generation()));

            if (!this.status.sha256().isEmpty())
            {
                this.addRow(UIKeys.PLUGINS_DETAIL_SHA256, shorten(this.status.sha256()));
            }

            this.addRow(UIKeys.PLUGINS_DETAIL_LAST_CHANGE, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(this.status.lastTransitionMillis())));

            if (isErrorState(this.status.state()) && !this.status.lastMessage().isEmpty())
            {
                UIText error = new UIText(this.status.lastCode() + ": " + this.status.lastMessage());

                error.color(Colors.RED, true);
                this.view.add(label(UIKeys.PLUGINS_DETAIL_LAST_ERROR));
                this.view.add(error);
            }
        }

        if (descriptor != null)
        {
            this.addRow(UIKeys.PLUGINS_DETAIL_KIND, descriptor.kind().name());
            this.addRow(UIKeys.PLUGINS_DETAIL_SIDE, descriptor.side().name());
            this.addRow(UIKeys.PLUGINS_DETAIL_API, descriptor.api());

            if (!descriptor.capabilities().isEmpty())
            {
                this.addRow(UIKeys.PLUGINS_DETAIL_CAPABILITIES, descriptor.capabilities().stream()
                    .map(Enum::name).collect(Collectors.joining(", ")));
            }

            if (!descriptor.dependencies().isEmpty())
            {
                this.addRow(UIKeys.PLUGINS_DETAIL_DEPENDENCIES, String.join(", ", descriptor.dependencies()));
            }
        }

        if (this.jarInfo != null && this.jarInfo.changelog != null && !this.jarInfo.changelog.isBlank())
        {
            this.view.add(label(UIKeys.PLUGINS_DETAIL_CHANGELOG));

            UIText changelog = new UIText(this.jarInfo.changelog.strip());

            changelog.color(Colors.LIGHTEST_GRAY, false);
            this.view.add(changelog);
        }
    }

    private void buildActions()
    {
        UIElement actions = new UIElement();

        actions.relative(this.content).x(6).y(1F, -24).w(1F, -12).h(20).row(4).preferred(0);

        if (this.installMode)
        {
            IKey label;

            if (this.status == null)
            {
                label = UIKeys.PLUGINS_ACTION_INSTALL;
            }
            else
            {
                int comparison = compareVersions(this.jarInfo.manifest.version(), this.status.version());

                label = comparison > 0
                    ? UIKeys.PLUGINS_ACTION_UPDATE.format(this.jarInfo.manifest.version())
                    : UIKeys.PLUGINS_ACTION_REINSTALL;
            }

            UIButton install = new UIButton(label, (b) -> this.install());

            actions.add(install);
        }
        else if (this.status != null)
        {
            String id = this.status.pluginId();
            boolean disabled = this.status.state() == BBSPluginState.DISABLED;

            UIButton reload = new UIButton(UIKeys.PLUGINS_ACTION_RELOAD, (b) -> this.act(() -> BBSMod.reloadPlugin(id)));
            UIButton toggle = disabled
                ? new UIButton(UIKeys.PLUGINS_ACTION_ENABLE, (b) -> this.act(() -> BBSMod.enablePlugin(id)))
                : new UIButton(UIKeys.PLUGINS_ACTION_DISABLE, (b) -> this.act(() -> BBSMod.disablePlugin(id)));
            UIButton uninstall = new UIButton(UIKeys.PLUGINS_ACTION_UNINSTALL, (b) -> this.act(() ->
            {
                try
                {
                    PluginInstaller.uninstall(id);
                }
                catch (Exception ignored)
                {}
            }));

            uninstall.color(Colors.RED);
            actions.add(reload, toggle, uninstall);
        }

        this.content.add(actions);
    }

    private void install()
    {
        try
        {
            PluginInstaller.install(this.jarInfo);
            this.close();

            if (this.refreshCallback != null)
            {
                this.refreshCallback.run();
            }
        }
        catch (Exception error)
        {
            UIText failure = new UIText(UIKeys.PLUGINS_INSTALL_FAILED.get() + ": " + error.getMessage());

            failure.color(Colors.RED, true);
            this.view.add(failure);
            this.view.resize();
        }
    }

    private void act(Runnable action)
    {
        action.run();
        this.close();

        if (this.refreshCallback != null)
        {
            this.refreshCallback.run();
        }
    }

    private void addRow(IKey label, String value)
    {
        this.view.add(new UIDetailRow(label, value == null ? "" : value));
    }

    private static UIText label(IKey key)
    {
        UIText text = new UIText(key);

        text.color(Colors.A50 | 0xffffff, false);

        return text;
    }

    private static boolean isErrorState(BBSPluginState state)
    {
        return state == BBSPluginState.FAILED || state == BBSPluginState.INCOMPATIBLE || state == BBSPluginState.RESTART_REQUIRED;
    }

    private static String shorten(String sha256)
    {
        return sha256.length() > 16 ? sha256.substring(0, 16) + "…" : sha256;
    }

    /** Lenient dotted-numeric comparison; falls back to string comparison per segment. */
    public static int compareVersions(String a, String b)
    {
        String[] left = a.split("[.\\-+]");
        String[] right = b.split("[.\\-+]");
        int length = Math.max(left.length, right.length);

        for (int i = 0; i < length; i++)
        {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int comparison;

            try
            {
                comparison = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
            }
            catch (NumberFormatException e)
            {
                comparison = l.compareTo(r);
            }

            if (comparison != 0)
            {
                return comparison;
            }
        }

        return 0;
    }

    private Texture getIconTexture()
    {
        if (!this.iconAttempted)
        {
            this.iconAttempted = true;

            if (this.jarInfo != null && this.jarInfo.iconBytes != null)
            {
                try
                {
                    Pixels pixels = Pixels.fromPNGStream(new ByteArrayInputStream(this.jarInfo.iconBytes));

                    this.iconTexture = Texture.textureFromPixels(pixels, GL11.GL_LINEAR);
                }
                catch (Throwable ignored)
                {}
            }
        }

        return this.iconTexture;
    }

    @Override
    public void onClose()
    {
        super.onClose();

        if (this.iconTexture != null)
        {
            this.iconTexture.delete();
            this.iconTexture = null;
        }
    }

    /** Icon + identity header row. */
    private static class UIIconHeader extends UIElement
    {
        private final UIPluginDetailOverlayPanel panel;

        public UIIconHeader(UIPluginDetailOverlayPanel panel)
        {
            this.panel = panel;
            this.h(40);
        }

        @Override
        public void render(UIContext context)
        {
            int x = this.area.x;
            Texture icon = this.panel.getIconTexture();

            if (icon != null)
            {
                context.batcher.fullTexturedBox(icon, x, this.area.y, 36, 36);
                x += 42;
            }

            String name = displayName(this.panel.status, this.panel.jarInfo);
            String id = this.panel.jarInfo != null
                ? this.panel.jarInfo.manifest.id()
                : (this.panel.status == null ? "" : this.panel.status.pluginId());

            context.batcher.textShadow(name, x, this.area.y + 8, BBSSettings.textColor());
            context.batcher.textShadow(id, x, this.area.y + 22, Colors.GRAY);

            super.render(context);
        }
    }

    /** Simple "label: value" row rendered on one line. */
    private static class UIDetailRow extends UIElement
    {
        private final IKey label;
        private final String value;

        public UIDetailRow(IKey label, String value)
        {
            this.label = label;
            this.value = value;
            this.h(12);
        }

        @Override
        public void render(UIContext context)
        {
            String prefix = this.label.get();

            context.batcher.textShadow(prefix, this.area.x, this.area.y + 2, Colors.GRAY);
            context.batcher.textShadow(this.value, this.area.x + context.batcher.getFont().getWidth(prefix + " ") + 4, this.area.y + 2, BBSSettings.textColor());

            super.render(context);
        }
    }
}
