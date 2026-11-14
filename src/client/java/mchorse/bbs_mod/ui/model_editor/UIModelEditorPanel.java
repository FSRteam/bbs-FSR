package mchorse.bbs_mod.ui.model_editor;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.cubic.data.animation.Animation;
import mchorse.bbs_mod.cubic.data.animation.AnimationPart;
import mchorse.bbs_mod.cubic.model.ModelManager;
import mchorse.bbs_mod.cubic.model.config.ModelConfig;
import mchorse.bbs_mod.cubic.data.model.Model;
import mchorse.bbs_mod.cubic.data.model.ModelGroup;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.ContentType;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.dashboard.panels.UIDataDashboardPanel;
import mchorse.bbs_mod.ui.dashboard.panels.overlay.UICRUDOverlayPanel;
import mchorse.bbs_mod.ui.dashboard.panels.tabs.DataTab;
import mchorse.bbs_mod.ui.film.utils.undo.UIUndoHistoryOverlay;
import mchorse.bbs_mod.ui.forms.editors.UIFormUndoHandler;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.UIScrollView;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UIDraggable;
import mchorse.bbs_mod.ui.framework.elements.utils.UISplitter;
import mchorse.bbs_mod.ui.framework.elements.utils.UIUndoKeys;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Owns open model tabs, the shared preview and undo; configuration and geometry live in their editors.
 * FSR keeps unsaved model drafts when a write fails, and reuses the preview's solved pose for thumbnails.
 */
public class UIModelEditorPanel extends UIDataDashboardPanel<ModelConfig>
{
    /** Kept for callers that need the general settings page. */
    public UIScrollView general;
    public UIModelEditorRenderer renderer;
    public UIDraggable splitter;
    private UIModelConfigEditor configEditor;
    private UIModelGeometryEditor geometryEditor;
    private UIElement uvPane;
    private boolean geometryMode;
    private boolean modelDirty;
    private final List<String[]> renames = new ArrayList<>();
    private record ModelDraft(ModelInstance instance, List<String[]> renames)
    {}
    private final Map<String, ModelDraft> drafts = new HashMap<>();
    private UIDraggable uvSplitter;
    private UISplitter settingsSize;
    private UISplitter uvSize;
    private int uvSplitWidth = 200;
    private UIIcon modelMode;
    private final ModelForm form = new ModelForm();
    private String pendingId;
    private int splitWidth = 200;
    private ModelInstance bound;
    private UIElement miniPreview;
    private UIIcon folderIcon;
    private UIIcon historyIcon;
    private UIIcon animationIcon;
    private UIFormUndoHandler undoHandler;
    private final Set<ModelConfig> hookedConfigs = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean preserveUndo;

    /** Compatibility names for callers of the panel before the configuration owner was extracted. */
    public enum ConfigPage
    {
        GENERAL(Icons.GEAR, UIKeys.FORMS_EDITORS_GENERAL),
        BONES(Icons.LIMB, UIKeys.MODEL_EDITOR_BONES),
        WELDS(Icons.LINK, UIKeys.MODEL_EDITOR_WELDS),
        ARMOR(Icons.ARMOR_CHESTPLATE, UIKeys.MODEL_EDITOR_ARMOR),
        ITEMS(Icons.HOTBAR, UIKeys.MODEL_EDITOR_ITEMS),
        FIRST_PERSON(Icons.LOOKING, UIKeys.MODEL_EDITOR_FIRST_PERSON),
        PROCEDURAL(Icons.PLAY, L10n.lang("bbs.ui.model_editor.procedural_tab")),
        POSES(Icons.POSE, UIKeys.MODEL_EDITOR_POSES);

        public final Icon icon;
        public final IKey label;

        ConfigPage(Icon icon, IKey label)
        {
            this.icon = icon;
            this.label = label;
        }
    }

