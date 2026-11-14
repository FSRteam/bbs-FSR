package mchorse.bbs_mod.ui.textures;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.utils.FontRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.utils.context.ContextMenuManager;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.NaturalOrderComparator;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.colors.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The folders of every source as a tree down the side of a texture browser, for jumping
 * between {@code models/…/skins} of different models without climbing up and down. Branches
 * are listed only when unfolded, so a huge assets folder costs nothing until opened.
 *
 * <p>A row is a folder: the arrow unfolds it, the name goes there. While textures are
 * dragged, the row under the cursor is where they'd drop.</p>
 *
 * <p>Above the sources sit the {@link TexturePins pins} — the folders and textures the user
 * keeps at hand — under a title of their own, which folds like a branch and sits on the same
 * grid as the sources: arrow, icon, name. So the tree reads as one list of groups rather than
 * a heading with the roots tucked in beside it. A divider cuts the group off from the tree
 * below. They're the same list everywhere a tree is shown.</p>
 */
public class UIFolderTree extends UIList<UIFolderTree.Node>
{
    public static final int ROW = 16;
    public static final int INDENT = 10;

    /** What a row is: a folder of the tree, one of the pins above it, or the pins' own title. */
    public enum Kind
    {
        FOLDER, PIN, HEADER
    }

    public record Node(Link link, int depth, boolean branch, boolean expanded, Kind kind)
    {
        public static Node folder(Link link, int depth, boolean branch, boolean expanded)
        {
            return new Node(link, depth, branch, expanded, Kind.FOLDER);
        }

        public static Node pin(Link link)
        {
            return new Node(link, 1, false, false, Kind.PIN);
        }

        /** The row the pins hang under; it points at nothing, and folds them away. */
        public static Node pinsHeader(boolean expanded)
        {
            return new Node(new Link("", ""), 0, true, expanded, Kind.HEADER);
        }

        public boolean pin()
        {
            return this.kind == Kind.PIN;
        }

        public boolean header()
        {
            return this.kind == Kind.HEADER;
        }

        /** A source root and anything with a trailing slash; only a pin can be a texture. */
        public boolean folder()
        {
            return this.link.path.isEmpty() || this.link.path.endsWith("/");
        }
    }

    private final IFolderTreeHost browser;

    /* Which folders are unfolded; it outlives every relisting of the tree */
    private final Set<Link> expanded = new HashSet<>();

    /** Whether a folder has folders inside — asked once per listing, not per frame. */
    private final Map<Link, Boolean> branches = new HashMap<>();

    /** Whether the pins are listed under their title. Folded, only the title is left. */
    private boolean pinsExpanded = true;

    /**
     * How many rows the pins take, their title included: where the divider goes, and where
     * the tree itself starts. Zero when nothing is pinned — no title, no divider.
     */
    private int pinRows;

    /* The row right clicked on, whose pin the context menu acts upon */
    private Node contextNode;

    public UIFolderTree(IFolderTreeHost browser)
    {
        super(null);

        this.browser = browser;
        this.scroll.scrollItemSize = ROW;
        this.scroll.scrollSpeed = ROW * 2;
        this.cancelScrollEdge();
        this.context(this::buildContextMenu);
    }

    /** Forget what's cached about the disk and lay the tree out again. */
    public void refresh()
    {
        this.branches.clear();
        this.rebuild();
    }

    /** Unfold every folder above {@code path} so it shows, then lay out. */
    public void reveal(Link path)
    {
        if (path != null && !path.source.isEmpty())
        {
            Link folder = TextureEntry.folderLink(path);

            while (!folder.path.isEmpty())
            {
                folder = TextureEntry.folderLink(folder.parent());
                this.expanded.add(folder);
            }

            this.expanded.add(new Link(path.source, ""));
        }

        this.rebuild();
    }

    private void rebuild()
    {
        this.clear();

        List<Link> pinned = TexturePins.getPins();

        if (!pinned.isEmpty())
        {
            this.add(Node.pinsHeader(this.pinsExpanded));

            if (this.pinsExpanded)
            {
                for (Link pin : pinned)
                {
                    this.add(Node.pin(pin));
                }
            }
        }

        this.pinRows = pinned.isEmpty() ? 0 : this.pinsExpanded ? pinned.size() + 1 : 1;

        List<String> sources = new ArrayList<>(BBSMod.getProvider().getSourceKeys());

        sources.sort((a, b) -> NaturalOrderComparator.compare(true, a, b));

        for (String source : sources)
        {
            this.addBranch(new Link(source, ""), 0);
        }

        this.update();
    }

    private void addBranch(Link folder, int depth)
    {
        boolean branch = this.isBranch(folder);
        boolean open = branch && this.expanded.contains(folder);

        this.add(Node.folder(folder, depth, branch, open));

        if (!open)
        {
            return;
        }

        List<Link> children = new ArrayList<>();

        for (Link link : BBSMod.getProvider().getLinksFromPath(folder, false))
        {
            if (link.path.endsWith("/"))
            {
                children.add(link);
            }
        }

        children.sort((a, b) -> NaturalOrderComparator.compare(true, a.path, b.path));

        for (Link child : children)
        {
            this.addBranch(child, depth + 1);
        }
    }

