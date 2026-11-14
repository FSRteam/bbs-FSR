package mchorse.bbs_mod.ui.dashboard.panels.landing;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.loader.LoaderAccessHolder;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.core.ValueRecentData.Entry;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.context.MenuVerb;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.colors.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Empty document tab: entry points and the most recently opened documents. */
public class UILandingScreen extends UIElement
{
    public static final String DISCORD_LINK = "https://discord.gg/66mVb7Ezjj";
    public static final String TUTORIALS_LINK = "https://www.youtube.com/watch?v=yY5uE3PVd5Y&list=PLM5Z4FJ0AVdw";
    public static final String WIKI_LINK = "https://github.com/Wemppy4/bbs-fs/wiki";

    private static final Link[] BANNERS = {
        Link.assets("textures/banners/bg1.png"),
        Link.assets("textures/banners/bg2.png"),
        Link.assets("textures/banners/bg3.png")
    };
    private static final double BANNER_HOLD_SECONDS = 6;
    private static final double BANNER_FADE_SECONDS = 2;
    private static long bannerStarted;

    public final UIRecentDataList recent;
    private final String type;
    private final LandingBackdrop backdrop = new LandingBackdrop();
    private final UIElement card = new UIElement();
    private final UIElement banner = new UIElement();
    private final String bannerVersion = getReleaseVersion();
    private final UILandingRow list;
    private final UILandingRow directory;
    private final UIElement links;
    private final UILabel heading;
    private final UILabel recentHeading;
    private int preferredHeight = 432;
    private UILandingRow create;
    private Set<String> known;
    private Consumer<String> showInList;

    public UILandingScreen(ILandingHost host)
    {
        this(host.getRecentType(), host.getTitle(), host::getTabIcon, host::pickData, host::openDataManager, host::getDataFolder);
        this.showInList = host::showInList;

        if (host.getCreateLabel() != null)
        {
            this.creation(host.getCreateLabel(), () -> host.addNewData(this.getContext()));
        }
        else
        {
            this.list.accent();
        }
    }

    public UILandingScreen(String type, IKey title, Function<String, Icon> icons,
        Consumer<String> open, Runnable manager, Supplier<File> folder)
    {
        this.type = type;
        this.showInList = (id) -> manager.run();
        this.card.relative(this).xy(0.5F, 0.5F).wh(460, this.preferredHeight).anchor(0.5F);
        this.banner.relative(this.card).xy(0, 0).w(1F).h(180);
        this.banner.add(new UIRenderable((context) -> this.renderBanner(context, this.banner.area)));
        this.banner.add(new UIRenderable((context) -> this.renderBannerCaption(context, this.banner.area)));

        this.heading = UI.label(title);
        this.heading.relative(this.card).xy(10, 202).w(140).h(20);
        this.recentHeading = UI.label(UIKeys.PANELS_LANDING_RECENT);
        this.recentHeading.relative(this.card).xy(170, 202).w(280).h(20);

        this.list = new UILandingRow(Icons.MORE, UIKeys.PANELS_LANDING_LIST, (button) -> manager.run());
        this.directory = new UILandingRow(Icons.FOLDER, UIKeys.PANELS_CONTEXT_OPEN, (button) ->
        {
            File path = folder.get();

            if (path != null)
            {
                UIUtils.openFolder(path);
            }
        });
        this.directory.setEnabled(folder.get() != null);

        this.links = communityLinks();
        this.links.relative(this.card).xy(10, 302).w(140).h(110);
        this.list.relative(this.card).xy(10, 230).wh(140, 20);
        this.directory.relative(this.card).xy(10, 254).wh(140, 20);

        this.recent = new UIRecentDataList((entries) -> open.accept(entries.get(0).id), icons);
        this.recent.relative(this.card).xy(170, 230).w(280).h(192);
        this.recent.context((menu) ->
        {
            Entry entry = this.recent.getEntryAtCursor(this.getContext());

            if (entry != null)
            {
                menu.action(icons.apply(entry.id), UIKeys.PANELS_LANDING_OPEN, () -> open.accept(entry.id));
                menu.action(Icons.MORE, UIKeys.PANELS_LANDING_SHOW_IN_MANAGER, () -> this.showInList.accept(entry.id));
                menu.icon(MenuVerb.REMOVE, () ->
                {
                    BBSSettings.recentData.forget(this.type, entry.id);
                    this.refresh();
                }).label(UIKeys.PANELS_LANDING_FORGET);
            }
        });

        this.card.add(this.banner, this.heading, this.recentHeading, this.list, this.directory, this.links, this.recent);
        this.add(this.card);
        this.refresh();
    }