    public UIModelEditorPanel(UIDashboard dashboard)
    {
        super(dashboard);

        this.enableTabs();

        this.renderer = new UIModelEditorRenderer()
            .target(() -> this.geometryMode ? this.geometryEditor.shownTarget() : this.configEditor.shownTarget())
            .onPick((bone, cube) -> this.geometryMode ? this.geometryEditor.selectPick(bone, cube) : this.configEditor.selectBone(bone))
            .outlines(() -> this.geometryMode ? this.geometryEditor.outlines() : List.of());
        this.renderer.form = this.form;
        this.configEditor = new UIModelConfigEditor(this);
        this.general = this.configEditor.generalPage();
        this.geometryEditor = new UIModelGeometryEditor(this);
        this.geometryEditor.setVisible(false);
        this.uvPane = new UIElement();
        this.uvPane.add(this.geometryEditor.uvPanel().full(this.uvPane));
        this.uvPane.setVisible(false);

        this.settingsSize = new UISplitter("model_editor.split", false, 220).fromEnd();
        this.settingsSize.measure(this.editor).range(160, () -> (float) (this.editor.area.w - 160)).onChange(() ->
        {
            this.splitWidth = this.settingsSize.getPixels();
            this.layoutPanes();
            this.resize();
        });
        this.splitter = this.settingsSize;
        this.splitWidth = this.settingsSize.getPixels();
        this.uvSize = new UISplitter("model_editor.uv_split", false, 200);
        this.uvSize.measure(this.editor).range(160, () -> (float) (this.editor.area.w - this.splitWidth - 160)).onChange(() ->
        {
            this.uvSplitWidth = this.uvSize.getPixels();
            this.layoutPanes();
            this.resize();
        });
        this.uvSplitter = this.uvSize;
        this.uvSplitWidth = this.uvSize.getPixels();
        this.uvSplitter.setVisible(false);

        this.layoutPanes();

        this.miniPreview = new UIElement()
        {
            @Override
            public void render(UIContext context)
            {
                int x1 = this.area.x;
                int y1 = this.area.y;
                int x2 = this.area.ex();
                int y2 = this.area.ey();

                context.batcher.box(x1, y1, x2, y2, BBSSettings.deepSurface());
                if (FormUtilsClient.getRenderer(UIModelEditorPanel.this.form) instanceof ModelFormRenderer modelRenderer)
                {
                    modelRenderer.renderPreviewThumbnail(
                        context,
                        x1,
                        y1,
                        x2,
                        y2,
                        UIModelEditorPanel.this.renderer,
                        UIModelEditorPanel.this.renderer.getEntity()
                    );
                }
                else
                {
                    FormUtilsClient.renderUI(UIModelEditorPanel.this.form, context, x1, y1, x2, y2);
                }
                context.batcher.outline(x1, y1, x2, y2, Colors.setA(Colors.WHITE, 0.2F));

                super.render(context);
            }

            /* It's a thumbnail — swallow clicks so they don't orbit the main viewport underneath. */
            @Override
            public boolean subMouseClicked(UIContext context)
            {
                return this.area.isInside(context);
            }
        };
        this.miniPreview.relative(this.renderer).x(1F, -6).y(6).wh(64, 64).anchor(1F, 0F);
        this.renderer.add(this.miniPreview);

        this.editor.add(this.configEditor, this.geometryEditor, this.uvPane, this.renderer, this.splitter, this.uvSplitter);
        UIIcon configMode = new UIIcon(Icons.GEAR, (button) -> this.showGeometry(false));
        configMode.tooltip(UIKeys.MODEL_EDITOR_OPEN_CONFIG_EDITOR);
        this.modelMode = new UIIcon(Icons.POSE, (button) -> this.showGeometry(true));
        this.modelMode.tooltip(UIKeys.MODEL_EDITOR_OPEN_MODEL_EDITOR);
        this.actions().editor(configMode, () -> !this.geometryMode).editor(this.modelMode, () -> this.geometryMode);

        this.openOverlay.tooltip(UIKeys.FORMS_EDITOR_MODEL_PICK_MODEL, Direction.BOTTOM);

        this.folderIcon = new UIIcon(Icons.FOLDER, (b) -> this.openModelFolder());
        this.folderIcon.tooltip(UIKeys.FORMS_CATEGORIES_CONTEXT_OPEN_MODEL_FOLDER, Direction.BOTTOM);
        this.actions().action(this.folderIcon);

        this.historyIcon = new UIIcon(Icons.UNDO, (b) -> this.openHistory());
        this.historyIcon.tooltip(UIKeys.MODEL_EDITOR_OPEN_HISTORY, Direction.BOTTOM);
        this.actions().action(this.historyIcon);
        this.animationIcon = new UIIcon(Icons.PLAY, (button) -> this.openAnimations());
        this.animationIcon.tooltip(UIKeys.MODEL_EDITOR_ANIMATION_PLAY, Direction.BOTTOM);
        this.actions().action(this.animationIcon);

        this.mountLanding();
        this.add(new UIUndoKeys(this::undo, this::redo).full(this));

        this.registerKeybinds();

        this.fill(null);
    }