    private boolean isBranch(Link folder)
    {
        return this.branches.computeIfAbsent(folder, (f) ->
        {
            for (Link link : BBSMod.getProvider().getLinksFromPath(f, false))
            {
                if (link.path.endsWith("/"))
                {
                    return true;
                }
            }

            return false;
        });
    }

    @Override
    protected int indent(Node node)
    {
        return node.depth() * INDENT;
    }

    @Override
    protected Boolean branch(Node node)
    {
        return node.branch() ? node.expanded() : null;
    }

    @Override
    protected void toggle(Node node)
    {
        if (node.header())
        {
            this.pinsExpanded = !this.pinsExpanded;
        }
        else if (!this.expanded.remove(node.link()))
        {
            this.expanded.add(node.link());
        }

        this.rebuild();
    }

    /** A pin was clicked: its folder is entered, its texture handed to whoever shows the tree. */
    private void openPin(Node node)
    {
        if (node.folder())
        {
            this.browser.navigate(node.link());
        }
        else
        {
            this.browser.openPinned(node.link());
        }
    }

    private String nameOf(Node node)
    {
        return node.link().path.isEmpty() ? node.link().source : StringUtils.fileName(node.link().path).replace("/", "");
    }

    /**
     * Whether a pin points at something that is gone — shown greyed out, so a pin left over
     * from a deleted folder says so instead of quietly going nowhere. Only what lives on disk
     * can be told: a source inside the mod's jar hands out no files, and is never called stale.
     */
    private boolean isMissing(Node node)
    {
        File file = TextureFiles.file(node.link());

        return file != null && !file.exists();
    }

    @Override
    protected boolean sortElements()
    {
        return false;
    }

    /* Input */

    private void buildContextMenu(ContextMenuManager menu)
    {
        Node node = this.contextNode;

        if (node == null || !TexturePins.canPin(node.link()))
        {
            return;
        }

        Link link = node.link();

        if (node.pin())
        {
            menu.action(Icons.MOVE_UP, UIKeys.TEXTURES_BROWSER_PIN_UP, () -> this.shiftPin(link, -1));
            menu.action(Icons.MOVE_DOWN, UIKeys.TEXTURES_BROWSER_PIN_DOWN, () -> this.shiftPin(link, 1));
            menu.action(Icons.BOOKMARK, UIKeys.TEXTURES_BROWSER_UNPIN, () -> this.togglePin(link));
        }
        else
        {
            menu.action(Icons.BOOKMARK, TexturePins.isPinned(link) ? UIKeys.TEXTURES_BROWSER_UNPIN : UIKeys.TEXTURES_BROWSER_PIN, () -> this.togglePin(link));
        }
    }

    private void togglePin(Link link)
    {
        TexturePins.toggle(link);
        this.rebuild();
    }

    private void shiftPin(Link link, int delta)
    {
        TexturePins.shift(link, delta);
        this.rebuild();
    }

    @Override
    public boolean subMouseClicked(UIContext context)
    {
        if (!this.area.isInside(context))
        {
            return false;
        }

        if (this.scroll.mouseClicked(context))
        {
            return true;
        }

        int index = this.scroll.getIndex(context.mouseX, context.mouseY);
        Node node = this.exists(index) ? this.getList().get(index) : null;

        if (context.mouseButton == 1)
        {
            this.contextNode = node;

            return false;
        }

        if (node == null || context.mouseButton != 0)
        {
            return false;
        }

        if (node.header())
        {
            /* Nothing to enter: the whole row folds the pins away, arrow or not */
            this.toggle(node);

            return true;
        }

        if (node.pin())
        {
            this.openPin(node);

            return true;
        }

        if (node.branch() && this.hitsArrow(node, context.mouseX - this.area.x))
        {
            this.toggle(node);
        }
        else
        {
            this.browser.navigate(node.link());

            /* Entering a folder reveals the way down to it; a source root is the one row that
             * reveal unfolds itself, so asking the folds afterwards would answer "expanded" to
             * every click on one — the row's own state is what the click flips */
            if (node.branch() && !node.expanded())
            {
                this.expanded.add(node.link());
                this.rebuild();
            }
        }

        return true;
    }

    @Override
    public boolean subMouseReleased(UIContext context)
    {
        this.browser.release();

        return false;
    }

    /* Rendering */