    public UILandingScreen creation(IKey label, Runnable create)
    {
        UILandingRow button = new UILandingRow(Icons.ADD, label, (b) -> create.run()).accent();

        this.create = button;
        button.relative(this.card).xy(10, 230).wh(140, 20);
        this.preferredHeight = 456;
        this.list.y(254);
        this.directory.y(278);
        this.links.y(326);
        this.recent.h(216);
        this.card.add(button);

        return this;
    }

    @Override
    protected void afterResizeApplied()
    {
        int width = Math.min(460, Math.max(0, this.area.w - 16));
        int height = Math.min(this.preferredHeight, Math.max(0, this.area.h - 16));
        int bannerHeight = Math.max(0, Math.min(180, height - (this.create == null ? 240 : 264) - 12));
        int contentY = bannerHeight + 12;
        int left = Math.min(140, width / 3);
        int recentX = left + 30;
        int recentWidth = Math.max(0, width - recentX - 10);

        this.card.wh(width, height);
        this.banner.h(bannerHeight);
        this.heading.y(contentY + 10).w(left);
        this.list.y(contentY + (this.create == null ? 38 : 62)).w(left);
        this.directory.y(contentY + (this.create == null ? 62 : 86)).w(left);
        this.links.y(contentY + (this.create == null ? 110 : 134)).w(left);

        if (this.create != null)
        {
            this.create.y(contentY + 38).w(left);
        }
        this.recentHeading.x(recentX).y(contentY + 10).w(recentWidth);
        this.recent.x(recentX).y(contentY + 38).w(recentWidth).h(Math.max(0, height - contentY - 48));
        super.afterResizeApplied();
    }

    public static String getVersion()
    {
        String version = getReleaseVersion();

        return "BBS FSR" + (version.isEmpty() ? "" : " " + version);
    }

    private static String getReleaseVersion()
    {
        return LoaderAccessHolder.has() ? LoaderAccessHolder.get().getModVersion(BBSMod.MOD_ID).orElse("") : "";
    }

    private void renderBanner(UIContext context, Area area)
    {
        if (area.w <= 0 || area.h <= 0)
        {
            return;
        }

        for (Link link : BANNERS)
        {
            BBSModClient.getTextures().getTexture(link);
        }

        if (bannerStarted == 0)
        {
            bannerStarted = System.nanoTime();
        }

        double duration = BANNER_HOLD_SECONDS + BANNER_FADE_SECONDS;
        double time = (System.nanoTime() - bannerStarted) / 1_000_000_000D;
        double cycle = time % (duration * BANNERS.length);
        int current = (int) (cycle / duration);
        float progress = (float) Math.max(0D, (cycle % duration - BANNER_HOLD_SECONDS) / BANNER_FADE_SECONDS);
        float alpha = progress * progress * (3F - 2F * progress);

        /* Keep the lower image opaque throughout the crossfade. */
        this.renderBannerImage(context, area, BANNERS[current], 1F);

        if (alpha > 0F)
        {
            this.renderBannerImage(context, area, BANNERS[(current + 1) % BANNERS.length], alpha);
        }
    }