    private void layoutPanes()
    {
        int available = this.editor.area.w;
        if (available > 0)
        {
            int minimum = Math.min(160, Math.max(1, available / (this.geometryMode ? 3 : 2)));
            this.splitWidth = MathUtils.clamp(this.splitWidth, minimum, available - minimum * (this.geometryMode ? 2 : 1));
            this.uvSplitWidth = MathUtils.clamp(this.uvSplitWidth, minimum, Math.max(minimum, available - this.splitWidth - minimum));
        }
        int uvWidth = this.geometryMode ? this.uvSplitWidth : 0;
        this.configEditor.relative(this.editor).x(1F, -this.splitWidth).y(0).w(this.splitWidth).h(1F);
        this.geometryEditor.relative(this.editor).x(1F, -this.splitWidth).y(0).w(this.splitWidth).h(1F);
        this.uvPane.relative(this.editor).x(0).y(0).w(uvWidth).h(1F);
        this.renderer.relative(this.editor).x(uvWidth).y(0).w(1F, -this.splitWidth - uvWidth).h(1F);
        this.splitter.relative(this.editor).x(1F, -this.splitWidth).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);
        this.uvSplitter.relative(this.editor).x(uvWidth).y(0.5F).w(6).h(40).anchor(0.5F, 0.5F);