    @Override
    public void renderListElement(UIContext context, Node node, int i, int x, int y, boolean hover, boolean selected)
    {
        int h = this.rowHeight();

        if (node.header())
        {
            RowStyle.row(context.batcher, x, y, this.area.w, h, 0, false, hover, false);
            this.renderHeader(context, node, x, y, hover);

            return;
        }

        TextureDrag drag = this.browser.getDrag();
        boolean current = node.folder() ? this.browser.isCurrentFolder(node.link()) : this.browser.isCurrentTexture(node.link());
        boolean target = false;

        /* A folder can't receive itself; the carried entries are matched by link since the tree has no entries */
        if (hover && node.folder() && drag != null && drag.isActive() && !drag.isDragging(node.link()))
        {
            drag.setTarget(node.link());
            target = true;
        }

        RowStyle.row(context.batcher, x, y, this.area.w, h, 0, false, hover, current);

        if (target)
        {
            RowStyle.dropTarget(context.batcher, x, y, this.area.w, h);
        }

        this.renderElementPart(context, node, i, x, y, hover, selected);

        /* The pins are their own group: a divider says where the tree itself begins */
        if (node.pin() && i == this.pinRows - 1)
        {
            context.batcher.box(x, y + h - 1, x + this.area.w, y + h, BBSSettings.dividerColor());
        }
    }

    /** Where the browser currently is, which is this tree's idea of the pick. */
    private boolean isCurrent(Node node)
    {
        return node.folder() ? this.browser.isCurrentFolder(node.link()) : this.browser.isCurrentTexture(node.link());
    }

    @Override
    protected void renderElementPart(UIContext context, Node node, int i, int x, int y, boolean hover, boolean selected)
    {
        FontRenderer font = context.batcher.getFont();
        int ix = x + this.rowContentX(node);
        int h = this.rowHeight();
        int my = y + h / 2;
        boolean missing = node.pin() && this.isMissing(node);
        boolean lit = hover || this.isCurrent(node);
        int color = missing ? RowStyle.textColor(lit, Colors.GRAY) : RowStyle.textColor(lit);
        int iconColor = missing ? color : RowStyle.iconColor(lit);

        this.renderArrow(context, node, x, y, lit);

        if (node.folder())
        {
            context.batcher.icon(Icons.FOLDER, iconColor, ix + ARROW_SLOT, my - 8);
        }
        else if (missing)
        {
            /* Nothing to show, and nothing to ask the texture manager for either */
            context.batcher.icon(Icons.IMAGE, color, ix + ARROW_SLOT, my - 8);
        }
        else
        {
            this.renderThumbnail(context, node.link(), ix + ARROW_SLOT, my - 8, iconColor);
        }

        /* A folder of the mod's own can't be changed, and its name says so by going faint -
         * the same fade the grid gives such a cell's name. What a pin is, its title says. */
        boolean readOnly = !node.pin() && TextureFiles.isReadOnly(node.link());
        int nameColor = readOnly ? Colors.mulA(color, TextureCellRenderer.READ_ONLY_ALPHA) : color;
        int textX = iconRowTextX(ix);
        String name = font.limitToWidth(this.nameOf(node), this.area.ex() - 4 - textX);

        context.batcher.textShadow(name, textX, y + (h - font.getHeight()) / 2 + 1, nameColor);
    }

    /**
     * The pins' title: an arrow, a bookmark and a word, in the same full white as everything
     * else down this side — greyed out, it read as disabled rather than as a label. It carries
     * no band of its own: the side panel is already chrome, and a second tone there would muddy it.
     */
    private void renderHeader(UIContext context, Node node, int x, int y, boolean hover)
    {
        FontRenderer font = context.batcher.getFont();
        int ix = x + this.rowContentX(node);
        int h = this.rowHeight();
        int my = y + h / 2;
        int color = RowStyle.textColor(hover);
        int iconColor = RowStyle.iconColor(hover);

        this.renderArrow(context, node, x, y, hover);
        context.batcher.icon(Icons.BOOKMARK, iconColor, ix + ARROW_SLOT, my - 8);

        int textX = iconRowTextX(ix);
        String title = font.limitToWidth(UIKeys.TEXTURES_BROWSER_PINNED.get(), this.area.ex() - 4 - textX);

        context.batcher.textShadow(title, textX, y + (h - font.getHeight()) / 2 + 1, color);
    }

    /** A pinned texture shows itself, fitted into the icon's place, so it's told apart at a glance. */
    private void renderThumbnail(UIContext context, Link link, int x, int y, int color)
    {
        Texture texture = BBSModClient.getTextures().getTexture(link);

        if (texture == null || texture == BBSModClient.getTextures().getError())
        {
            context.batcher.icon(Icons.IMAGE, color, x, y);

            return;
        }

        int tw = Math.max(1, texture.width);
        int th = Math.max(1, texture.height);
        float scale = Math.min(16F / tw, 16F / th);
        int w = Math.max(1, Math.round(tw * scale));
        int h = Math.max(1, Math.round(th * scale));

        context.batcher.iconArea(Icons.CHECKBOARD, x + (16 - w) / 2, y + (16 - h) / 2, w, h);
        context.batcher.fullTexturedBox(texture, x + (16 - w) / 2, y + (16 - h) / 2, w, h);
    }
}
