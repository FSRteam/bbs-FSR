package mchorse.bbs_mod.ui.framework.elements.input;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.importers.IImportPathProvider;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.resources.packs.URLSourcePack;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.textures.TextureEntry;
import mchorse.bbs_mod.ui.textures.TextureFiles;
import mchorse.bbs_mod.ui.textures.UITextureBrowser;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.IUITabs;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.UIDataTabs;
import mchorse.bbs_mod.ui.dashboard.textures.UITextureEditor;
import mchorse.bbs_mod.ui.dashboard.textures.UITexturePainter;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIFilteredLinkList;
import mchorse.bbs_mod.ui.framework.elements.input.multilink.UIMultiLinkEditor;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.EventPropagation;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.presets.UICopyPasteController;
import mchorse.bbs_mod.ui.utils.presets.UIPresetContextMenu;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.presets.PresetManager;
import mchorse.bbs_mod.utils.resources.FilteredLink;
import mchorse.bbs_mod.utils.resources.GifFrames;
import mchorse.bbs_mod.utils.resources.LinkUtils;
import mchorse.bbs_mod.utils.resources.MultiLink;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Texture picker GUI
 *
 * This bad boy allows picking a texture from the file browser (hosted in {@link UITextureBrowser}),
 * and also it allows creating multi-skins. See {@link MultiLink} for more information.
 */
public class UITexturePicker extends UIElement implements IImportPathProvider, IUITabs
{
    public UIIcon close;
    public UITextureBrowser browser;

    public UIFilteredLinkList multiList;
    public UIMultiLinkEditor editor;

    public UIDataTabs tabs;
    public UIElement browseContent;
    public UITexturePainter painter;

    /** Shared open textures: tab 0 is the browser and tabs 1..N are these editors. */
    private static final List<UITextureEditor> EDITORS = new ArrayList<>();
    private int currentTab;

    public UIElement buttons;
    public UIIcon add;
    public UIIcon remove;
    public UIIcon edit;

    public Consumer<Link> callback;

    public MultiLink multiLink;
    public FilteredLink currentFiltered;
    public Link current;

    private String initialModelPreview;

    private boolean canBeClosed = true;

    private UICopyPasteController copyPasteController;

    public static UITexturePicker open(UIContext context, Link current, Consumer<Link> callback)
    {
        return open(context.menu.overlay, current, callback);
    }

    public static UITexturePicker open(UIElement parent, Link current, Consumer<Link> callback)
    {
        if (!parent.getChildren(UITexturePicker.class).isEmpty())
        {
            return null;
        }

        UITexturePicker picker = new UITexturePicker(callback);

        picker.full(parent);
        picker.resize();
        picker.fill(current);

        parent.add(picker);

        return picker;
    }

    public static void findAllTextures(UIContext context, Link current, Consumer<String> callback)
    {
        List<String> list = new ArrayList<>();

        for (Link link : BBSMod.getProvider().getLinksFromPath(Link.assets("")))
        {
            String string = link.toString();

            if (TextureFiles.isTexture(link) && !string.contains(":textures/banners/")) list.add(string);
        }

        for (Link link : BBSMod.getProvider().getLinksFromPath(new Link("http", "")))
        {
            String string = link.toString();

            if (string.contains(".png") || string.contains(GifFrames.EXTENSION)) list.add(string);
        }

        for (Link link : BBSMod.getProvider().getLinksFromPath(new Link("https", "")))
        {
            String string = link.toString();

            if (string.contains(".png") || string.contains(GifFrames.EXTENSION)) list.add(string);
        }

        UIListOverlayPanel panel = new UIListOverlayPanel(UIKeys.TEXTURE_FIND_TITLE, callback);

        panel.addValues(list);
        panel.list.list.sort();

        if (current != null)
        {
            panel.setValue(current.toString());
        }

        UIOverlay.addOverlay(context, panel);
    }