    private void renderBannerImage(UIContext context, Area area, Link link, float alpha)
    {
        Texture texture = BBSModClient.getTextures().getTexture(link);
        float textureAspect = (float) texture.width / texture.height;
        float areaAspect = (float) area.w / area.h;
        float width = areaAspect > textureAspect ? texture.width : texture.height * areaAspect;
        float height = areaAspect > textureAspect ? texture.width / areaAspect : texture.height;
        float u = (texture.width - width) * 0.5F;
        float v = (texture.height - height) * 0.5F;

        context.batcher.texturedBox(texture, Colors.setA(Colors.WHITE, alpha), area.x, area.y, area.w, area.h,
            u, v, u + width, v + height, texture.width, texture.height);
    }

    private void renderBannerCaption(UIContext context, Area area)
    {
        FontRenderer font = context.batcher.getFont();
        String brand = "\u00a7lBBS FSR";
        int y = area.ey() - font.getHeight() - 12;
        int brandWidth = font.getWidth(brand);
        int versionWidth = this.bannerVersion.isEmpty() ? 0 : font.getWidth(this.bannerVersion) + 16;

        if (area.w <= 0 || area.h < font.getHeight() + 20)
        {
            return;
        }

        context.batcher.clip(area, context);
        context.batcher.box(area.x, y - 6, area.ex(), area.ey(), BBSSettings.deepSurface());
        context.batcher.box(area.x + 10, y, area.x + 12, y + font.getHeight(), BBSSettings.primaryColor(Colors.A100));
        context.batcher.text(brand, area.x + 19, y, BBSSettings.textColor(), false);

        if (!this.bannerVersion.isEmpty())
        {
            int dividerX = area.x + 26 + brandWidth;

            context.batcher.box(dividerX, y, dividerX + 1, y + font.getHeight(), BBSSettings.dividerColor());
            context.batcher.text(this.bannerVersion, dividerX + 8, y, BBSSettings.mutedTextColor(), false);
        }

        String credit = "render by Kizrum";

        if (area.w > brandWidth + versionWidth + font.getWidth(credit) + 48)
        {
            context.batcher.text(credit, area.ex() - 10 - font.getWidth(credit), y, BBSSettings.mutedTextColor(), false);
        }

        context.batcher.unclip(context);
    }

    public static UIElement communityLinks()
    {
        return UI.column(4,
            new UILandingRow(Icons.DISCORD, IKey.raw("Discord"), (button) -> UIUtils.openWebLink(DISCORD_LINK)),
            new UILandingRow(Icons.PLAY, UIKeys.PANELS_LANDING_TUTORIALS, (button) -> UIUtils.openWebLink(TUTORIALS_LINK)),
            new UILandingRow(Icons.HELP, UIKeys.PANELS_LANDING_WIKI, (button) -> UIUtils.openWebLink(WIKI_LINK)));
    }

    public void fillNames(Collection<String> names)
    {
        this.known = new HashSet<>(names);
        this.refresh();
    }

    public void refresh()
    {
        this.recent.setList(recentEntries(this.type, this.known));
        this.recent.deselect();
    }

    public static List<Entry> recentEntries(String type, Set<String> known)
    {
        List<Entry> entries = new ArrayList<>();

        if (BBSSettings.recentData != null)
        {
            for (Entry entry : BBSSettings.recentData.get(type))
            {
                if (known == null || known.contains(entry.id))
                {
                    entries.add(entry);
                }
            }
        }

        return entries;
    }

    @Override
    public void setVisible(boolean visible)
    {
        super.setVisible(visible);

        if (visible && this.recent != null)
        {
            this.refresh();
        }
    }

    @Override
    public void render(UIContext context)
    {
        this.backdrop.render(context, this.area);
        this.card.area.render(context.batcher, BBSSettings.raisedSurface());

        if (this.recent.getList().isEmpty())
        {
            context.batcher.text(UIKeys.PANELS_LANDING_RECENT_EMPTY.get(), this.recent.area.x + 4,
                this.recent.area.y + 6, BBSSettings.mutedTextColor(), false);
        }

        super.render(context);
    }
}
