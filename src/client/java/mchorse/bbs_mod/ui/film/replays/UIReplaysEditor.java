package mchorse.bbs_mod.ui.film.replays;

import mchorse.bbs_mod.api.client.editor.TrackCategories;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackCatalog;
import mchorse.bbs_mod.ui.forms.editors.UIForms;
import mchorse.bbs_mod.film.replays.tracks.TrackStyle;
import mchorse.bbs_mod.film.replays.tracks.TrackDescriptor;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.Waveform;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.camera.clips.misc.AudioClip;
import mchorse.bbs_mod.camera.utils.TimeUtils;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.ik.ModelIKRuntime;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsConfig;
import mchorse.bbs_mod.cubic.physics.ModelPhysicsIO;
import mchorse.bbs_mod.data.DataStorageUtils;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.IKBake;
import mchorse.bbs_mod.film.replays.FormControlKeys;
import mchorse.bbs_mod.film.replays.tracks.ConstraintTracks;
import mchorse.bbs_mod.film.replays.tracks.MaterialTracks;
import mchorse.bbs_mod.film.replays.PerLimbService;
import mchorse.bbs_mod.film.replays.tracks.MaterialTracks;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.FilmTarget;
import mchorse.bbs_mod.ui.framework.elements.input.drag.TransformOp;
import mchorse.bbs_mod.film.replays.ReplayKeyframes;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.forms.IPosedForm;
import mchorse.bbs_mod.forms.forms.sound.AbstractSoundForm;
import mchorse.bbs_mod.forms.forms.PoseForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.base.BaseValueBasic;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.film.view.ViewportPickIntent;
import mchorse.bbs_mod.ui.film.replays.overlays.UIAnimationToPoseOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.overlays.UIBakeIKOverlayPanel;
import mchorse.bbs_mod.ui.film.replays.overlays.UIKeyframeSheetFilterOverlayPanel;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.items.FoldState;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.UIKeyframeDopeSheet;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.utils.UIRenderable;
import mchorse.bbs_mod.ui.framework.elements.utils.UITimelineCategoryBar;
import mchorse.bbs_mod.ui.utils.BoneSelection;
import mchorse.bbs_mod.ui.utils.IBoneSelectionHost;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.Gizmo;
import mchorse.bbs_mod.ui.utils.Scale;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.ui.utils.renderers.TimelineRulerRenderer;
import mchorse.bbs_mod.utils.CollectionUtils;
import mchorse.bbs_mod.utils.Direction;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.PlayerUtils;
import mchorse.bbs_mod.utils.RayTracing;
import mchorse.bbs_mod.utils.StringUtils;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class UIReplaysEditor extends UIElement implements IBoneSelectionHost {

    private final BoneSelection boneSelection = new BoneSelection();

    @Override
    public BoneSelection getBoneSelection()
    {
        return this.boneSelection;
    }

    private static String lastFilm = "";
    private static int lastReplay;

    public UIReplaysListPanel replaysList;
    public UIReplayPropertiesPanel replayProperties;

    private static final int CATEGORY_BAR_WIDTH = 20;

    public UITimelineCategoryBar iconBar;
    public Map<TrackCategory, UIIcon> tabButtons = new HashMap<>();
    private TrackCategory category = TrackCategories.REPLAY;

    /* «All tracks» view: shows every category's tracks at once, bypassing the category filter. */
    private UIIcon allToggle;
    /** One button that folds or unfolds every dope-sheet section (see {@link #toggleAllSections()}). */
    private UIIcon sectionsToggle;
    private boolean allMode;
    private UIClipsPanel actionTimeline;
    private UIIcon actionsToggle;
    private boolean actionsMode;

    /* Keyframes */
    public UIKeyframeEditor keyframeEditor;

    /* Clips */
    private UIFilmPanel filmPanel;
    private Film film;
    private Replay replay;
    private boolean settingReplay;
    private long pendingPickGeneration;
    private UIFilmController pendingPickOwner;
    /**
     * Monotonically identifies the latest keyframe-editor rebuild.  Hierarchy
     * mutations are deferred while a mouse dispatch is active, so the field
     * can temporarily point at an editor which is not mounted yet.
     */
    private long keyframeEditorGeneration;
    private boolean keyframeEditorResetPending;
    private boolean timelineVisible = true;
    private boolean propertiesVisible = true;
    private Set<String> keys = new LinkedHashSet<>();
    private final Map<String, FoldState<String>> expandedTracksByReplay = new HashMap<>();
    /** Dope-sheet section folds, per replay id: the timeline is rebuilt on many editor actions. */
    private final Map<String, Map<String, Boolean>> sectionFoldsByReplay = new HashMap<>();
    private final Map<String, String> selectedPartsByReplay = new HashMap<>();
    private String selectedPart = "";
    private int poseOverlayCount;
    private int transformOverlayCount;
    private final UIElement partHeader = new UIElement()
    {
        @Override
        public void render(UIContext context)
        {
            context.batcher.box(this.area.x, this.area.y, this.area.ex(), this.area.ey(), BBSSettings.chromeSurface());
            context.batcher.text(UIReplaysEditor.this.getSelectedPartName(), this.area.x + 5, this.area.y + 6);
            super.render(context);
        }
    };

    public final UIReplayPropTransform replayTransform = new UIReplayPropTransform();

    private static int categoryColor(TrackCategory category)
    {
        if (category == TrackCategories.REPLAY) return Colors.CYAN;
        if (category == TrackCategories.FORM) return Colors.ORANGE;
        if (category == TrackCategories.POSE) return Colors.RED;
        if (category == TrackCategories.IK) return Colors.YELLOW;
        if (category == TrackCategories.PHYSICS) return Colors.GREEN;

        return category.id.hashCode() & Colors.RGB;
    }

    /**
     * Display sections of the dope sheet: one per category, so tracks group under the same key the
     * category tabs filter on. Shared instances, which lets the fold map address them by id.
     */
    private final Map<TrackCategory, UIKeyframeSheet.Section> sections = buildSections();

    private static Map<TrackCategory, UIKeyframeSheet.Section> buildSections() {
        Map<TrackCategory, UIKeyframeSheet.Section> sections = new HashMap<>();

        for (TrackCategory category : TrackCategories.values()) {
            sections.put(category, new UIKeyframeSheet.Section(
                    "replay_section/" + category.id,
                    category.label,
                    category.icon,
                    categoryColor(category)
            ));
        }

        return sections;
    }

    public static Icon getIcon(String key) { return TrackStyle.icon(key); }
    public static int getColor(String key) { return TrackStyle.color(key); }

    /** Compatibility facade for FSR addon timeline affordances. */
    public static void registerTrackStyle(String property, Icon icon, int color)
    {
        TrackStyle.register(property, icon, color);
    }

    public static Icon getRegisteredIcon(String property) { return TrackStyle.registeredIcon(property); }

    public static void registerTrackLabel(String property, IKey label)
    {
        if (property != null && !property.isEmpty() && label != null) TrackStyle.registerLabel(property, label);
    }

    public static IKey getRegisteredLabel(String property) { return TrackStyle.registeredLabel(property); }

    public static UIKeyframeSheet createTrackSheet(String property, KeyframeChannel channel, BaseValueBasic value)
    {
        IKey title = getRegisteredLabel(property);
        return (title == null ? new UIKeyframeSheet(getColor(property), channel, value)
            : new UIKeyframeSheet(channel.getId(), title, getColor(property), channel, value)).icon(getIcon(property));
    }

    /** Stable key shared by filters and global track styles. */
    public static String getSheetFilterKey(UIKeyframeSheet sheet) {
        return sheet.getFilterKey();
    }

    /** The form a sheet belongs to, whether it backs a form property or carries its owner directly (bones, materials, IK). */
    public static Form getSheetForm(UIKeyframeSheet sheet) {
        if (sheet.form != null) {
            return sheet.form;
        }

        return sheet.property == null ? null : FormUtils.getForm(sheet.property);
    }

    /** Keep track-category ownership in one place so the all-tracks view and category tabs cannot drift apart. */
    public static TrackCategory categoryOf(UIKeyframeSheet sheet)
    {
        return categoryOf(new TrackId(sheet.id), sheet.property != null || sheet.form != null);
    }

    public static TrackCategory categoryOf(TrackId track, boolean owned)
    {
        return TrackCategories.categoryOf(track, owned);
    }

    static boolean shouldShowTrack(UIKeyframeSheet sheet, TrackCategory category, boolean allTracks)
    {
        return allTracks || categoryOf(sheet) == category;
    }

    public static void renderRuler(
            UIContext context,
            UIKeyframes keyframes,
            UIClipsPanel clipsPanel,
            Clips camera,
            int clipOffset
    ) {
        Area area = keyframes.graphArea;
        int rulerBottom = TimelineRulerRenderer.getRulerBottom(area);

        if (rulerBottom <= area.y)
        {
            return;
        }

        context.batcher.clip(area.x, area.y, area.w, rulerBottom - area.y, context);

        renderRulerAudio(context, keyframes, camera, clipOffset, area, rulerBottom);
        renderRulerClipGradient(context, keyframes, clipsPanel, clipOffset, area, rulerBottom);

        context.batcher.unclip(context);
    }

    private static boolean renderRulerAudio(
            UIContext context,
            UIKeyframes keyframes,
            Clips camera,
            int clipOffset,
            Area area,
            int rulerBottom
    ) {
        if (!BBSSettings.audioWaveformVisibleInKeyframes.get()) {
            return false;
        }

        Scale scale = keyframes.getXAxis();
        boolean renderedOnce = false;
        int y = area.y + 1;
        int h = Math.max(1, rulerBottom - y - 1);

        for (Clip clip : camera.get()) {
            if (!(clip instanceof AudioClip audioClip)) {
                continue;
            }

            Link link = audioClip.audio.get();

            if (link == null) {
                continue;
            }

            SoundBuffer buffer = BBSModClient.getSounds().get(link, true);

            if (buffer == null || buffer.getWaveform() == null) {
                continue;
            }

            Waveform wave = buffer.getWaveform();
            int audioOffset = audioClip.offset.get();
            float offset = audioClip.tick.get() - clipOffset;
            int duration = Math.min((int) (wave.getDuration() * 20), clip.duration.get());
            int x1 = (int) scale.to(offset);
            int x2 = (int) scale.to(offset + duration);

            if (x2 <= area.x || x1 >= area.ex()) {
                continue;
            }

            wave.render(
                    context.batcher,
                    Colors.WHITE,
                    x1,
                    y,
                    x2 - x1,
                    h,
                    TimeUtils.toSeconds(audioOffset),
                    TimeUtils.toSeconds(audioOffset + duration)
            );

            renderedOnce = true;
        }

        return renderedOnce;
    }

    private static void renderRulerClipGradient(
            UIContext context,
            UIKeyframes keyframes,
            UIClipsPanel clipsPanel,
            int clipOffset,
            Area area,
            int rulerBottom
    ) {
        Clip clip = clipsPanel.getClip();

        if (clip == null || clip instanceof AudioClip || !BBSSettings.editorClipPreview.get()) {
            return;
        }

        Scale scale = keyframes.getXAxis();
        int x1 = (int) scale.to(clip.tick.get() - clipOffset);
        int x2 = (int) scale.to(clip.tick.get() + clip.duration.get() - clipOffset);

        if (x2 <= area.x || x1 >= area.ex()) {
            return;
        }

        int color = clipsPanel.clips.getClipFactoryData(clip).color;
        int left = Math.max(area.x, x1);
        int right = Math.min(area.ex(), x2);
        int top = area.y + 1;
        int bottom = Math.max(top + 1, rulerBottom - 1);

        context.batcher.gradientVBox(left, top, right, bottom, Colors.setA(color, 0.03F), Colors.setA(color, 0.78F));
        context.batcher.box(left, Math.max(top, bottom - 2), right, bottom, Colors.setA(color, 0.92F));
    }

    public UIReplaysEditor(UIFilmPanel filmPanel) {
        this.filmPanel = filmPanel;
        this.replayProperties = new UIReplayPropertiesPanel(filmPanel);
        this.replaysList = new UIReplaysListPanel(filmPanel, (l) ->
        {
            Replay selected = l.isEmpty() ? null : l.get(0);

            if (selected != this.replay)
            {
                this.setReplay(selected, false, OrbitReaction.SWITCH);
            }
        }, this.replayProperties.getFormConsumer(), this::selectBodyPart);
        this.replayProperties.attachReplayList(this.replaysList.replays);

        /* Room for folding and Actions below the category icons. */
        this.iconBar = new UITimelineCategoryBar(CATEGORY_BAR_WIDTH * 2);
        this.iconBar.relative(this).x(0).y(0).h(1F);

        this.allToggle = new UIIcon(Icons.LIST, b -> this.setAllTracks());
        this.allToggle.tooltip(UIKeys.FILM_REPLAY_ALL_TRACKS, Direction.RIGHT);
        this.allToggle.highlight(() -> !this.actionsMode && this.allMode, Direction.LEFT);
        this.iconBar.add(this.allToggle);
        this.iconBar.add(new UIRenderable(context ->
        {
            Area area = this.allToggle.area;

            context.batcher.box(area.x + 4, area.ey() - 1, area.ex() - 4, area.ey(), BBSSettings.dividerColor());
        }));

        for (TrackCategory category : TrackCategories.values()) {
            UIIcon button = new UIIcon(category.icon, b -> this.setCategory(category));

            button.tooltip(category.tooltip, Direction.RIGHT);
            button.highlight(() -> !this.actionsMode && !this.allMode && this.category == category, Direction.LEFT);
            this.iconBar.add(button);
            this.tabButtons.put(category, button);
        }

        /* Fold every dope-sheet section at once; its icon follows the current state (see render()). */
        this.sectionsToggle = new UIIcon(Icons.EXPAND_ALL, b -> this.toggleAllSections());
        this.sectionsToggle.tooltip(L10n.lang("bbs.ui.film.replays.expand_all"), Direction.RIGHT);

        this.actionsToggle = new UIIcon(Icons.ACTION, (b) -> this.setActionsMode(!this.actionsMode));
        this.actionsToggle.tooltip(UIKeys.FILM_REPLAY_ACTIONS_TIMELINE, Direction.RIGHT);
        this.actionsToggle.highlight(() -> this.actionsMode, Direction.LEFT);

        this.layoutBottomToggles();

        this.setAllTracks();

        TrackCategories.registerShortcuts(this.keys(), this::visibleCategories, this::setCategory);

        this.add(this.iconBar, this.sectionsToggle, this.actionsToggle, this.replayTransform, this.partHeader);
        this.keys().register(Keys.TRANSFORMATIONS_TRANSLATE, () -> this.startReplayHotkey(TransformOp.TRANSLATE))
            .active(this::isReplayGizmo).category(UIKeys.FILM_REPLAY_TITLE);
        this.keys().register(Keys.TRANSFORMATIONS_ROTATE, () -> this.startReplayHotkey(TransformOp.ROTATE))
            .active(this::isReplayGizmo).category(UIKeys.FILM_REPLAY_TITLE);
        this.markContainer();
    }

    private boolean isReplayGizmo()
    {
        return this.getContext() != null && this.filmPanel.getActivePreview().isInsideFrame(this.getContext())
            && this.filmPanel.getActivePreview().getViewController().getEditTarget().is(FilmTarget.Kind.ROOT);
    }

    private void startReplayHotkey(TransformOp operation)
    {
        this.replayTransform.enableMode(operation);
        if (this.replayTransform.isEditing()) this.showReplayTracks();
    }

    public void showReplayTracks()
    {
        if (this.actionsMode || (!this.allMode && this.category != TrackCategories.REPLAY)) this.setCategory(TrackCategories.REPLAY);
    }

    /** Fold or unfold every dope-sheet section, whichever the timeline is not currently doing. */
    private void toggleAllSections() {
        if (this.keyframeEditor == null) {
            return;
        }

        UIKeyframeDopeSheet dopeSheet = this.keyframeEditor.view.getDopeSheet();

        dopeSheet.setAllSectionsExpanded(!dopeSheet.hasExpandedSections());
    }

    private void setCategory(TrackCategory c) {
        this.setActionsMode(false);
        this.category = c;
        this.allMode = false;
        this.updateChannelsList();
    }

    /** Show every category's tracks at once, bypassing the category filter. */
    private void setAllTracks() {
        this.setActionsMode(false);
        this.allMode = true;
        this.updateChannelsList();
    }

    /** Pin folding and Actions to the bottom of the category bar. */
    private void layoutBottomToggles() {
        this.sectionsToggle.relative(this).x(0).y(1F, -40).wh(CATEGORY_BAR_WIDTH, 20);
        this.actionsToggle.relative(this).x(0).y(1F, -20).wh(CATEGORY_BAR_WIDTH, 20);
    }

    /**
     * Select the category sitting at the given visual position in the tab bar. The IK and physics tabs are only
     * present when the record has IK / physics, so a fixed key-to-category mapping would point past the gap; the
     * number keys instead follow the tabs as the user sees them.
     */
    private void setCategoryByPosition(int index)
    {
        List<TrackCategory> present = new ArrayList<>();

        for (TrackCategory category : TrackCategories.values())
        {
            UIIcon button = this.tabButtons.get(category);

            if (button != null && button.getParent() != null)
            {
                present.add(category);
            }
        }

        present.sort(Comparator.comparingInt((c) -> this.tabButtons.get(c).area.y));

        if (index >= 0 && index < present.size())
        {
            this.setCategory(present.get(index));
        }
    }

    private List<TrackCategory> visibleCategories()
    {
        List<TrackCategory> present = new ArrayList<>();
        for (TrackCategory category : TrackCategories.values())
        {
            UIIcon button = this.tabButtons.get(category);
            if (button != null && button.getParent() != null) present.add(category);
        }
        return present;
    }

    public String getSelectedPartName()
    {
        for (UIForms.FormEntry entry : this.replaysList.bodyParts.getList())
            if (entry.getPath().equals(this.selectedPart)) return entry.toString();
        return "";
    }

    public String getSelectedPart() { return this.selectedPart; }

    public void selectBodyPart(String path)
    {
        if (this.replay == null || Objects.equals(this.selectedPart, path)) return;
        this.selectedPart = this.replaysList.setBodyPartsReplay(this.replay, path);
        this.selectedPartsByReplay.put(this.replay.getId(), this.selectedPart);
        this.updateChannelsList();
    }

    public TrackCategory getCategory() {
        return this.category;
    }

    public void pickPlayerCategory()
    {
        if (this.actionsMode || this.category != TrackCategories.REPLAY)
        {
            this.setCategory(TrackCategories.REPLAY);
        }
    }

    public void setFilm(Film film) {
        this.expandedTracksByReplay.clear();
        this.selectedPartsByReplay.clear();
        this.film = film;

        if (film != null) {
            List<Replay> replays = film.replays.getList();
            int index = film.getId().equals(lastFilm) ? lastReplay : 0;

            if (!CollectionUtils.inRange(replays, index)) {
                index = 0;
            }

            this.replaysList.replays.refreshReplayList();
            this.setReplay(replays.isEmpty() ? null : replays.get(index), true, OrbitReaction.SWITCH);
        }
    }

    public Replay getReplay() {
        return this.replay;
    }

    public void setReplay(Replay replay) {
        this.setReplay(replay, true, OrbitReaction.SWITCH);
    }

    public void setReplay(Replay replay, boolean select, OrbitReaction orbit) {
        /* Guard against re-entry: scrollToReplay() below picks the replay in the list,
         * which fires the list's selection callback and calls setReplay() again. The
         * outermost call owns the orbit reaction, so the nested call is redundant and
         * must not override it (otherwise undo would teleport via the SWITCH callback). */
        if (this.settingReplay) {
            return;
        }

        this.settingReplay = true;

        try {
            this.replay = replay;
            this.selectedPart = this.replaysList.setBodyPartsReplay(replay,
                replay == null ? "" : this.selectedPartsByReplay.getOrDefault(replay.getId(), ""));

            if (orbit == OrbitReaction.RESET) {
                this.filmPanel.getActivePreview().getViewController().resetOrbit();
            }
            else if (orbit == OrbitReaction.SWITCH && replay != null && BBSSettings.editorOrbitTeleportOnSwitch.get())
            {
                this.filmPanel.getActivePreview().getViewController().orbit.teleportPivotToReplay();
            }

            this.replayProperties.setReplay(replay);
            this.filmPanel.actionEditor.setClips(replay == null ? null : replay.actions);
            this.updateChannelsList();

            if (select && replay != null) {
                this.replaysList.replays.scrollToReplay(replay, false);
            }
        }
        finally {
            this.settingReplay = false;
        }
    }

    public void moveReplay(double x, double y, double z) {
        if (this.replay != null) {
            UIContext context = this.getContext();
            float cursor = this.filmPanel.getKeyframeCursor(context == null ? 0F : context.getTransition());

            this.replay.keyframes.x.insert(cursor, x);
            this.replay.keyframes.y.insert(cursor, y);
            this.replay.keyframes.z.insert(cursor, z);
        }
    }

    public void updateChannelsList() {
        UIKeyframeEditor previousEditor = this.keyframeEditor;
        UIKeyframes lastEditor = previousEditor != null ? previousEditor.view : null;
        boolean resetView = lastEditor == null || this.keyframeEditorResetPending;
        long editorGeneration = this.keyframeEditorGeneration == Long.MAX_VALUE
                ? 1L
                : this.keyframeEditorGeneration + 1L;

        this.keyframeEditorGeneration = editorGeneration;
        this.keyframeEditor = null;
        this.keyframeEditorResetPending = false;

        if (this.replay == null) {
            this.replaceKeyframeEditor(previousEditor, null, editorGeneration, false);

            return;
        }

        this.poseOverlayCount = BBSSettings.recordingPoseOverlays.get();
        this.transformOverlayCount = BBSSettings.recordingTransformOverlays.get();
        List<UIKeyframeSheet> sheets = new ArrayList<>();
        if (this.selectedPart.isEmpty()) this.collectCuratedSheets(sheets);
        List<TrackDescriptor> catalog = TrackCatalog.forPart(this.replay.form.get(), this.replay.properties, this.selectedPart);
        UIReplaysEditorUtils.buildSheets(catalog, sheets);
        this.updateCategoryButtons(sheets);

        this.keys.clear();
        Map<String, Integer> keyToColor = new HashMap<>();
        Map<String, String> keyToLabel = new HashMap<>();

        for (UIKeyframeSheet sheet : sheets) {
            String filterKey = getSheetFilterKey(sheet);

            this.keys.add(filterKey);
            keyToColor.put(filterKey, sheet.color);
            keyToLabel.put(filterKey, sheet.title.get());
        }

        Set<String> disabled = BBSSettings.disabledSheets.get();

        sheets.removeIf(v -> !shouldShowTrack(v, this.category, this.showAllTracks()));

        /* The tab isn't empty by itself - so if the filter empties it, the timeline has to stay (see below). */
        boolean hadTracks = !sheets.isEmpty();

        sheets.removeIf(v -> {
            String filterKey = getSheetFilterKey(v);
            for (String s : disabled) {
                if (filterKey.equals(s) || v.id.equals(s) || v.id.endsWith("/" + s)) {
                    return true;
                }
            }

            Form owner = getSheetForm(v);

            if (owner != null) {
                Set<String> ownerDisabled = owner.disabledTracks.get();

                return ownerDisabled.contains(Form.DISABLED_ALL) || ownerDisabled.contains(filterKey);
            }

            return false;
        });

        /*
         * Filtering every track off used to drop the timeline itself, and the track filter lives in its
         * context menu - so "disable all" locked the user out of the only way back. Keep the (empty)
         * timeline whenever the tab had tracks before the filter ran; the dope sheet says why it's blank.
         */
        boolean filteredOutEverything = hadTracks && sheets.isEmpty();

        UIReplaysEditorUtils.pruneTree(sheets);

        /* Group the kept rows into collapsible dope-sheet sections, one per track category. */
        for (UIKeyframeSheet sheet : sheets) {
            sheet.section = this.sections.get(categoryOf(sheet));
        }

        if (!sheets.isEmpty() || filteredOutEverything) {
            this.keyframeEditor = new UIKeyframeEditor(consumer
                    -> new UIFilmKeyframes(this.filmPanel.cameraEditor, consumer).absolute()
            )
                    .target(this.filmPanel.editArea)
                    .editPanelTopOffset(this.filmPanel::getEditPanelTopOffsetPx);
            this.keyframeEditorResetPending = resetView;
            UIKeyframeEditor editor = this.keyframeEditor;
            UIKeyframes view = editor.view;
            Replay replayForEditor = this.replay;

            editor.relative(this).x(CATEGORY_BAR_WIDTH).y(0).w(1F, -CATEGORY_BAR_WIDTH).h(1F);
            editor.setUndoId("replay_keyframe_editor");
            editor.setTimelineVisible(this.timelineVisible && !this.actionsMode);
            editor.setPropertiesVisible(this.propertiesVisible && !this.actionsMode);
            view.getDopeSheet().setEmptyState(UIKeys.KEYFRAMES_EMPTY_FILTERED, UIKeys.KEYFRAMES_EMPTY_FILTERED_HINT);

            /* Reset */
            if (lastEditor != null) {
                view.copyViewport(lastEditor);
            }

            view.rulerRenderer(context ->
                    renderRuler(context, view, this.filmPanel.cameraEditor, this.film.camera, 0)
            );
            view.duration(() -> this.film.calculateDuration());
            view.context(menu -> {
                /* The old view can render for one frame after the replay is removed.  Do not
                 * let a delayed context-menu callback read the replacement editor state. */
                if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                    return;
                }

                int mouseY = this.getContext().mouseY;
                UIKeyframeSheet sheet = view.getGraph().getSheet(mouseY);
                UIReplaysEditorUtils.addOverlayTrackAction(menu, sheet, parent ->
                {
                    if (this.keyframeEditor != editor || this.replay != replayForEditor) return;
                    this.getExpandedTracks().set(parent.toKey(), true);
                    this.updateChannelsList();
                });

                ModelForm poseModelForm = sheet == null ? null : sheet.getPoseForm();
                IPosedForm posedForm = sheet == null ? null : sheet.getPosedForm();

                if (poseModelForm != null) {
                    menu.action(Icons.POSE, UIKeys.FILM_REPLAY_CONTEXT_ANIMATION_TO_KEYFRAMES, () -> {
                        ModelInstance model = ModelFormRenderer.getModel(poseModelForm);

                        if (model != null) {
                            UIOverlay.addOverlay(
                                    this.getContext(),
                                            new UIAnimationToPoseOverlayPanel(
                                            (animationKey, onlyKeyframes, length, step) -> {
                                                if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                                                    return;
                                                }

                                                UIContext context = this.getContext();
                                                float current = this.filmPanel.getKeyframeCursor(context == null ? 0F : context.getTransition());
                                                IEntity entity = this.filmPanel.getController().getCurrentEntity();

                                                UIReplaysEditorUtils.animationToPoseKeyframes(
                                                        editor,
                                                        sheet,
                                                        poseModelForm,
                                                        entity,
                                                        current,
                                                        animationKey,
                                                        onlyKeyframes,
                                                        length,
                                                        step
                                                );
                                            },
                                            poseModelForm,
                                            sheet
                                    ),
                                    200,
                                    197
                            );
                        }
                    });
                }

                if (replayForEditor.form.get() instanceof ModelForm modelForm) {
                    List<String> controllers = ModelIKRuntime.getControllers(ModelFormRenderer.getModel(modelForm));

                    if (!controllers.isEmpty()) {
                        menu.action(Icons.CLOSE, UIKeys.FILM_REPLAY_CONTEXT_CLEAR_IK, () -> {
                            if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                                return;
                            }

                            UIReplaysEditorUtils.clearIKTracks(replayForEditor, modelForm);
                            this.updateChannelsList();
                        });
                    }

                    ModelInstance ikInstance = ModelFormRenderer.getModel(modelForm);
                    /* Keyed by each chain's tip bone, in the order getChains returns: chain root
                     * depth ascending, and config order within one depth (ModelIKCache.compile).
                     * The panel sorts its own list, so nothing here depends on that order. */
                    Map<String, List<String>> chains = ikInstance == null
                            ? Collections.emptyMap()
                            : ModelIKRuntime.getChains(ikInstance.model, modelForm);

                    if (!chains.isEmpty()) {
                        menu.action(Icons.KEY, UIKeys.FILM_REPLAY_CONTEXT_BAKE_IK, () -> {
                            if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                                return;
                            }

                            /* The range on offer ends where the replay's own motion ends — past its
                             * last keyframe the solve only repeats itself, and a film is usually
                             * longer than any one replay in it. A replay without keyframes gets the film. */
                            int lastTick = (int) Math.ceil(replayForEditor.getLastKeyframeTick());

                            if (lastTick < 0) {
                                lastTick = Math.max(0, this.film.calculateDuration() - 1);
                            }

                            UIOverlay.addOverlay(this.getContext(), new UIBakeIKOverlayPanel(chains.keySet(), lastTick,
                                    (tips, start, end, step, disable) -> {
                                        if (IKBake.bake(this.film, replayForEditor, tips, start, end, step, disable)) {
                                            this.updateChannelsList();
                                        }
                                    }), 240, 240);
                        });
                    }
                }

                if (posedForm != null && sheet.selection.hasAny() && posedForm.hasBoneTracks()) {
                    menu.action(Icons.LIMB, UIKeys.FILM_REPLAY_CONTEXT_POSES_TO_LIMBS, () -> {
                        if (this.keyframeEditor != editor || this.replay != replayForEditor) {
                            return;
                        }

                        if (UIReplaysEditorUtils.posesToLimbTracks(replayForEditor.properties, sheet)) {
                            this.getExpandedTracks().set(sheet.id, true);
                            this.updateChannelsList();
                        }
                    });
                }

                if (view.getGraph() instanceof UIKeyframeDopeSheet) {
                    menu.action(Icons.FILTER, UIKeys.FILM_REPLAY_FILTER_SHEETS, () -> {
                        if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                            return;
                        }

                        Set<String> disabledSet = BBSSettings.disabledSheets.get();
                        UIKeyframeSheetFilterOverlayPanel panel = new UIKeyframeSheetFilterOverlayPanel(
                                disabledSet,
                                this.keys,
                                keyToColor,
                                keyToLabel
                        );

                        UIOverlay.addOverlay(this.getContext(), panel, 240, 0.9F);

                        panel.onClose(e -> {
                            if (this.keyframeEditor != editor || this.replay != replayForEditor || replayForEditor == null) {
                                return;
                            }

                            BBSSettings.disabledSheets.set(disabledSet);
                            this.updateChannelsList();
                        });
                    });
                }
            });

            for (UIKeyframeSheet sheet : sheets) {
                view.addSheet(sheet);
            }

            view.getDopeSheet().setExpanded(this.getExpandedTracks());
            /* The timeline is rebuilt on many actions, so section folds live here and are handed back each time. */
            view.getDopeSheet().configureSectionFolds(this.sectionFoldsByReplay.computeIfAbsent(
                this.replay == null ? "" : this.replay.getId(),
                k -> new HashMap<>()
            ));

        }

        UIKeyframeEditor editor = this.keyframeEditor;

        this.replaceKeyframeEditor(previousEditor, editor, editorGeneration, resetView);
    }

    private void replaceKeyframeEditor(
            UIKeyframeEditor previous,
            UIKeyframeEditor replacement,
            long generation,
            boolean resetView
    ) {
        Runnable mutation = () -> {
            if (previous != null && previous.getParent() == this) {
                this.remove(previous);
            }

            if (generation != this.keyframeEditorGeneration || this.keyframeEditor != replacement) {
                return;
            }

            for (UIKeyframeEditor mounted : new ArrayList<>(this.getChildren(UIKeyframeEditor.class))) {
                if (mounted != replacement && mounted.getParent() == this) {
                    this.remove(mounted);
                }
            }

            if (replacement != null && replacement.getParent() != this) {
                this.add(replacement);
            }

            if (replacement != null) {
                /* Category bar and its bottom toggles stay on top of the timeline. */
                if (this.iconBar.getParent() != null) {
                    this.iconBar.removeFromParent();
                }
                if (this.sectionsToggle.getParent() != null) {
                    this.sectionsToggle.removeFromParent();
                }
                this.actionsToggle.removeFromParent();
                this.add(this.iconBar, this.sectionsToggle, this.actionsToggle, this.replayTransform, this.partHeader);
            }

            this.updateTimelineModeVisibility();
            this.resize();

            if (replacement != null && resetView) {
                replacement.view.resetView();

                if (generation == this.keyframeEditorGeneration && this.keyframeEditor == replacement) {
                    this.keyframeEditorResetPending = false;
                }
            }
        };
        UIContext context = this.getContext();

        if (context == null) {
            mutation.run();
        } else if (previous == null) {
            context.menu.runAfterHierarchyMutation(mutation);
        } else {
            context.menu.runAfterHierarchyMutation(mutation, previous);
        }
    }

    /** All-tracks view: transient toggle, or forced on when the replay tabs setting is disabled. */
    private boolean showAllTracks() {
        return this.allMode || !BBSSettings.editorReplayTabs.get();
    }

    private void collectCuratedSheets(List<UIKeyframeSheet> sheets) {
        UIKeyframeSheet hotbarRoot = null;

        for (String key : ReplayKeyframes.CURATED_CHANNELS) {
            BaseValue value = this.replay.keyframes.get(key);
            KeyframeChannel channel = (KeyframeChannel) value;
            UIKeyframeSheet sheet = new UIKeyframeSheet(getColor(key), channel, null).icon(getIcon(key));

            /* Curated channels list the hotbar in slot order, starting with its parent row. */
            if (this.replay.keyframes.hotbar.stream().anyMatch(slot -> slot == channel)) {
                if (hotbarRoot == null) {
                    hotbarRoot = sheet;
                }
                else {
                    sheet.setParent(hotbarRoot);
                }
            }

            sheets.add(sheet);
        }
    }

    private void updateCategoryButtons(List<UIKeyframeSheet> sheets)
    {
        for (TrackCategory candidate : TrackCategories.values())
        {
            UIIcon button = this.tabButtons.get(candidate);
            if (button == null) continue;
            boolean show = candidate == TrackCategories.FORM || candidate == TrackCategories.POSE
                || (candidate == TrackCategories.REPLAY && this.selectedPart.isEmpty())
                || sheets.stream().anyMatch(sheet -> categoryOf(sheet) == candidate);
            if (show && button.getParent() == null) this.iconBar.add(button);
            else if (!show) button.removeFromParent();
        }
        if (!this.visibleCategories().contains(this.category)) this.category = TrackCategories.FORM;
        this.sections.putAll(buildSections());
    }

    public Set<String> getExpandedPoseTabIds()
    {
        return this.keyframeEditor == null ? Collections.emptySet() : this.keyframeEditor.view.getDopeSheet().getExpandedPoseTabIds();
    }

    /** Shared across timeline rebuilds, categories and body-part selections of one replay. */
    public FoldState<String> getExpandedTracks()
    {
        return this.expandedTracksByReplay.computeIfAbsent(this.replay == null ? "" : this.replay.getId(), key -> new FoldState<>());
    }

    /**
     * Re-applies keyframe parameters panel position (e.g. after layout lock
     * toggle).
     */
    public void refreshEditPanelOffset() {
        if (this.keyframeEditor != null) {
            this.keyframeEditor.refreshEditPanelOffset();
        }

        if (this.replaysList != null)
        {
            this.replaysList.refreshEditPanelOffset();
        }

        if (this.replayProperties != null)
        {
            this.replayProperties.refreshEditPanelOffset();
        }
    }

    public void setTimelineVisible(boolean visible)
    {
        this.timelineVisible = visible;
        this.updateTimelineModeVisibility();
    }

    public void setPropertiesVisible(boolean visible)
    {
        this.propertiesVisible = visible;
        this.updateTimelineModeVisibility();
    }

    public void attachActionTimeline(UIClipsPanel actionTimeline)
    {
        this.actionTimeline = actionTimeline;
        actionTimeline.relative(this).x(CATEGORY_BAR_WIDTH).y(0).w(1F, -CATEGORY_BAR_WIDTH).h(1F);
        this.add(actionTimeline);
        this.actionsToggle.removeFromParent();
        this.add(this.actionsToggle);
        this.updateTimelineModeVisibility();
    }

    public boolean isActionsMode()
    {
        return this.actionsMode;
    }

    public void setActionsMode(boolean actionsMode)
    {
        if (this.actionsMode == actionsMode)
        {
            return;
        }

        if (this.keyframeEditor != null && this.actionTimeline != null)
        {
            if (actionsMode)
            {
                this.actionTimeline.clips.scale.view(this.keyframeEditor.view.getXAxis().getMinValue(), this.keyframeEditor.view.getXAxis().getMaxValue());
            }
            else
            {
                this.keyframeEditor.view.getXAxis().view(this.actionTimeline.clips.scale.getMinValue(), this.actionTimeline.clips.scale.getMaxValue());
            }
        }

        this.actionsMode = actionsMode;
        this.updateTimelineModeVisibility();
        this.filmPanel.onReplayTimelineModeChanged();
    }

    private void updateTimelineModeVisibility()
    {
        if (this.keyframeEditor != null)
        {
            this.keyframeEditor.setTimelineVisible(this.timelineVisible && !this.actionsMode);
            this.keyframeEditor.setPropertiesVisible(this.propertiesVisible && !this.actionsMode);
        }

        if (this.actionTimeline != null)
        {
            this.actionTimeline.setVisible((this.timelineVisible || this.propertiesVisible) && this.actionsMode);
            this.actionTimeline.setTimelineVisible(this.timelineVisible && this.actionsMode);
            this.actionTimeline.setPropertiesVisible(this.propertiesVisible && this.actionsMode);
        }
    }

    public void pickForm(Form form, String bone) {
        this.pickFormBone(form, bone, false);
    }

    /** Route deferred gizmo-sphere picks through the same modifier gestures as direct bone picks. */
    public void pickFormWithOffers(UIContext context, Form form, String bone) {
        UIReplaysEditorUtils.pickFormWithOffers(context, new Pair<>(form, bone), this::pickFormBone);
    }

    public void pickViewportFormWithOffers(UIContext context, Pair<Form, String> pair, ViewportPickIntent.Input input)
    {
        Film ownerFilm = this.film;
        Replay ownerReplay = this.replay;
        int ownerTick = this.filmPanel.getCursor();

        if (!this.isVisible())
        {
            this.filmPanel.showPanel(this);
        }

        UIReplaysEditorUtils.pickFormWithOffers(context, pair, (form, bone, insert) ->
        {
            if (this.film == ownerFilm && this.replay == ownerReplay && this.filmPanel.getCursor() == ownerTick)
            {
                this.pickFormBone(form, bone, insert);
            }
        }, input.button(), input.alt(), input.control(), input.shift());
    }

    /**
     * Picking a model bone in the viewport is a pose edit, but the pose/bone tracks
     * only exist in the {@link TrackCategory#POSE} category. So when another category
     * is open, jump to Pose first before delegating to the shared pick logic — otherwise
     * the click finds no pose sheet in the current graph and silently does nothing,
     * forcing a manual tab switch. With all tracks shown the pose sheets are already
     * in the graph, so no switch is forced.
     */
    private void pickFormBone(Form form, String bone, boolean insert) {
        this.setActionsMode(false);
        if (form != null) this.selectBodyPart(FormUtils.getPath(form));
        if (form instanceof PoseForm && bone != null && !bone.isEmpty() && !this.showAllTracks() && this.category != TrackCategories.POSE) {
            this.setCategory(TrackCategories.POSE);
        }

        UIReplaysEditorUtils.pickForm(this.keyframeEditor, this.filmPanel, form, bone, insert);
    }

    /** Preserve the legacy direct-addon JVM descriptor. */
    public void releaseViewport(UIContext context, boolean dragged)
    {
        this.releaseViewport(context, dragged, this.pendingPickGeneration);
    }

    public void releaseViewport(UIContext context, boolean dragged, long generation)
    {
        this.releaseViewport(context, dragged, this.filmPanel.getController(), generation);
    }

    public void releaseViewport(UIContext context, boolean dragged, UIFilmController controller, long generation)
    {
        if (generation == 0L || this.pendingPickGeneration != generation || this.pendingPickOwner != controller)
        {
            return;
        }

        this.pendingPickGeneration = 0L;
        this.pendingPickOwner = null;
        controller.releaseViewportPick(context, dragged, generation);
    }

    public void cancelViewportPick(long generation)
    {
        this.cancelViewportPick(this.filmPanel.getController(), generation);
    }

    public void cancelViewportPick(UIFilmController controller, long generation)
    {
        if (generation != 0L && this.pendingPickGeneration == generation && this.pendingPickOwner == controller)
        {
            this.pendingPickGeneration = 0L;
            this.pendingPickOwner = null;
            controller.cancelViewportPick(generation);
        }
    }

    public boolean clickViewport(UIContext context, Area area)
    {
        return this.clickViewport(context, area, this.filmPanel.getController());
    }

    public boolean clickViewport(UIContext context, Area area, UIFilmController controller)
    {
        boolean inside = area.isInside(context);
        StencilFormFramebuffer stencil = controller.getStencil();

        if (controller.isViewFlying() && inside) {
            if (context.mouseButton == 0 && controller.orbit.enabled) {
                long generation = controller.orbit.startGesture(context);

                return generation != 0L;
            }
            if (context.mouseButton == 2) {
                if (controller.orbit.enabled) {
                    long generation = controller.orbit.startGesture(context);

                    return generation != 0L;
                } else {
                    long generation = this.filmPanel.dashboard.orbitUI.startGesture(context);

                    return generation != 0L;
                }
            }
        }

        if (controller.isViewFlying()) {
            return false;
        }

        if (inside && context.mouseButton == 2 && controller.orbit.enabled) {
            long generation = controller.orbit.startGesture(context);

            return generation != 0L;
        }

        if (inside && context.mouseButton == 1 && !controller.isViewportPickReady())
        {
            return controller.deferViewportPick(context, 0L);
        }

        if (stencil != null && stencil.hasPicked()) {
            if (inside && context.mouseButton == 0
                    && controller.startViewportSoundGuide(context)) {
                return true;
            }

            if (inside && context.mouseButton == 0
                    && controller.startViewportGizmo(context)) {
                return true;
            }

            Pair<Form, String> pair = stencil.getPicked();
            boolean deferred = context.mouseButton == 0 && controller.orbit.enabled
                && !Window.isShiftPressed() && !Window.isAltPressed();

            if (!deferred && pair != null && (context.mouseButton < 2 || (context.mouseButton == 2 && Window.isCtrlPressed()))) {
                if (!this.isVisible()) {
                    this.filmPanel.showPanel(this);
                }

                if (UIReplaysEditorUtils.pickFormWithOffers(context, pair, this::pickFormBone)) {
                    return true;
                }
            }
        } else if (inside && context.mouseButton == 1) {
            return this.openViewportContextMenu(context, area, controller, Window.isShiftPressed());
        }

        if (inside && context.mouseButton == 0 && controller.orbit.enabled) {
            long generation = controller.orbit.startGesture(context);

            if (generation != 0L) {
                this.pendingPickGeneration = generation;
                this.pendingPickOwner = controller;
                controller.deferViewportPick(context, generation);
            }

            return generation != 0L;
        }

        if (inside && context.mouseButton == 0 && !controller.isViewportPickReady())
        {
            return controller.deferViewportPick(context, 0L);
        }

        return false;
    }

    public boolean openViewportContextMenu(UIContext context, Area area, UIFilmController controller, boolean snapToBlock)
    {
        if (!this.isVisible() || controller.isViewFlying())
        {
            return false;
        }

        Level world = Minecraft.getInstance().level;
        Camera camera = controller.getViewCamera();
        Vector3f rayOffset = new Vector3f();
        Vector3f rayDirection = CameraUtils.getMouseRay(camera.projection, camera.view, context.mouseX, context.mouseY, area.x, area.y, area.w, area.h, rayOffset);
        BlockHitResult blockHitResult = RayTracing.rayTrace(
            world,
            RayTracing.fromVector3d(new Vector3d(camera.position).add(rayOffset.x, rayOffset.y, rayOffset.z)),
            RayTracing.fromVector3f(rayDirection),
            256F
        );

        if (blockHitResult.getType() == HitResult.Type.MISS)
        {
            return false;
        }

        Vector3d vec = new Vector3d(blockHitResult.getLocation().x, blockHitResult.getLocation().y, blockHitResult.getLocation().z);

        if (snapToBlock)
        {
            vec.set(Math.floor(vec.x) + 0.5D, Math.round(vec.y), Math.floor(vec.z) + 0.5D);
        }

        Film ownerFilm = this.film;
        Replay ownerReplay = this.replay;
        int ownerTick = this.filmPanel.getCursor();
        float yaw = MathUtils.toDeg(camera.rotation.y);

        context.replaceContextMenu(menu ->
        {
            menu.action(Icons.ADD, UIKeys.FILM_REPLAY_CONTEXT_ADD, () ->
            {
                if (this.film == ownerFilm)
                {
                    this.replaysList.replays.addReplay(vec, 0F, yaw);
                }
            });
            menu.action(Icons.POINTER, UIKeys.FILM_REPLAY_CONTEXT_MOVE_HERE, () ->
            {
                if (this.film == ownerFilm && ownerReplay != null && CollectionUtils.getIndex(ownerFilm.replays.getList(), ownerReplay) >= 0)
                {
                    ownerReplay.keyframes.x.insert(ownerTick, vec.x);
                    ownerReplay.keyframes.y.insert(ownerTick, vec.y);
                    ownerReplay.keyframes.z.insert(ownerTick, vec.z);
                }
            });
        });

        return true;
    }

    public void close() {
        if (this.film != null) {
            lastFilm = this.film.getId();
            Replay r = this.getReplay();

            lastReplay = r == null ? 0 : CollectionUtils.getIndex(this.film.replays.getList(), r);
        }
    }

    public void teleport() {
        if (this.filmPanel.getData() == null) {
            return;
        }

        /* Through the shared helper so the teleport key and a take started on the mark
         * can never drift apart (and a replay without position keyframes is left alone) */
        PlayerUtils.teleportToReplay(this.getReplay(), this.filmPanel.getCursor());
    }

    @Override
    public void render(UIContext context) {
        if (this.replay != null && (this.poseOverlayCount != BBSSettings.recordingPoseOverlays.get()
            || this.transformOverlayCount != BBSSettings.recordingTransformOverlays.get())) this.updateChannelsList();
        this.partHeader.setVisible(!this.actionsMode && this.keyframeEditor != null && !this.keyframeEditor.view.isEditing()
            && this.keyframeEditor.view.getGraph() == this.keyframeEditor.view.getDopeSheet());
        /* Hide category bar while the "edit track" overlay is open */
        boolean barVisible = this.timelineVisible
                && (this.actionsMode || this.keyframeEditor == null || !this.keyframeEditor.view.isEditing());

        this.iconBar.setVisible(barVisible);
        this.allToggle.setVisible(barVisible);
        this.actionsToggle.setVisible(barVisible);

        /* The sections button only makes sense on a timeline whose rows carry sections, and only while
         * it fits above the category icons. */
        UIKeyframeDopeSheet dopeSheet = this.keyframeEditor == null ? null : this.keyframeEditor.view.getDopeSheet();
        boolean sectionsAvailable = dopeSheet != null
                && this.keyframeEditor.view.getGraph() == dopeSheet
                && dopeSheet.hasSections();
        boolean collapseSections = sectionsAvailable && dopeSheet.hasExpandedSections();

        this.sectionsToggle.setVisible(barVisible && !this.actionsMode && this.foldingButtonFits());
        this.sectionsToggle.setEnabled(sectionsAvailable);
        this.sectionsToggle.both(collapseSections ? Icons.COLLAPSE_ALL : Icons.EXPAND_ALL);
        this.sectionsToggle.tooltip(L10n.lang(collapseSections
                ? "bbs.ui.film.replays.collapse_all"
                : "bbs.ui.film.replays.expand_all"), Direction.RIGHT);

        if (!this.actionsMode) UIReplaysEditorUtils.configureFilmHotkeyDrag(this.filmPanel, context);

        super.render(context);
    }

    /** Whether the category icons stop before the pinned section toggle, so it would not overlap them. */
    private boolean foldingButtonFits() {
        for (UIIcon button : this.iconBar.getChildren(UIIcon.class)) {
            if (button.isVisible() && button.area.ey() >= this.sectionsToggle.area.y) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void afterResizeApplied() {
        super.afterResizeApplied();

        this.layoutCategoryBar();
    }

    /** Let the category bar take the width its buttons need and pull the timeline up next to it. */
    private void layoutCategoryBar() {
        int width = this.iconBar.getWidthForHeight(this.area.h);

        this.iconBar.w(width);
        int labelWidth = this.keyframeEditor == null ? 0 : Math.min(this.keyframeEditor.view.getLabelWidth(), this.keyframeEditor.view.area.w);
        this.partHeader.relative(this).x(width).y(0).w(labelWidth).h(20);

        if (this.keyframeEditor != null) {
            this.keyframeEditor.x(width).w(1F, -width);
        }

        if (this.actionTimeline != null)
        {
            this.actionTimeline.x(width).w(1F, -width);
        }

        this.layoutBottomToggles();
    }

    @Override
    public void applyUndoData(MapType data) {
        super.applyUndoData(data);

        List<Integer> selection = DataStorageUtils.intListFromData(data.getList("selection"));
        List<Integer> currentIndices = this.replaysList.replays.getCurrentIndices();

        Replay savedReplay = data.has("replay_id") ? this.film.replays.getById(data.getString("replay_id"))
            : CollectionUtils.getSafe(this.film.replays.getList(), data.getInt("replay"));
        if (savedReplay != null) this.selectedPartsByReplay.put(savedReplay.getId(), data.getString("body_part"));
        this.setReplay(
                savedReplay,
                true,
                OrbitReaction.KEEP
        );

        if (data.has("selection_ids"))
        {
            List<ReplayListEntry> entries = new ArrayList<>();

            for (String id : DataStorageUtils.stringListFromData(data.getList("selection_ids")))
            {
                Replay selected = this.film.replays.getById(id);

                if (selected != null)
                {
                    entries.add(ReplayListEntry.replay(selected));
                }
            }

            this.replaysList.replays.setCurrent(entries);
        }
        else
        {
            currentIndices.clear();
            currentIndices.addAll(selection);
        }
        this.replaysList.replays.update();
    }

    @Override
    public void collectUndoData(MapType data) {
        super.collectUndoData(data);

        int index = CollectionUtils.getIndex(this.film.replays.getList(), this.getReplay());

        data.putInt("replay", index);
        data.putString("replay_id", this.replay == null ? "" : this.replay.getId());
        data.putString("body_part", this.selectedPart);
        List<String> selectionIds = new ArrayList<>();

        for (Replay selected : this.replaysList.replays.getSelectedReplays())
        {
            selectionIds.add(selected.getId());
        }

        data.put("selection_ids", DataStorageUtils.stringListToData(selectionIds));
        data.put(
                "selection",
                DataStorageUtils.intListToData(this.replaysList.replays.getCurrentIndices())
        );
    }

    /**
     * How the orbit camera should react when the selected replay is set.
     */
    public enum OrbitReaction {
        /** Reset the orbit camera to its default position. */
        RESET,
        /** Treat it as a user switching replays — teleport the pivot onto the replay if the setting allows. */
        SWITCH,
        /** Leave the orbit camera untouched (used when restoring selection during undo/redo). */
        KEEP
    }
}