    public UITexturePicker(Consumer<Link> callback)
    {
        super();

        this.copyPasteController = new UICopyPasteController(PresetManager.TEXTURES, "_CopyTexture")
            .supplier(this::copyLink)
            .consumer((data, x, y) -> this.pasteLink(this.parseLink(data)))
            .canCopy(() -> this.current != null);

        this.browseContent = new UIElement();
        this.close = new UIIcon(Icons.CLOSE, (b) -> this.close());

        /* The multiskin column and its editor are built first: the browser places them in its side panel */
        this.multiList = new UIFilteredLinkList((list) -> this.setFilteredLink(list.get(0)));
        this.multiList.sorting();

        this.editor = new UIMultiLinkEditor(this);
        this.editor.setVisible(false);

        this.buttons = new UIElement();
        this.add = new UIIcon(Icons.ADD, (b) -> this.addMulti());
        this.remove = new UIIcon(Icons.REMOVE, (b) -> this.removeMulti());
        this.edit = new UIIcon(Icons.EDIT, (b) -> this.toggleEditor());

        this.buttons.add(this.add, this.remove, this.edit);

        this.browser = new UITextureBrowser(this);
        this.browser.grid.context((menu) ->
        {
            /* The presets of the old path text box live on in the grid's context menu */
            menu.custom(new UIPresetContextMenu(this.copyPasteController)
                .labels(UIKeys.TEXTURE_EDITOR_CONTEXT_COPY, UIKeys.TEXTURE_EDITOR_CONTEXT_PASTE));

            this.browser.buildContextMenu(menu);
        });
        this.browser.full(this.browseContent);

        this.browseContent.add(this.browser);

        this.painter = new UITexturePainter(this::onTextureSaved).onRename(this::onTextureRenamed);

        this.tabs = new UIDataTabs(this);
        this.tabs.relative(this).w(1F).h(UIDataTabs.TABS_HEIGHT_PX);
        this.browseContent.relative(this.tabs).y(1F).w(1F).hTo(this.area, 1F);
        this.painter.relative(this.tabs).y(1F).w(1F).hTo(this.area, 1F);
        this.painter.setVisible(false);

        this.add(this.tabs, this.browseContent, this.painter);

        this.callback = callback;

        this.keys().register(Keys.TEXTURE_PICKER_FIND, () ->
        {
            findAllTextures(this.getContext(), this.current, (s) ->
            {
                this.selectCurrent(Link.create(s));
                this.displayCurrent(Link.create(s), true);
            });
        });
        this.keys().register(Keys.CYCLE_PANELS, this::cycleTabs).inside();
        this.keys().register(Keys.OPEN_NEW_TAB, this::addTab);

        this.fill(null);
        this.markContainer().eventPropagataion(EventPropagation.BLOCK);
        this.showTab(0);
    }

    public UITexturePicker withModelPreview(String model)
    {
        this.initialModelPreview = model;
        return this;
    }

    public UITexturePicker cantBeClosed()
    {
        this.close.removeFromParent();
        this.eventPropagataion(EventPropagation.PASS);

        this.canBeClosed = false;

        return this;
    }

    private Link parseLink(MapType map)
    {
        return map == null ? null : LinkUtils.create(map.get("link"));
    }

    private MapType copyLink()
    {
        BaseType base = LinkUtils.toData(this.multiLink != null ? this.multiLink : this.current);

        if (base == null)
        {
            return null;
        }

        MapType map = new MapType();

        map.put("link", base);

        return map;
    }

    private void pasteLink(Link location)
    {
        this.setMulti(location, true);
    }