        if (this.renderer.isFirstPerson())
        {
            Minecraft minecraft = Minecraft.getInstance();
            float aspect = minecraft.getWindow().getWidth() / (float) Math.max(1, minecraft.getWindow().getHeight());
            int roomW = Math.max(1, this.editor.area.w - this.splitWidth - uvWidth);
            int roomH = Math.max(1, this.editor.area.h);
            int width = roomW;
            int height = Math.round(width / aspect);

            if (height > roomH)
            {
                height = roomH;
                width = Math.round(height * aspect);
            }

            this.renderer.relative(this.editor).x(uvWidth + (roomW - width) / 2)
                .y((roomH - height) / 2).wh(width, height);
        }
    }

    @Override
    public void resize()
    {
        super.resize();
        if (this.uvSplitter != null)
        {
            this.layoutPanes();
            this.editor.resize();
        }
    }

    private void registerKeybinds()
    {
        IKey category = UIKeys.MODEL_EDITOR_TITLE;
        Supplier<Boolean> open = () -> this.data != null;

        this.keys().register(Keys.FILM_CONTROLLER_CYCLE_EDITORS, () -> this.showGeometry(!this.geometryMode)).active(open).category(category);
        this.keys().register(Keys.MODEL_EDITOR_FIND_BONE, this::findBone).active(open).category(category);
        this.keys().register(Keys.MODEL_EDITOR_OPEN_HISTORY, this::openHistory).active(open).category(category);
    }

    private void findBone()
    {
        this.showGeometry(false);
        this.configEditor.findBone();
    }

    public void selectConfigPage(ConfigPage page)
    {
        this.showGeometry(false);
        this.configEditor.selectPage(UIModelConfigEditor.Tab.valueOf(page.name()));
    }

    @Override
    public void render(UIContext context)
    {
        /* A fully solid dark backdrop over the whole panel so the dashboard background doesn't show through
         * the settings pane or behind the preview. deepSurface() is the same solid the mini preview uses. */
        context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.deepSurface());

        super.render(context);
    }

    @Override
    public ContentType getType()
    {
        return ContentType.MODELS;
    }

    @Override
    public Icon getTabIcon(DataTab tab)
    {
        return tab != null && tab.dataId == null ? Icons.SEARCH : Icons.POSE;
    }

    @Override
    public IKey getTitle()
    {
        return UIKeys.MODEL_EDITOR_TITLE;
    }

    @Override
    public IKey getCreateLabel()
    {
        return UIKeys.MODEL_EDITOR_LANDING_NEW;
    }

    @Override
    public IKey getListLabel()
    {
        return UIKeys.MODEL_EDITOR_LANDING_LIST;
    }

    @Override
    protected boolean shouldAutoOpenListOnFirstResize()
    {
        return false;
    }

    @Override
    public void requestData(String id)
    {
        ModelDraft draft = this.drafts.remove(id);
        if (draft != null)
        {
            this.bound = draft.instance();
            BBSModClient.getModels().models.put(id, this.bound);
            this.form.model.set(id);
            this.pendingId = null;
            this.fill(this.bound.config);
            this.modelDirty = true;
            this.renames.addAll(draft.renames());
            this.refresh();
            return;
        }
        this.pendingId = id;
        this.tryLoadPending();
    }

    @Override
    public void update()
    {
        super.update();

        this.tryLoadPending();
        this.checkReload();

        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
        }
    }

    /**
     * When the model's files change on disk (e.g. a bone deleted in Blockbench) the watchdog drops the old
     * {@link ModelInstance} and a fresh one loads under the same id. The preview follows it by id every frame,
     * but our settings widgets are static — built off {@link #bound} — so the bone lists keep the old bones
     * until re-entering the tab. Detect the swap and re-bind + refill so they track the reload live.
     *
     * <p>Gated on a model actually being open here ({@code data != null}): switching to a new tab first
     * auto-saves the model we're leaving, whose {@code config.json} write trips the same watchdog reload —
     * without this gate that reload would yank the old model back over the fresh tab's empty picker.
     */
    private void checkReload()
    {
        if (this.data == null || this.bound == null || this.pendingId != null)
        {
            return;
        }

        String id = this.form.model.get();

        if (id == null || id.isEmpty())
        {
            return;
        }

        ModelInstance instance = BBSModClient.getModels().getModel(id);

        if (instance != this.bound && this.modelDirty)
        {
            if (instance != null) instance.delete();
            BBSModClient.getModels().models.put(id, this.bound);
            this.refresh();
            return;
        }

        if (instance != null && instance != this.bound)
        {
            /* A reload (our own 60s periodic save writes config.json too, tripping the watchdog) — keep the
             * undo stack: its commands are path-based and resolve fine against the fresh config, so re-binding
             * shouldn't wipe the user's history every time it autosaves. */
            this.preserveUndo = true;
            this.hookedConfigs.remove(this.data);
            this.bound = instance;
            this.fill(instance.config);
        }
    }

    private void tryLoadPending()
    {
        if (this.pendingId == null)
        {
            return;
        }

        ModelInstance instance = BBSModClient.getModels().getModel(this.pendingId);

        if (instance != null)
        {
            this.bound = instance;
            this.form.model.set(this.pendingId);
            this.pendingId = null;
            this.fill(instance.config);
        }
    }

    /** Models are assets, so the data manager creates models, while asset folder management stays in the file browser. */
    @Override
    protected UICRUDOverlayPanel createOverlayPanel()
    {
        return new UIModelOverlayPanel(this.getTitle(), this, this::pickData);
    }

    @Override
    protected void fillData(ModelConfig data)
    {
        if (data == null)
        {
            /* No model open in this tab — drop the (possibly already watchdog-deleted) instance so neither
             * the preview nor checkReload clings to it while the picker is up. */
            this.bound = null;
        }

        this.setupUndo(data);
        this.geometryEditor.fill(this.bound);
        this.modelDirty = false;
        this.renames.clear();
        if (this.bound == null || !this.bound.isEditable()) this.showGeometry(false);
        this.modelMode.setEnabled(this.bound != null && this.bound.isEditable());
        this.modelMode.tooltip(this.modelMode.isEnabled() ? UIKeys.MODEL_EDITOR_OPEN_MODEL_EDITOR : UIKeys.MODEL_EDITOR_OPEN_MODEL_EDITOR_UNAVAILABLE);

        this.configEditor.fill(data);
        this.syncPreview();

        if (this.folderIcon != null)
        {
            this.folderIcon.setEnabled(data != null);
        }

        if (this.historyIcon != null)
        {
            this.historyIcon.setEnabled(data != null);
        }

        if (this.animationIcon != null)
        {
            this.animationIcon.setEnabled(data != null);
        }
    }

    private void openHistory()
    {
        if (this.data == null || this.undoHandler == null)
        {
            return;
        }

        UIOverlay.addOverlay(this.getContext(), new UIUndoHistoryOverlay(UIKeys.MODEL_EDITOR_HISTORY_TITLE, this.undoHandler.getUndoManager(), () -> this.data, this::afterUndo), 200, 0.6F);
    }

    /**
     * Wire (or reset) undo for the config that just got filled in. The handler is created once and kept —
     * so the pre-callback bound into each config stays valid — while its stack is cleared per tab switch.
     * Each distinct config instance gets the pre-callback registered exactly once (tracked by identity).
     */
    private void setupUndo(ModelConfig data)
    {
        if (data == null)
        {
            return;
        }

        if (this.undoHandler == null)
        {
            this.undoHandler = new UIFormUndoHandler(this);
        }
        else if (!this.preserveUndo)
        {
            /* Reset on real navigation (open / tab switch / pick) but keep it across a live reload re-bind. */
            this.undoHandler.reset();
        }

        this.preserveUndo = false;

        if (this.hookedConfigs.add(data))
        {
            data.preCallback(this.undoHandler::handlePreValues);
        }
    }

    public void undo()
    {
        if (this.data != null && this.undoHandler != null && this.undoHandler.getUndoManager().undo(this.data))
        {
            this.afterUndo();
            UIUtils.playClick();
        }
    }

    public void redo()
    {
        if (this.data != null && this.undoHandler != null && this.undoHandler.getUndoManager().redo(this.data))
        {
            this.afterUndo();
            UIUtils.playClick();
        }
    }

    /**
     * An undo/redo restores config values straight through {@code fromData}, which the static widgets and
     * baked geometry don't track. Re-derive the config's caches, re-bake the instance, re-seed the working
     * map rows and refill the sections so the whole editor reflects the restored state.
     */
    private void afterUndo()
    {
        this.data.rebuild();
        this.refresh();
        this.configEditor.fill(this.data);
        this.geometryEditor.fill(this.bound);
    }

    private void openModelFolder()
    {
        String id = this.form.model.get();

        if (id != null && !id.isEmpty())
        {
            UIUtils.openFolder(BBSMod.getAssetsPath(ModelManager.MODELS_PREFIX + id + "/"));
        }
    }

    /**
     * Rebuild the live instance's baked state so a config edit that changed the render path shows in the
     * preview without saving: re-resolve welds + derived caches, re-bake VAOs, and reset the renderer's
     * cached animator (the procedural/non-procedural choice). The plain scalar reads (scale, texture,
     * culling...) already update every frame, so they don't go through here.
     */
    public void refresh()
    {
        if (this.bound == null)
        {
            return;
        }

        this.bound.invalidateWelds();
        this.bound.delete();
        this.bound.setup();
        this.resetAnimator();
    }

    private void resetAnimator()
    {
        if (FormUtilsClient.getRenderer(this.form) instanceof ModelFormRenderer renderer)
        {
            renderer.resetAnimator();
        }
    }

    @Override
    public void forceSave()
    {
        if (this.undoHandler != null) this.undoHandler.submitUndo();
        if (this.modelDirty && this.bound != null)
        {
            if (!BBSModClient.getModels().saveModel(this.bound, this.renames))
            {
                this.drafts.put(this.bound.id, new ModelDraft(this.bound, new ArrayList<>(this.renames)));
                UIContext context = this.getContext();
                if (context != null) context.notifyError(UIKeys.MODEL_EDITOR_SAVE_FAILED);
                return;
            }
            this.modelDirty = false;
            this.renames.clear();
            this.drafts.remove(this.bound.id);
        }
        super.forceSave();
    }

    /** The model's structure changed — a group came, went, moved or was renamed: settle it, re-bake, refill. */
    public void modelStructureChanged()
    {
        if (this.bound != null && this.bound.getModel() instanceof Model model)
        {
            model.initialize();
        }

        this.refresh();
        this.geometryEditor.fill(this.bound);
        this.configEditor.fill(this.data);
    }

    /** Rename a group everywhere the model's folder knows it: the group itself, the config, the animations. */
    public void renameBone(String from, String to)
    {
        if (this.bound == null || !(this.bound.getModel() instanceof Model model))
        {
            return;
        }

        ModelGroup group = model.getGroup(from);

        if (group == null)
        {
            return;
        }

        group.id = to;
        model.initialize();
        this.data.renameBone(from, to);
        this.renameAnimations(from, to);
    }

    /** The model editor changed the model: the next save writes the model's file too. */
    public void markModelEdited()
    {
        this.modelDirty = true;
    }

    /** An edit of the model itself goes on the same stack as the config's edits, in order. */
    public void pushModelEdit(ModelEditUndo undo)
    {
        if (this.undoHandler != null)
        {
            this.undoHandler.submitUndo();
            this.undoHandler.getUndoManager().pushUndo(undo);
        }

        this.markModelEdited();
    }

    /** A gesture on the model ended: the next edit is a step of its own. */
    public void closeModelEdit()
    {
        if (this.undoHandler != null)
        {
            this.undoHandler.getUndoManager().markLastUndoNoMerging();
        }
    }

    /**
     * Put the model back to a snapshot, and the config too when one comes along — an undo or a
     * redo of a model edit; the panel re-bakes and refills right after, as after any undo.
     */
    public void restoreModel(MapType model, MapType config)
    {
        if (this.bound != null && this.bound.getModel() instanceof Model live)
        {
            live.reload(model);
        }

        if (config != null && this.data != null)
        {
            this.data.fromData(config);
            this.data.rebuild();
        }

        this.markModelEdited();
    }

    /** Move a bone's keys in the animations — in memory now, in the file at the next save. */
    public void renameAnimations(String from, String to)
    {
        if (this.bound == null)
        {
            return;
        }

        for (Animation animation : this.bound.animations.getAll())
        {
            AnimationPart part = animation.parts.remove(from);

            if (part != null)
            {
                animation.parts.put(to, part);
            }
        }

        this.renames.add(new String[] {from, to});
        this.markModelEdited();
    }


    public ModelInstance getInstance()
    {
        return this.bound;
    }

    public void syncPreview()
    {
        if (!this.geometryMode)
        {
            this.configEditor.applyPreview();
            return;
        }

        this.renderer.setProceduralPreview(-1);
        this.renderer.setFirstPerson(false);
        this.renderer.setEquipment(false, false);
        this.renderer.getEntity().setSneaking(false);
    }

    public void refreshPreview()
    {
        this.syncPreview();
        this.layoutPanes();
        this.resize();
    }

    private void openAnimations()
    {
        if (this.bound == null)
        {
            return;
        }

        List<String> names = new ArrayList<>();

        if (this.bound.animations != null)
        {
            for (Animation animation : this.bound.animations.getAll())
            {
                names.add(animation.id);
            }
        }

        names.sort(String::compareToIgnoreCase);
        this.getContext().replaceContextMenu((menu) ->
        {
            if (names.isEmpty())
            {
                menu.action(Icons.NONE, UIKeys.MODEL_EDITOR_ANIMATION_NONE, () -> {});
            }

            for (String name : names)
            {
                menu.action(Icons.PLAY, IKey.raw(name), () ->
                {
                    if (FormUtilsClient.getRenderer(this.form) instanceof ModelFormRenderer renderer && renderer.getAnimator() != null)
                    {
                        renderer.getAnimator().playAnimation(name);
                    }
                });
            }

            menu.action(Icons.REFRESH, UIKeys.MODEL_EDITOR_ANIMATION_RESET, this::resetAnimator);
        });
    }

    public void showGeometry(boolean show)
    {
        if (show && (this.bound == null || !this.bound.isEditable())) return;
        this.geometryMode = show;
        this.configEditor.setVisible(!show);
        this.syncPreview();
        this.geometryEditor.setVisible(show);
        this.uvPane.setVisible(show);
        this.uvSplitter.setVisible(show);
        this.renderer.setCubePicking(show);
        ModelFormRenderer preview = (ModelFormRenderer) FormUtilsClient.getRenderer(this.form);
        preview.setRest(show);
        this.layoutPanes();
        this.resize();
    }
}
