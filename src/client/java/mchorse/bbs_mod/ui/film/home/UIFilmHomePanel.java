package mchorse.bbs_mod.ui.film.home;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.network.ClientNetwork;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.dashboard.list.UIDataPathList;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.grid.UICardGrid;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.framework.elements.input.UISliderTrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIPromptOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.keys.KeyCombo;
import mchorse.bbs_mod.utils.DataPath;
import mchorse.bbs_mod.utils.NaturalOrderComparator;
import mchorse.bbs_mod.utils.RelativeTime;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.resources.Link;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Film home start page: full-width banner, toolbar (search/sort/view/breadcrumb/CRUD)
 * and the film collection presented either as a thumbnail card grid (default) or
 * the classic data path list. Replaces {@link mchorse.bbs_mod.ui.film.UIFilmSelectionPanel}
 * as the presentation layer shown by {@link mchorse.bbs_mod.ui.film.UIFilmPanel}
 * while no film is open.
 *
 * <p>The {@link UIDataPathList} inside the list view stays the single source of
 * truth for the folder hierarchy and current path; the grid is a projection of
 * it enriched with metadata from {@link ClientNetwork#requestFilmMeta}.
 */
public class UIFilmHomePanel extends UIElement
{
    private static final int BANNER_H = 150;
    private static final int BAR_H = 28;
    private static final int CONTENT_Y = BANNER_H + BAR_H + 6;
    private static final int CARD_H = 144;
    private static final int CARD_W = 220;
    private static final int THUMB_H = 88;

    private static final Link BANNER = Link.assets("textures/banners/bg.png");

    /** Muted color pairs for procedural thumbnails, indexed by film id hash (content coding, not semantic). */
    static final int[][] THUMB_COLORS = {
        {0xff31415c, 0xff46587a}, {0xff46345c, 0xff5d4878}, {0xff2f4a44, 0xff40625a},
        {0xff553a34, 0xff6e4c42}, {0xff4c4230, 0xff635640}, {0xff37474f, 0xff4a5e68},
        {0xff4a3550, 0xff604768}, {0xff3d4a2f, 0xff52603f}
    };

    private enum SortMode
    {
        UPDATED, CREATED, NAME, DURATION
    }

    /** One renderable entry of the grid: a folder, the ".." parent hop or a film file. */
    public static class FilmCard
    {
        public DataPath path;
        public boolean folder;
        public boolean parent;
        public String name = "";
        public Instant updated;
        public Instant created;
        public int duration;
        public String renderedTitle = "";
        public int renderedTitleWidth = -1;
    }

    private final UIFilmPanel panel;

    /* Banner */
    private final UILabel stats;
    private final UIElement bar;

    /* Content sections */
    private final UINewsStrip newsStrip = new UINewsStrip();
    private final UIAdBoard board = new UIAdBoard();

    /* Toolbar */
    private final UITextbox search;
    private final UIButton sortButton;
    private final UIIcon gridView;
    private final UIIcon listView;
    private final UILabel breadcrumb;
    private final UIIcon add;
    private final UIIcon dupe;
    private final UIIcon rename;
    private final UIIcon remove;

    /* Content */
    private final UICardGrid<FilmCard> grid;
    private final UISearchList<DataPath> names;
    private final UIDataPathList namesList;

    private boolean gridMode = true;
    private SortMode sortMode = SortMode.UPDATED;
    private float cardScale = 1F;
    private final Map<String, MapType> metaById = new HashMap<>();
    private int metaGeneration;
    private String filter = "";

    public UIFilmHomePanel(UIFilmPanel panel)
    {
        this.panel = panel;

        FilmThumbnails.setPanel(panel);

        /* Banner */
        UIElement banner = new UIElement();

        banner.relative(this).xy(0, 0).w(1F).h(BANNER_H);

        UILabel title = UI.label(UIKeys.FILM_TITLE);

        title.relative(banner).xy(12, BANNER_H - 58).w(1F, -160).h(16);

        this.stats = UI.label(L10n.lang("bbs.ui.film.home.count").format(0)).color(BBSSettings.mutedTextColor());

        this.stats.relative(banner).xy(12, BANNER_H - 38).w(1F, -160).h(12);

        banner.add(new UIRenderable((ctx) -> this.renderBanner(ctx, banner.area)), title, this.stats);

        /* Toolbar */
        this.bar = new UIElement();

        this.bar.relative(this).xy(0, BANNER_H).w(1F).h(BAR_H);

        this.search = new UITextbox((t) ->
        {
            this.filter = t.toLowerCase();
            this.syncCards();
        });
        this.search.placeholder(L10n.lang("bbs.ui.film.home.search"));
        this.search.icon(Icons.SEARCH);
        this.search.delayedInput();
        this.search.relative(bar).xy(8, 4).wh(180, 20);

        this.sortButton = new UIButton(this.getSortLabel(), (b) -> this.cycleSort());
        this.sortButton.relative(bar).x(1F, -296).y(4).wh(140, 20);

        this.gridView = new UIIcon(Icons.LAYOUT, (b) -> this.setView(true));
        this.gridView.wh(20, 20);
        this.listView = new UIIcon(Icons.LIST, (b) -> this.setView(false));
        this.listView.wh(20, 20);

        this.breadcrumb = UI.label(L10n.lang("bbs.ui.film.home.root")).color(BBSSettings.mutedTextColor());

        this.add = new UIIcon(Icons.ADD, (b) -> this.addData());
        this.add.wh(20, 20);
        this.add.context((menu) -> menu.action(Icons.FOLDER, UIKeys.PANELS_MODALS_ADD_FOLDER_TITLE, this::addNewFolder));

        this.dupe = new UIIcon(Icons.DUPE, (b) -> this.dupeSelected());
        this.rename = new UIIcon(Icons.EDIT, (b) -> this.renameSelected());
        this.remove = new UIIcon(Icons.REMOVE, (b) -> this.removeSelected());

        this.gridView.relative(bar).x(1F, -128).y(4);
        this.listView.relative(bar).x(1F, -152).y(4);
        this.breadcrumb.relative(bar).xy(196, 9).w(1F, -606).h(14);
        this.add.relative(bar).x(1F, -104).y(4);
        this.dupe.relative(bar).x(1F, -80).y(4);
        this.rename.relative(bar).x(1F, -56).y(4);
        this.remove.relative(bar).x(1F, -32).y(4);

        bar.add(this.search, this.sortButton, this.gridView, this.listView, this.breadcrumb, this.add, this.dupe, this.rename, this.remove);

        /* Content: grid */
        this.grid = new UICardGrid<>(this::activateCard, new FilmCardRenderer());
        this.grid.multi();
        this.grid.relative(this).xy(8, CONTENT_Y).w(1F, -16).h(1F, -CONTENT_Y - 8);
        this.grid.context((menu) ->
        {
            menu.action(Icons.ADD, UIKeys.GENERAL_ADD, this::addData);
            menu.action(Icons.FOLDER, UIKeys.PANELS_MODALS_ADD_FOLDER_TITLE, this::addNewFolder);
            menu.action(Icons.EDIT, UIKeys.GENERAL_RENAME, this::renameSelected);
            menu.action(Icons.DUPE, UIKeys.GENERAL_DUPE, this::dupeSelected);
            menu.action(Icons.REMOVE, UIKeys.GENERAL_REMOVE, this::removeSelected);

            if (this.getSelectedFiles().size() == 1)
            {
                menu.action(Icons.COPY, UIKeys.PANELS_CONTEXT_COPY, this::copySelected);
                menu.action(Icons.CAMERA, L10n.lang("bbs.ui.film.home.refresh_cover"), this::refreshCover);
            }
        });
        this.grid.keys().register(Keys.DELETE, this::removeSelected).active(this::canUseGridKeys);
        this.grid.keys().register(new KeyCombo(UIKeys.GENERAL_RENAME, GLFW.GLFW_KEY_F2), this::renameSelected).active(this::canUseGridKeys);
        this.grid.keys().register(new KeyCombo(UIKeys.PANELS_CONTEXT_OPEN, GLFW.GLFW_KEY_ENTER), this.grid::activateSelection).active(this::canUseGridKeys);
        this.grid.keys().register(new KeyCombo(UIKeys.KEYFRAMES_CONTEXT_SELECT_ALL, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT_CONTROL), this.grid::selectAll).active(this::canUseGridKeys);

        /* Card size slider (right cluster, left of the sort button) */
        UISliderTrackpad sizeSlider = new UISliderTrackpad((v) ->
        {
            this.cardScale = (float) v.doubleValue();
            this.grid.resize();
        });

        sizeSlider.limit(0.6D, 1.6D).increment(0.05D);
        sizeSlider.setValue(1F);
        sizeSlider.relative(this.bar).x(1F, -394).y(4).wh(90, 20);
        sizeSlider.tooltip(L10n.lang("bbs.ui.film.home.card_size"));
        this.bar.add(sizeSlider);

        /* Content: list (secondary view) */
        this.names = new UISearchList<>(new UIDataPathList((list) -> this.panel.pickData(list.get(0).toString())));
        this.names.full(this.grid);
        this.namesList = (UIDataPathList) this.names.list;
        this.namesList.multi();
        this.namesList.openOnSingleClick(false);
        this.namesList.setFileIcon(Icons.FILM);
        this.namesList.context((menu) ->
        {
            menu.action(Icons.ADD, UIKeys.GENERAL_ADD, this::addData);
            menu.action(Icons.FOLDER, UIKeys.PANELS_MODALS_ADD_FOLDER_TITLE, this::addNewFolder);
            menu.action(Icons.EDIT, UIKeys.GENERAL_RENAME, this::renameSelected);
            menu.action(Icons.DUPE, UIKeys.GENERAL_DUPE, this::dupeSelected);
            menu.action(Icons.REMOVE, UIKeys.GENERAL_REMOVE, this::removeSelected);

            if (this.getSelectedFiles().size() == 1)
            {
                menu.action(Icons.COPY, UIKeys.PANELS_CONTEXT_COPY, this::copySelected);
            }
        });

        this.newsStrip.setVisible(false);
        this.board.setVisible(false);

        this.add(new UIRenderable((ctx) -> this.renderEmptyState(ctx)), banner, bar, this.grid, this.names, this.newsStrip, this.board);

        this.setView(true);
        this.relayout();
        this.updateActionButtons();
    }

    /**
     * Repositions every section below the banner. The news strip collapses
     * when empty; the commission board takes a ~30% column next to the film
     * area when it has content.
     */
    private void relayout()
    {
        int y = BANNER_H;

        if (this.newsStrip.isVisible())
        {
            int stripH = this.newsStrip.getPreferredHeight();

            this.newsStrip.relative(this).xy(0, y).w(1F).h(stripH);
            y += stripH;
        }

        this.bar.relative(this).xy(0, y).w(1F).h(BAR_H);

        int contentY = y + BAR_H + 6;
        boolean hasBoard = this.board.isVisible();

        /* The ad column stays narrow: slides keep their 4:3 ratio without
         * cropping and the board doesn't crowd the film grid. */
        int boardW = Math.max(170, Math.min(210, this.area.w * 22 / 100));

        if (hasBoard)
        {
            this.board.relative(this).xy(8, contentY).w(boardW).h(1F, -contentY - 8);
        }

        int left = 8 + (hasBoard ? boardW + 10 : 0);

        this.grid.relative(this).xy(left, contentY).w(1F, -left - 8).h(1F, -contentY - 8);
    }

    /** Reloads editorial content from config/jar and shows or hides the sections. */
    private void applyContent()
    {
        FilmHomeContent.INSTANCE.load();
        this.newsStrip.fill(FilmHomeContent.INSTANCE.news);
        this.board.fill(FilmHomeContent.INSTANCE.ads);
        this.newsStrip.setVisible(!FilmHomeContent.INSTANCE.news.isEmpty());
        /* The rent slot keeps the ad board present even without paid ads. */
        this.board.setVisible(true);
        this.relayout();
    }

    @Override
    public void resize()
    {
        super.resize();

        this.relayout();
    }

    /* View switching & sorting */

    private void setView(boolean grid)
    {
        this.gridMode = grid;

        this.grid.setVisible(grid);
        this.names.setVisible(!grid);
        this.search.setVisible(grid);
        this.gridView.setEnabled(!grid);
        this.listView.setEnabled(grid);

        if (!grid && !this.filter.isEmpty())
        {
            this.names.filter(this.filter, true);
        }
    }

    private mchorse.bbs_mod.l10n.keys.IKey getSortLabel()
    {
        switch (this.sortMode)
        {
            case CREATED: return L10n.lang("bbs.ui.film.home.sort_created");
            case NAME: return L10n.lang("bbs.ui.film.home.sort_name");
            case DURATION: return L10n.lang("bbs.ui.film.home.sort_duration");
            default: return L10n.lang("bbs.ui.film.home.sort_updated");
        }
    }

    private void cycleSort()
    {
        SortMode[] modes = SortMode.values();

        this.sortMode = modes[(this.sortMode.ordinal() + 1) % modes.length];
        this.sortButton.label = this.getSortLabel();
        this.syncCards();
    }

    /* Data flow */

    public void fillNames(Collection<String> names)
    {
        this.namesList.fill(names);
        this.syncCards();
        this.requestMeta();
    }

    /**
     * One-shot batched metadata fetch; the generation counter drops stale
     * responses arriving after a newer refill.
     */
    private void requestMeta()
    {
        final int generation = ++this.metaGeneration;

        ClientNetwork.requestFilmMeta((list) ->
        {
            if (generation != this.metaGeneration)
            {
                return;
            }

            this.metaById.clear();

            for (MapType meta : list)
            {
                String id = meta.getString("id");

                if (!id.isEmpty())
                {
                    this.metaById.put(id, meta);
                }
            }

            this.syncCards();
        });
    }

    /** Project the currently visible data paths into sorted/filtered grid cards. */
    private void syncCards()
    {
        List<FilmCard> cards = new ArrayList<>();
        List<DataPath> visible = this.namesList.getList();
        int files = 0;
        Instant latest = null;

        for (DataPath path : visible)
        {
            FilmCard card = new FilmCard();

            card.path = path;
            card.folder = path.folder;
            card.parent = path.folder && path.getLast().equals("..");
            card.name = card.parent ? ".." : path.getLast();

            if (!card.folder)
            {
                MapType meta = this.metaById.get(path.toString());

                if (meta != null)
                {
                    card.updated = Film.parseTimestamp(meta.getString("updated_at"));
                    card.created = Film.parseTimestamp(meta.getString("created_at"));

                    if (card.updated == null)
                    {
                        card.updated = card.created;
                    }

                    card.duration = meta.getInt("duration");
                }

                files += 1;

                if (card.updated != null && (latest == null || card.updated.isAfter(latest)))
                {
                    latest = card.updated;
                }
            }

            if (this.filter.isEmpty() || card.name.toLowerCase().contains(this.filter))
            {
                cards.add(card);
            }
        }

        this.sortCards(cards);
        this.grid.fill(cards);
        this.updateStats(files, latest);
        this.updateBreadcrumb();
        this.updateActionButtons();
    }

    private void sortCards(List<FilmCard> cards)
    {
        Comparator<FilmCard> base = (a, b) ->
        {
            if (a.parent != b.parent) return a.parent ? -1 : 1;
            if (a.folder != b.folder) return a.folder ? -1 : 1;

            return 0;
        };
        Comparator<FilmCard> tail;

        switch (this.sortMode)
        {
            case CREATED:
                tail = Comparator.comparing((FilmCard c) -> c.created == null ? Instant.EPOCH : c.created).reversed();
                break;
            case NAME:
                tail = (a, b) -> NaturalOrderComparator.compare(true, a.name, b.name);
                break;
            case DURATION:
                tail = Comparator.comparingInt((FilmCard c) -> c.duration).reversed();
                break;
            default:
                tail = Comparator.comparing((FilmCard c) -> c.updated == null ? Instant.EPOCH : c.updated).reversed();
                break;
        }

        cards.sort(base.thenComparing(tail).thenComparing((a, b) -> NaturalOrderComparator.compare(true, a.name, b.name)));
    }

    private void updateStats(int files, Instant latest)
    {
        String stats = L10n.lang("bbs.ui.film.home.count").format(files).get();

        if (latest != null)
        {
            RelativeTime.Result result = RelativeTime.describe(latest);

            if (result != null)
            {
                stats += " · " + L10n.lang("bbs.ui.film.home.modified").format(this.formatRelative(result)).get();
            }
        }

        this.stats.label = IKey.raw(stats);
    }

    private String formatRelative(RelativeTime.Result result)
    {
        switch (result.code)
        {
            case NOW: return L10n.lang("bbs.ui.film.home.relative_now").get();
            case MINUTES: return L10n.lang("bbs.ui.film.home.relative_minutes").format(result.amount).get();
            case HOURS: return L10n.lang("bbs.ui.film.home.relative_hours").format(result.amount).get();
            case YESTERDAY: return L10n.lang("bbs.ui.film.home.relative_yesterday").get();
            case DAYS: return L10n.lang("bbs.ui.film.home.relative_days").format(result.amount).get();
            default: return Film.formatCreatedAtForDisplay(result.instant.toString());
        }
    }

    private void updateBreadcrumb()
    {
        DataPath path = this.namesList.getPath();

        if (path.strings.isEmpty())
        {
            this.breadcrumb.label = IKey.raw(L10n.lang("bbs.ui.film.home.root").get());

            return;
        }

        StringBuilder builder = new StringBuilder(L10n.lang("bbs.ui.film.home.root").get());

        for (String segment : path.strings)
        {
            builder.append(" / ").append(segment);
        }

        this.breadcrumb.label = IKey.raw(builder.toString());
    }

    /* Activation */

    private void activateCard(List<FilmCard> selected)
    {
        if (selected.isEmpty())
        {
            return;
        }

        FilmCard card = selected.get(0);

        if (card.folder)
        {
            DataPath target = card.path;

            this.namesList.setCurrent(target);
            this.namesList.activateSelection();
            this.syncCards();
        }
        else
        {
            this.panel.pickData(card.path.toString());
        }
    }

    /* Selection helpers */

    private boolean canUseGridKeys()
    {
        UIContext context = this.getContext();

        if (!this.isVisible() || context == null)
        {
            return false;
        }

        return this.grid.area.isInside(context) || this.grid.isSelected();
    }

    private List<DataPath> getSelectedPaths()
    {
        List<DataPath> paths = new ArrayList<>();

        if (this.gridMode)
        {
            for (FilmCard card : this.grid.getCurrent())
            {
                paths.add(card.path);
            }
        }
        else
        {
            paths.addAll(this.namesList.getCurrent());
        }

        return paths;
    }

    private List<DataPath> getSelectedFiles()
    {
        List<DataPath> files = new ArrayList<>();

        for (DataPath path : this.getSelectedPaths())
        {
            if (path != null && !path.folder)
            {
                files.add(path);
            }
        }

        return files;
    }

    private void updateActionButtons()
    {
        List<DataPath> selected = this.getSelectedPaths();
        boolean single = selected.size() == 1;

        this.dupe.setEnabled(!this.getSelectedFiles().isEmpty());
        this.rename.setEnabled(single);
        this.remove.setEnabled(!selected.isEmpty());
    }

    /* CRUD (mirrors UISelectionScreen, operating on the shared hierarchy) */

    private void addNewFilm()
    {
        this.addData();
    }

    private void addData()
    {
        if (Window.isShiftPressed())
        {
            this.addNewData(this.getNextAutoId(), null);
        }
        else
        {
            this.addNewData(null);
        }
    }

    private String getNextAutoId()
    {
        int i = 1;

        while (true)
        {
            DataPath copy = this.namesList.getPath().copy();

            copy.combine(new DataPath(String.valueOf(i)));

            if (!this.namesList.hasInHierarchy(copy))
            {
                return copy.toString();
            }

            i += 1;

            if (i >= 10000)
            {
                DataPath last = this.namesList.getPath().copy();

                last.combine(new DataPath("afk"));

                return last.toString();
            }
        }
    }

    private void addNewData(MapType data)
    {
        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            UIKeys.GENERAL_ADD,
            UIKeys.PANELS_MODALS_ADD,
            (str) -> this.addNewData(this.namesList.getPath(str).toString(), data)
        );

        panel.text.filename();

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void addNewData(String name, MapType mapType)
    {
        if (name.trim().isEmpty())
        {
            this.getContext().notifyError(UIKeys.PANELS_MODALS_EMPTY);

            return;
        }

        if (this.namesList.hasInHierarchy(name))
        {
            return;
        }

        this.panel.save();

        Film data;

        if (mapType == null)
        {
            data = new Film();

            data.setId(name);
            data.stampCreationTimeNow();
            data.stampUpdatedTimeNow();
            this.panel.fillDefaultData(data);
        }
        else
        {
            data = (Film) this.panel.getRepository().create(name, mapType);
        }

        this.panel.fill(data);
        this.panel.save();
        this.panel.requestNames();
    }

    private void addNewFolder()
    {
        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            UIKeys.PANELS_MODALS_ADD_FOLDER_TITLE,
            UIKeys.PANELS_MODALS_ADD_FOLDER,
            (str) -> this.addNewFolder(this.namesList.getPath(str).toString())
        );

        panel.text.filename();

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void addNewFolder(String path)
    {
        if (path.trim().isEmpty())
        {
            this.getContext().notifyError(UIKeys.PANELS_MODALS_EMPTY);

            return;
        }

        this.panel.getRepository().addFolder(path, (bool) ->
        {
            if (bool)
            {
                this.panel.requestNames();
            }
        });
    }

    private void copySelected()
    {
        List<DataPath> files = this.getSelectedFiles();

        if (files.size() != 1)
        {
            return;
        }

        String id = files.get(0).toString();

        this.panel.getRepository().load(id, (data) ->
        {
            if (data != null)
            {
                Window.setClipboard(data.toData().asMap(), "_ContentType_" + this.panel.getType().getId());
            }
        });
    }

    /** Drops the selected film's cover; re-captures immediately when it's open, otherwise on next open/save. */
    private void refreshCover()
    {
        List<DataPath> files = this.getSelectedFiles();

        if (files.size() != 1)
        {
            return;
        }

        String id = files.get(0).toString();

        FilmThumbnails.invalidate(id);

        if (this.panel.getData() != null && id.equals(this.panel.getData().getId()))
        {
            FilmThumbnails.requestCapture(id, 10);
        }
        else
        {
            this.getContext().notifyInfo(L10n.lang("bbs.ui.film.home.refresh_scheduled"));
        }
    }

    private void dupeSelected()
    {
        List<DataPath> files = this.getSelectedFiles();

        if (files.isEmpty())
        {
            return;
        }

        if (files.size() == 1)
        {
            DataPath first = files.get(0);

            UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
                UIKeys.GENERAL_DUPE,
                UIKeys.PANELS_MODALS_DUPE,
                (str) -> this.dupeTo(first.toString(), this.namesList.getPath(str).toString())
            );

            panel.text.setText(first.getLast());
            panel.text.filename();

            UIOverlay.addOverlay(this.getContext(), panel);

            return;
        }

        for (DataPath src : files)
        {
            this.dupeTo(src.toString(), this.getNextDupeId(src));
        }

        this.panel.requestNames();
    }

    private String getNextDupeId(DataPath source)
    {
        String base = source.getLast();
        DataPath parent = source.getParent();
        String prefix = parent.strings.isEmpty() ? "" : parent.toString() + "/";
        String candidate = prefix + base + "_copy";
        int i = 2;

        while (this.namesList.hasInHierarchy(candidate))
        {
            candidate = prefix + base + "_copy" + i;
            i += 1;
        }

        return candidate;
    }

    private void dupeTo(String from, String to)
    {
        if (to.trim().isEmpty())
        {
            this.getContext().notifyError(UIKeys.PANELS_MODALS_EMPTY);

            return;
        }

        if (this.namesList.hasInHierarchy(to))
        {
            return;
        }

        this.panel.getRepository().load(from, (data) ->
        {
            Film loaded = (Film) data;

            if (loaded != null)
            {
                loaded.stampCreationTimeNow();
                loaded.stampUpdatedTimeNow();
                this.panel.getRepository().save(to, loaded.toData().asMap());
            }

            this.panel.requestNames();
        });
    }

    private void renameSelected()
    {
        List<DataPath> selected = this.getSelectedPaths();

        if (selected.size() != 1)
        {
            return;
        }

        DataPath first = selected.get(0);
        boolean folder = first.folder && !first.getLast().equals("..");
        UIPromptOverlayPanel panel = new UIPromptOverlayPanel(
            folder ? UIKeys.PANELS_MODALS_RENAME_FOLDER_TITLE : UIKeys.GENERAL_RENAME,
            folder ? UIKeys.PANELS_MODALS_RENAME_FOLDER : UIKeys.PANELS_MODALS_RENAME,
            (str) -> this.rename(first.toString(), this.namesList.getPath(str).toString())
        );

        panel.text.setText(first.getLast());
        panel.text.filename();

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void rename(String from, String to)
    {
        if (to.trim().isEmpty())
        {
            this.getContext().notifyError(UIKeys.PANELS_MODALS_EMPTY);

            return;
        }

        if (this.namesList.hasInHierarchy(to))
        {
            return;
        }

        this.panel.getRepository().rename(from, to);

        if (this.panel.getData() != null && from.equals(this.panel.getData().getId()))
        {
            this.panel.getData().setId(to);
        }

        this.panel.requestNames();
    }

    private void removeSelected()
    {
        List<DataPath> selected = this.getSelectedPaths();

        if (selected.isEmpty())
        {
            return;
        }

        boolean folderOnly = selected.size() == 1 && selected.get(0).folder && !selected.get(0).getLast().equals("..");
        UIConfirmOverlayPanel panel = new UIConfirmOverlayPanel(
            folderOnly ? UIKeys.PANELS_MODALS_REMOVE_FOLDER_TITLE : UIKeys.GENERAL_REMOVE,
            folderOnly ? UIKeys.PANELS_MODALS_REMOVE_FOLDER : UIKeys.PANELS_MODALS_REMOVE,
            (confirm) ->
            {
                if (confirm)
                {
                    this.removeSelectedNow(selected);
                }
            }
        );

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void removeSelectedNow(List<DataPath> selected)
    {
        for (DataPath dataPath : selected)
        {
            if (dataPath.folder)
            {
                if (!dataPath.getLast().equals(".."))
                {
                    this.panel.getRepository().deleteFolder(dataPath.toString(), (b) -> this.panel.requestNames());
                }
            }
            else
            {
                this.panel.getRepository().delete(dataPath.toString());
            }
        }

        this.grid.deselect();
        this.namesList.deselect();
        this.panel.requestNames();
    }

    /* Rendering */

    @Override
    public void setVisible(boolean visible)
    {
        boolean wasVisible = this.isVisible();

        super.setVisible(visible);

        if (visible && !wasVisible)
        {
            this.search.setText("");
            this.filter = "";
            this.grid.deselect();
            this.applyContent();
            this.panel.requestNames();
        }
    }

    /** Cover-cropped banner texture with a readability gradient and accent hairline. */
    private void renderBanner(UIContext context, Area area)
    {
        Texture texture = BBSModClient.getTextures().getTexture(BANNER);

        if (texture != null)
        {
            float texW = texture.width;
            float texH = texture.height;
            float texAspect = texW / texH;
            float areaAspect = area.w / (float) area.h;
            float u1;
            float u2;
            float v1;
            float v2;

            if (areaAspect > texAspect)
            {
                float cropH = texW / areaAspect;

                u1 = 0;
                u2 = texW;
                v1 = (texH - cropH) * 0.5F;
                v2 = v1 + cropH;
            }
            else
            {
                float cropW = texH * areaAspect;

                u1 = (texW - cropW) * 0.5F;
                u2 = u1 + cropW;
                v1 = 0;
                v2 = texH;
            }

            context.batcher.texturedBox(texture, Colors.WHITE, area.x, area.y, area.w, area.h, u1, v1, u2, v2, texture.width, texture.height);
        }

        int deep = BBSSettings.deepSurface();

        context.batcher.gradientVBox(area.x, area.ey() - area.h / 2, area.ex(), area.ey(), Colors.setA(deep, 0F), Colors.setA(deep, 0.75F));

        int accent = BBSSettings.accentColorRGB();
        int mid = area.mx();

        context.batcher.gradientHBox(area.x, area.ey() - 2, mid, area.ey(), Colors.setA(accent, 0F), Colors.A100 | accent);
        context.batcher.gradientHBox(mid, area.ey() - 2, area.ex(), area.ey(), Colors.A100 | accent, Colors.setA(accent, 0F));
    }

    private void renderEmptyState(UIContext context)
    {
        if (!this.gridMode || !this.grid.getList().isEmpty() || !this.filter.isEmpty())
        {
            return;
        }

        Area area = this.grid.area;
        int muted = BBSSettings.mutedTextColor();

        context.batcher.icon(Icons.FILM, muted, area.mx(), area.my() - 24, 0.5F, 0.5F);

        String label = L10n.lang("bbs.ui.film.home.empty").get();

        context.batcher.textShadow(label, area.mx() - context.batcher.getFont().getWidth(label) / 2, area.my() + 8, muted);
    }

    /**
     * Card content renderer: procedural first-frame placeholder (hash-tinted
     * gradient + film icon + duration badge) over title and relative modified
     * time. Real first-frame thumbnails land in P1 behind the same contract.
     */
    private class FilmCardRenderer implements UICardGrid.ICardRenderer<FilmCard>
    {
        @Override
        public int cardHeight()
        {
            return (int) (CARD_H * cardScale);
        }

        @Override
        public int preferredCardWidth()
        {
            return (int) (CARD_W * cardScale);
        }

        @Override
        public void render(UIContext context, Area area, FilmCard card, boolean selected, boolean hovered)
        {
            int pad = 6;
            int tx = area.x + pad;
            int ty = area.y + pad;
            int tw = area.w - pad * 2;
            int th = (int) (THUMB_H * cardScale);

            if (card.folder)
            {
                context.batcher.box(tx, ty, tx + tw, ty + th, BBSSettings.chromeSurface());
                context.batcher.iconArea(Icons.FOLDER, Colors.WHITE, tx + tw / 2 - 6, ty + th / 2 - 6, 12, 12);
            }
            else
            {
                Texture thumb = FilmThumbnails.getCached(card.path.toString());

                if (thumb != null)
                {
                    UINewsStrip.drawCover(context.batcher, thumb, tx, ty, tw, th);
                }
                else
                {
                    int index = Math.abs(card.path.toString().hashCode()) % THUMB_COLORS.length;
                    int[] colors = THUMB_COLORS[index];

                    context.batcher.gradientVBox(tx, ty, tx + tw, ty + th, colors[0], colors[1]);
                    context.batcher.iconArea(Icons.FILM, Colors.WHITE, tx + tw / 2 - 6, ty + th / 2 - 6, 12, 12);
                }

                if (card.duration > 0)
                {
                    String duration = TimeUtils.formatTime(card.duration);
                    int badgeW = context.batcher.getFont().getWidth(duration) + 6;

                    context.batcher.box(tx + tw - badgeW - 3, ty + th - 13, tx + tw - 3, ty + th - 3, Colors.A75);
                    context.batcher.textShadow(duration, tx + tw - badgeW, ty + th - 11, Colors.WHITE);
                }
            }

            int textY = ty + th + 5;
            int maxW = area.w - pad * 2;

            if (card.renderedTitleWidth != area.w)
            {
                card.renderedTitle = context.batcher.getFont().limitToWidth(card.folder ? card.name + "/" : card.name, "...", maxW);
                card.renderedTitleWidth = area.w;
            }

            if (card.folder)
            {
                context.batcher.iconArea(Icons.FOLDER, Colors.WHITE, area.x + pad, textY - 1, 12, 12);
                context.batcher.textShadow(card.renderedTitle, area.x + pad + 15, textY, BBSSettings.textColor());
            }
            else
            {
                context.batcher.textShadow(card.renderedTitle, area.x + pad, textY, BBSSettings.textColor());
            }

            if (!card.folder && card.updated != null)
            {
                String meta = UIFilmHomePanel.this.formatRelative(RelativeTime.describe(card.updated));

                context.batcher.textShadow(meta, area.x + pad, textY + 12, BBSSettings.mutedTextColor());
            }
        }
    }
}