    public void download(String inputUrl)
    {
        Link path = this.browser.getPath();

        if (!Link.isAssets(path))
        {
            return;
        }

        UITextbox textboxFilename = new UITextbox();
        UITextbox textboxUrl = new UITextbox(1000, (t) ->
        {
            String newFilename = StringUtils.fileName(t).replaceAll("[^\\w\\d_\\-.]+", "");

            textboxFilename.setText(newFilename);
        });
        UIConfirmOverlayPanel panel = new UIConfirmOverlayPanel(UIKeys.TEXTURES_DOWNLOAD_TITLE, UIKeys.TEXTURES_DOWNLOAD_DESCRIPTION, (b) ->
        {
            if (b)
            {
                String url = textboxUrl.getText();
                String filename = textboxFilename.getText();
                Link urlLink = path.combine(filename);

                try (InputStream stream = URLSourcePack.downloadImage(Link.create(url)))
                {
                    File file = BBSMod.getProvider().getFile(urlLink);

                    try (OutputStream outputStream = new FileOutputStream(file))
                    {
                        IOUtils.copy(stream, outputStream);
                    }
                }
                catch (Exception e)
                {}
            }
        });

        if (!inputUrl.isEmpty())
        {
            String newFilename = StringUtils.fileName(inputUrl).replaceAll("[^\\w\\d_\\-.]+", "");

            textboxUrl.setText(inputUrl);
            textboxFilename.setText(newFilename);
            textboxFilename.textbox.selectFilename();
        }

        textboxFilename.placeholder(UIKeys.TEXTURES_DOWNLOAD_FILENAME);
        textboxUrl.placeholder(UIKeys.TEXTURES_DOWNLOAD_URL);

        textboxFilename.relative(panel.confirm).y(-5).w(1F).anchorY(1F);
        textboxUrl.relative(textboxFilename).y(-5).w(1F).anchorY(1F);
        panel.confirm.w(1F, -10);
        panel.content.add(textboxFilename, textboxUrl);

        UIContext context = this.getContext();

        UIOverlay.addOverlay(context, panel);
        context.focus(textboxFilename);
    }

    public void close()
    {
        boolean wasVisible = this.hasParent();

        this.editor.close();
        this.removeFromParent();

        if (this.callback != null && wasVisible)
        {
            if (this.multiLink != null)
            {
                this.multiLink.recalculateId();
            }

            this.callback.accept(this.multiLink != null ? this.multiLink : this.current);
        }
    }

    @Override
    public File getImporterPath()
    {
        File target = BBSMod.getProvider().getFile(this.browser.getPath());

        if (target == null || !target.isDirectory())
        {
            return null;
        }

        return target;
    }

    public void refresh()
    {
        this.browser.refresh();
    }

    public void openFolder()
    {
        this.browser.openFolder();
    }

    /**
     * Grid click: selects the texture (single click), and opens it in a tab on a double click of
     * the same file — the double click detection itself lives in the browser's list.
     */
    public void onFileClicked(Link link)
    {
        this.selectCurrent(link);
    }

    /**
     * Opens {@code link} as a texture tab (tabs 1..N) on top of the browser tab: focuses an existing
     * tab for it, otherwise loads it into a fresh editor and appends a tab. Fired by the pencil.
     */
    public void openTexture(Link link)
    {
        if (link == null)
        {
            return;
        }

        for (int i = 0; i < EDITORS.size(); i++)
        {
            UITextureEditor editor = EDITORS.get(i);

            if (editor.getTexture() != null && link.toString().equals(editor.getTexture().toString()))
            {
                this.showTab(i + 1);

                return;
            }
        }

        UITextureEditor editor = this.painter.openEditor(link);

        if (editor == null)
        {
            return;
        }

        editor.setEditing(true);
        EDITORS.add(editor);
        this.showTab(EDITORS.size());

        if (this.initialModelPreview != null && !this.initialModelPreview.isEmpty())
        {
            this.painter.openModelPreview(this.initialModelPreview);
            this.initialModelPreview = null;
        }
    }

    /** Opens {@code link} like {@link #openTexture} and turns its animation on, if it wasn't already. */
    public void openTextureAnimated(Link link)
    {
        this.openTexture(link);

        UITextureEditor editor = this.painter.getCurrentEditor();

        if (editor != null && link != null && link.toString().equals(String.valueOf(editor.getTexture())))
        {
            this.painter.enableAnimation();
        }
    }

