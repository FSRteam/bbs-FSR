package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.blocks.entities.ModelBlockEntity;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.data.migration.FilmStableIds;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.structure.StructureCut;
import mchorse.bbs_mod.forms.structure.StructureSelection;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.math.IExpression;
import mchorse.bbs_mod.math.MathBuilder;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.IValueListener;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueForm;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.forms.UIFormPalette;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIPanelBase;
import mchorse.bbs_mod.ui.framework.elements.context.UIContextMenu;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIList;
import mchorse.bbs_mod.ui.framework.elements.input.list.UILabelList;
import mchorse.bbs_mod.ui.framework.elements.input.list.UISearchList;
import mchorse.bbs_mod.ui.framework.elements.input.list.UIStringList;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockEntityList;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIConfirmOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIFolderOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UINumberOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.framework.elements.utils.RowStyle;
import mchorse.bbs_mod.ui.framework.elements.utils.UIText;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.presets.UICopyPasteController;
import mchorse.bbs_mod.ui.utils.presets.UIPresetContextMenu;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.NaturalOrderComparator;
import mchorse.bbs_mod.utils.RayTracing;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.utils.presets.PresetManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import mchorse.bbs_mod.utils.categories.Category;
import mchorse.bbs_mod.utils.categories.CategoryPath;
import mchorse.bbs_mod.utils.categories.CategoryTree;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * This GUI is responsible for drawing replays available in the director thing
 */
public class UIReplayList extends UIList<ReplayListEntry>
{
    private static String LAST_OFFSET = "0";
    private static final int INDENT = 10;
    private static final int PREVIEW = 40;

    public UIFilmPanel panel;
    private final Consumer<Form> formConsumer;
    private String pressedFolder;

    private final UICopyPasteController presetController;

    private String replaySearch = "";

    /** Set while building the context menu when the cursor is on a category folder row. */
    private String contextFolderCategoryName;