    /** Shows tab {@code index}: the file browser for tab 0, otherwise the corresponding editor. */
    private void showTab(int index)
    {
        if (index < 0 || index > EDITORS.size())
        {
            index = 0;
        }

        this.currentTab = index;

        boolean browse = index == 0;
        UITextureEditor editor = browse ? null : EDITORS.get(index - 1);

        this.painter.setEditor(editor);
        this.painter.setVisible(!browse);
        this.browseContent.setVisible(browse);

        if (this.browser != null)
        {
            this.browser.setEditing(!browse);
        }

        this.tabs.sync();
        this.resize();
    }

    /** Re-attaches shared editors after another picker changed or hosted them. */
    public void syncToSharedTabs()
    {
        if (this.currentTab < 0 || this.currentTab > EDITORS.size())
        {
            this.currentTab = 0;
        }

        this.showTab(this.currentTab);
    }

    private void cycleTabs()
    {
        int count = this.getTabCount();

        if (count <= 1)
        {
            return;
        }

        int next = this.currentTab + (Window.isShiftPressed() ? -1 : 1);

        next = ((next % count) + count) % count;

        this.showTab(next);
        UIUtils.playClick();
    }

    private void onTextureSaved(Link link)
    {
        this.selectCurrent(link);
        this.displayCurrent(link);
        this.tabs.sync();
    }

    /**
     * A Save As changed {@code editor}'s link: close any other tab that already referenced the new
     * link and refresh the strip.
     */
    private void onTextureRenamed(UITextureEditor editor, Link newLink)
    {
        for (int i = EDITORS.size() - 1; i >= 0; i--)
        {
            UITextureEditor other = EDITORS.get(i);

            if (other != editor && newLink.equals(other.getTexture()))
            {
                int tabIndex = i + 1;

                if (!this.prepareTabRemoval(tabIndex))
                {
                    this.tabs.sync();

                    return;
                }

                this.removeEditor(tabIndex);
            }
        }

        this.currentTab = EDITORS.indexOf(editor) + 1;
        this.tabs.sync();
    }

    private boolean prepareTabRemoval(int index)
    {
        if (!this.canCloseTab(index))
        {
            return false;
        }

        UITextureEditor editor = EDITORS.get(index - 1);

        if (editor.isDirty())
        {
            this.showTab(index);
            editor.openSaveOverlay();

            return false;
        }

        return true;
    }

    private void removeEditor(int index)
    {
        UITextureEditor editor = EDITORS.remove(index - 1);

        editor.removeFromParent();
        editor.deleteTexture();
    }

    /* IUITabs — tab 0 is the browser, tabs 1..N are open textures */

    @Override
    public boolean areTabsEnabled()
    {
        return true;
    }

    @Override
    public int getTabCount()
    {
        return EDITORS.size() + 1;
    }

    @Override
    public int getCurrentTab()
    {
        return this.currentTab;
    }

    @Override
    public IKey getTabLabel(int index)
    {
        return index == 0 ? UIKeys.TEXTURES_TOOLTIP : IKey.raw(StringUtils.fileName(EDITORS.get(index - 1).getTexture().path));
    }

    @Override
    public IKey getTabTooltip(int index)
    {
        return index == 0 ? null : IKey.raw(EDITORS.get(index - 1).getTexture().path);
    }

    @Override
    public Icon getTabIcon(int index)
    {
        return index == 0 ? Icons.FOLDER : Icons.MATERIAL;
    }

    @Override
    public IKey getNewTabLabel()
    {
        return UIKeys.TEXTURES_TOOLTIP;
    }

    @Override
    public boolean isNewTab(int index)
    {
        return false;
    }

    @Override
    public boolean canCloseTab(int index)
    {
        return index >= 1 && index <= EDITORS.size();
    }

    @Override
    public void addTab()
    {
        findAllTextures(this.getContext(), this.current, (path) -> this.openTexture(Link.create(path)));
    }

    @Override
    public void switchTab(int index)
    {
        if (index >= 0 && index < this.getTabCount())
        {
            this.showTab(index);
        }
    }

    @Override
    public void closeTab(int index)
    {
        if (!this.prepareTabRemoval(index))
        {
            return;
        }

        this.removeEditor(index);

        int target;

        if (this.currentTab == index)
        {
            target = Math.min(index, EDITORS.size());
        }
        else if (this.currentTab > index)
        {
            target = this.currentTab - 1;
        }
        else
        {
            target = this.currentTab;
        }

        this.showTab(target);
    }

    @Override
    public void closeOtherTabs(int index)
    {
        if (!this.canCloseTab(index))
        {
            return;
        }

        UITextureEditor keep = EDITORS.get(index - 1);

        for (int i = EDITORS.size() - 1; i >= 0; i--)
        {
            if (EDITORS.get(i) != keep)
            {
                UITextureEditor editor = EDITORS.remove(i);

                editor.removeFromParent();
                editor.deleteTexture();
            }
        }

        this.showTab(1);
    }

    @Override
    public void closeTabsLeft(int index)
    {
        if (index < 2 || index > EDITORS.size())
        {
            return;
        }

        for (int i = index - 2; i >= 0; i--)
        {
            UITextureEditor editor = EDITORS.remove(i);

            editor.removeFromParent();
            editor.deleteTexture();
        }

        this.showTab(1);
    }

    @Override
    public void closeTabsRight(int index)
    {
        if (index < 1 || index >= EDITORS.size())
        {
            return;
        }

        for (int i = EDITORS.size() - 1; i >= index; i--)
        {
            UITextureEditor editor = EDITORS.remove(i);

            editor.removeFromParent();
            editor.deleteTexture();
        }

        this.showTab(index);
    }

    public void fill(Link link)
    {
        this.setMulti(link, false, true);
    }

    /**
     * Add a {@link Link} to the MultiLink
     */
    private void addMulti()
    {
        FilteredLink filtered = this.currentFiltered.copyFiltered();

        this.multiList.add(filtered);
        this.multiList.setIndex(this.multiList.getList().size() - 1);
        this.setFilteredLink(this.multiList.getCurrent().get(0));
    }

    /**
     * Remove currently selected {@link Link} from multiLink
     */
    private void removeMulti()
    {
        int index = this.multiList.getIndex();

        if (index < 0)
        {
            return;
        }

        if (this.multiList.getList().size() == 1)
        {
            /* A multiskin of one skin is just that texture: taking out the last one ends the multiskin */
            this.setMulti(this.multiList.getList().get(0).path, true);

            return;
        }

        this.multiList.getList().remove(index);
        this.multiList.update();
        this.multiList.setIndex(Math.min(index, this.multiList.getList().size() - 1));

        if (this.multiList.getIndex() >= 0)
        {
            this.setFilteredLink(this.multiList.getCurrent().get(0));
        }
    }

    private void setFilteredLink(FilteredLink location)
    {
        this.setFilteredLink(location, false);
    }

    private void setFilteredLink(FilteredLink location, boolean scroll)
    {
        this.currentFiltered = location;
        this.displayCurrent(location.path);
        this.editor.setLink(location);
    }

    private void toggleEditor()
    {
        this.editor.toggleVisible();
        this.browser.setEditing(this.editor.isVisible());

        if (this.editor.isVisible())
        {
            this.editor.resetView();
        }
    }

    /** Put the skin's editor away, if it's open: it has nothing to stand on without the multiskin column. */
    public void closeEditor()
    {
        if (this.editor.isVisible())
        {
            this.toggleEditor();
        }
    }