    public UIReplayList(Consumer<List<Replay>> callback, Consumer<Form> formConsumer, UIFilmPanel panel)
    {
        super(callback == null ? null : (entries) -> callback.accept(replaysFromEntries(entries)), (a, b) ->
            a.kind == b.kind && (a.isReplay() ? a.replay == b.replay : a.folderPath.equals(b.folderPath)));

        this.formConsumer = formConsumer;
        this.panel = panel;

        this.presetController = new UICopyPasteController(PresetManager.REPLAYS, "_CopyReplay")
            .supplier(() -> this.hasReplaySelection() ? this.replaysToData() : null)
            .consumer((data, mouseX, mouseY) -> this.pasteReplay(data))
            .canCopy(this::hasReplaySelection)
            .canPaste(() -> this.panel != null && this.panel.getData() != null);

        this.multi().sorting();
        this.context((menu) ->
        {
            Film film = this.panel.getData();
            UIContext context = this.getContext();

            menu.custom(new UIPresetContextMenu(this.presetController, context.mouseX, context.mouseY)
                .labels(UIKeys.SCENE_REPLAYS_CONTEXT_COPY, UIKeys.SCENE_REPLAYS_CONTEXT_PASTE));

            menu.action(Icons.ADD, UIKeys.SCENE_REPLAYS_CONTEXT_ADD, this::addReplay);

            if (film != null)
            {
                menu.action(Icons.FOLDER, UIKeys.SCENE_REPLAYS_CONTEXT_ADD_CATEGORY, () -> this.openAddCategoryOverlay(this.contextFolderCategoryName == null ? "" : this.contextFolderCategoryName));
            }

            if (film != null && this.contextFolderCategoryName != null)
            {
                String cat = this.contextFolderCategoryName;
                boolean enabled = this.isCategoryEnabled(cat);
                menu.action(Icons.EDIT, UIKeys.SCENE_REPLAYS_CONTEXT_EDIT_CATEGORY, () -> this.openCategoryEditMenu(cat));
                menu.action(enabled ? Icons.INVISIBLE : Icons.VISIBLE, enabled ? UIKeys.SCENE_REPLAYS_CONTEXT_DISABLE_CATEGORY : UIKeys.SCENE_REPLAYS_CONTEXT_ENABLE_CATEGORY,
                    () -> this.setCategoryEnabled(cat, !enabled));

                menu.action(Icons.TRASH, UIKeys.SCENE_REPLAYS_CONTEXT_REMOVE_CATEGORY, () -> this.removeReplayCategory(cat));
            }

            if (film != null && StructureSelection.isReady())
            {
                menu.action(Icons.BLOCK, UIKeys.STRUCTURE_CUT_TITLE, this::cutSelectionIntoReplay);
            }

            if (film != null)
            {
                String cameraId = this.panel.getEditedCameraId();
                int duration = film.getCameraClips(cameraId).calculateDuration();

                if (duration > 0)
                {
                    menu.action(Icons.PLAY, UIKeys.SCENE_REPLAYS_CONTEXT_FROM_CAMERA, () -> this.fromCamera(cameraId, duration));
                }
            }

            menu.action(Icons.BLOCK, UIKeys.SCENE_REPLAYS_CONTEXT_FROM_MODEL_BLOCK, this::fromModelBlock);

            if (this.hasReplaySelection())
            {
                boolean shift = Window.isShiftPressed();
                MapType data = Window.getClipboardMap("_CopyKeyframes");

                if (film != null && this.hasReplayCategoryNames())
                {
                    menu.action(Icons.SHIFT_TO, UIKeys.SCENE_REPLAYS_CONTEXT_MOVE_TO_CATEGORY, this::openMoveToCategoryContextMenu);
                }

                menu.action(Icons.ALL_DIRECTIONS, UIKeys.SCENE_REPLAYS_CONTEXT_PROCESS, this::processReplays);
                menu.action(Icons.TIME, UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME, this::offsetTimeReplays);

                if (this.getSelectedReplays().size() > 1)
                {
                    menu.action(Icons.MATERIAL, UIKeys.SCENE_REPLAYS_CONTEXT_RANDOM_TEXTURES, this::openRandomTexturesOverlay);
                }

                if (data != null)
                {
                    menu.action(Icons.PASTE, UIKeys.SCENE_REPLAYS_CONTEXT_PASTE_KEYFRAMES, () -> this.pasteToReplays(data));
                }

                menu.action(Icons.DUPE, UIKeys.SCENE_REPLAYS_CONTEXT_DUPE, () ->
                {
                    if (Window.isShiftPressed() || shift)
                    {
                        this.dupeReplay();
                    }
                    else
                    {
                        UINumberOverlayPanel numberPanel = new UINumberOverlayPanel(UIKeys.SCENE_REPLAYS_CONTEXT_DUPE, UIKeys.SCENE_REPLAYS_CONTEXT_DUPE_DESCRIPTION, (n) ->
                        {
                            for (int i = 0; i < n; i++)
                            {
                                this.dupeReplay();
                            }
                        });

                        numberPanel.value.limit(1).integer();
                        numberPanel.value.setValue(1D);

                        UIOverlay.addOverlay(this.getContext(), numberPanel);
                    }
                });
                menu.action(Icons.REMOVE, UIKeys.SCENE_REPLAYS_CONTEXT_REMOVE, this::removeReplay);
            }
        });

        this.keys().register(Keys.DELETE, this::removeReplay)
            .inside()
            .label(UIKeys.SCENE_REPLAYS_CONTEXT_REMOVE)
            .active(this::hasReplaySelection)
            .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.COPY, this::copyReplay)
            .inside()
            .label(UIKeys.SCENE_REPLAYS_CONTEXT_COPY)
            .active(this::hasReplaySelection)
            .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.PASTE, () ->
        {
            MapType data = Window.getClipboardMap("_CopyReplay");
            if (data != null)
            {
                this.pasteReplay(data);
            }
        }).inside()
            .label(UIKeys.SCENE_REPLAYS_CONTEXT_PASTE)
            .active(() -> Window.getClipboardMap("_CopyReplay") != null && this.panel != null && this.panel.getData() != null)
            .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.REPLAYS_DUPE, this::dupeReplay)
            .inside()
            .label(UIKeys.SCENE_REPLAYS_CONTEXT_DUPE)
            .active(this::hasReplaySelection)
            .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.REPLAYS_SELECT_ALL, this::selectAllReplays)
            .inside()
            .category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.FORMS_EDIT, () ->
        {
            Replay r = this.getSelectedReplayFirst();
            if (r != null)
            {
                this.openFormEditor(r.form, true, null);
            }
        }).inside()
            .category(UIKeys.FILM_REPLAY_TITLE);
    }

    @Override
    protected boolean clearsOnEmpty()
    {
        return true;
    }

    @Override
    public void selectAll()
    {
        if (!this.multi)
        {
            return;
        }

        this.current.clear();

        for (int i = 0; i < this.list.size(); i++)
        {
            ReplayListEntry e = this.list.get(i);

            if (e.isReplay())
            {
                this.current.add(i);
            }
        }

    }

    private void selectAllReplays()
    {
        this.selectAll();
        this.fireCallback();
    }

    @Override
    public UIContextMenu createContextMenu(UIContext context)
    {
        this.contextFolderCategoryName = null;

        int idx = this.getIndexAtCursor(context);

        if (this.exists(idx))
        {
            ReplayListEntry e = this.list.get(idx);

            if (e.isFolder())
            {
                String cat = Replay.normalizeCategory(e.folderName);

                if (!cat.isEmpty())
                {
                    this.contextFolderCategoryName = cat;
                }
            }
        }

        try
        {
            return super.createContextMenu(context);
        }
        finally
        {
            this.contextFolderCategoryName = null;
        }
    }

    /**
     * Remove a category from the film and move all replays in it to root.
     */
    private void removeReplayCategory(String path)
    {
        Film film = this.panel.getData();

        if (film == null || path.isEmpty())
        {
            return;
        }

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);
        String parent = CategoryPath.parent(path);

        for (Replay replay : film.replays.getList())
        {
            String category = Replay.normalizeCategory(replay.category.get());

            if (CategoryPath.isInside(category, path))
            {
                replay.category.set(CategoryPath.reparent(category, path, parent));
            }
        }

        film.replayCategories.removeRecord(path);
        film.replayCategories.renameSubtree(path, parent);

        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.refreshReplayList();
        this.updateFilmEditor();
    }

    private static List<Replay> replaysFromEntries(List<ReplayListEntry> entries)
    {
        List<Replay> out = new ArrayList<>();

        for (ReplayListEntry e : entries)
        {
            if (e.isReplay())
            {
                out.add(e.replay);
            }
        }

        return out;
    }

    /**
     * Ensure the replay row is visible and selected (expands its category if needed).
     */
    public void scrollToReplay(Replay replay)
    {
        this.scrollToReplay(replay, true);
    }

    /**
     * Ensure the replay row is visible and selected (expands its category if
     * needed). Optionally skips the selection callback to avoid re-entering
     * replay switching logic while syncing the list UI.
     */
    public void scrollToReplay(Replay replay, boolean notifySelection)
    {
        if (replay == null)
        {
            return;
        }

        String cat = Replay.normalizeCategory(replay.category.get());

        if (!cat.isEmpty())
        {
            this.expandTo(cat);
        }

        this.refreshReplayList();

        for (int i = 0; i < this.list.size(); i++)
        {
            ReplayListEntry e = this.list.get(i);

            if (e.isReplay() && e.replay == replay)
            {
                if (notifySelection)
                {
                    this.pick(i);
                }
                else
                {
                    this.setIndex(i);
                }

                this.scroll.setScroll(i * this.scroll.scrollItemSize);

                return;
            }
        }
    }

    private void restoreReplaySelection(List<Replay> replays)
    {
        this.restoreReplaySelection(replays, true);
    }

    private void restoreReplaySelection(List<Replay> replays, boolean notify)
    {
        this.current.clear();
        Film film = this.panel.getData();

        for (Replay r : replays)
        {
            if (film == null || !film.replays.getList().contains(r))
            {
                continue;
            }

            ReplayListEntry selected = ReplayListEntry.replay(r);

            for (int i = 0; i < this.list.size(); i++)
            {
                ReplayListEntry e = this.list.get(i);

                if (e.isReplay() && e.replay == r)
                {
                    selected = e;

                    break;
                }
            }

            /* Folding changes the rows, not the pick. Keep the replay in the same object
             * selection so plain/Ctrl/Shift clicks and clearing still share one owner. */
            this.selection.add(selected, null);
        }

        if (notify)
        {
            this.fireCallback();
        }
    }

    @Override
    public void setCurrent(List<ReplayListEntry> entries)
    {
        this.restoreReplaySelection(replaysFromEntries(entries), false);
    }

    @Override
    protected List<ReplayListEntry> selected()
    {
        return new ArrayList<>(this.selection.getItems());
    }

    @Override
    public boolean isDeselected()
    {
        return this.selection.isEmpty();
    }

    public List<Replay> getSelectedReplays()
    {
        List<Replay> out = new ArrayList<>();
        Film film = this.panel.getData();

        for (ReplayListEntry entry : this.selection.getItems())
        {
            if (entry.isReplay() && film != null && film.replays.getList().contains(entry.replay))
            {
                out.add(entry.replay);
            }
        }

        return out;
    }

    public Replay getSelectedReplayFirst()
    {
        List<Replay> selected = this.getSelectedReplays();

        return selected.isEmpty() ? null : selected.get(0);
    }

    public boolean hasReplaySelection()
    {
        return this.getSelectedReplayFirst() != null;
    }

    /**
     * Selected replays in Film order, including picks inside collapsed folders.
     */
    private List<Replay> getSelectedReplaysInFilmOrder()
    {
        List<Replay> out = new ArrayList<>();
        List<Replay> selected = this.getSelectedReplays();
        Film film = this.panel.getData();

        if (film == null)
        {
            return out;
        }

        for (Replay replay : film.replays.getList())
        {
            if (selected.contains(replay))
            {
                out.add(replay);
            }
        }

        return out;
    }

    /**
     * Replay index in the Film, independent of folder rows and filtering.
     */
    private int getFilmReplayIndex(Replay replay)
    {
        Film film = this.panel.getData();

        return film == null ? -1 : CollectionUtils.getIndex(film.replays.getList(), replay);
    }

    /**
     * Global index of the first selected replay in {@link Film#replays}, or {@code -1}.
     */
    public int getGlobalReplayIndex()
    {
        Replay r = this.getSelectedReplayFirst();
        Film film = this.panel.getData();

        if (r == null || film == null)
        {
            return -1;
        }

        return CollectionUtils.getIndex(film.replays.getList(), r);
    }

    /**
     * Top Y of a currently visible Replay row for collaboration overlays.
     * Category rows, filtering, collapsing and scroll offset are resolved by
     * this list, so the overlay never treats a global Film index as a UI row.
     */
    public int getReplayRowY(Replay replay)
    {
        if (replay == null)
        {
            return Integer.MIN_VALUE;
        }

        for (int visibleIndex = 0; visibleIndex < this.list.size(); visibleIndex++)
        {
            ReplayListEntry entry = this.getElementAt(visibleIndex);

            if (entry == null)
            {
                break;
            }

            if (entry.isReplay() && entry.replay == replay)
            {
                return this.area.y + visibleIndex * this.scroll.scrollItemSize
                    - (int) this.scroll.getScroll();
            }
        }

        return Integer.MIN_VALUE;
    }

    @Override
    public void filter(String filter)
    {
        this.replaySearch = filter.toLowerCase(Locale.ROOT);
        this.refreshReplayList();
    }

    public void refreshReplayList()
    {
        Film film = this.panel.getData();
        if (film == null)
        {
            this.current.clear();
            super.filter("");
            this.setList(new ArrayList<>());
            return;
        }
        List<Replay> keep = new ArrayList<>(this.getSelectedReplays());
        List<ReplayListEntry> entries = new ArrayList<>();
        this.addFolderRows(entries, film, film.replays.getList(), CategoryTree.byParent(this.collectCategoryPaths(film)), "", 0, 0, 0);
        this.setList(entries);
        super.filter("");
        super.filter(this.replaySearch);
        this.restoreReplaySelection(keep, false);
        this.update();
    }

    /**
     * All category folder names: explicit empty folders plus names used by replays.
     */


    private boolean hasReplayCategoryNames()
    {
        Film film = this.panel.getData();

        return film != null && !this.collectCategoryPaths(film).isEmpty();
    }

    /**
     * Update {@link Replay#category} and uncollapse the folder; does not refresh the list (for use before index-based ops).
     */
    private void assignReplayCategoryValue(Replay replay, String rawCategory)
    {
        String path = Replay.normalizeCategory(rawCategory);
        Film film = this.panel.getData();

        replay.category.set(path);

        if (!path.isEmpty() && film != null)
        {
            film.replayCategories.ensure(path);
            this.expandTo(path);
        }
    }

    private void openAddCategoryOverlay(String parent)
    {
        this.openCategoryNameOverlay(UIKeys.SCENE_REPLAYS_ADD_CATEGORY_TITLE, UIKeys.SCENE_REPLAYS_ADD_CATEGORY_DESCRIPTION, "", (name) ->
        {
            Film film = this.panel.getData();
            String path = CategoryPath.join(parent, name);

            if (film == null || path.isEmpty())
            {
                return;
            }

            film.replayCategories.ensure(path);
            this.expandTo(path);
            this.refreshReplayList();
            this.updateFilmEditor();
        });
    }

    /**
     * Second context menu: pick target category (replaces main replay context menu).
     */
    private void openMoveToCategoryContextMenu()
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        List<Replay> selected = new ArrayList<>(this.getSelectedReplays());

        if (selected.isEmpty())
        {
            return;
        }

        UIContext context = this.getContext();

        if (context == null)
        {
            return;
        }

        context.replaceContextMenu((add) ->
        {
            add.action(Icons.ARROW_DOWN, UIKeys.SCENE_REPLAYS_CATEGORY_NONE, () -> this.applyReplayCategory(selected, ""));
            add.action(Icons.ADD, UIKeys.SCENE_REPLAYS_CATEGORY_NEW, () -> this.openCategoryNameOverlay(
                UIKeys.SCENE_REPLAYS_ADD_CATEGORY_TITLE,
                UIKeys.SCENE_REPLAYS_ADD_CATEGORY_DESCRIPTION,
                "",
                (name) -> this.applyReplayCategory(selected, name)));

            for (String path : this.collectCategoryPaths(film))
            {
                add.action(Icons.FOLDER, IKey.raw(path), () -> this.applyReplayCategory(selected, path));
            }
        });
    }

    private void applyReplayCategory(List<Replay> selected, String rawCategory)
    {
        Film film = this.panel.getData();
        if (film == null) return;
        film.preNotify(IValueListener.FLAG_UNMERGEABLE);
        for (Replay replay : selected) this.assignReplayCategoryValue(replay, rawCategory);
        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.refreshReplayList();
        this.restoreReplaySelection(selected);
        this.updateFilmEditor();
    }

    @Override
    public boolean isSelected()
    {
        return this.hasReplaySelection();
    }

    @Override
    protected boolean sortElements()
    {
        return false;
    }

    @Override
    protected int indent(ReplayListEntry element)
    {
        return element.depth * INDENT;
    }

    @Override
    protected int indentStep()
    {
        return INDENT;
    }

    @Override
    protected Boolean branch(ReplayListEntry element)
    {
        Film film = this.panel.getData();

        return element.isFolder() && film != null ? film.replayCategories.isExpanded(element.folderPath) : null;
    }

    @Override
    protected void toggle(ReplayListEntry element)
    {
        Film film = this.panel.getData();

        if (element.isFolder() && film != null)
        {
            film.replayCategories.setExpanded(element.folderPath, !film.replayCategories.isExpanded(element.folderPath));
            this.refreshReplayList();
        }
    }

    @Override
    protected boolean pressItem(int index, UIContext context)
    {
        ReplayListEntry entry = this.visible().get(index);
        this.pressedFolder = null;

        if (!entry.isFolder())
        {
            return super.pressItem(index, context);
        }

        if (this.hitsArrow(entry, this.contentX(context)))
        {
            this.toggle(entry);
        }
        else if (Window.isCtrlPressed())
        {
            this.selectCategory(entry.folderPath);
        }
        else
        {
            this.pressedFolder = entry.folderPath;
            List<ReplayListEntry> payload = this.dragPayload(entry);

            if (payload != null && !payload.isEmpty())
            {
                this.drag.start(payload, context.mouseX, context.mouseY);
            }
        }

        this.cursor = index;

        if (context.menu == null)
        {
            this.fireCallback();
        }
        else
        {
            context.menu.runWithPreservedMouseCapture(this, this::fireCallback);
        }

        this.update();

        return true;
    }

    /** A folder folds only after its owned press finishes without becoming a drag. */
    @Override
    protected void release()
    {
        String pressed = this.pressedFolder;
        this.pressedFolder = null;
        UIContext context = this.getContext();
        ReplayListEntry clicked = null;

        if (pressed != null && !this.drag.isActive() && context != null && this.area.isInside(context))
        {
            int index = this.indexAt(this.contentX(context), this.contentY(context));
            List<ReplayListEntry> visible = this.visible();

            if (index >= 0 && index < visible.size())
            {
                ReplayListEntry entry = visible.get(index);

                if (entry.isFolder() && entry.folderPath.equals(pressed))
                {
                    clicked = entry;
                }
            }
        }

        super.release();

        if (clicked != null)
        {
            this.toggle(clicked);
            this.update();
        }
    }

    @Override
    protected void cancelGesture()
    {
        this.pressedFolder = null;
        super.cancelGesture();
    }

    /** Replay groups share the list's payload; a folder carries its entire subtree as one row. */
    @Override
    protected List<ReplayListEntry> dragPayload(ReplayListEntry item)
    {
        if (item.isFolder())
        {
            return this.sorting && !this.isFiltering() && !item.folderPath.isEmpty() ? List.of(item) : null;
        }

        return super.dragPayload(item);
    }

    @Override
    protected boolean acceptsDrop(ReplayListEntry element)
    {
        if (!element.isFolder())
        {
            return false;
        }

        List<ReplayListEntry> items = this.drag.getItems();

        return items.isEmpty() || !items.get(0).isFolder()
            || !CategoryPath.isInside(element.folderPath, items.get(0).folderPath);
    }

    @Override
    protected int dropInset(ReplayListEntry element)
    {
        return ROW_PADDING + this.indent(element);
    }

    @Override
    protected void onDrop(Object target, List<ReplayListEntry> items)
    {
        if (!(target instanceof ReplayListEntry folder) || !folder.isFolder() || items.isEmpty())
        {
            return;
        }

        ReplayListEntry first = items.get(0);

        if (first.isFolder())
        {
            this.moveCategory(first.folderPath, folder.folderPath, "");
        }
        else
        {
            this.applyReplayCategory(this.draggedReplays(items), folder.folderPath);
        }
    }

    @Override
    protected void reorder(List<ReplayListEntry> items, int insertion)
    {
        if (items.isEmpty())
        {
            return;
        }

        ReplayListEntry first = items.get(0);

        if (first.isFolder())
        {
            this.moveCategoryToSlot(first.folderPath, insertion);

            return;
        }

        Film film = this.panel.getData();
        List<Replay> moving = this.draggedReplays(items);

        if (film == null || moving.isEmpty())
        {
            return;
        }

        String category = this.slotCategory(insertion);
        Replay above = this.neighbourInCategory(insertion - 1, -1, category, moving);
        Replay below = this.neighbourInCategory(insertion, 1, category, moving);
        List<Replay> all = film.replays.getAllTyped();

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);

        for (Replay replay : moving)
        {
            int index = CollectionUtils.getIndex(all, replay);

            if (index >= 0)
            {
                all.remove(index);
            }

            this.assignReplayCategoryValue(replay, category);
        }

        int target = above != null ? CollectionUtils.getIndex(all, above) + 1
            : below != null ? CollectionUtils.getIndex(all, below) : all.size();

        all.addAll(MathUtils.clamp(target, 0, all.size()), moving);
        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.refreshReplayList();
        this.restoreReplaySelection(moving);
        this.updateFilmEditor();
    }

    private List<Replay> draggedReplays(List<ReplayListEntry> items)
    {
        return replaysFromEntries(this.inViewOrder(items));
    }

    /** Compatibility for callers that expressed a move as two row indices. */
    @Override
    protected void handleSwap(int from, int to)
    {
        if (this.exists(from) && this.exists(to))
        {
            ReplayListEntry entry = this.list.get(from);
            List<ReplayListEntry> items = this.selection.contains(entry)
                ? new ArrayList<>(this.selection.getItems()) : List.of(entry);

            this.reorder(items, to);
        }
    }

    private void moveCategoryToSlot(String path, int insertion)
    {
        String parent = "";
        String before = "";

        if (insertion >= 0 && insertion < this.list.size())
        {
            ReplayListEntry at = this.list.get(insertion);

            if (at.isFolder())
            {
                if (at.folderPath.equals(path))
                {
                    return;
                }

                parent = CategoryPath.parent(at.folderPath);
                before = at.folderPath;
            }
            else
            {
                parent = Replay.normalizeCategory(at.replay.category.get());
            }
        }

        this.moveCategory(path, parent, before);
    }

    private void moveCategory(String path, String parent, String before)
    {
        Film film = this.panel.getData();

        if (film == null || path.isEmpty() || CategoryPath.isInside(parent, path))
        {
            return;
        }

        String moved = CategoryPath.join(parent, CategoryPath.name(path));

        if (!moved.equals(path) && this.collectCategoryPaths(film).contains(moved))
        {
            return;
        }

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);

        if (!moved.equals(path))
        {
            for (Replay replay : film.replays.getList())
            {
                String category = Replay.normalizeCategory(replay.category.get());

                if (CategoryPath.isInside(category, path))
                {
                    replay.category.set(CategoryPath.reparent(category, path, moved));
                }
            }

            film.replayCategories.renameSubtree(path, moved);
        }

        film.replayCategories.moveBefore(moved, before.equals(path) ? "" : before);
        this.expandTo(moved);
        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.refreshReplayList();
        this.updateFilmEditor();
    }

    private String slotCategory(int insertion)
    {
        if (insertion >= this.list.size())
        {
            return "";
        }

        ReplayListEntry at = this.list.get(insertion);

        if (at.isReplay())
        {
            return Replay.normalizeCategory(at.replay.category.get());
        }

        ReplayListEntry above = insertion > 0 ? this.list.get(insertion - 1) : null;

        return above == null ? "" : above.isReplay()
            ? Replay.normalizeCategory(above.replay.category.get()) : above.folderPath;
    }

    private Replay neighbourInCategory(int from, int step, String category, List<Replay> moving)
    {
        for (int i = from; i >= 0 && i < this.list.size(); i += step)
        {
            ReplayListEntry entry = this.list.get(i);

            if (entry.isReplay() && CollectionUtils.getIndex(moving, entry.replay) < 0
                && Replay.normalizeCategory(entry.replay.category.get()).equals(category))
            {
                return entry.replay;
            }
        }

        return null;
    }

    private void pasteToReplays(MapType data)
    {
        List<Replay> selectedReplays = this.getSelectedReplays();

        if (data == null)
        {
            return;
        }

        Map<String, UIKeyframes.PastedKeyframes> parsedKeyframes = UIKeyframes.parseKeyframes(data);

        if (parsedKeyframes.isEmpty())
        {
            return;
        }

        UINumberOverlayPanel offsetPanel = new UINumberOverlayPanel(UIKeys.SCENE_REPLAYS_CONTEXT_PASTE_KEYFRAMES_TITLE, UIKeys.SCENE_REPLAYS_CONTEXT_PASTE_KEYFRAMES_DESCRIPTION, (n) ->
        {
            UIContext context = this.getContext();
            float tick = this.panel.getKeyframeCursor(context == null ? 0F : context.getTransition());

            for (Replay replay : selectedReplays)
            {
                int randomOffset = (int) (n.intValue() * Math.random());

                for (Map.Entry<String, UIKeyframes.PastedKeyframes> entry : parsedKeyframes.entrySet())
                {
                    String id = entry.getKey();
                    UIKeyframes.PastedKeyframes pastedKeyframes = entry.getValue();
                    KeyframeChannel channel = (KeyframeChannel) replay.keyframes.get(id);

                    if (channel == null || channel.getFactory() != pastedKeyframes.factory)
                    {
                        channel = replay.properties.getOrCreate(replay.form.get(), id);
                    }

                    float min = Integer.MAX_VALUE;

                    for (Keyframe kf : pastedKeyframes.keyframes)
                    {
                        min = Math.min(kf.getTick(), min);
                    }

                    for (Keyframe kf : pastedKeyframes.keyframes)
                    {
                        float finalTick = tick + (kf.getTick() - min) + randomOffset;
                        int idx = channel.insert(finalTick, kf.getValue());
                        Keyframe inserted = channel.get(idx);

                        inserted.copy(kf);
                        inserted.setTick(finalTick);
                    }

                    channel.sort();
                }
            }
        });

        UIOverlay.addOverlay(this.getContext(), offsetPanel);
    }

    private void openRandomTexturesOverlay()
    {
        List<Replay> selected = new ArrayList<>(this.getSelectedReplays());

        if (selected.size() < 2)
        {
            return;
        }

        UIFolderOverlayPanel panel = new UIFolderOverlayPanel(UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_TITLE, UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_DESCRIPTION, (folder) ->
        {
            this.applyRandomTextures(folder, selected, this.getContext());
        }).confirmLabel(UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_APPLY);

        UIOverlay.addOverlay(this.getContext(), panel, 320, 0.8F);
    }

    private void applyRandomTextures(Link folder, List<Replay> replays, UIContext context)
    {
        if (folder == null || folder.source.isEmpty())
        {
            context.notifyError(UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_ERROR);

            return;
        }

        List<Link> textures = this.collectTextures(folder);

        if (textures.isEmpty())
        {
            context.notifyError(UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_ERROR);

            return;
        }

        int applied = 0;
        Random random = new Random();

        for (Replay replay : replays)
        {
            Form form = replay.form.get();

            if (form == null)
            {
                continue;
            }

            Form copy = FormUtils.copy(form);
            BaseValue property = FormUtils.getProperty(copy, "texture");

            if (property instanceof ValueLink valueLink)
            {
                valueLink.set(textures.get(random.nextInt(textures.size())));
                replay.form.set(copy);
                applied += 1;
            }
        }

        if (applied == 0)
        {
            context.notifyError(UIKeys.SCENE_REPLAYS_RANDOM_TEXTURES_ERROR);

            return;
        }

        this.updateFilmEditor();
    }

    private List<Link> collectTextures(Link folder)
    {
        List<Link> textures = new ArrayList<>();

        for (Link link : BBSMod.getProvider().getLinksFromPath(folder, false))
        {
            if (!link.path.endsWith("/") && link.path.endsWith(".png"))
            {
                textures.add(link);
            }
        }

        return textures;
    }

    private void processReplays()
    {
        Replay first = this.getSelectedReplayFirst();

        if (first == null)
        {
            return;
        }

        UIProcessReplaysPanel panel = new UIProcessReplaysPanel(this.panel, this.getSelectedReplaysInFilmOrder(),
            this::getFilmReplayIndex, this::updateFilmEditor);

        UIOverlay.addOverlay(this.getContext(), panel, 320, 320);
    }

    private void offsetTimeReplays()
    {
        Replay first = this.getSelectedReplayFirst();

        if (first == null)
        {
            return;
        }

        UITextbox tick = new UITextbox((t) -> LAST_OFFSET = t);
        UIConfirmOverlayPanel panel = new UIConfirmOverlayPanel(UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME_TITLE, UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME_DESCRIPTION, (b) ->
        {
            if (b)
            {
                MathBuilder builder = new MathBuilder();
                int min = Integer.MAX_VALUE;

                builder.register("i");
                builder.register("o");

                IExpression parse = null;

                try
                {
                    parse = builder.parse(tick.getText());
                }
                catch (Exception e)
                {}

                List<Replay> selected = this.getSelectedReplaysInFilmOrder();

                for (Replay replay : selected)
                {
                    int index = this.getFilmReplayIndex(replay);

                    if (index < 0)
                    {
                        continue;
                    }

                    min = Math.min(min, index);
                }

                if (min == Integer.MAX_VALUE)
                {
                    return;
                }

                for (Replay replay : selected)
                {
                    int index = this.getFilmReplayIndex(replay);

                    if (index < 0)
                    {
                        continue;
                    }

                    builder.variables.get("i").set(index);
                    builder.variables.get("o").set(index - min);

                    float tickv = parse == null ? 0F : (float) parse.doubleValue();

                    BaseValue.edit(replay, (r) -> r.shift(tickv));
                }
            }
        });

        tick.setText(LAST_OFFSET);
        tick.tooltip(UIKeys.SCENE_REPLAYS_CONTEXT_OFFSET_TIME_EXPRESSION_TOOLTIP);
        tick.relative(panel.confirm).y(-1F, -5).w(1F).h(20);

        panel.confirm.w(1F, -10);
        panel.content.add(tick);

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    public void copyReplay()
    {
        Window.setClipboard(this.replaysToData(), "_CopyReplay");
    }

    /**
     * Serialize the selected replays into the shared {@code {"replays": [...]}} format
     * used by both clipboard copy/paste and presets.
     */
    private MapType replaysToData()
    {
        MapType replays = new MapType();
        ListType replayList = new ListType();

        replays.put("replays", replayList);

        for (Replay replay : this.getSelectedReplays())
        {
            replayList.add(replay.toData());
        }

        return replays;
    }

    /**
     * Open the presets overlay for replays (save the current selection, or load a preset into the film).
     */
    public void openReplayPresets()
    {
        UIContext context = this.getContext();

        if (context != null)
        {
            this.presetController.openPresets(context, context.mouseX, context.mouseY);
        }
    }

    /**
     * Insert a {@code {"replays": [...]}} clipboard payload or preset into the film.
     *
     * <p>Clipboard replays and presets are read outside any film document, so no save version gates
     * them and {@code Film.fromData} never sees them — but the pasted map still carries whatever
     * references the source film wrote. Those references are answered in two different places, one
     * per kind of reference: a legacy positional index is converted by {@link Replay#fromData},
     * which runs for every element below with an empty replay list (a bare replay carries no source
     * film order, so a cross-film index cannot be resolved and fails closed to "no target"); a
     * reference that is <em>already</em> a stable id is a membership question about <em>this</em>
     * film and is answered by the prune call above the loop, against the ids the film holds right
     * now.
     *
     * <p>Neither question belongs to a second copy of the conversion written out at this call site
     * — that is how the two would drift apart. The prune call is a call into the same walk
     * {@code Film.fromData} runs, not a reimplementation of it.
     */
    public void pasteReplay(MapType data)
    {
        Film film = this.panel.getData();
        ListType replays = data.getList("replays");

        /* The film's own ids, taken before the first insert: a within-film copy carries ids that
         * are in here, which is what keeps it pointing at the replay it was copied from. */
        List<String> filmReplayIds = new ArrayList<>();

        for (Replay existing : film.replays.getAllTyped())
        {
            filmReplayIds.add(existing.getId());
        }

        Replay last = null;

        for (BaseType replayType : replays)
        {
            Replay replay = film.replays.addReplay();

            /* A stable id that addresses a replay this film does not have would send
             * ActionTarget.resolve down its scoped branch and lose the uuid fallback. */
            if (replayType.isMap())
            {
                FilmStableIds.pruneDanglingReplayReferences(replayType.asMap(), filmReplayIds);
            }

            /* The legacy index conversion happens inside Replay.fromData; do not duplicate it. */
            BaseValue.edit(replay, (r) -> r.fromData(replayType));
            replay.category.set("");

            last = replay;
        }

        if (last != null)
        {
            this.refreshReplayList();
            this.update();
            this.panel.replayEditor.setReplay(last);
            this.scrollToReplay(last);
            this.updateFilmEditor();
            this.saveFilm();
        }
    }

    public void openFormEditor(ValueForm form, boolean editing, Consumer<Form> consumer)
    {
        UIElement target = this.panel;

        if (this.getRoot() != null)
        {
            target = this.getParentContainer();
        }

        UIFormPalette palette = UIFormPalette.open(target, editing, form.get(), (f) ->
        {
            for (Replay replay : this.getSelectedReplays())
            {
                replay.form.set(FormUtils.copy(f));
            }

            this.updateFilmEditor();

            if (consumer != null)
            {
                consumer.accept(f);
            }
            else if (this.formConsumer != null)
            {
                this.formConsumer.accept(f);
            }
        });

        palette.updatable();
    }

    public void addReplay()
    {
        Level world = Minecraft.getInstance().level;
        Camera camera = this.panel.getCamera();

        BlockHitResult blockHitResult = RayTracing.rayTrace(world, camera, 64F);
        Vec3 p = blockHitResult.getLocation();
        Vector3d position = new Vector3d(p.x, p.y, p.z);

        if (blockHitResult.getType() == HitResult.Type.MISS)
        {
            position.set(camera.getLookDirection()).mul(5F).add(camera.position);
        }

        this.addReplay(position, camera.rotation.x, camera.rotation.y + MathUtils.PI);
    }

    private void fromCamera(String cameraId, int duration)
    {
        Film film = this.panel.getData();
        Replay replay = ReplayFactory.fromCamera(film, cameraId, duration, this.panel.getController().getEntities());

        this.refreshReplayList();
        this.update();
        this.panel.replayEditor.setReplay(replay);
        this.scrollToReplay(replay);
        this.updateFilmEditor();
        this.saveFilm();

        this.openFormEditor(replay.form, false, null);
    }

    private void fromModelBlock()
    {
        /* The same list the model block panel shows, so a block is picked here by the
         * face it wears there instead of by a line of coordinates. */
        UIModelBlockEntityList list = new UIModelBlockEntityList(null);
        UISearchList<ModelBlockEntity> search = new UISearchList<>(list);
        UIConfirmOverlayPanel panel = new UIConfirmOverlayPanel(UIKeys.SCENE_REPLAYS_CONTEXT_FROM_MODEL_BLOCK_TITLE, UIKeys.SCENE_REPLAYS_CONTEXT_FROM_MODEL_BLOCK_DESCRIPTION, (b) ->
        {
            ModelBlockEntity modelBlock = b ? list.getCurrentFirst() : null;

            if (modelBlock != null)
            {
                this.fromModelBlock(modelBlock);
            }
        });

        list.setBlocks(BBSRendering.capturedModelBlocks);
        list.background();

        search.label(UIKeys.GENERAL_SEARCH);
        search.relative(panel.confirm).y(-5).w(1F).h(UIModelBlockEntityList.ROW * 7 + 20).anchor(0F, 1F);

        panel.confirm.w(1F, -10);
        panel.content.add(search);

        UIOverlay.addOverlay(this.getContext(), panel, 240, 300);
    }

    private void fromModelBlock(ModelBlockEntity modelBlock)
    {
        Film film = this.panel.getData();
        Replay replay = ReplayFactory.fromModelBlock(film, modelBlock);

        this.refreshReplayList();
        this.update();
        this.panel.replayEditor.setReplay(replay);
        this.scrollToReplay(replay);
        this.updateFilmEditor();
        this.saveFilm();
    }

    /**
     * The wand's region as a replay: saved as a structure, cleared out of the world, and added as a
     * form standing exactly where the blocks did. Destructive, so it asks first — and the message
     * names the command that puts the build back, because Minecraft has no undo for this.
     */
    private void cutSelectionIntoReplay()
    {
        Film film = this.panel.getData();

        if (film == null || !StructureSelection.isReady())
        {
            return;
        }

        String id = StructureCut.nextPath(film.getId());
        BlockPos min = StructureSelection.getMin();
        BlockPos max = StructureSelection.getMax();
        BlockPos size = StructureSelection.getSize();
        IKey message = UIKeys.STRUCTURE_CUT_CONFIRM.format(String.valueOf(StructureSelection.getVolume()), id);

        UIOverlay.addOverlay(this.getContext(), new UIConfirmOverlayPanel(UIKeys.STRUCTURE_CUT_TITLE, message, (confirmed) ->
        {
            if (confirmed)
            {
                StructureCut.request(id, min, max, (ok) ->
                {
                    if (ok)
                    {
                        this.addStructureReplay(id, min, size);
                    }
                });
            }
        }), 300, 140);
    }

    /** The cut region's form, dropped in at the very spot it was cut from. */
    private void addStructureReplay(String id, BlockPos min, BlockPos size)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        Replay replay = ReplayFactory.fromStructure(film, id, min, size);

        this.refreshReplayList();
        this.update();
        this.panel.replayEditor.setReplay(replay);
        this.scrollToReplay(replay);
        this.updateFilmEditor();
        this.saveFilm();
    }

    public void addReplay(Vector3d position, float pitch, float yaw)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        Replay replay = ReplayFactory.atPosition(film, position, pitch, yaw);

        this.refreshReplayList();
        this.update();
        this.panel.replayEditor.setReplay(replay);
        this.scrollToReplay(replay);
        this.updateFilmEditor();
        this.saveFilm();

        this.openFormEditor(replay.form, false, null);
    }

    protected void updateFilmEditor()
    {
        this.panel.getController().createEntities();
        this.panel.replayEditor.updateChannelsList();
    }

    private void saveFilm()
    {
        if (this.panel.getData() != null)
        {
            this.panel.forceSave();
        }
    }

    public void dupeReplay()
    {
        if (!this.hasReplaySelection())
        {
            return;
        }

        Replay last = null;

        for (Replay replay : this.getSelectedReplays())
        {
            Film film = this.panel.getData();
            Replay newReplay = film.replays.addReplay();

            newReplay.copy(replay);

            last = newReplay;
        }

        if (last != null)
        {
            this.refreshReplayList();
            this.update();
            this.panel.replayEditor.setReplay(last);
            this.scrollToReplay(last);
            this.updateFilmEditor();
            this.saveFilm();
        }

    }

    public void removeReplay()
    {
        if (!this.hasReplaySelection())
        {
            return;
        }

        Film film = this.panel.getData();
        List<Replay> removing = new ArrayList<>(this.getSelectedReplays());
        Replay focus = removing.get(0);
        int globalFocus = CollectionUtils.getIndex(film.replays.getList(), focus);

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);

        for (Replay replay : removing)
        {
            film.replays.remove(replay);
        }

        /* Deleting a replay no longer renumbers anybody: the survivors keep their ids, and a
         * reference to the deleted one stays dangling on purpose rather than sliding onto whichever
         * replay moved into its slot. */
        film.postNotify(IValueListener.FLAG_UNMERGEABLE);

        List<Replay> remaining = film.replays.getList();

        this.refreshReplayList();
        this.update();

        if (remaining.isEmpty())
        {
            this.panel.replayEditor.setReplay(null);
        }
        else
        {
            int idx = MathUtils.clamp(globalFocus, 0, remaining.size() - 1);
            Replay next = remaining.get(idx);

            this.panel.replayEditor.setReplay(next);
            this.scrollToReplay(next);
        }

        this.updateFilmEditor();
        this.saveFilm();
    }

    @Override
    protected String elementToString(UIContext context, int i, ReplayListEntry element)
    {
        if (element.isFolder())
        {
            return element.folderName();
        }

        int w = this.area.w - 20 - this.indent(element);

        return context.batcher.getFont().limitToWidth(element.replay.getName(), w);
    }

    @Override
    protected int rowColor(ReplayListEntry element)
    {
        return element.color;
    }

    @Override
    protected String elementToFilterString(UIContext context, int index, ReplayListEntry element)
    {
        return element.isFolder() ? element.folderPath : element.replay.getName() + " " + element.replay.category.get();
    }

    @Override
    protected void renderElementPart(UIContext context, ReplayListEntry element, int i, int x, int y, boolean hover, boolean selected)
    {
        int rowHeight = this.scroll.scrollItemSize;
        int textY = y + (rowHeight - context.batcher.getFont().getHeight()) / 2;

        if (element.isFolder())
        {
            int iconX = x + this.rowContentX(element) + ARROW_SLOT;
            int textX = x + iconRowTextX(this.rowContentX(element));

            this.renderTreeGuides(context, x, y, element.depth, element.lines, element.last, iconX);
            this.renderArrow(context, element, x, y, hover || selected);
            context.batcher.icon(Icons.FOLDER, RowStyle.iconColor(hover || selected), iconX, y + (rowHeight - 16) / 2);
            context.batcher.textShadow(this.elementToString(context, i, element), textX, textY, RowStyle.textColor(hover || selected));

            /* How much is in there, which a closed folder cannot say any other way. */
            String count = String.valueOf(element.count);

            context.batcher.textShadow(count, this.area.ex() - ROW_PADDING - context.batcher.getFont().getWidth(count), textY, Colors.GRAY);

            return;
        }

        Replay replay = element.replay;

        this.renderTreeGuides(context, x, y, element.depth, element.lines, element.last, x + this.rowContentX(element));

        if (replay.enabled.get())
        {
            super.renderElementPart(context, element, i, x, y, hover, selected);
        }
        else
        {
            context.batcher.textShadow(this.elementToString(context, i, element), x + this.rowContentX(element), textY, RowStyle.textColor(hover || selected, Colors.GRAY));
        }

        Form form = replay.form.get();

        if (form != null)
        {
            int formX = this.area.x + this.area.w - 30;
            int my = y + rowHeight / 2;
            int formY = my - PREVIEW / 2;

            if (BBSSettings.listModelPreview.get())
            {
                context.batcher.clip(formX, y, PREVIEW, rowHeight, context);

                FormUtilsClient.renderUI(form, context, formX, formY, formX + PREVIEW, formY + PREVIEW);

                context.batcher.unclip(context);
            }

            if (replay.fp.get())
            {
                context.batcher.outlinedIcon(Icons.ARROW_UP, formX, my, 0.5F, 0.5F);
            }
        }
    }

    private List<Replay> replaysIn(List<Replay> all, String path)
    {
        List<Replay> replays = new ArrayList<>();

        for (Replay replay : all)
        {
            if (path.equals(Replay.normalizeCategory(replay.category.get())))
            {
                replays.add(replay);
            }
        }

        return replays;
    }

    private List<String> collectCategoryPaths(Film film)
    {
        List<String> used = new ArrayList<>();

        for (Replay replay : film.replays.getList())
        {
            used.add(replay.category.get());
        }

        return CategoryTree.paths(film.replayCategories, used);
    }

    private void expandTo(String path)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        for (String walk = path; !walk.isEmpty(); walk = CategoryPath.parent(walk))
        {
            film.replayCategories.setExpanded(walk, true);
        }
    }

    private void expandSubtree(String path)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        this.expandTo(path);

        for (String other : this.collectCategoryPaths(film))
        {
            if (CategoryPath.isInside(other, path))
            {
                film.replayCategories.setExpanded(other, true);
            }
        }
    }

    private int countReplays(String path)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return 0;
        }

        int count = 0;

        for (Replay replay : film.replays.getList())
        {
            if (CategoryPath.isInside(Replay.normalizeCategory(replay.category.get()), path))
            {
                count += 1;
            }
        }

        return count;
    }

    private boolean isCategoryEnabled(String path)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return false;
        }

        for (Replay replay : film.replays.getList())
        {
            if (replay.enabled.get() && CategoryPath.isInside(Replay.normalizeCategory(replay.category.get()), path))
            {
                return true;
            }
        }

        return false;
    }

    private void setCategoryEnabled(String path, boolean enabled)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return;
        }

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);
        for (Replay replay : film.replays.getList())
        {
            if (CategoryPath.isInside(Replay.normalizeCategory(replay.category.get()), path))
            {
                replay.enabled.set(enabled);
            }
        }

        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.updateFilmEditor();
    }

    private void openCategoryEditMenu(String path)
    {
        Film film = this.panel.getData();
        UIContext context = this.getContext();

        if (film == null || context == null)
        {
            return;
        }

        Category category = film.replayCategories.getByPath(path);

        context.replaceContextMenu(new UIReplayCategoryContextMenu(this, path, category == null ? 0 : category.color.get()));
    }

    String applyCategoryEdit(String path, String name, int color)
    {
        Film film = this.panel.getData();

        if (film == null)
        {
            return path;
        }

        film.preNotify(IValueListener.FLAG_UNMERGEABLE);
        String renamed = CategoryPath.join(CategoryPath.parent(path), name);

        if (renamed.isEmpty() || this.collectCategoryPaths(film).contains(renamed))
        {
            renamed = path;
        }

        if (!renamed.equals(path))
        {
            for (Replay replay : film.replays.getList())
            {
                String category = Replay.normalizeCategory(replay.category.get());

                if (CategoryPath.isInside(category, path))
                {
                    replay.category.set(CategoryPath.reparent(category, path, renamed));
                }
            }

            film.replayCategories.renameSubtree(path, renamed);
        }

        film.replayCategories.ensure(renamed).color.set(color);
        this.expandTo(renamed);
        film.postNotify(IValueListener.FLAG_UNMERGEABLE);
        this.refreshReplayList();
        this.updateFilmEditor();

        return renamed;
    }

    private void openCategoryNameOverlay(IKey title, IKey description, String initial, Consumer<String> consumer)
    {
        UITextbox box = new UITextbox(1000, (text) -> {});

        box.setText(initial);
        box.placeholder(UIKeys.SCENE_REPLAYS_ADD_CATEGORY_PLACEHOLDER);

        UIConfirmOverlayPanel panel = new UIConfirmOverlayPanel(title, description, (ok) ->
        {
            if (ok)
            {
                consumer.accept(box.getText());
            }
        });

        box.relative(panel.confirm).y(-1F, -5).w(1F).h(20);
        panel.confirm.w(1F, -10);
        panel.content.add(box);

        UIOverlay.addOverlay(this.getContext(), panel);
    }

    private void addFolderRows(List<ReplayListEntry> entries, Film film, List<Replay> all, Map<String, List<String>> children, String parent, int depth, int lines, int color)
    {
        List<String> folders = children.getOrDefault(parent, Collections.emptyList());
        List<Replay> replays = this.replaysIn(all, parent);

        for (int i = 0; i < folders.size(); i++)
        {
            String path = folders.get(i);
            boolean last = i == folders.size() - 1 && replays.isEmpty();
            Category category = film.replayCategories.getByPath(path);
            /* A folder with no colour of its own wears the one it sits in, so a painted folder
             * marks everything under it rather than only its own replays. */
            int stripe = category == null || category.color.get() == 0 ? color : category.color.get();

            entries.add(ReplayListEntry.folder(path, depth, lines, last, this.countReplays(path), stripe));

            if (!this.replaySearch.isEmpty() || film.replayCategories.isExpanded(path))
            {
                this.addFolderRows(entries, film, all, children, path, depth + 1, childGuideLines(lines, depth, last), stripe);
            }
        }

        for (int i = 0; i < replays.size(); i++)
        {
            entries.add(ReplayListEntry.replay(replays.get(i), depth, lines, i == replays.size() - 1, color));
        }
    }

    private void selectCategory(String path)
    {
        this.expandSubtree(path);
        this.refreshReplayList();
        List<Replay> selected = new ArrayList<>();
        for (Replay replay : this.panel.getData().replays.getList())
        {
            if (CategoryPath.isInside(Replay.normalizeCategory(replay.category.get()), path)) selected.add(replay);
        }
        this.restoreReplaySelection(selected);
    }
}