    protected void displayCurrent(Link link)
    {
        this.displayCurrent(link, false);
    }

    /**
     * Display current resource location (it's just for visual, not
     * logic)
     */
    protected void displayCurrent(Link link, boolean scroll)
    {
        this.current = link;

        this.browser.setCurrent(link, scroll);
    }

    /**
     * Select current resource location
     */
    public void selectCurrent(Link link)
    {
        if (link != null && !BBSModClient.getTextures().has(link))
        {
            return;
        }

        this.current = link;

        if (this.multiLink != null)
        {
            if (link == null && this.multiLink.children.size() == 1)
            {
                this.currentFiltered.path = null;
                this.toggleMulti();
            }
            else
            {
                this.currentFiltered.path = link;
            }
        }
        else if (this.callback != null)
        {
            this.callback.accept(link);
        }

        this.browser.setCurrent(link, false);
    }

    public void toggleMulti()
    {
        if (this.multiLink != null)
        {
            this.setMulti(this.multiLink.children.get(0).path, true);
        }
        else if (this.current != null)
        {
            this.setMulti(new MultiLink(this.current.toString()), true);
        }
        else
        {
            TextureEntry entry = this.browser.getCurrentEntry();

            if (entry != null && !entry.folder())
            {
                this.setMulti(entry.link(), true);
            }
        }
    }

    protected void setMulti(Link skin, boolean notify)
    {
        this.setMulti(skin, notify, false);
    }

    protected void setMulti(Link skin, boolean notify, boolean scroll)
    {
        this.closeEditor();

        boolean show = skin instanceof MultiLink;

        if (show)
        {
            this.multiLink = (MultiLink) ((MultiLink) skin).copy();
            this.setFilteredLink(this.multiLink.children.get(0), scroll);

            this.multiList.setIndex(this.multiLink.children.isEmpty() ? -1 : 0);
            this.multiList.setList(this.multiLink.children);

            if (this.current != null)
            {
                this.multiList.setIndex(0);
            }
        }
        else
        {
            this.multiLink = null;

            this.displayCurrent(skin, scroll);
        }

        if (notify)
        {
            if (show)
            {
                /* The multiskin itself is only ever handed to the callback: it isn't a file,
                 * so it can't be the current texture the browser shows (in the dashboard
                 * manager, with no callback, it used to become one and led into a "multi:" folder) */
                if (this.callback != null)
                {
                    this.multiLink.recalculateId();
                    this.callback.accept(skin);
                }
            }
            else
            {
                this.selectCurrent(skin);
            }
        }

        this.browser.setMultiskin(show);

        this.resize();
    }

    @Override
    public boolean subKeyPressed(UIContext context)
    {
        if (this.currentTab == 0 && this.browser.isVisible() && this.browser.handleKey(context))
        {
            return true;
        }
        else if (context.isPressed(GLFW.GLFW_KEY_ESCAPE))
        {
            if (this.currentTab != 0)
            {
                this.showTab(0);

                return true;
            }
            else if (this.canBeClosed)
            {
                this.close();

                return true;
            }
        }
        else if (context.isPressed(Keys.PASTE.getMainKey()) && Window.isCtrlPressed())
        {
            this.download(Window.getClipboard());

            return true;
        }

        return super.subKeyPressed(context);
    }

    @Override
    public boolean subTextInput(UIContext context)
    {
        if (this.currentTab != 0 || !this.browser.isVisible())
        {
            return false;
        }

        return this.browser.pickByTyping(context.getInputCharacter());
    }

    @Override
    public void render(UIContext context)
    {
        /* Draw the background (browser tab only; the painter draws its own).
         * In the dashboard the panel stands on the dashboard's own background; a pop-up
         * paints the same "background" colour from the settings under itself. */
        if (this.currentTab == 0 && this.canBeClosed)
        {
            this.browseContent.area.render(context.batcher, BBSSettings.backgroundColor.get());
        }

        super.render(context);
    }
}
